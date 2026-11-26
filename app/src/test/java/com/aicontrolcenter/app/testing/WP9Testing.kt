package com.aicontrolcenter.app.testing

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tasks.MessageHeader
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.testkit.Samples

/*
 * WP9's test helpers: a small team as a registry snapshot, task summaries and message headers written the way the
 * orchestrator stores them, for the Team view's pure builder, its layout and its screen tests.
 */

object TeamSamples {
    val director = AgentId("agt_director")
    val researcher = AgentId("agt_researcher")
    val analyst = AgentId("agt_analyst")
    val reviewer = AgentId("agt_reviewer")
    val writer = AgentId("agt_writer")
    val root = TaskId("obj_notes")
    val other = TaskId("obj_trip")
    val commons = SpaceId("spc_commons")

    fun entry(
        id: AgentId,
        name: String,
        kind: AgentKind = AgentKind.SPECIALIST,
        colorIndex: Int = 0,
        availability: AgentAvailability = AgentAvailability.Ready(ModelId("mdl_sample"), usingFallback = false),
        designated: Boolean = false,
        readable: Set<SpaceId> = emptySet(),
        writable: Set<SpaceId> = emptySet(),
    ) = AgentEntry(
        Samples.agent(id = id.value, name = name, kind = kind).copy(
            colorIndex = colorIndex, memory = MemoryPolicy(readableSpaces = readable, writableSpaces = writable),
        ),
        availability, AgentStats(), designated,
    )

    /** The Director, Researcher, Analyst, Reviewer and Writer, every one ready on the sample model. */
    fun snapshot(vararg extra: AgentEntry) = RegistrySnapshot(
        agents = listOf(
            entry(director, "Director", AgentKind.DIRECTOR, 0, designated = true),
            entry(researcher, "Researcher", colorIndex = 1, readable = setOf(commons), writable = setOf(commons)),
            entry(analyst, "Analyst", colorIndex = 2, readable = setOf(commons)),
            entry(reviewer, "Reviewer", AgentKind.REVIEWER, 3),
            entry(writer, "Writer", colorIndex = 4),
        ) + extra,
        providers = listOf(Samples.provider()),
        models = listOf(Samples.model()),
    )

    val spaces = listOf(KnowledgeSpace(commons, "Team notes", createdAt = 1, updatedAt = 1))

    fun summary(
        id: String,
        title: String,
        status: TaskStatus,
        assignee: AgentId?,
        kind: TaskKind = TaskKind.WORK,
        rootId: TaskId = root,
        parentId: TaskId? = rootId,
        detail: String? = null,
        retryAt: Long? = null,
        dependsOn: List<String> = emptyList(),
        at: Long = 1_000,
    ) = TaskSummary(
        id = TaskId(id), rootId = rootId, parentId = if (kind == TaskKind.OBJECTIVE) null else parentId, kind = kind, status = status,
        assigneeId = assignee, title = title, statusDetailShort = detail, sortOrder = 0, attempt = 1, revisionRound = 0,
        needsReview = false, createdAt = at, updatedAt = at, startedAt = at, finishedAt = null, retryAt = retryAt,
        resultChars = 0, dependsOn = dependsOn.map(::TaskId),
    )

    /** The objective itself, run by the Director. */
    fun objective(status: TaskStatus = TaskStatus.RUNNING, detail: String? = null, rootId: TaskId = root, title: String = "Find the best offline note app", retryAt: Long? = null) =
        summary(rootId.value, title, status, director, TaskKind.OBJECTIVE, rootId = rootId, detail = detail, retryAt = retryAt)

    private var counter = 0

    fun header(from: Participant, to: Participant, type: MessageType, at: Long, rootId: TaskId = root, preview: String = "$type") = MessageHeader(
        id = MessageId("msg_${at}_${counter++}"), rootId = rootId, taskId = null, from = from, to = to, type = type, at = at, hop = 0,
        replyTo = null, preview = preview,
    )

    fun agent(id: AgentId) = Participant.Agent(id)
}
