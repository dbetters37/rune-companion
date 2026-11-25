package com.aicontrolcenter.app.ui.activity

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import kotlinx.serialization.Serializable

/** The Activity section's two views (Phase 6, wave 3; WP10a builds the second). */
@Serializable
enum class ActivityTab(val title: String) {
    /** Everything that happened, newest first, with "Show older" (D41) and search (Tier B). */
    LOG("Log"),

    /** How much the agents used (answers, tokens, estimated cost), how fast and how reliably. */
    USAGE("Usage and cost"),
}

/** Where the Activity section leads (supplied by the navigation shell; WP10a uses what it needs). */
class ActivityNavigation(
    val onOpenAgent: (AgentId) -> Unit = {},
    val onOpenObjective: (TaskId) -> Unit = {},
    val onOpenTask: (TaskId) -> Unit = {},
    val onOpenModel: (ModelId) -> Unit = {},
    val onOpenRequest: (RequestId) -> Unit = {},
    /** Settings > Daily limits. */
    val onOpenDailyLimits: () -> Unit = {},
    /** Settings, for "Keep usage history for". */
    val onOpenSettings: () -> Unit = {},
)
