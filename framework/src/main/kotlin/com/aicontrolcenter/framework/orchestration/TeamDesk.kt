package com.aicontrolcenter.framework.orchestration

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
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.agents.RunLimits
import com.aicontrolcenter.framework.orchestration.OrchestrationContext.Companion.countsAgainstLimit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
 * attempts and time limit as any task; the owner can cancel it on its own.
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
        guard(caller, TeamGuards.repeated(ctx.messages.list(caller.objective.id), caller.agent.id, target.id, MessageType.ASSISTANCE_REQUEST, text, target.name))?.let { return it }
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
                    prompt = ctx.briefs.ask(caller.agent.name, caller.task, text),
                    limits = RunLimits(maxModelCalls = ASK_MODEL_CALLS, maxToolCalls = ASK_TOOL_CALLS, timeoutMs = minOf(ASK_TIMEOUT_MS, remaining)),
                    label = "Task: ${caller.task.title} (helping ${caller.agent.name})", purpose = "a teammate's question",
                )
            } finally {
                ctx.runtimes.ended(target.id, caller.task.id)
            }
        }
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
        guard(caller, TeamGuards.taskLimit(ctx.tasks.getTree(caller.objective.id).count { it.countsAgainstLimit() }, caller.limits))?.let { return it }
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
        waitingFor(caller, target) {
            coroutineScope {
                while (ctx.tasks.get(child.id)?.status == TaskStatus.QUEUED) {
                    val job = launch(start = CoroutineStart.LAZY) { work.run(caller.rt, child.id) }
                    caller.rt.taskJobs[child.id] = job
                    job.invokeOnCompletion { caller.rt.taskJobs.remove(child.id, job) }
                    job.start()
                    job.join()
                    if (job.isCancelled) break
                }
            }
        }
        ctx.account(caller.rt, caller.task.id) { it.copy(usage = it.usage + TaskUsage(messagesSent = 1)) }
        val final = ctx.tasks.get(child.id) ?: return ToolOutcome.failure("The delegated task no longer exists.")
        return when (final.status) {
            TaskStatus.COMPLETED -> ToolOutcome.success("${target.name} completed the delegated task \"${final.title}\". Its result:\n${final.result.orEmpty()}")
            TaskStatus.CANCELLED -> ToolOutcome.failure("The delegated task \"${final.title}\" was cancelled${final.error?.let { ": $it" } ?: "."} Continue without it.")
            else -> ToolOutcome.failure("The delegated task \"${final.title}\" did not complete: ${FinalDocument.whyNot(final)} Continue without it or do that part yourself.")
        }
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
        private const val MAX_NAME_ECHO = 60
    }
}
