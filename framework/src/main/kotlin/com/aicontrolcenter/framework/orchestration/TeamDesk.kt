package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.taskId
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveLimits
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskUsage
import com.aicontrolcenter.core.tasks.isPausableTimeout
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.agents.RunLimits
import com.aicontrolcenter.framework.agents.TaskCallBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the team tools do: an agent working on a task asks a teammate a question, or hands it
 * part of the task. Both are bounded by the loop guards ([TeamGuards]) and refused with a
 * sentence the calling model can act on; every refusal by a limit is also recorded as a STATUS
 * message from the system, so the owner sees it.
 *
 * Only the agent assigned to a running WORK task of a running objective may use them: not in a
 * direct chat, not in a review, the plan or the synthesis, and not in a teammate's short answer
 * (whose agent is not the task's assignee). What comes back is a tool result, which the model
 * reads as data.
 *
 * While the teammate works, the caller's task is WAITING_FOR_AGENT. A delegated task is a
 * stored WORK task under the caller's task, run here (not by the scheduler) with the same
 * attempts as any task, and never past the time its delegating task has left; the owner can
 * cancel it on its own. When the delegating task stops waiting for whatever reason, a delegated
 * task that has not finished is ended with that reason, never left for nobody to run.
 *
 * A teammate's answer is part of the asking task's work: it runs within what is left of the
 * task's model and tool call limits and is counted against them ([TaskCallBudget]), and a task
 * may ask at most [MAX_ASKS_PER_TASK] questions.
 */
internal class TeamDesk(private val ctx: OrchestrationContext, private val work: WorkSteps) {

    private class Caller(val task: TaskRecord, val objective: TaskRecord, val agent: AgentProfile, val limits: ObjectiveLimits, val rt: ObjectiveRuntime)

    /** Who is calling, or why the call is refused. */
    private sealed interface Check {
        class Allowed(val caller: Caller) : Check

        class Refused(val outcome: ToolOutcome) : Check
    }

    suspend fun ask(context: ToolContext, agentName: String, question: String): ToolOutcome {
        val caller = when (val check = caller(context, "ask other agents")) {
            is Check.Refused -> return check.outcome
            is Check.Allowed -> check.caller
        }
        if (!caller.agent.delegation.canRequestAssistance) {
            return ToolOutcome.failure("${caller.agent.name} is not allowed to ask other agents for help. The owner can allow it in its delegation settings.")
        }
        val text = question.trim().take(MAX_QUESTION)
        if (text.isEmpty()) return ToolOutcome.failure("Write the question to ask.")
        val target = find(agentName) ?: return unknownAgent(agentName)
        val chain = caller.task.delegationChain
        guard(caller, TeamGuards.cycle(chain, caller.agent.id, target))?.let { return it }
        guard(caller, TeamGuards.depth(chain, caller.limits))?.let { return it }
        guard(caller, TeamGuards.messageLimit(ctx.messages.count(caller.objective.id), 2, caller.limits))?.let { return it }
        val history = ctx.messages.list(caller.objective.id)
        val asked = history.count { it.taskId == caller.task.id && it.type == MessageType.ASSISTANCE_REQUEST && it.from == Participant.Agent(caller.agent.id) }
        guardOnce(caller, history, TeamGuards.assistanceLimit(asked))?.let { return it }
        guard(caller, TeamGuards.repeated(history, caller.agent.id, target.id, MessageType.ASSISTANCE_REQUEST, text, target.name))?.let { return it }
        // The answer is part of the asking task's work: it runs within what the task has left of its own limits.
        val budget = caller.rt.callBudgets[caller.task.id]
        val modelCalls = minOf(ASK_MODEL_CALLS, budget?.remainingModelCalls ?: ASK_MODEL_CALLS)
        val toolCalls = minOf(ASK_TOOL_CALLS, budget?.remainingToolCalls ?: ASK_TOOL_CALLS)
        if (modelCalls < 1) {
            guardOnce(caller, history, "this task has used all ${budget?.maxModelCalls} model calls it may make, its teammates' answers included. Finish the task with what you have.")?.let { return it }
        }
        val ready = when (val readiness = ctx.steps.prepare(target.id)) {
            is Readiness.Ready -> readiness
            is Readiness.NotReady -> return ToolOutcome.failure("${target.name} cannot answer right now: ${readiness.reason}.")
        }
        val hop = chain.size + 1
        val request = ctx.journal.send(caller.objective.id, caller.task, Participant.Agent(caller.agent.id), Participant.Agent(target.id), MessageType.ASSISTANCE_REQUEST, text, hop)
        val answer = waitingFor(caller, target) {
            val remaining = ((caller.task.deadlineAt ?: Long.MAX_VALUE) - ctx.clock.now()).coerceAtLeast(1)
            ctx.runtimes.started(target.id, caller.task.id)
            try {
                ctx.steps.run(
                    ready, caller.task.copy(delegationChain = chain + caller.agent.id), conversationId = null,
                    prompt = ctx.briefs.ask(caller.agent.name, caller.task, text, ctx.steps.briefBudget(ready)),
                    limits = RunLimits(maxModelCalls = modelCalls, maxToolCalls = toolCalls, timeoutMs = minOf(ASK_TIMEOUT_MS, remaining)),
                    label = "Task: ${caller.task.title} (helping ${caller.agent.name})", purpose = "a teammate's question",
                )
            } finally {
                ctx.runtimes.ended(target.id, caller.task.id)
            }
        }
        budget?.charge(answer.usage.modelCalls, answer.usage.toolCalls)
        ctx.account(caller.rt, caller.task.id) { it.copy(usage = it.usage + answer.usage + TaskUsage(messagesSent = 1)) }
        return if (answer.ok) {
            ctx.journal.send(caller.objective.id, caller.task, Participant.Agent(target.id), Participant.Agent(caller.agent.id), MessageType.ASSISTANCE_RESPONSE, answer.text, hop, request.id)
            ToolOutcome.success("${target.name} answered:\n${answer.text}")
        } else {
            val why = answer.failure ?: "it gave no answer."
            ctx.journal.send(caller.objective.id, caller.task, Participant.Agent(target.id), Participant.Agent(caller.agent.id), MessageType.ASSISTANCE_RESPONSE, "Could not answer: $why", hop, request.id)
            ToolOutcome.failure("${target.name} could not answer: $why")
        }
    }

    suspend fun delegate(context: ToolContext, agentName: String, title: String, instructions: String, criteria: List<String>): ToolOutcome {
        val caller = when (val check = caller(context, "delegate work")) {
            is Check.Refused -> return check.outcome
            is Check.Allowed -> check.caller
        }
        if (!caller.agent.delegation.canDelegate) {
            return ToolOutcome.failure("${caller.agent.name} is not allowed to delegate work. The owner can allow it in its delegation settings.")
        }
        val what = instructions.trim().take(PlanParser.MAX_INSTRUCTIONS)
        if (what.isEmpty()) return ToolOutcome.failure("Write the instructions for the delegated task.")
        val name = title.trim().lineSequence().firstOrNull()?.take(PlanParser.MAX_TITLE)?.ifBlank { null } ?: what.lineSequence().first().take(PlanParser.MAX_TITLE)
        val target = find(agentName) ?: return unknownAgent(agentName)
        val chain = caller.task.delegationChain
        val newChain = chain + caller.agent.id
        val profiles = newChain.mapNotNull { ctx.agents.get(it) }.associateBy { it.id }
        guard(caller, TeamGuards.cycle(chain, caller.agent.id, target))?.let { return it }
        guard(caller, TeamGuards.depth(chain, caller.limits))?.let { return it }
        guard(caller, TeamGuards.policyDepth(newChain, profiles))?.let { return it }
        guard(caller, TeamGuards.taskLimit(ctx.committedTasks(ctx.tasks.getTree(caller.objective.id)), caller.limits))?.let { return it }
        guard(caller, TeamGuards.messageLimit(ctx.messages.count(caller.objective.id), 2, caller.limits))?.let { return it }
        val body = "$name\n$what"
        guard(caller, TeamGuards.repeated(ctx.messages.list(caller.objective.id), caller.agent.id, target.id, MessageType.DELEGATION, body, target.name))?.let { return it }
        if (ctx.steps.prepare(target.id) is Readiness.NotReady) return ToolOutcome.failure("${target.name} cannot take work right now.")

        val now = ctx.clock.now()
        val child = TaskRecord(
            id = ctx.ids.taskId(), rootId = caller.objective.id, parentId = caller.task.id, kind = TaskKind.WORK, title = name,
            instructions = what, completionCriteria = criteria.map { it.trim().take(PlanParser.MAX_CRITERION) }.filter { it.isNotEmpty() }.take(PlanParser.MAX_CRITERIA),
            assignee = target.id, requestedBy = TaskRequester.Agent(caller.agent.id), status = TaskStatus.QUEUED,
            statusDetail = "Delegated by ${caller.agent.name}.", delegationChain = newChain, order = caller.task.order, createdAt = now, updatedAt = now,
        )
        val stored = caller.rt.lock.withLockNonCancellable {
            if (ctx.tasks.get(caller.task.id)?.status != TaskStatus.RUNNING) return@withLockNonCancellable false
            ctx.tasks.upsert(child)
            true
        }
        if (!stored) return ToolOutcome.failure("The task was stopped before the work could be delegated.")
        ctx.journal.taskChanged(child, "Task delegated")
        ctx.journal.send(caller.objective.id, child, Participant.Agent(caller.agent.id), Participant.Agent(target.id), MessageType.DELEGATION, body, newChain.size)
        // The child gets no more time than its delegating task has left, attempts included. Read before each
        // attempt: a wait for the owner under the child moved the delegating task's stored deadline later.
        var ranOutOfTime = false
        try {
            waitingFor(caller, target) {
                coroutineScope {
                    while (ctx.tasks.get(child.id)?.status == TaskStatus.QUEUED) {
                        val notAfter = ctx.tasks.get(caller.task.id)?.deadlineAt ?: caller.task.deadlineAt
                        if (notAfter != null && ctx.clock.now() >= notAfter) {
                            ranOutOfTime = true
                            break
                        }
                        val job = launch(start = CoroutineStart.LAZY) { work.run(caller.rt, child.id, notAfter) }
                        caller.rt.taskJobs[child.id] = job
                        job.invokeOnCompletion { caller.rt.taskJobs.remove(child.id, job) }
                        job.start()
                        job.join()
                        if (job.isCancelled) break
                    }
                }
            }
        } catch (e: CancellationException) {
            if (isPausableTimeout(e) || generateSequence<Throwable>(e) { it.cause }.any { it is TimeoutCancellationException }) ranOutOfTime = true
            throw e
        } finally {
            // Whatever stopped the delegating task, its child must not be left unfinished with nobody to run it.
            withContext(NonCancellable) { settleChild(caller.rt, child.id, ranOutOfTime) }
        }
        ctx.account(caller.rt, caller.task.id) { it.copy(usage = it.usage + TaskUsage(messagesSent = 1)) }
        val final = ctx.tasks.get(child.id) ?: return ToolOutcome.failure("The delegated task no longer exists.")
        return when (final.status) {
            TaskStatus.COMPLETED -> ToolOutcome.success("${target.name} completed the delegated task \"${final.title}\". Its result:\n${final.result.orEmpty()}")
            TaskStatus.CANCELLED -> ToolOutcome.failure("The delegated task \"${final.title}\" was cancelled${final.error?.let { ": $it" } ?: "."} Continue without it.")
            else -> ToolOutcome.failure("The delegated task \"${final.title}\" did not complete: ${FinalDocument.whyNot(final)} Continue without it or do that part yourself.")
        }
    }

    /**
     * Ends a delegated task that is still unfinished once its delegating task's wait is over: it
     * is TIMED_OUT when that task ran out of time, otherwise CANCELLED, and says that its
     * delegating task stopped. A child the owner has just paused (with its objective, or its
     * delegating task) stays as it is: resuming decides what happens to it.
     */
    private suspend fun settleChild(rt: ObjectiveRuntime, childId: TaskId, ranOutOfTime: Boolean) {
        val settled = rt.lock.withLockNonCancellable {
            val child = ctx.tasks.get(childId)?.takeIf { !it.status.isFinished } ?: return@withLockNonCancellable null
            val parent = child.parentId?.let { ctx.tasks.get(it) }
            val objective = ctx.tasks.get(child.rootId)
            if (child.status == TaskStatus.PAUSED || parent?.status == TaskStatus.PAUSED || objective?.status == TaskStatus.PAUSED) return@withLockNonCancellable null
            val why = when {
                ranOutOfTime -> "it ran out of time"
                parent == null -> "it no longer exists"
                parent.status.isFinished -> FinalDocument.whyNot(parent).trimEnd('.')
                else -> "it was stopped"
            }
            val reason = "Its delegating task stopped: $why."
            val now = ctx.clock.now()
            child.copy(
                status = if (ranOutOfTime) TaskStatus.TIMED_OUT else TaskStatus.CANCELLED, error = reason, statusDetail = reason,
                finishedAt = now, updatedAt = now,
            ).also { ctx.tasks.upsert(it) }
        } ?: return
        rt.taskJobs[childId]?.cancel()
        ctx.journal.taskChanged(settled, if (settled.status == TaskStatus.TIMED_OUT) "Task timed out" else "Task cancelled", Severity.WARNING)
        if (settled.startedAt != null) ctx.journal.countFinished(settled)
    }

    /** The caller's task and objective when a team tool may be used now, or the refusal. */
    private suspend fun caller(context: ToolContext, doing: String): Check {
        val outside = Check.Refused(ToolOutcome.failure("Agents can $doing only while working on a task of one of the owner's objectives."))
        val task = context.taskId?.let { ctx.tasks.get(it) } ?: return outside
        if (task.kind != TaskKind.WORK || task.assignee != context.agent.id || task.status != TaskStatus.RUNNING) return outside
        val objective = ctx.tasks.get(task.rootId)?.takeIf { it.status == TaskStatus.RUNNING } ?: return outside
        val agent = ctx.agents.get(context.agent.id) ?: return outside
        return Check.Allowed(Caller(task, objective, agent, ctx.limits(objective, ctx.settings.get()), ctx.runtimes.of(objective.id)))
    }

    /** Records a loop guard's refusal for the owner and returns it for the model; null when there is none. */
    private suspend fun guard(caller: Caller, refusal: String?): ToolOutcome? {
        refusal ?: return null
        ctx.journal.limitReached(caller.objective.id, caller.task, "${caller.agent.name} was refused: $refusal")
        return ToolOutcome.failure("Refused: $refusal")
    }

    /** As [guard], but the owner is told only once per task: a model that keeps asking does not fill the objective's messages. */
    private suspend fun guardOnce(caller: Caller, history: List<com.aicontrolcenter.core.tasks.AgentMessage>, refusal: String?): ToolOutcome? {
        refusal ?: return null
        val reported = "Limit reached: ${caller.agent.name} was refused: $refusal"
        if (history.none { it.taskId == caller.task.id && it.type == MessageType.STATUS && it.body == reported }) return guard(caller, refusal)
        return ToolOutcome.failure("Refused: $refusal")
    }

    /** Marks the caller's task WAITING_FOR_AGENT while [block] runs, then RUNNING again (unless the owner changed it meanwhile). */
    private suspend fun <T> waitingFor(caller: Caller, target: AgentProfile, block: suspend () -> T): T {
        val waiting = "Waiting for ${target.name}."
        ctx.change(caller.rt, caller.task.id, setOf(TaskStatus.RUNNING)) { it.copy(status = TaskStatus.WAITING_FOR_AGENT, statusDetail = waiting) }
        ctx.monitor.report(caller.agent.id, AgentActivity.WAITING_FOR_AGENT, "Task: ${caller.task.title} · $waiting", caller.task.id)
        try {
            return block()
        } finally {
            ctx.change(caller.rt, caller.task.id, setOf(TaskStatus.WAITING_FOR_AGENT)) {
                it.copy(status = TaskStatus.RUNNING, statusDetail = caller.task.statusDetail)
            }
        }
    }

    private suspend fun find(name: String): AgentProfile? {
        val wanted = name.trim().trim('"', '\'', '@').trim()
        return ctx.agents.getAll().firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
    }

    private suspend fun unknownAgent(name: String): ToolOutcome {
        val names = ctx.agents.getAll().map { it.name }.sorted()
        return ToolOutcome.failure("There is no agent called \"${name.trim().take(MAX_NAME_ECHO)}\". The team: ${names.joinToString(", ")}.")
    }

    companion object {
        const val MAX_QUESTION = 2_000
        const val ASK_MODEL_CALLS = 4
        const val ASK_TOOL_CALLS = 4
        const val ASK_TIMEOUT_MS = 5 * 60_000L

        /** Questions one task may ask its teammates, over all its attempts. */
        const val MAX_ASKS_PER_TASK = 5
        private const val MAX_NAME_ECHO = 60
    }
}
