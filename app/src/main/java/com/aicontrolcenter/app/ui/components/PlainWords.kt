package com.aicontrolcenter.app.ui.components

/*
 * Sentences several screens say in the same words (wave 3 design review). One place, so the New objective form, the
 * Dashboard, Settings › Daily limits and the objective screen never promise different things. The rest of the shared
 * wording (tokens, answers, the estimate disclaimer) moves here in wave 4 (D3A-20).
 */

/**
 * What happens to running objectives when a daily limit is reached (D3A-3). Nothing resumes a paused objective by itself,
 * at midnight or later: the owner opens it and taps Resume.
 */
const val LIMIT_PAUSES_OBJECTIVES: String =
    "When a limit is reached, running objectives pause. After midnight, or once you raise the limit, open each one and tap Resume."

/** How one objective paused by today's limit goes on (D3A-3), for a line about that objective. */
const val RESUME_AFTER_LIMIT: String =
    "Raise the limit in Settings › Daily limits, then open the objective and tap Resume (after midnight works too)."
