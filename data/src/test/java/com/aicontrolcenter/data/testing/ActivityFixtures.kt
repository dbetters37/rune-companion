package com.aicontrolcenter.data.testing

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.registry.Locality
import kotlin.random.Random

/** Deterministic activity data and the full grid of filters, shared by the activity-log tests. */
object ActivityFixtures {
    val agents = listOf("agt_a", "agt_b", "agt_c").map(::AgentId)
    val rootTasks = listOf("tsk_root_1", "tsk_root_2").map(::TaskId)
    val tasks = (1..12).map { TaskId("tsk_$it") }

    /**
     * [count] events with a realistic mix: mostly INFO, some warnings and errors, about a third
     * with egress, some without agent or task, and many sharing a timestamp so that the
     * insertion-order tie-break is exercised.
     */
    fun events(count: Int, seed: Int = 7): List<ActivityEvent> {
        val random = Random(seed)
        return List(count) { index ->
            val task = tasks.randomOrNull(random).takeIf { random.nextInt(10) > 1 }
            ActivityEvent(
                id = EventId("evt_$index"),
                at = 1_700_000_000_000 + random.nextInt(count / 2 + 1),
                type = ActivityType.entries.random(random),
                severity = when (random.nextInt(20)) {
                    0 -> Severity.ERROR
                    1, 2 -> Severity.WARNING
                    else -> Severity.INFO
                },
                agentId = agents.random(random).takeIf { random.nextInt(10) > 0 },
                taskId = task,
                rootTaskId = task?.let { rootTasks[tasks.indexOf(it) % rootTasks.size] },
                title = "Event $index",
                detail = "detail $index",
                egress = if (random.nextInt(3) == 0) {
                    EgressInfo("api.example.com", Locality.CLOUD, ProviderId("prv_cloud"), "prompt and conversation", 512)
                } else {
                    null
                },
            )
        }
    }

    /** Every combination of the six filter dimensions, each with its "off" and "on" settings. */
    val allFilterCombinations: List<ActivityFilter> = buildList {
        val typeSets = listOf(
            emptySet(),
            setOf(ActivityType.MODEL_CALL),
            setOf(ActivityType.TOOL_CALL, ActivityType.MODEL_CALL, ActivityType.ERROR),
            ActivityType.entries.toSet(),
        )
        for (types in typeSets) {
            for (agent in listOf(null, agents[0])) {
                for (task in listOf(null, tasks[2])) {
                    for (rootTask in listOf(null, rootTasks[0])) {
                        for (severity in Severity.entries) {
                            for (egressOnly in listOf(false, true)) {
                                add(ActivityFilter(types, agent, task, rootTask, severity, egressOnly))
                            }
                        }
                    }
                }
            }
        }
    }

    /** What the port's reference implementation would return for [events] in insertion order. */
    fun expected(events: List<ActivityEvent>, filter: ActivityFilter, limit: Int): List<ActivityEvent> =
        events.asReversed().filter(filter::matches).sortedByDescending { it.at }.take(limit)
}
