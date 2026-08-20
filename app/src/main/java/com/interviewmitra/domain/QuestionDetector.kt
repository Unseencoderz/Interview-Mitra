package com.interviewmitra.domain

object QuestionDetector {
    private val directPrefixes = listOf(
        "why", "what", "how", "when", "where", "who", "which", "can you", "could you",
        "would you", "do you", "have you", "tell me about", "walk me through", "explain"
    )
    private val indirectPatterns = listOf("i'm curious about", "i am curious about", "let's talk about", "lets talk about")

    fun isQuestion(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.length < 3) return false
        return normalized.endsWith("?") || directPrefixes.any { normalized.startsWith(it) } ||
            indirectPatterns.any { normalized.startsWith(it) && normalized.split(Regex("\\s+")).size >= 5 }
    }
}

fun TranscriptChunk.roleFor(candidateSpeakerId: Int?): SpeakerRole =
    if (candidateSpeakerId != null && speakerId == candidateSpeakerId) SpeakerRole.CANDIDATE else SpeakerRole.INTERVIEWER
