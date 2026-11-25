package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.messageId
import com.aicontrolcenter.core.operator.OwnerAttention
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.QuestionRules
import com.aicontrolcenter.core.requestId
import com.aicontrolcenter.core.settings.SettingsBounds
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.orchestration.DataBlock
import com.aicontrolcenter.framework.orchestration.TeamTools
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The agent's side of a question to the owner (Phase 6, C1, Tier B; rules 2, 13, 32, 33): what
 * `team.ask_owner` does. The owner answers in the app ([com.aicontrolcenter.core.operator.OwnerRequests.answer]);
 * nothing here can answer, grant, approve or close another request.
 *
 * Only the assignee of a RUNNING WORK task of a RUNNING objective may ask, and only while the
 * owner allows agents' questions ([com.aicontrolcenter.core.settings.FrameworkSettings.agentQuestionsEnabled]).
 * A question is checked ([QuestionRules.questionProblem]: length, at most four suggested answers,
 * none using an approval word or mixing alphabets) and then, under the one lock of
 * [OwnerAttentionGate] shared with the permission broker and the capability requester (so
 * concurrent asks cannot pass a cap together), refused when (rule 13):
 *  - a PERMISSION or APPROVAL of the same task waits for the owner;
 *  - the task has a question open ([OwnerAttention.MAX_OPEN_QUESTIONS_PER_TASK]), or asked
 *    [OwnerAttention.MAX_QUESTIONS_PER_TASK] already, or its objective
 *    [OwnerAttention.MAX_QUESTIONS_PER_OBJECTIVE];
 *  - the task asked nearly the same before ([QuestionRules.nearlySame]);
 *  - too many agent-initiated requests are open ([OwnerAttentionGate.agentRequestRefusal]).
 * A refusal tells the model to decide itself, say so and go on; the owner is told once per task.
 *
 * Otherwise a QUESTION request is raised (SAFE, no constraint, no scope: it can give nothing) and
 * the call waits exactly as a tool call waits for an approval ([OwnerRequestWait]): outside every
 * time limit, the task WAITING_FOR_OWNER, its deadline moved by the wait, the request closed as
 * CANCELLED if the call is stopped (pause, cancel, task end) and as EXPIRED after
 * `ownerQuestionTimeoutMinutes`. The question (agent → owner) and the answer (owner → agent) are
 * stored as messages of the objective; the log names them without the owner's words.
 *
 * What the model reads back: the owner's answer framed as quoted data ("The owner answered, in
 * their own words:" and a [DataBlock]); "decide yourself"; or, at the time-out, "Make the most
 * reasonable choice, say which one in your result, and go on." After any answer the task's next
 * call that may send information off the phone asks the owner first ([AnsweredQuestions], rule 32).
 */
class OwnerQuestions(
    private val requests: OwnerRequestQueue,
    waiters: RequestWaiters,
    private val tasks: TaskRepository,
    private val messages: MessageRepository,
    /** The agent as stored and the settings, read fresh. */
    private val facts: OperatorFacts,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    /** The one lock shared with the permission broker and the capability requester. */
    attention: OwnerAttentionGate,
    /** Where the time the question waited is noted, for the bulk rule's window (D12). */
    ownerWaits: OwnerWaitLedger?,
    /** Shared with the permission broker: a task whose question was answered (rule 32). */
    private val answered: AnsweredQuestions,
) {
    private val waiting = OwnerRequestWait(requests, grants = null, waiters, tasks, clock, attention, ownerWaits)

    /** Asks the owner [question] for the call of [context], with at most four suggested [options], and waits for the answer. */
    suspend fun ask(context: ToolContext, question: String, options: List<String>): ToolOutcome {
        val settings = facts.settings()
        if (!settings.agentQuestionsEnabled) return ToolOutcome.failure(SWITCHED_OFF)
        val task = context.taskId?.let { tasks.get(it) }
        // RUNNING, or waiting for the owner on another call of its own (then rule 13 says why not, below).
        if (task == null || task.kind != TaskKind.WORK || task.assignee != context.agent.id || task.status !in ASKING_STATUSES) {
            return ToolOutcome.failure(OUTSIDE_TASK)
        }
        val objective = tasks.get(task.rootId)?.takeIf { it.kind == TaskKind.OBJECTIVE && it.status == TaskStatus.RUNNING }
            ?: return ToolOutcome.failure(OUTSIDE_TASK)
        val text = question.trim()
        val suggested = options.map { it.trim() }.filter { it.isNotEmpty() }
        QuestionRules.questionProblem(text, suggested)?.let { return ToolOutcome.failure("The question was not asked: $it") }
        val agent = facts.agent(context.agent.id) ?: return ToolOutcome.failure("This agent no longer exists.")
        val now = clock.now()
        val minutes = settings.ownerQuestionTimeoutMinutes.coerceIn(SettingsBounds.QUESTION_TIMEOUT_MINUTES)
        val request = OwnerRequest(
            id = ids.requestId(), kind = OwnerRequestKind.QUESTION, agentId = agent.id, toolId = TeamTools.ASK_OWNER_ID,
            capability = TeamTools.COMMUNICATE, risk = RiskLevel.SAFE, summary = "A question from ${agent.name}",
            taskId = task.id, rootId = objective.id, conversationId = context.conversationId,
            createdAt = now, expiresAt = now + minutes * MINUTE_MS, agentName = agent.name, toolTitle = TITLE,
            delegationChain = context.delegationChain, question = text, options = suggested,
        )
        return waiting.ask(
            request, context, OwnerRequestWait.line("Waiting for the owner's answer: $text", MAX_DETAIL),
            admit = { live -> refusal(context, task, objective, text, live) },
            afterRaise = { send(task, Participant.Agent(agent.id), Participant.Owner, MessageType.QUESTION, questionBody(text, suggested), "Question") },
            use = { closed -> outcome(closed, task) },
            finished = { closed, _ -> logEnd(closed) },
            refused = { reason ->
                waiting.attention.tellOwnerOnce(context, "ask you a question", reason)
                ToolOutcome.failure(reason)
            },
        )
    }

    /** Rule 13's refusals, under the lock, given the [live] pending requests. Null to ask. */
    private suspend fun refusal(context: ToolContext, task: TaskRecord, objective: TaskRecord, text: String, live: List<OwnerRequest>): String? {
        if (live.any { it.taskId == task.id && it.kind != OwnerRequestKind.QUESTION }) {
            return "A permission or an approval of this task waits for the owner, so no question is asked beside it. $DECIDE"
        }
        if (live.count { it.taskId == task.id && it.kind == OwnerRequestKind.QUESTION } >= OwnerAttention.MAX_OPEN_QUESTIONS_PER_TASK) {
            return "Your earlier question in this task still waits for the owner. $DECIDE"
        }
        val asked = messages.list(objective.id).filter { it.type == MessageType.QUESTION && it.to == Participant.Owner }
        val ofTask = asked.filter { it.taskId == task.id }
        if (ofTask.size >= OwnerAttention.MAX_QUESTIONS_PER_TASK) {
            return "This task already asked the owner ${OwnerAttention.MAX_QUESTIONS_PER_TASK} questions, the most one task may ask. $DECIDE"
        }
        if (asked.size >= OwnerAttention.MAX_QUESTIONS_PER_OBJECTIVE) {
            return "This objective already asked the owner ${OwnerAttention.MAX_QUESTIONS_PER_OBJECTIVE} questions, the most one objective may ask. $DECIDE"
        }
        if (ofTask.any { QuestionRules.nearlySame(questionOf(it.body), text) }) {
            return "You already asked the owner nearly the same question in this task. Use the answer you got, or $DECIDE_LOWER"
        }
        return waiting.attention.agentRequestRefusal(context, live)
    }

    /** What the closed question means for the model. */
    private suspend fun outcome(closed: OwnerRequest, task: TaskRecord): ToolOutcome {
        val minutes = ((closed.expiresAt - closed.createdAt) / MINUTE_MS).coerceAtLeast(1)
        return when (closed.status) {
            OwnerRequestStatus.APPROVED -> {
                // Rule 32: the owner's words may now be in what the agent sends; its next call off the phone asks first.
                answered.mark(task.id)
                val words = closed.answer
                send(task, Participant.Owner, Participant.Agent(closed.agentId), MessageType.ANSWER, words ?: DECIDE_YOURSELF_BODY, "Answer")
                if (words == null) {
                    ToolOutcome.success("The owner asked you to decide yourself. Make the most reasonable choice, say which one in your result, and go on.")
                } else {
                    ToolOutcome.success("The owner answered, in their own words:\n" + DataBlock.frame(words, "The owner's answer"))
                }
            }
            OwnerRequestStatus.EXPIRED -> ToolOutcome.success(
                "The owner did not answer within $minutes minutes. Make the most reasonable choice, say which one in your result, and go on.",
            )
            OwnerRequestStatus.CANCELLED, OwnerRequestStatus.PENDING, OwnerRequestStatus.DENIED -> ToolOutcome.success(
                "The question was withdrawn (${closed.decisionNote ?: PermissionBroker.CANCELLED_NOTE}). Make the most reasonable choice, " +
                    "say which one in your result, and go on.",
            )
        }
    }

    /**
     * Stores a message of the question's objective and logs it. The log names the question's first
     * line, never the owner's words (the answer stays in the objective's messages only).
     */
    private suspend fun send(task: TaskRecord, from: Participant, to: Participant, type: MessageType, body: String, label: String) {
        val message = AgentMessage(
            id = ids.messageId(), rootId = task.rootId, taskId = task.id, from = from, to = to, type = type,
            body = body.trim().take(MAX_BODY), at = clock.now(),
        )
        messages.append(message)
        val agentId = (from as? Participant.Agent)?.agentId ?: (to as? Participant.Agent)?.agentId
        val name = agentId?.let { facts.agent(it)?.name } ?: "An agent"
        logSafely {
            activity.record(
                ActivityType.MESSAGE,
                if (from == Participant.Owner) "$label: Owner → $name" else "$label: $name → Owner",
                if (type == MessageType.ANSWER) "You answered a question · Task: ${task.title}" else "${firstLine(questionOf(body))} · Task: ${task.title}",
                agentId = agentId, taskId = task.id, rootTaskId = task.rootId,
                data = buildJsonObject {
                    put("messageId", message.id.value)
                    put("type", type.name)
                    put("hop", 0)
                },
            )
        }
    }

    private suspend fun logEnd(closed: OwnerRequest) = logSafely {
        val who = closed.agentName ?: "an agent"
        val (title, severity) = when (closed.status) {
            OwnerRequestStatus.APPROVED -> (if (closed.answer == null) "You let $who decide itself" else "You answered $who's question") to Severity.INFO
            OwnerRequestStatus.EXPIRED -> "No answer in time: $who's question" to Severity.WARNING
            else -> "Question withdrawn: $who's question" to Severity.WARNING
        }
        activity.record(
            ActivityType.MESSAGE, title, closed.decisionNote.orEmpty().take(MAX_LOGGED), severity = severity,
            agentId = closed.agentId, taskId = closed.taskId, rootTaskId = closed.rootId,
            data = buildJsonObject {
                put("requestId", closed.id.value)
                put("kind", closed.kind.name)
                put("status", closed.status.name)
                put("answered", closed.answer != null)
            },
        )
    }

    /** Texts and limits. */
    companion object {
        /** The tool's title, also on the request. */
        const val TITLE = "Ask the owner"

        const val SWITCHED_OFF =
            "The owner has switched off questions from agents. Make the most reasonable choice, say which one in your result, and go on."

        const val OUTSIDE_TASK = "Agents can ask the owner a question only while working on a task of one of the owner's objectives."

        private val ASKING_STATUSES = setOf(TaskStatus.RUNNING, TaskStatus.WAITING_FOR_OWNER)

        private const val DECIDE_LOWER = "make the most reasonable choice, say in your result what you chose and what still needs the owner, and go on."
        private const val DECIDE = "Nothing was asked. Make the most reasonable choice, say in your result what you chose and what still needs the owner, and go on."
        private const val DECIDE_YOURSELF_BODY = "The owner asked you to decide yourself."
        private const val SUGGESTED = "\n\nSuggested answers:\n"
        private const val MINUTE_MS = 60_000L
        private const val MAX_DETAIL = 300
        private const val MAX_BODY = 6_000
        private const val MAX_LOGGED = 600
        private const val MAX_LINE = 120

        /** The question's message: the question, then the suggested answers, one per line. */
        fun questionBody(question: String, options: List<String>): String =
            if (options.isEmpty()) question else question + SUGGESTED + options.joinToString("\n") { "- $it" }

        /** The question of a question message, without its suggested answers. */
        fun questionOf(body: String): String = body.substringBefore(SUGGESTED)

        private fun firstLine(text: String): String {
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return OwnerRequestWait.line(line, MAX_LINE)
        }
    }
}

/**
 * The tasks whose agent got the owner's answer to a question (Phase 6, rule 32, second part):
 * until the owner has approved one call of such a task (or of a task under it) that may send
 * information off the phone, every such call asks the owner first ([PermissionBroker]), with a
 * preview that says it is asked after an answer. In memory, one flag per task, bounded; after a
 * restart a task starts afresh and asks anew.
 */
class AnsweredQuestions(private val maxTasks: Int = 1_000) {
    private val flags = object : LinkedHashMap<TaskId, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TaskId, Unit>): Boolean = size > maxTasks
    }

    /** The owner answered a question of [task]. */
    @Synchronized
    fun mark(task: TaskId) {
        flags[task] = Unit
    }

    /** Ends [task]'s flag: the owner approved a call after the answer. */
    @Synchronized
    fun clear(task: TaskId) {
        flags.remove(task)
    }

    @Synchronized
    fun isMarked(task: TaskId): Boolean = task in flags

    /**
     * The flagged task that [taskId] is or works under (its parents, for a delegated task), read in
     * [tasks]; null when none is flagged or there is no task.
     */
    suspend fun flagged(taskId: TaskId?, tasks: TaskRepository?): TaskId? {
        var current = taskId ?: return null
        repeat(MAX_WALK) {
            if (isMarked(current)) return current
            val parent = tasks?.get(current)?.parentId ?: return null
            if (parent == current) return null
            current = parent
        }
        return null
    }

    private companion object {
        const val MAX_WALK = 16
    }
}
