package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus

/** Small records for the pure tests. */
object TaskSamples {
    fun objective(text: String) = TaskRecord(
        id = TaskId("tsk_root"), rootId = TaskId("tsk_root"), kind = TaskKind.OBJECTIVE, title = text.take(40), instructions = text,
        requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = 1, updatedAt = 1,
    )

    fun work(title: String, result: String? = null, assignee: String = "agt_author") = TaskRecord(
        id = TaskId("tsk_$title"), rootId = TaskId("tsk_root"), parentId = TaskId("tsk_root"), kind = TaskKind.WORK, title = title,
        instructions = "Do $title", completionCriteria = listOf("Covers $title"), assignee = AgentId(assignee),
        requestedBy = TaskRequester.Agent(AgentId("agt_director")), status = TaskStatus.IN_REVIEW, result = result, createdAt = 2, updatedAt = 2,
    )

    fun entry(
        name: String,
        kind: AgentKind = AgentKind.SPECIALIST,
        skills: Set<String> = emptySet(),
        ready: Boolean = true,
        director: Boolean = false,
        stats: AgentStats = AgentStats(),
        description: String = "",
    ) = AgentEntry(
        profile = AgentProfile(AgentId("agt_${name.lowercase()}"), name, kind, description = description, skills = skills, createdAt = 1, updatedAt = 1),
        availability = if (ready) AgentAvailability.Ready(ModelId("mdl_1"), usingFallback = false) else AgentAvailability.Unavailable(UnavailableReason.NO_MODEL_ASSIGNED, "No model."),
        stats = stats,
        isDesignatedDirector = director,
    )
}
