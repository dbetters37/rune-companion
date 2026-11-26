package com.aicontrolcenter.framework.orchestration

/**
 * The words that open status lines the orchestrator writes on tasks and that the owner's screens recognise (wave 3,
 * WP8): the app reads these constants, so changing the words here changes them there, and nothing can drift apart.
 */
object TaskStatusLines {
    /** Work waiting for its provider: "Waiting: xAI is not answering; trying again at 10:45." */
    const val WAITING = "Waiting:"

    /** Such work once its objective stopped: "Was waiting: xAI is not answering. Tries again when the objective resumes." */
    const val WAS_WAITING = "Was waiting:"

    /** A finished review: "Verdict: approved.", "Verdict: revise." (the decision's name in lower case). */
    const val VERDICT = "Verdict: "
}
