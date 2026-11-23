package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.ReviewVerdict
import com.aicontrolcenter.core.tasks.TaskRecord

/** What happens to a reviewed result next. */
internal sealed interface ReviewStep {
    data object Accept : ReviewStep

    /** The author revises; the task goes back to its queue in [round]. */
    data class Revise(val round: Int) : ReviewStep

    /** The author starts over as [attempt] (rejected result). */
    data class NewAttempt(val attempt: Int) : ReviewStep

    /** The Director decides; [why] is what the arbitration brief tells it. */
    data class Arbitrate(val why: String) : ReviewStep
}

/**
 * Decides what a verdict leads to, from the stored task and the verdict alone, so the decision
 * is the same however often it is made (after a restart, for example).
 *
 *  - Approved: the result is accepted.
 *  - Revise: the author revises, up to [maxRevisionRounds] rounds. When the rounds are used up,
 *    or the reviewer asks for the same changes as in the previous round (the author did not or
 *    would not make them: a disagreement), the Director arbitrates.
 *  - Rejected: a new attempt with the issues, while attempts are left; then the Director arbitrates.
 *  - An unreadable verdict counts as "revise" once; a second one goes to the Director.
 *
 * [work] is the task before [verdict] was added to its reviews.
 */
internal object ReviewPolicy {
    /** Opens the summary of a verdict recorded for an answer that could not be read. */
    const val UNREADABLE_PREFIX = "The review could not be read"

    fun next(work: TaskRecord, verdict: ReviewVerdict, unreadable: Boolean, maxRevisionRounds: Int, maxTaskAttempts: Int): ReviewStep {
        if (unreadable && work.reviews.any { it.summary.startsWith(UNREADABLE_PREFIX) }) {
            return ReviewStep.Arbitrate("the reviewer's answer could not be read twice, so the review cannot settle it.")
        }
        return when (verdict.decision) {
            ReviewDecision.APPROVED -> ReviewStep.Accept
            ReviewDecision.REVISE -> {
                val previous = work.reviews.lastOrNull()
                when {
                    !unreadable && previous?.decision == ReviewDecision.REVISE && sameIssues(previous.issues, verdict.issues) ->
                        ReviewStep.Arbitrate("the reviewer asked again for the same changes, so the author and the reviewer still disagree.")
                    work.revisionRound >= maxRevisionRounds ->
                        ReviewStep.Arbitrate("the result was revised $maxRevisionRounds times, the limit, and the reviewer still asks for changes.")
                    else -> ReviewStep.Revise(work.revisionRound + 1)
                }
            }
            ReviewDecision.REJECTED ->
                if (work.attempt + 1 < maxTaskAttempts) ReviewStep.NewAttempt(work.attempt + 1)
                else ReviewStep.Arbitrate("the reviewer rejected the result and no attempts are left.")
        }
    }

    private fun sameIssues(before: List<String>, now: List<String>): Boolean {
        if (before.isEmpty() || now.isEmpty()) return false
        return TeamGuards.similar(before.joinToString(" "), now.joinToString(" "))
    }
}
