package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.tasks.ReviewDecision

/** A reviewer's answer, read. */
internal sealed interface VerdictReading {
    data class Read(val decision: ReviewDecision, val summary: String, val issues: List<String>) : VerdictReading

    /** [why] says what was wrong with the answer, for the record. */
    data class Unreadable(val why: String) : VerdictReading
}

/** What the Director decided when it arbitrated a review. */
internal enum class Arbitration { ACCEPT, FAIL }

internal sealed interface ArbitrationReading {
    data class Read(val decision: Arbitration, val reason: String) : ArbitrationReading

    data class Unreadable(val why: String) : ArbitrationReading
}

/**
 * Reads the JSON answers of reviews and arbitrations. Decision words are matched loosely
 * ("approve", "APPROVED", "needs_revision" …) because models vary, but a decision that matches
 * none of them makes the whole answer unreadable: guessing a verdict would be worse than
 * handling an unreadable one deliberately.
 */
internal object VerdictParser {
    private const val MAX_SUMMARY = 1_000
    private const val MAX_ISSUES = 10
    private const val MAX_ISSUE = 400

    fun review(text: String): VerdictReading {
        val json = JsonReply.firstObject(text) ?: return VerdictReading.Unreadable("the answer contained no JSON object")
        val word = json.field("decision", "verdict", "status").text()?.lowercase()?.replace(' ', '_')
            ?: return VerdictReading.Unreadable("the JSON object has no \"decision\"")
        val decision = when (word) {
            "approved", "approve", "accept", "accepted", "pass", "passed", "ok" -> ReviewDecision.APPROVED
            "revise", "revision", "needs_revision", "revision_requested", "changes_requested", "request_changes" -> ReviewDecision.REVISE
            "rejected", "reject", "fail", "failed" -> ReviewDecision.REJECTED
            else -> return VerdictReading.Unreadable("\"${word.take(40)}\" is not one of approved, revise or rejected")
        }
        val issues = json.field("issues", "changes", "problems").texts().map { it.take(MAX_ISSUE) }.take(MAX_ISSUES)
        val summary = json.field("summary", "reason", "comment").text()?.take(MAX_SUMMARY)
            ?: issues.firstOrNull()
            ?: decision.name.lowercase()
        return VerdictReading.Read(decision, summary, if (decision == ReviewDecision.APPROVED) emptyList() else issues)
    }

    fun arbitration(text: String): ArbitrationReading {
        val json = JsonReply.firstObject(text) ?: return ArbitrationReading.Unreadable("the answer contained no JSON object")
        val word = json.field("decision", "verdict").text()?.lowercase()
            ?: return ArbitrationReading.Unreadable("the JSON object has no \"decision\"")
        val decision = when (word) {
            "accept", "accepted", "approve", "approved" -> Arbitration.ACCEPT
            "fail", "failed", "reject", "rejected" -> Arbitration.FAIL
            else -> return ArbitrationReading.Unreadable("\"${word.take(40)}\" is not accept or fail")
        }
        val reason = json.field("reason", "summary").text()?.take(MAX_SUMMARY) ?: "No reason was given."
        return ArbitrationReading.Read(decision, reason)
    }
}
