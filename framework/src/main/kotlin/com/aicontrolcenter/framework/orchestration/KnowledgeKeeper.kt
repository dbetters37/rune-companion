package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import kotlinx.coroutines.flow.first

/**
 * Saves what an objective taught the team, when the owner allowed it for that objective:
 *  - The Director saves a SUMMARY memory of the final document, as itself (so its memory
 *    policy decides where it may write), into the first shared space it may write, or into its
 *    own memory when it may write no space. Provenance names the objective and the Director's model.
 *  - Each agent's task memory of the objective's completed work is promoted into its long-term
 *    memory, as that agent.
 *
 * Nothing here can fail the objective: a permission refusal or a store error is logged and
 * the objective finishes as it would have.
 */
internal class KnowledgeKeeper(private val ctx: OrchestrationContext) {

    suspend fun save(objective: TaskRecord, document: String) {
        val memory = ctx.memory ?: return ctx.journal.warn(objective.id, objective, "Knowledge not saved", "Memory is not available in this build.")
        val directorId = objective.assignee ?: return
        val director = ctx.agents.get(directorId)
            ?: return ctx.journal.warn(objective.id, objective, "Knowledge not saved", "The Director no longer exists.")
        try {
            saveSummary(objective, director, document)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            ctx.journal.warn(objective.id, objective, "Knowledge not saved", refusal(e), directorId)
        }
        val tree = ctx.tasks.getTree(objective.id)
        for (task in tree.filter { it.kind == TaskKind.WORK && it.status == TaskStatus.COMPLETED }) {
            val agent = task.assignee?.let { ctx.agents.get(it) } ?: continue
            try {
                memory.promoteTaskMemory(MemoryActor.Agent(agent), task.id, agent.id)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                ctx.journal.warn(objective.id, task, "Task memory not kept", refusal(e), agent.id)
            }
        }
    }

    private suspend fun saveSummary(objective: TaskRecord, director: AgentProfile, document: String) {
        val memory = ctx.memory ?: return
        val actor = MemoryActor.Agent(director)
        val access = memory.access(actor)
        val space = memory.observeSpaces().first().firstOrNull { access.canWrite(MemoryScope.Shared(it.id)) }
        val own = MemoryScope.Agent(director.id)
        val scope: MemoryScope = space?.let { MemoryScope.Shared(it.id) } ?: own.takeIf { access.canWrite(it) }
            ?: return ctx.journal.warn(objective.id, objective, "Knowledge not saved", "${director.name} may not write to any shared space or to its own memory.", director.id)
        val model = (ctx.steps.prepare(director.id) as? Readiness.Ready)
        val saved = memory.remember(
            actor,
            MemoryDraft(
                scope = scope, kind = MemoryKind.SUMMARY, title = "Objective: ${objective.title}".take(MAX_TITLE),
                content = summary(objective, document), tags = setOf("objective", "summary"), importance = IMPORTANCE,
                provenance = Provenance(
                    origin = ProvenanceOrigin.AGENT, agentId = director.id, taskId = objective.id,
                    modelId = model?.modelId, modelName = model?.modelName, source = "Objective \"${objective.title}\"", recordedAt = ctx.clock.now(),
                ),
            ),
        )
        val where = space?.let { "the shared space \"${it.name}\"" } ?: "its own memory"
        ctx.journal.send(objective.id, objective, Participant.Agent(director.id), Participant.Owner, MessageType.STATUS, "Saved a summary of this objective to $where (memory ${saved.id.value}).")
    }

    private fun summary(objective: TaskRecord, document: String): String {
        val request = objective.instructions.trim().take(MAX_REQUEST)
        val body = document.trim()
        val room = MAX_CONTENT - request.length - HEADER_ROOM
        val cut = if (body.length <= room) body else body.take(room).trimEnd() + "\n[…]"
        return "Objective: $request\n\nResult:\n$cut"
    }

    private fun refusal(e: Exception): String = when (e) {
        is MemoryPermissionException -> "Not allowed: ${e.message.orEmpty().take(MAX_REASON)}"
        else -> "The memory store failed (${e::class.simpleName})."
    }

    private companion object {
        const val MAX_TITLE = 120
        const val MAX_REQUEST = 500
        const val MAX_CONTENT = 3_800
        const val HEADER_ROOM = 40
        const val MAX_REASON = 300
        const val IMPORTANCE = 0.7
    }
}
