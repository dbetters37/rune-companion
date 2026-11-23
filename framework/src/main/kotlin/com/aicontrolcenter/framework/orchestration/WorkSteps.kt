package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.taskId
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.agents.RunLimits
import com.aicontrolcenter.framework.agents.TaskCallBudget
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.WORKING

/**
 * Runs WORK tasks: the planned ones the scheduler starts, and the delegated ones a teammate's
 * team_delegate call runs. One call is one attempt; it leaves the task COMPLETED, IN_REVIEW
 * (with its review task created in the same transaction), QUEUED for another attempt or a
 * revision, or finally FAILED or TIMED_OUT.
 *
 * Every state change is stored before anything acts on it, and only while the task still has
 * the status this step expects: when the owner paused or cancelled the task meanwhile, the
 * step leaves the owner's decision in place and records nothing over it.
 *
 * Cancellation (pause, cancel, a time limit of the objective) writes no status here: whoever
 * cancels decides the status, and has written it before cancelling.
 */
internal class WorkSteps(private val ctx: OrchestrationContext) {

    /**
     * Runs one attempt of [taskId]. [notAfter] bounds it further than the task time limit: a
     * delegated task gets no more time than the task that delegated it has left.
     */
    suspend fun run(rt: ObjectiveRuntime, taskId: TaskId, notAfter: Long? = null) {
        val queued = ctx.tasks.get(taskId) ?: return
        val objective = ctx.tasks.get(queued.rootId) ?: return
        val settings = ctx.settings.get()
        val limits = ctx.limits(objective, settings)
        val delegated = queued.delegationChain.isNotEmpty()

        val ready = when (val choice = chooseAgent(queued, delegated)) {
            is Readiness.Ready -> choice
            is Readiness.NotReady -> return finalFailure(rt, queued, TaskStatus.FAILED, "No agent could take this task: ${choice.reason}.")
        }
        val agent = ready.agent
        val conversationId = queued.conversationId ?: ctx.transcripts.open(agent.id, "Task · ${queued.title}")
        if (queued.conversationId != null) ctx.transcripts.note(conversationId, restartNote(queued))
        val now = ctx.clock.now()
        val timeoutMs = minOf(limits.taskTimeoutMs, ((notAfter ?: Long.MAX_VALUE) - now).coerceAtLeast(1))
        val task = ctx.change(rt, taskId, setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
            it.copy(
                status = TaskStatus.RUNNING, assignee = agent.id, conversationId = conversationId,
                startedAt = it.startedAt ?: now, finishedAt = null, deadlineAt = now + timeoutMs,
                statusDetail = "${agent.name} is working, ${ready.destination}.",
            )
        } ?: return
        ctx.journal.taskChanged(task, if (task.attempt == 0 && task.revisionRound == 0) "Task started" else "Task restarted")
        if (queued.startedAt == null) ctx.journal.countStats(agent.id) { it.copy(tasksAssigned = it.tasksAssigned + 1) }
        if (!delegated) {
            ctx.journal.send(task.rootId, task, ctx.assigner(task), Participant.Agent(agent.id), MessageType.ASSIGNMENT, assignmentBody(task))
        }

        val tools = teamTools(agent)
        val brief = ctx.briefs.work(
            WorkBrief(
                objective = objective, task = task, assignedBy = assignerName(task), delegated = delegated,
                dependencies = task.dependsOn.mapNotNull { ctx.tasks.get(it) }.filter { it.status == TaskStatus.COMPLETED }
                    .map { SourceResult(it, authorName(it.assignee)) },
                previousFailure = task.error, feedback = feedback(task),
                canAsk = TeamTools.ASK_ID in tools, canDelegate = TeamTools.DELEGATE_ID in tools,
            ),
            ctx.steps.briefBudget(ready, tools),
        )
        // Shared with the teammates' answers to this run's questions, which count against its limits.
        val budget = TaskCallBudget().also { rt.callBudgets[task.id] = it }
        ctx.runtimes.started(agent.id, task.id)
        val result = try {
            ctx.steps.run(
                ready, task, conversationId, brief, extraTools = tools, callBudget = budget,
                limits = RunLimits(timeoutMs = timeoutMs),
                onTool = { execution ->
                    if (execution.spec?.id == MemoryTools.SHARE_ID && execution.outcome.ok) {
                        // Shared into a knowledge space: every agent the owner lets read it can use it from now on.
                        ctx.journal.send(task.rootId, task, Participant.Agent(agent.id), Participant.System, MessageType.MEMORY_SHARE, execution.outcome.content)
                    }
                },
            )
        } finally {
            rt.callBudgets.remove(task.id, budget)
            ctx.runtimes.ended(agent.id, task.id)
        }
        ctx.account(rt, task.id) { it.copy(usage = it.usage + result.usage) }
        if (result.ok) finished(rt, task, agent, result.text, limits.maxTasks) else failed(rt, task, agent, result, limits.maxTaskAttempts)
    }

    /** The stored assignee when it can run; otherwise, for planned work, the best other agent. A delegated task keeps the agent it was handed to. */
    private suspend fun chooseAgent(task: TaskRecord, delegated: Boolean): Readiness {
        val assigned = task.assignee?.let { ctx.steps.prepare(it) }
        if (assigned is Readiness.Ready) return assigned
        if (delegated && assigned != null) return assigned
        val team = ctx.directory.snapshot().agents
        return when (val selection = ctx.selector.forWork(TaskNeeds(task.title, task.instructions), team, ctx.runtimes.loads(), exclude = setOfNotNull(task.assignee))) {
            is Selection.Chosen -> ctx.steps.prepare(selection.agent.id)
            is Selection.NoneSuitable -> Readiness.NotReady((assigned as? Readiness.NotReady)?.reason ?: selection.reason)
        }
    }

    private suspend fun finished(rt: ObjectiveRuntime, task: TaskRecord, agent: AgentProfile, text: String, maxTasks: Int) {
        val objective = ctx.tasks.get(task.rootId) ?: return
        val reviewOn = objective.options?.reviewResults ?: true
        val wantsReview = reviewOn && task.needsReview && task.delegationChain.isEmpty()
        var reviewer: AgentProfile? = null
        var unreviewed: String? = null
        val stored = rt.lock.withLockNonCancellable {
            val current = ctx.tasks.get(task.id)?.takeIf { it.status in WORKING } ?: return@withLockNonCancellable null
            val now = ctx.clock.now()
            val withResult = current.copy(result = text, error = null, updatedAt = now)
            if (!wantsReview) return@withLockNonCancellable completed(withResult, "Completed by ${agent.name}.").also { ctx.tasks.upsert(it) }
            val tree = ctx.tasks.getTree(task.rootId)
            val needs = TaskNeeds(current.title, current.instructions)
            // The plan kept a place for this task's first review; later rounds need a free one.
            val limitProblem = if (ctx.committedTasks(tree, current.id) >= maxTasks) "the objective has reached its limit of $maxTasks tasks" else null
            val selection = ctx.selector.forReview(needs, agent.id, ctx.directory.snapshot().agents, ctx.runtimes.loads())
            if (limitProblem == null && selection is Selection.Chosen) {
                reviewer = selection.agent
                val review = reviewTask(current, selection.agent, ReviewKind.REVIEW, "Check the result of \"${current.title}\" against its completion criteria.", now)
                val inReview = withResult.copy(status = TaskStatus.IN_REVIEW, statusDetail = "Waiting for ${selection.agent.name}'s review.")
                ctx.tasks.upsertAll(listOf(inReview, review))
                inReview
            } else {
                unreviewed = limitProblem ?: (selection as Selection.NoneSuitable).reason
                completed(withResult, "Completed by ${agent.name}; not reviewed: $unreviewed.").also { ctx.tasks.upsert(it) }
            }
        } ?: return
        val to = ctx.assigner(stored)
        val chosen = reviewer
        if (chosen != null) {
            ctx.journal.taskChanged(stored, "Result ready for review")
            ctx.journal.send(stored.rootId, stored, Participant.Agent(agent.id), to, MessageType.FINDINGS, summaryBody("Result ready for review", stored))
            val director = objective.assignee
            ctx.journal.send(
                stored.rootId, stored, director?.let { Participant.Agent(it) } ?: Participant.System, Participant.Agent(chosen.id),
                MessageType.REVIEW_REQUEST, "Please review \"${stored.title}\" by ${agent.name} against its completion criteria.",
            )
            return
        }
        unreviewed?.let { ctx.journal.limitReached(stored.rootId, stored, "\"${stored.title}\" was used without review: $it.") }
        ctx.journal.taskChanged(stored, "Task completed")
        ctx.journal.send(stored.rootId, stored, Participant.Agent(agent.id), to, MessageType.COMPLETION, summaryBody("Completed", stored), hop = stored.delegationChain.size)
        ctx.journal.countFinished(stored)
    }

    private suspend fun failed(rt: ObjectiveRuntime, task: TaskRecord, agent: AgentProfile, result: StepResult, maxAttempts: Int) {
        val reason = result.failure ?: "The task did not produce a result."
        val timedOut = result.end == StepEnd.TIMED_OUT
        val stored = ctx.change(rt, task.id, WORKING) { current ->
            val now = ctx.clock.now()
            if (current.attempt + 1 < maxAttempts) {
                current.copy(status = TaskStatus.QUEUED, attempt = current.attempt + 1, error = reason, statusDetail = "Attempt ${current.attempt + 1} did not succeed; trying again. $reason")
            } else {
                current.copy(
                    status = if (timedOut) TaskStatus.TIMED_OUT else TaskStatus.FAILED, error = reason, finishedAt = now,
                    statusDetail = if (timedOut) "Ran out of time." else "Failed after ${current.attempt + 1} attempt(s).",
                )
            }
        } ?: return
        val final = stored.status.isFinished
        ctx.journal.taskChanged(stored, if (final) (if (timedOut) "Task timed out" else "Task failed") else "Task will be retried", if (final) Severity.WARNING else Severity.INFO)
        ctx.journal.send(stored.rootId, stored, Participant.Agent(agent.id), ctx.assigner(stored), MessageType.FAILURE, "${stored.title}: $reason", hop = stored.delegationChain.size)
        if (final) ctx.journal.countFinished(stored)
    }

    /** Fails a task that never started, for example because nobody can take it. */
    suspend fun finalFailure(rt: ObjectiveRuntime, task: TaskRecord, status: TaskStatus, reason: String) {
        val stored = ctx.change(rt, task.id, setOf(TaskStatus.QUEUED, TaskStatus.RUNNING)) {
            it.copy(status = status, error = reason, statusDetail = reason, finishedAt = ctx.clock.now())
        } ?: return
        ctx.journal.taskChanged(stored, "Task failed", Severity.WARNING)
        ctx.journal.send(stored.rootId, stored, Participant.System, ctx.assigner(stored), MessageType.FAILURE, "${stored.title}: $reason")
    }

    /** The team tools offered to [agent] in a task, as its delegation policy allows and where they are registered. */
    private fun teamTools(agent: AgentProfile): Set<ToolId> = buildSet {
        if (agent.delegation.canRequestAssistance && ctx.tools.byId(TeamTools.ASK_ID) != null) add(TeamTools.ASK_ID)
        if (agent.delegation.canDelegate && ctx.tools.byId(TeamTools.DELEGATE_ID) != null) add(TeamTools.DELEGATE_ID)
    }

    /** The last verdict, when it asked for changes that this attempt must address. */
    private suspend fun feedback(task: TaskRecord): ReviewFeedback? {
        val verdict = task.reviews.lastOrNull()?.takeIf { it.decision != ReviewDecision.APPROVED } ?: return null
        return ReviewFeedback(verdict, authorName(verdict.reviewer), task.result.takeIf { verdict.decision == ReviewDecision.REVISE })
    }

    private suspend fun assignerName(task: TaskRecord): String = when (val by = task.requestedBy) {
        is TaskRequester.Agent -> authorName(by.agentId)
        TaskRequester.Owner -> "the owner"
    }

    private suspend fun authorName(id: AgentId?): String = id?.let { ctx.agents.get(it)?.name } ?: "a former team member"

    private fun restartNote(task: TaskRecord): String = when {
        task.revisionRound > 0 && task.error == null -> "Revision round ${task.revisionRound} started."
        task.error != null -> "Attempt ${task.attempt + 1} started."
        else -> "Restarted from its brief."
    }

    private fun assignmentBody(task: TaskRecord): String {
        val what = when {
            task.revisionRound > 0 -> "Revise (round ${task.revisionRound})"
            task.attempt > 0 -> "Attempt ${task.attempt + 1}"
            else -> "New task"
        }
        return "$what: ${task.title}\n${task.instructions.lineSequence().firstOrNull().orEmpty()}"
    }

    private fun completed(task: TaskRecord, detail: String) =
        task.copy(status = TaskStatus.COMPLETED, statusDetail = detail, finishedAt = ctx.clock.now())

    companion object {
        /** A short summary of a result for a message: the first lines, cut. */
        fun summaryBody(what: String, task: TaskRecord): String {
            val result = task.result.orEmpty().trim()
            val excerpt = if (result.length <= SUMMARY_CHARS) result else result.take(SUMMARY_CHARS).trimEnd() + "…"
            return "$what: ${task.title}\n$excerpt".trim()
        }

        private const val SUMMARY_CHARS = 400
    }

    /** Builds a REVIEW task: a review by [reviewer], or the Director's arbitration. */
    fun reviewTask(work: TaskRecord, reviewer: AgentProfile, kind: ReviewKind, instructions: String, now: Long): TaskRecord = TaskRecord(
        id = ctx.ids.taskId(), rootId = work.rootId, parentId = work.id, kind = TaskKind.REVIEW,
        title = kind.prefix + work.title, instructions = instructions, completionCriteria = work.completionCriteria,
        assignee = reviewer.id, requestedBy = work.requestedBy, status = TaskStatus.QUEUED,
        statusDetail = if (kind == ReviewKind.REVIEW) "Waiting to review." else "Waiting for the Director's decision.",
        order = work.order, attempt = 0, createdAt = now, updatedAt = now,
    )
}

/** What a REVIEW task is for; told apart by the title prefix the framework gives it. */
internal enum class ReviewKind(val prefix: String) {
    REVIEW("Review: "),
    ARBITRATION("Decide: "),
    ;

    companion object {
        fun of(task: TaskRecord): ReviewKind = if (task.title.startsWith(ARBITRATION.prefix)) ARBITRATION else REVIEW
    }
}
