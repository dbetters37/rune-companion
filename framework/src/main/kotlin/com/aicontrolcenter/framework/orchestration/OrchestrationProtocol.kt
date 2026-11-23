package com.aicontrolcenter.framework.orchestration

/**
 * The marker lines that open the briefs the orchestrator writes for agents. Each names the
 * protocol of the expected answer and its version, so that a reply can be checked against what
 * was asked, and so that the built-in simulator can recognise a brief and answer it offline
 * with a well-formed plan, verdict or document instead of a generic restatement.
 *
 * A marker is a line of its own and is written only by the framework. Content that quotes a
 * marker cannot pass for a brief: every line of agent-written content inside a brief is quoted
 * ([DataBlock]), so a marker in it never stands alone on a line.
 */
object OrchestrationProtocol {
    /** The Director turns the owner's objective into a JSON plan. */
    const val PLAN = "[aicc-protocol plan/1]"

    /** An agent carries out one task of a plan (or of a delegation) and answers with its result. */
    const val TASK = "[aicc-protocol task/1]"

    /** A reviewer judges a result against its completion criteria and answers with a JSON verdict. */
    const val REVIEW = "[aicc-protocol review/1]"

    /** The Director settles a review that did not converge and answers with a JSON decision. */
    const val ARBITRATE = "[aicc-protocol arbitrate/1]"

    /** The Director combines the results into the final Markdown document. */
    const val SYNTHESIS = "[aicc-protocol synthesis/1]"

    /** An agent answers a teammate's question during a task. */
    const val ASK = "[aicc-protocol ask/1]"

    val ALL: List<String> = listOf(PLAN, TASK, REVIEW, ARBITRATE, SYNTHESIS, ASK)

    /** The marker that stands on a line of its own in [text], or null when there is none. */
    fun markerIn(text: String): String? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.startsWith("[aicc-protocol ") }.toSet()
        return ALL.firstOrNull { it in lines }
    }
}
