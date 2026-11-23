package com.aicontrolcenter.memory

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.registry.AgentRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** "agent:<id>", "space:<id>" or "task:<id>": a stable, content-free name for a scope. */
internal val MemoryScope.key: String
    get() = when (this) {
        is MemoryScope.Agent -> "agent:${agentId.value}"
        is MemoryScope.Shared -> "space:${spaceId.value}"
        is MemoryScope.Task -> "task:${taskId.value}"
    }

internal val MemoryScope.taskIdOrNull: TaskId? get() = (this as? MemoryScope.Task)?.taskId

/**
 * Writes the memory system's entries in the activity log. Privacy rule: an event may name a
 * memory's title, its id, its scope and who acted, but never its content. Every method here
 * takes records or plain facts and decides itself what goes in, so callers cannot leak content
 * by accident.
 */
internal class MemoryActivity(
    private val recorder: ActivityRecorder,
    private val agents: AgentRepository,
    private val repository: MemoryRepository,
) {
    /** Where a memory lives, in words: "the private memory of Researcher". */
    suspend fun describe(scope: MemoryScope): String = when (scope) {
        is MemoryScope.Agent -> "the private memory of ${agentName(scope.agentId)}"
        is MemoryScope.Shared -> repository.getSpace(scope.spaceId)?.let { "the knowledge space \"${it.name}\"" }
            ?: "the knowledge space ${scope.spaceId.value}"
        is MemoryScope.Task -> "the task memory of ${agentName(scope.agentId)} (task ${scope.taskId.value})"
    }

    private suspend fun agentName(id: com.aicontrolcenter.core.AgentId): String = agents.get(id)?.name ?: "agent ${id.value}"

    /** A change to one memory, e.g. action "Memory saved". */
    suspend fun changed(actor: ResolvedActor, action: String, record: MemoryRecord, note: String? = null) {
        val detail = buildString {
            append("By ").append(if (actor.isOwner) "the owner" else actor.name)
            append(" in ").append(describe(record.scope)).append('.')
            if (note != null) append(' ').append(note)
        }
        recorder.record(
            type = ActivityType.MEMORY, title = "$action: ${record.title}", detail = detail,
            agentId = actor.agentId, taskId = record.scope.taskIdOrNull,
            data = buildJsonObject {
                put("memoryId", record.id.value)
                put("scope", record.scope.key)
                put("actor", actor.key)
                put("revision", record.revision)
            },
        )
    }

    /** A change that is not about one memory (links, spaces, clean-up). [actor] null: the system. */
    suspend fun event(actor: ResolvedActor?, title: String, detail: String, data: JsonObject? = null, severity: Severity = Severity.INFO) {
        recorder.record(
            type = ActivityType.MEMORY, title = title, detail = detail, severity = severity,
            agentId = actor?.agentId, data = JsonObject(data.orEmpty() + ("actor" to JsonPrimitive(actor?.key ?: "system"))),
        )
    }

    /** A refused request. [message] is the same sentence the caller receives. */
    suspend fun refused(actor: ResolvedActor, message: String) {
        recorder.record(
            type = ActivityType.PERMISSION, title = "Memory access refused", detail = message,
            severity = Severity.WARNING, agentId = actor.agentId, data = buildJsonObject { put("actor", actor.key) },
        )
    }

    /** Memory text about to leave the device. Recorded before sending, so nothing leaves unlogged. */
    suspend fun egress(agentId: com.aicontrolcenter.core.AgentId?, egress: EgressInfo, what: String) {
        recorder.record(
            type = ActivityType.DATA_EGRESS, title = "Memory text sent for embedding", detail = what,
            agentId = agentId, egress = egress,
        )
    }
}
