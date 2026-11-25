package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.agents.label
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.taskId
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.agents.RunLimits

/**
 * The Director turns the objective into a plan: one run with the planning brief, asking for
 * one JSON object (as JSON output where the model supports it). A plan that cannot be used is
 * sent back once with every problem quoted; a second failure fails the objective with the
 * problems as its reason.
 *
 * A valid plan is stored in one transaction: its WORK tasks (each with the agent chosen for
 * it, or failed at once when nobody can take it), and the SYNTHESIS task, whose instructions
 * keep the Director's interpretation and final instructions. Everything the rest of the run
 * needs is then in the task store, so an interrupted objective resumes without planning again.
 *
 * Phase 6: today's limit reached pauses the objective (rule 6); a Director whose provider is not
 * answering, or asks the app to slow down, waits and plans again without using a planning round
 * (R4): the objective's status says until when, and [com.aicontrolcenter.core.tasks.TaskRecord.retryAt]
 * on the objective publishes the wait. The objective's own time limit bounds it.
 */
internal class PlanningStep(private val ctx: OrchestrationContext) {

    /** Returns true when the plan is stored; false when the objective failed, paused or is no longer running. */
    suspend fun plan(rt: ObjectiveRuntime): Boolean {
        while (true) {
            val objective = ctx.tasks.get(rt.rootId) ?: return false
            val directorId = objective.assignee ?: return failObjective(rt, "No Director was set for this objective.")
            val ready = when (val readiness = ctx.prepareChecked(directorId)) {
                is Readiness.Ready -> readiness
                is Readiness.NotReady -> {
                    if (!readiness.transient) return failObjective(rt, "The Director cannot plan: ${readiness.reason}.")
                    if (!waitToPlan(rt, ProviderWaits.named(readiness.provider, "The provider"), null)) return false
                    continue
                }
            }
            val name = ProviderWaits.named(ready.provider, ready.providerName)
            // The Director's provider asked the app to slow down for another step: planning waits with it (INT-1).
            val held = ctx.slowDowns.heldUntil(ready.provider?.id, ctx.clock.now())
            if (held != null) {
                if (!waitToPlan(rt, name, held.second, at = held.first)) return false
                continue
            }
            when (val outcome = planWith(rt, objective, directorId, ready)) {
                is Planned.Done -> return outcome.stored
                is Planned.Wait -> {
                    // A few waits at most; then, or for a used-up quota, the objective cannot be planned (INT-1).
                    if (!ctx.mayWait(rt, rt.rootId, ready.provider, outcome.error)) {
                        return failObjective(rt, "The Director could not plan: ${ProviderWaits.what(name, outcome.error)}, and waiting for it did not help. Try again later.")
                    }
                    if (!waitToPlan(rt, name, outcome.error)) return false
                }
            }
        }
    }

    /** How one planning attempt ended: stored (or failed, or paused), or waiting for the Director's provider. */
    private sealed interface Planned {
        class Done(val stored: Boolean) : Planned

        class Wait(val error: LlmException) : Planned
    }

    /**
     * Waits for the Director's provider ([provider], [error] as for [OrchestrationContext.waitForProvider]): the
     * objective, still running, says until when and carries the time as its retryAt. False when it is no longer running.
     */
    private suspend fun waitToPlan(rt: ObjectiveRuntime, provider: String, error: LlmException?, at: Long? = null): Boolean {
        val earlier = rt.waitsSoFar[rt.rootId] ?: 0
        val until = at ?: (ctx.clock.now() + ProviderWaits.waitMs(earlier, error))
        val waiting = ctx.change(rt, rt.rootId, setOf(TaskStatus.RUNNING)) {
            it.copy(retryAt = until, statusDetail = ProviderWaits.text(provider, error, until, ctx.zone()))
        } ?: return false
        if (at == null) rt.waitsSoFar[rt.rootId] = earlier + 1
        ctx.journal.taskChanged(waiting, "Planning waits for its provider")
        ctx.waits.until(rt, rt.rootId, until)
        return ctx.change(rt, rt.rootId, setOf(TaskStatus.RUNNING)) { it.copy(retryAt = null) } != null
    }

    private suspend fun planWith(rt: ObjectiveRuntime, objective: TaskRecord, directorId: AgentId, ready: Readiness.Ready): Planned {
        val settings = ctx.settings.get()
        val limits = ctx.limits(objective, settings)
        val conversationId = objective.conversationId ?: ctx.transcripts.open(directorId, "Plan · ${objective.title}")
        val planning = ctx.change(rt, objective.id, setOf(TaskStatus.RUNNING)) {
            it.copy(conversationId = conversationId, retryAt = null, statusDetail = "${ready.agent.name} is planning, ${ready.destination}.")
        } ?: return Planned.Done(false)
        ctx.journal.taskChanged(planning, "Planning started")

        val snapshot = ctx.directory.snapshot()
        val roster = snapshot.agents.filter { it.profile.id != directorId }.map {
            RosterEntry(it.profile.name, it.profile.kind.label, it.profile.description, it.profile.skills.toList(), it.availability.isReady)
        }
        val reviewOn = objective.options?.reviewResults ?: true
        val notes = ctx.ownerNotes(planning.id).map { it.id.value to it.body }
        var prompt = ctx.briefs.plan(planning, roster, ready.agent.name, limits.maxTasks, reviewOn, ctx.steps.briefBudget(ready), notes)
        var history = emptyList<ChatMessage>()
        var problems = emptyList<String>()
        for (round in 0 until PLANNING_ROUNDS) {
            // Planning is part of the objective's time: it never runs past the objective's deadline as its running
            // limit has it now (a wait for the owner during the last round moved it later; the run's own limit pauses meanwhile).
            val deadline = rt.deadlineNow(ctx.tasks.get(planning.id)?.deadlineAt ?: planning.deadlineAt)
            val left = ((deadline ?: Long.MAX_VALUE) - ctx.clock.now()).coerceAtLeast(1)
            ctx.runtimes.started(directorId, planning.id)
            val result = try {
                ctx.steps.run(
                    ready, planning, conversationId, prompt, history, json = true, limits = RunLimits(timeoutMs = minOf(limits.taskTimeoutMs, left)),
                    label = "Task: Planning · ${planning.title}", purpose = "the owner's objective and the team roster",
                    screens = planning.options?.screenActionsAllowed ?: true,
                )
            } finally {
                ctx.runtimes.ended(directorId, planning.id)
            }
            ctx.account(rt, planning.id) { it.copy(usage = it.usage + result.usage) }
            when (val trouble = Trouble.of(result)) {
                is Trouble.DailyLimit -> {
                    ctx.pauseForDailyLimit(rt, trouble.sentence)
                    return Planned.Done(false)
                }
                is Trouble.Passing -> return Planned.Wait(trouble.error)
                null -> {
                    rt.waitsSoFar.remove(rt.rootId)
                    ctx.providerAnswered(rt, rt.rootId, ready.provider)
                }
            }
            problems = if (result.ok) {
                when (val reading = PlanParser.read(result.text, limits.maxTasks, reviewOn)) {
                    is PlanReading.Valid -> return Planned.Done(store(rt, planning, directorId, reading.plan, reviewOn, limits.maxTasks))
                    is PlanReading.Invalid -> reading.problems
                }
            } else {
                listOf(result.failure ?: "The planning run did not produce an answer.")
            }
            if (round + 1 < PLANNING_ROUNDS) {
                ctx.journal.warn(planning.rootId, planning, "Plan sent back to the Director", StoredText.capChars(problems.joinToString(" "), StoredText.MAX_REASON_CHARS), directorId)
                history = history + ChatMessage.user(prompt) + result.produced
                prompt = ctx.briefs.planCorrection(problems, limits.maxTasks)
            }
        }
        return Planned.Done(failObjective(rt, StoredText.capChars("The Director could not produce a usable plan. ${problems.joinToString(" ")}", StoredText.MAX_REASON_CHARS)))
    }

    private suspend fun store(rt: ObjectiveRuntime, objective: TaskRecord, directorId: AgentId, plan: ObjectivePlan, reviewOn: Boolean, maxTasks: Int): Boolean {
        val snapshot = ctx.directory.snapshot()
        val team = snapshot.agents
        val load = ctx.runtimes.loads().toMutableMap()
        val idsByKey = plan.tasks.associate { it.key to ctx.ids.taskId() }
        val base = ctx.clock.now()
        val director = TaskRequester.Agent(directorId)
        val works = plan.tasks.mapIndexed { index, planned ->
            val at = base + index
            val record = TaskRecord(
                id = idsByKey.getValue(planned.key), rootId = objective.id, parentId = objective.id, kind = TaskKind.WORK,
                title = planned.title, instructions = planned.instructions, completionCriteria = planned.completionCriteria,
                requestedBy = director, status = TaskStatus.QUEUED, dependsOn = planned.dependsOn.map { idsByKey.getValue(it) },
                order = index, needsReview = planned.needsReview && reviewOn, createdAt = at, updatedAt = at,
            )
            when (val selection = ctx.selector.forWork(TaskNeeds(planned.title, planned.instructions, planned.skills, planned.assignee), team, load, models = snapshot.modelsById)) {
                is Selection.Chosen -> {
                    load[selection.agent.id] = (load[selection.agent.id] ?: 0) + 1
                    record.copy(assignee = selection.agent.id, statusDetail = "Assigned to ${selection.agent.name}: ${selection.why}.")
                }
                is Selection.NoneSuitable -> record.copy(
                    status = TaskStatus.FAILED, error = "No suitable agent: ${selection.reason}.", statusDetail = "No suitable agent: ${selection.reason}.", finishedAt = at,
                )
            }
        }
        val synthesisAt = base + works.size
        val synthesis = TaskRecord(
            id = ctx.ids.taskId(), rootId = objective.id, parentId = objective.id, kind = TaskKind.SYNTHESIS, title = SYNTHESIS_TITLE,
            instructions = synthesisInstructions(plan), completionCriteria = SYNTHESIS_CRITERIA, assignee = directorId,
            requestedBy = director, status = TaskStatus.QUEUED, statusDetail = "Waits until every task has finished.",
            order = works.size, createdAt = synthesisAt, updatedAt = synthesisAt,
        )
        val stored = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(objective.id)?.takeIf { it.status == TaskStatus.RUNNING } ?: return@withLockNonCancellable null
            val updated = current.copy(statusDetail = "Working on ${works.size} task(s).", updatedAt = ctx.clock.now())
            ctx.tasks.upsertAll(works + synthesis + updated)
            updated
        } ?: return false
        works.forEach { ctx.journal.taskChanged(it, if (it.status == TaskStatus.FAILED) "Task could not be assigned" else "Task planned", if (it.status == TaskStatus.FAILED) Severity.WARNING else Severity.INFO) }
        val names = team.associate { it.profile.id to it.profile.name }
        val summary = buildString {
            append("Plan: ").append(plan.interpretation.ifBlank { stored.title })
            works.forEachIndexed { index, task ->
                append("\n${index + 1}. ${task.title} → ").append(task.assignee?.let { names[it] } ?: "nobody (${task.error})")
                if (task.needsReview) append(" (reviewed)")
                if (plan.tasks[index].key in plan.unreviewed) append(" (not reviewed: no task place left for its review)")
            }
        }
        ctx.journal.send(stored.rootId, stored, Participant.Agent(directorId), Participant.Owner, MessageType.STATUS, summary)
        if (plan.unreviewed.isNotEmpty()) {
            val skipped = works.filterIndexed { index, _ -> plan.tasks[index].key in plan.unreviewed }.joinToString(", ") { "\"${it.title}\"" }
            val note = "Not every task marked for review will be reviewed: each review takes a task place of its own, and the objective allows " +
                "$maxTasks tasks for ${works.size} planned. Reviews are kept in plan order; not reviewed: $skipped."
            ctx.journal.send(stored.rootId, stored, Participant.System, Participant.Owner, MessageType.STATUS, note)
            ctx.journal.warn(stored.rootId, stored, "Some tasks will not be reviewed", note, directorId)
        }
        return true
    }

    /** Fails the objective while it is still running; always returns false. */
    suspend fun failObjective(rt: ObjectiveRuntime, reason: String): Boolean {
        val failed = ctx.change(rt, rt.rootId, setOf(TaskStatus.RUNNING)) {
            it.copy(status = TaskStatus.FAILED, error = reason, statusDetail = "Planning failed.", finishedAt = ctx.clock.now())
        } ?: return false
        ctx.journal.taskChanged(failed, "Objective failed", Severity.WARNING)
        ctx.journal.send(failed.rootId, failed, failed.assignee?.let { Participant.Agent(it) } ?: Participant.System, Participant.Owner, MessageType.FAILURE, reason)
        return false
    }

    private fun synthesisInstructions(plan: ObjectivePlan): String = buildString {
        if (plan.interpretation.isNotBlank()) append("Interpretation: ").append(plan.interpretation.trim()).append("\n\n")
        append(plan.finalInstructions.trim())
    }

    companion object {
        const val PLANNING_ROUNDS = 2
        const val SYNTHESIS_TITLE = "Final result"
        val SYNTHESIS_CRITERIA = listOf("Answers the objective directly", "States plainly what could not be completed and why")
    }
}
