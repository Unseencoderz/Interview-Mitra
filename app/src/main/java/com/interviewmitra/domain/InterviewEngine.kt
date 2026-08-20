package com.interviewmitra.domain

/** Pure V2 reconstruction engine. Candidate words never enter its returned question. */
class InterviewEngine {
    private val interviewerFragments = mutableListOf<TranscriptChunk>()
    private var candidateWindow: LongRange? = null

    fun accept(chunk: TranscriptChunk, role: SpeakerRole): InterviewerQuestion? {
        if (!chunk.isFinal || chunk.text.isBlank()) return null
        if (role == SpeakerRole.CANDIDATE) {
            candidateWindow = chunk.startMs..chunk.endMs
            return null
        }
        interviewerFragments += chunk
        if (!chunk.isSpeechFinal) return null
        // Keep the interviewer fragments, even if candidate speech overlapped; this is the recovery strategy.
        val text = interviewerFragments.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
        val reconstructed = interviewerFragments.size > 1 || candidateWindow?.let { it.first <= chunk.endMs && it.last >= interviewerFragments.first().startMs } == true
        interviewerFragments.clear()
        candidateWindow = null
        return if (QuestionDetector.isQuestion(text)) InterviewerQuestion(text, System.currentTimeMillis(), reconstructed) else null
    }

    fun reset() { interviewerFragments.clear(); candidateWindow = null }
}
