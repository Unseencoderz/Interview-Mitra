package com.interviewmitra.network

import com.interviewmitra.BuildConfig
import com.interviewmitra.domain.CandidateProfile
import com.interviewmitra.domain.QaExchange
import com.interviewmitra.domain.ResponseMode
import com.interviewmitra.domain.TranscriptChunk
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

interface SttClient {
    val chunks: Flow<TranscriptChunk>
    suspend fun connect()
    fun sendPcm(bytes: ByteArray)
    fun close()
}

/** Deepgram live WS client. Schema is intentionally isolated so providers can be swapped. */
class DeepgramSttClient(private val apiKey: String = BuildConfig.DEEPGRAM_API_KEY) : SttClient {
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private val parser = Json { ignoreUnknownKeys = true }
    override val chunks: Flow<TranscriptChunk> = callbackFlow {
        val request = Request.Builder()
            .url("wss://api.deepgram.com/v1/listen?model=nova-3&language=en-US&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&endpointing=350&utterance_end_ms=1000&vad_events=true&punctuate=true&diarize_model=latest")
            .header("Authorization", "Token $apiKey")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = parser.parseToJsonElement(text).jsonObject
                    if (root["type"]?.jsonPrimitive?.content != "Results") return
                    val alt = root["channel"]?.jsonObject?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject ?: return
                    val words = alt["words"]?.jsonArray.orEmpty()
                    val first = words.firstOrNull()?.jsonObject ?: return
                    val last = words.lastOrNull()?.jsonObject ?: first
                    val transcript = alt["transcript"]?.jsonPrimitive?.content.orEmpty()
                    if (transcript.isBlank()) return
                    trySend(TranscriptChunk(
                        speakerId = first["speaker"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        text = transcript,
                        isFinal = root["is_final"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        isSpeechFinal = root["speech_final"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        startMs = ((first["start"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                        endMs = ((last["end"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                    ))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { socket?.close(1000, "session ended"); socket = null }
    }

    override suspend fun connect() = suspendCancellableCoroutine { continuation ->
        if (apiKey.isBlank()) { continuation.resume(Unit); return@suspendCancellableCoroutine }
        // Collection opens the socket; this lightweight pre-warm avoids a competing second connection.
        continuation.resume(Unit)
    }
    override fun sendPcm(bytes: ByteArray) { socket?.send(ByteString.of(*bytes)) }
    override fun close() { socket?.close(1000, "session ended"); socket = null }
}

interface AnswerClient {
    fun streamAnswer(question: String, profile: CandidateProfile?, history: List<QaExchange>, mode: ResponseMode): Flow<String>
}

interface ProfileClient { suspend fun structureResume(rawResume: String): CandidateProfile }

/** One setup-time call; the compact result is reused for every answer request. */
class ClaudeProfileClient(private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY) : ProfileClient {
    private val client = OkHttpClient()
    override suspend fun structureResume(rawResume: String): CandidateProfile {
        if (apiKey.isBlank()) return CandidateProfile()
        val requestBody = """{"model":"claude-haiku-4-5-20251001","max_tokens":700,"system":"Extract a compact candidate profile from a resume. Return ONLY valid JSON with name (string or null), skills (string array), projects (array of objects with name, techUsed string array, description), experience (string array), and education (string array). Do not invent facts.","messages":[{"role":"user","content":${Json.encodeToString(rawResume.take(40_000))}}]}"""
        val request = Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").header("content-type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType())).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Resume structuring failed (${response.code})")
        val content = JSONObject(response.body?.string().orEmpty()).getJSONArray("content").getJSONObject(0).getString("text")
        val root = JSONObject(content)
        fun strings(key: String) = root.optJSONArray(key)?.let { array -> List(array.length()) { array.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val projects = root.optJSONArray("projects")?.let { array -> List(array.length()) { index ->
            val item = array.optJSONObject(index) ?: JSONObject()
            val tech = item.optJSONArray("techUsed")?.let { values -> List(values.length()) { values.optString(it) } }.orEmpty()
            com.interviewmitra.domain.ProjectSummary(item.optString("name"), tech, item.optString("description"))
        } }.orEmpty()
        return CandidateProfile(root.optString("name").ifBlank { null }, strings("skills"), projects, strings("experience"), strings("education"))
    }
}

class ClaudeAnswerClient(private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY) : AnswerClient {
    private val client = OkHttpClient()
    private val json = Json
    override fun streamAnswer(question: String, profile: CandidateProfile?, history: List<QaExchange>, mode: ResponseMode): Flow<String> = flow {
        if (apiKey.isBlank()) {
            emit("Add ANTHROPIC_API_KEY to local.properties to enable live answers. The question was detected successfully.")
            return@flow
        }
        val recent = history.takeLast(4).joinToString("\n") { "Q: ${it.question}\nSuggested answer: ${it.answer}" }
        val system = """You are Interview Mitra. Give a candidate a natural answer they can adapt aloud. ${mode.instruction}
Only use the supplied candidate background. Never invent projects, employers, technologies, results, or experience. If background is unavailable, answer generally without implying personal experience."""
        val payload = """{"model":"claude-haiku-4-5-20251001","max_tokens":450,"stream":true,"system":${json.encodeToString(system)},"messages":[{"role":"user","content":${json.encodeToString("Candidate background:\n${profile?.asPrompt() ?: "No resume provided."}\n\nRecent exchanges:\n$recent\n\nInterviewer question: $question")}}]}"""
        val request = Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Claude request failed (${response.code})")
        response.body?.charStream()?.buffered()?.useLines { lines ->
            lines.filter { it.startsWith("data: ") }.forEach { line ->
                val data = line.removePrefix("data: ")
                if (data == "[DONE]") return@forEach
                runCatching {
                    val event = json.parseToJsonElement(data).jsonObject
                    if (event["type"]?.jsonPrimitive?.content == "content_block_delta") {
                        event["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.content?.let { emit(it) }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)
}
