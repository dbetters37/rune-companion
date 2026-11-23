package com.aicontrolcenter.app.ui.settings

import com.aicontrolcenter.core.settings.FrameworkSettings

/**
 * The numeric safety limits of [FrameworkSettings], each with the range the settings screen
 * allows and a one-line explanation. Declared as data so the screen, the view model and the
 * tests all share one definition of what is adjustable and how far.
 *
 * @param unit shown after the number ("min"), or null for plain counts.
 */
enum class OrchestrationLimit(
    val title: String,
    val description: String,
    val range: IntRange,
    val step: Int = 1,
    val unit: String? = null,
    private val read: (FrameworkSettings) -> Int,
    private val write: (FrameworkSettings, Int) -> FrameworkSettings,
) {
    MAX_CONCURRENT_TASKS(
        "Tasks at once", "How many tasks may run at the same time.", 1..8,
        read = { it.maxConcurrentTasks }, write = { s, v -> s.copy(maxConcurrentTasks = v) },
    ),
    MAX_DELEGATION_DEPTH(
        "Delegation depth", "The longest chain of one agent handing work to another.", 0..10,
        read = { it.maxDelegationDepth }, write = { s, v -> s.copy(maxDelegationDepth = v) },
    ),
    MAX_TASKS_PER_OBJECTIVE(
        "Tasks per objective", "Hard cap on the tasks created for one objective.", 4..100, step = 4,
        read = { it.maxTasksPerObjective }, write = { s, v -> s.copy(maxTasksPerObjective = v) },
    ),
    MAX_MESSAGES_PER_OBJECTIVE(
        "Messages per objective", "Hard cap on agent-to-agent messages for one objective.", 50..1000, step = 50,
        read = { it.maxMessagesPerObjective }, write = { s, v -> s.copy(maxMessagesPerObjective = v) },
    ),
    MAX_MODEL_CALLS_PER_TASK(
        "Model calls per task", "A task or chat turn is stopped once it has called its model this often.", 4..100, step = 4,
        read = { it.maxModelCallsPerTask }, write = { s, v -> s.copy(maxModelCallsPerTask = v) },
    ),
    MAX_TOOL_CALLS_PER_TASK(
        "Tool calls per task", "A task or chat turn is stopped once it has used tools this often.", 5..200, step = 5,
        read = { it.maxToolCallsPerTask }, write = { s, v -> s.copy(maxToolCallsPerTask = v) },
    ),
    MAX_REVISION_ROUNDS(
        "Revision rounds", "How many times a reviewer may send the same work back.", 0..5,
        read = { it.maxRevisionRounds }, write = { s, v -> s.copy(maxRevisionRounds = v) },
    ),
    MAX_TASK_ATTEMPTS(
        "Attempts per task", "How many times a failing task is tried in total.", 1..5,
        read = { it.maxTaskAttempts }, write = { s, v -> s.copy(maxTaskAttempts = v) },
    ),
    TASK_TIMEOUT_MINUTES(
        "Task time-out", "A single task or chat turn is stopped after this long.", 5..120, step = 5, unit = "min",
        read = { it.taskTimeoutMinutes }, write = { s, v -> s.copy(taskTimeoutMinutes = v) },
    ),
    OBJECTIVE_TIMEOUT_MINUTES(
        "Objective time-out", "A whole objective is stopped after this long.", 15..480, step = 15, unit = "min",
        read = { it.objectiveTimeoutMinutes }, write = { s, v -> s.copy(objectiveTimeoutMinutes = v) },
    ),
    APPROVAL_TIMEOUT_MINUTES(
        "Approval time-out", "How long an agent waits for your answer before the action is refused.", 5..240, step = 5, unit = "min",
        read = { it.approvalTimeoutMinutes }, write = { s, v -> s.copy(approvalTimeoutMinutes = v) },
    ),
    ;

    fun valueIn(settings: FrameworkSettings): Int = read(settings)

    /** Returns [settings] with this limit set to [value], forced into [range]. */
    fun applyTo(settings: FrameworkSettings, value: Int): FrameworkSettings =
        write(settings, value.coerceIn(range.first, range.last))

    fun format(value: Int): String = if (unit == null) value.toString() else "$value $unit"
}

/** The retention periods offered for the activity log. Zero keeps everything. */
val ActivityRetentionChoices: List<Int> = listOf(7, 30, 90, 365, 0)

fun retentionLabel(days: Int): String = when (days) {
    0 -> "Keep everything"
    1 -> "1 day"
    365 -> "1 year"
    else -> "$days days"
}
