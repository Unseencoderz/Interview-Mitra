package com.interviewmitra.domain

data class TranscriptChunk(
    val speakerId: Int,
    val text: String,
    val isFinal: Boolean,
    val isSpeechFinal: Boolean = false,
    val startMs: Long,
    val endMs: Long,
)

enum class SpeakerRole { CANDIDATE, INTERVIEWER }
enum class ResponseMode(val instruction: String) {
    CONCISE("Use 2–3 direct sentences."),
    DETAILED("Use 5–8 clear, interview-ready sentences."),
    BULLETS("Use bullets: problem, approach, technology, and result.")
}

data class TranscriptLine(val role: SpeakerRole, val text: String, val isInterim: Boolean = false)
data class InterviewerQuestion(val text: String, val detectedAtMs: Long, val reconstructedFromFragments: Boolean)
data class QaExchange(val question: String, val answer: String)
data class ProjectSummary(val name: String, val techUsed: List<String>, val description: String)
data class CandidateProfile(
    val name: String? = null,
    val skills: List<String> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val experience: List<String> = emptyList(),
    val education: List<String> = emptyList(),
) {
    fun asPrompt() = buildString {
        appendLine("Name: ${name ?: "Not provided"}")
        appendLine("Skills: ${skills.joinToString().ifBlank { "Not provided" }}")
        if (projects.isNotEmpty()) appendLine("Projects: " + projects.joinToString("; ") { "${it.name} (${it.techUsed.joinToString()}): ${it.description}" })
        if (experience.isNotEmpty()) appendLine("Experience: ${experience.joinToString("; ")}")
        if (education.isNotEmpty()) appendLine("Education: ${education.joinToString("; ")}")
    }
}
