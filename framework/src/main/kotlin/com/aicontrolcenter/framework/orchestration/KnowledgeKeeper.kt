package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import kotlinx.coroutines.flow.first

/**
 * Saves what an objective taught the team, when the owner allowed it for that objective:
 *  - The Director keeps one SUMMARY memory of the objective's final document, as itself (so its
 *    memory policy decides where it may write): first saved into the first shared space it may
 *    write, or into its own memory when it may write no space. When a task run again gives the
 *    objective another final document, that same memory is updated in place through the memory
 *    service (which keeps its history), never written twice. Provenance names the objective and
 *    the Director's model.
 *  - Each agent's task memory of the objective's completed work is promoted into its long-term
 *    memory, as that agent, for every completed task each time (promoting again keeps nothing
 *    twice: the memory store expires what it promoted, in the same transaction as the copies).
 *
 * [ObjectiveDriver] saves once for each final result (a round) and again after an attempt the
 * app stopped. Saving again repeats nothing, judged by what is stored: the summary stored for the
 * objective ([savedSummary]) is found and only changed when the final document did; and the
 * Director's message about it ([isNotice]) is sent when the owner has not been told where it is
 * yet, or (once in a round) that it was updated.
 *
 * Nothing here can fail the objective: a permission refusal or a store error is logged and
 * the objective finishes as it would have.
 */
internal class KnowledgeKeeper(private val ctx: OrchestrationContext) {

    /** What is stored of an objective's knowledge, against one final document; see [stored]. */
    class Stored(
        /** The objective's summary memory, when one is stored. */
        val summary: MemoryRecord?,
        /** Where the summary is, in words for the owner. */
        val place: String,
        /** The summary holds that final document. */
        val current: Boolean,
        /** The Director told the owner where the summary is. */
        val told: Boolean,
        /** No completed task's important task memory is left unkept; null when the store could not tell. */
        val kept: Boolean?,
    )

    /**
     * Saves what the objective taught the team, its final document being [document]. [changed]:
     * that document is not the one the objective last ended with (only then is a stored summary
     * changed). [since]: when this round of saving began (its first attempt), which tells a
     * summary changed by an earlier attempt of this round from one an earlier round left.
     */
    suspend fun save(objective: TaskRecord, document: String, changed: Boolean, since: Long) {
        val memory = ctx.memory ?: return ctx.journal.warn(objective.id, objective, "Knowledge not saved", "Memory is not available in this build.")
        val directorId = objective.assignee ?: return
        val director = ctx.agents.get(directorId)
            ?: return ctx.journal.warn(objective.id, objective, "Knowledge not saved", "The Director no longer exists.")
        try {
            saveSummary(objective, director, summary(objective, document), changed, since)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            ctx.journal.warn(objective.id, objective, "Knowledge not saved", refusal(e), directorId)
        }
        for ((task, agent) in keptWork(objective)) {
            try {
                memory.promoteTaskMemory(MemoryActor.Agent(agent), task.id, agent.id, KEEP_IMPORTANCE)
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                ctx.journal.warn(objective.id, task, "Task memory not kept", refusal(e), agent.id)
            }
        }
    }

    /**
     * Saves the summary ([content]) unless one is stored for the objective already; updates a
     * stored one that holds another final document when [changed]; then tells the owner, unless
     * that was said.
     */
    private suspend fun saveSummary(objective: TaskRecord, director: AgentProfile, content: String, changed: Boolean, since: Long) {
        val memory = ctx.memory ?: return
        val from = Participant.Agent(director.id)
        val stored = savedSummary(objective)
        if (stored == null) {
            writeSummary(objective, director, content)?.let { send(objective, from, SAVED_SUMMARY, it) }
            return
        }
        val notices = ctx.messages.list(objective.id).filter { it.from == from && isNotice(it) && it.body.contains(noticeOf(stored.id.value)) }
        val toldThisRound = notices.any { it.at > since }
        when {
            changed && !same(stored.content, content) -> {
                val updated = memory.update(MemoryActor.Agent(director), stored.id, MemoryChange(content = content))
                if (!toldThisRound) send(objective, from, UPDATED_SUMMARY, updated)
            }
            // Nothing to write: the summary holds what it should. The message about it may be missing (an attempt the app stopped).
            notices.isEmpty() -> send(objective, from, SAVED_SUMMARY, stored)
            stored.updatedAt > since && !toldThisRound -> send(objective, from, UPDATED_SUMMARY, stored)
        }
    }

    private suspend fun send(objective: TaskRecord, from: Participant, opener: String, summary: MemoryRecord) {
        val where = placeOf(summary.scope, "its own memory")
        val body = if (opener == SAVED_SUMMARY) "$SAVED_SUMMARY $where ${noticeOf(summary.id.value)}."
        else "$UPDATED_SUMMARY $where to the new final result ${noticeOf(summary.id.value)}."
        ctx.journal.send(objective.id, objective, from, Participant.Owner, MessageType.STATUS, body)
    }

    /** Writes the summary as the Director, into the first shared space it may write, else its own memory; null when it may write neither. */
    private suspend fun writeSummary(objective: TaskRecord, director: AgentProfile, content: String): MemoryRecord? {
        val memory = ctx.memory ?: return null
        val actor = MemoryActor.Agent(director)
        val access = memory.access(actor)
        val space = memory.observeSpaces().first().firstOrNull { access.canWrite(MemoryScope.Shared(it.id)) }
        val own = MemoryScope.Agent(director.id)
        val scope: MemoryScope = space?.let { MemoryScope.Shared(it.id) } ?: own.takeIf { access.canWrite(it) }
            ?: return null.also { ctx.journal.warn(objective.id, objective, "Knowledge not saved", "${director.name} may not write to any shared space or to its own memory.", director.id) }
        val model = (ctx.steps.prepare(director.id) as? Readiness.Ready)
        return memory.remember(
            actor,
            MemoryDraft(
                scope = scope, kind = MemoryKind.SUMMARY, title = "Objective: ${objective.title}".take(MAX_TITLE),
                content = content, tags = setOf("objective", "summary"), importance = IMPORTANCE,
                provenance = Provenance(
                    origin = ProvenanceOrigin.AGENT, agentId = director.id, taskId = objective.id,
                    modelId = model?.modelId, modelName = model?.modelName, source = sourceOf(objective), recordedAt = ctx.clock.now(),
                ),
            ),
        )
    }

    /**
     * The summary saved for [objective], when one is stored: a SUMMARY memory in the Director's
     * own memory or a shared space (the only places it is written), whose provenance names the
     * objective, its Director and this keeper as its source, and that is not a copy (a shared or
     * promoted copy keeps its original's provenance). Read as the owner, so that whatever the
     * Director may read now, a summary it wrote is found; null without memory.
     */
    suspend fun savedSummary(objective: TaskRecord): MemoryRecord? {
        val memory = ctx.memory ?: return null
        val directorId = objective.assignee ?: return null
        val scopes = memory.observeSpaces().first().map<_, MemoryScope> { MemoryScope.Shared(it.id) }.toSet() + MemoryScope.Agent(directorId)
        val source = sourceOf(objective)
        return memory.observe(MemoryActor.Owner, MemoryFilter(scopes = scopes, kinds = setOf(MemoryKind.SUMMARY))).first().lastOrNull {
            val p = it.provenance
            p.taskId == objective.id && p.agentId == directorId && p.origin == ProvenanceOrigin.AGENT && p.source == source && p.derivedFrom.isEmpty()
        }
    }

    /**
     * What is stored of the objective's knowledge against its final [document]: the summary,
     * whether it holds that document and the owner was told of it, and whether the team's
     * important task memories were all kept. Read for the objective's status when saving did not
     * end; a store that fails to answer reads as nothing stored (and nothing known to be kept).
     */
    suspend fun stored(objective: TaskRecord, document: String): Stored {
        val summary = try {
            savedSummary(objective)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            null
        }
        val told = summary != null && objective.assignee?.let { director ->
            ctx.messages.list(objective.id).any { it.from == Participant.Agent(director) && isNotice(it) && it.body.contains(noticeOf(summary.id.value)) }
        } == true
        val place = summary?.let { placeOf(it.scope, "the Director's own memory") }.orEmpty()
        return Stored(summary, place, summary != null && same(summary.content, summary(objective, document)), told, memoriesKept(objective))
    }

    /**
     * Whether no completed task of the objective has important task memory left unkept (what
     * [save] promotes is expired once kept): true when there is none to keep; null when the store
     * cannot tell. One query, by the tasks' scopes.
     */
    private suspend fun memoriesKept(objective: TaskRecord): Boolean? = try {
        val memory = ctx.memory
        val scopes = keptWork(objective).map<_, MemoryScope> { (task, agent) -> MemoryScope.Task(task.id, agent.id) }.toSet()
        when {
            memory == null -> null
            scopes.isEmpty() -> true
            else -> memory.observe(MemoryActor.Owner, MemoryFilter(scopes = scopes, excludeExpiredAt = ctx.clock.now())).first()
                .none { it.importance >= KEEP_IMPORTANCE }
        }
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        null
    }

    /** The objective's completed WORK tasks whose agent still exists, with that agent: the task memory [save] keeps. */
    private suspend fun keptWork(objective: TaskRecord): List<Pair<TaskRecord, AgentProfile>> =
        ctx.tasks.getTree(objective.id).filter { it.kind == TaskKind.WORK && it.status == TaskStatus.COMPLETED }
            .mapNotNull { task -> task.assignee?.let { ctx.agents.get(it) }?.let { task to it } }

    private suspend fun placeOf(scope: MemoryScope, own: String): String = when (scope) {
        is MemoryScope.Shared -> ctx.memory?.observeSpaces()?.first()?.firstOrNull { it.id == scope.spaceId }?.let { "the shared space \"${it.name}\"" } ?: "a shared space"
        else -> own
    }

    private fun same(stored: String, content: String) = stored.trim() == content.trim()

    private fun sourceOf(objective: TaskRecord) = "Objective \"${objective.title}\""

    private fun noticeOf(memoryId: String) = "(memory $memoryId)"

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

    companion object {
        /** Opens the Director's message saying where the summary was saved. */
        const val SAVED_SUMMARY = "Saved a summary of this objective to"

        /** Opens the Director's message saying the summary was updated to a new final result. */
        const val UPDATED_SUMMARY = "Updated the summary of this objective in"

        /** Task memory at least this important is kept when an objective's knowledge is saved (the memory service's own default). */
        const val KEEP_IMPORTANCE = 0.6

        /**
         * [message] is the Director's message about the summary (saved or updated): with the
         * objective's announcement ([ObjectiveDriver.isAnnouncement]), the only messages an agent
         * sends while an objective finishes, which [DefaultOrchestrator] does not count as progress.
         */
        fun isNotice(message: AgentMessage): Boolean =
            message.from is Participant.Agent && message.type == MessageType.STATUS && message.taskId == message.rootId &&
                (message.body.startsWith(SAVED_SUMMARY) || message.body.startsWith(UPDATED_SUMMARY))

        private const val MAX_TITLE = 120
        private const val MAX_REQUEST = 500
        private const val MAX_CONTENT = 3_800
        private const val HEADER_ROOM = 40
        private const val MAX_REASON = 300
        private const val IMPORTANCE = 0.7
    }
}
