package com.interviewmitra

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interviewmitra.audio.AudioBus
import com.interviewmitra.audio.ListeningService
import com.interviewmitra.domain.CandidateProfile
import com.interviewmitra.domain.InterviewEngine
import com.interviewmitra.domain.QaExchange
import com.interviewmitra.domain.ResponseMode
import com.interviewmitra.domain.SpeakerRole
import com.interviewmitra.domain.TranscriptLine
import com.interviewmitra.domain.roleFor
import com.interviewmitra.network.AnswerClient
import com.interviewmitra.network.ClaudeAnswerClient
import com.interviewmitra.network.DeepgramSttClient
import com.interviewmitra.network.ClaudeProfileClient
import com.interviewmitra.network.ProfileClient
import com.interviewmitra.network.SttClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

enum class SessionStage { SETUP, CALIBRATION, LIVE, ENDED }
data class InterviewUiState(
    val stage: SessionStage = SessionStage.SETUP,
    val status: String = "Ready",
    val candidateSpeakerId: Int? = null,
    val responseMode: ResponseMode = ResponseMode.CONCISE,
    val profile: CandidateProfile? = null,
    val resumeText: String? = null,
    val transcript: List<TranscriptLine> = emptyList(),
    val currentQuestion: String? = null,
    val answer: String = "",
    val answerError: String? = null,
)

class InterviewViewModel(app: Application) : AndroidViewModel(app) {
    private val stt: SttClient = DeepgramSttClient()
    private val answers: AnswerClient = ClaudeAnswerClient()
    private val profiles: ProfileClient = ClaudeProfileClient()
    private val engine = InterviewEngine()
    private var streamJob: Job? = null
    private var transcriptJob: Job? = null
    private var answerJob: Job? = null
    private val history = mutableListOf<QaExchange>()
    private val _state = MutableStateFlow(InterviewUiState())
    val state = _state.asStateFlow()

    fun setResumeText(text: String) {
        _state.update { it.copy(resumeText = text, profile = profileFromText(text), status = "Structuring resume…") }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { profiles.structureResume(text) } }.getOrNull()?.takeIf { it != CandidateProfile() }?.let { profile ->
                _state.update { it.copy(profile = profile, status = "Resume ready") }
            } ?: _state.update { it.copy(status = "Resume ready (basic profile)") }
        }
    }
    fun setMode(mode: ResponseMode) { _state.update { it.copy(responseMode = mode) } }
    fun openCalibration() {
        _state.update { it.copy(stage = SessionStage.CALIBRATION, status = "Connecting for calibration…") }
        connect()
        collectTranscripts() // Opens the WS before the user begins speaking, hiding handshake latency.
    }
    fun beginCalibration() { startSession(calibrating = true) }
    fun beginInterview() { if (_state.value.candidateSpeakerId != null) startSession(calibrating = false) }
    fun stop() {
        answerJob?.cancel(); streamJob?.cancel(); transcriptJob?.cancel(); streamJob = null; transcriptJob = null; stt.close(); engine.reset()
        getApplication<Application>().stopService(Intent(getApplication(), ListeningService::class.java))
        _state.update { it.copy(stage = SessionStage.ENDED, status = "Session ended") }
    }
    private fun connect() { viewModelScope.launch { stt.connect() } }
    private fun startSession(calibrating: Boolean) {
        if (streamJob != null) return
        _state.update { it.copy(status = if (calibrating) "Hold the button and speak a short sentence" else "Connected — listening", stage = if (calibrating) SessionStage.CALIBRATION else SessionStage.LIVE) }
        getApplication<Application>().startForegroundService(Intent(getApplication(), ListeningService::class.java))
        streamJob = viewModelScope.launch { AudioBus.pcm.collect { stt.sendPcm(it) } }
    }
    private fun collectTranscripts() {
        if (transcriptJob != null) return
        transcriptJob = viewModelScope.launch {
            stt.chunks.retryWhen { _, attempt ->
                if (attempt < 3) {
                    _state.update { it.copy(status = "Reconnecting transcription (${attempt + 1}/3)…") }
                    delay(500L * (attempt + 1))
                    true
                } else false
            }.catch { error ->
                _state.update { it.copy(status = "Transcription stopped: ${error.message ?: "connection error"}") }
                stopAudioOnly()
            }.collect { chunk ->
                if (_state.value.candidateSpeakerId == null && _state.value.stage == SessionStage.CALIBRATION && chunk.isFinal && chunk.isSpeechFinal) {
                    _state.update { it.copy(candidateSpeakerId = chunk.speakerId, status = "Voice confirmed. Start when ready.") }
                    stopAudioOnly(); return@collect
                }
                val role = chunk.roleFor(_state.value.candidateSpeakerId)
                _state.update { state ->
                    val withoutPriorInterim = if (!chunk.isFinal && state.transcript.lastOrNull()?.let { it.role == role && it.isInterim } == true) state.transcript.dropLast(1) else state.transcript
                    state.copy(transcript = (withoutPriorInterim + TranscriptLine(role, chunk.text, !chunk.isFinal)).takeLast(50))
                }
                if (role == SpeakerRole.INTERVIEWER) engine.accept(chunk, role)?.let(::answerQuestion) else engine.accept(chunk, role)
            }
        }
    }
    private fun stopAudioOnly() { streamJob?.cancel(); streamJob = null; getApplication<Application>().stopService(Intent(getApplication(), ListeningService::class.java)) }
    private fun answerQuestion(question: com.interviewmitra.domain.InterviewerQuestion) {
        answerJob?.cancel()
        _state.update { it.copy(currentQuestion = question.text, answer = "", answerError = null, status = "Preparing answer…") }
        answerJob = viewModelScope.launch {
            runCatching {
                answers.streamAnswer(question.text, _state.value.profile, history.takeLast(4), _state.value.responseMode).collect { token ->
                    _state.update { it.copy(answer = it.answer + token, status = "Streaming answer…") }
                }
            }.onSuccess {
                val answer = _state.value.answer
                if (answer.isNotBlank()) history += QaExchange(question.text, answer)
                _state.update { it.copy(status = "Listening") }
            }.onFailure { error -> _state.update { it.copy(answerError = error.message ?: "Unable to generate an answer", status = "Answer error") } }
        }
    }
    private fun profileFromText(text: String): CandidateProfile = CandidateProfile(
        skills = Regex("(?i)(kotlin|java|python|android|compose|react|sql|firebase|aws|docker|git)").findAll(text).map { it.value }.distinct().take(12).toList(),
        experience = text.lines().filter { it.contains("experience", true) || it.contains("intern", true) }.take(4),
        education = text.lines().filter { it.contains("university", true) || it.contains("bachelor", true) || it.contains("college", true) }.take(3),
    )
}
