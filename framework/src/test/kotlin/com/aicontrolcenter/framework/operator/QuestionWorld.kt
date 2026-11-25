package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.OwnerKeyCheck
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.orchestration.TeamTools
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecution
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.InMemoryMessageRepository
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.testTimeSource

/**
 * Questions to the owner over the real permission broker, the real tool executor and the real
 * owner port, sharing one [OwnerAttentionGate], one [OwnerWaitLedger] and one [AnsweredQuestions],
 * as the app wires them (OperatorWiring). The agent "Operator" works on the RUNNING task
 * "tsk_work" of the RUNNING objective "tsk_root"; time is the test's virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestionWorld(val test: TestScope, keyCheck: OwnerKeyCheck = OwnerKeyCheck.None) {
    val b = BrokerFixture(test)
    val messages = InMemoryMessageRepository()
    val ledger = OwnerWaitLedger()
    val answered = AnsweredQuestions()
    val attention = OwnerAttentionGate(b.requests, b.waiters, b.activity)
    val broker = PermissionBroker(b.facts, b.requests, b.grants, b.waiters, b.tasks, b.activity, b.ids, b.clock, attention, ledger, answered)
    val owner = DefaultOwnerRequests(b.requests, b.waiters, b.facts, b.activity, b.ids, b.clock, keyCheck)
    val questions = OwnerQuestions(b.requests, b.waiters, b.tasks, messages, b.facts, b.activity, b.ids, b.clock, attention, ledger, answered)
    val registry = DefaultToolRegistry()
    val executor = ToolExecutor(registry, broker, b.activity, b.clock, timeSource = test.testTimeSource, tasks = b.tasks)
    lateinit var agent: AgentProfile
    private var calls = 0

    suspend fun setUp(): QuestionWorld {
        b.setUp()
        TeamTools.registerAskOwner(registry, b.capabilities, questions)
        agent = b.agent.copy(toolIds = b.agent.toolIds + TeamTools.ASK_OWNER_ID)
        b.agents.upsert(agent)
        b.agent = agent
        b.runningTask("tsk_work", deadlineAt = START + 15 * BrokerFixture.MINUTE)
        return this
    }

    fun context(task: String = "tsk_work", who: AgentProfile = agent): ToolContext = b.context(task = task, who = who)

    /** Starts `team_ask_owner` through the executor and lets it run until it waits or ends. */
    fun ask(question: String, vararg options: String, task: String = "tsk_work", who: AgentProfile = agent): Deferred<ToolExecution> {
        val args = if (options.isEmpty()) json("question" to question) else json("question" to question, "options" to options.toList())
        return test.async { executor.execute(ToolCallRequest("q${++calls}", "team_ask_owner", args), context(task, who)) }.also { test.runCurrent() }
    }

    /** Starts the broker's decision of [spec]'s call in [task] and lets it run until it waits or ends. */
    fun authorize(spec: ToolSpec, assessment: ActionAssessment, task: String = "tsk_work"): Deferred<Authorization> =
        test.async { broker.authorize(spec, assessment, context(task)) }.also { test.runCurrent() }

    suspend fun pendingQuestion(): OwnerRequest = b.requests.observePending().first().single { it.kind == OwnerRequestKind.QUESTION }

    suspend fun questionMessages() = messages.list(TaskId("tsk_root")).filter { it.type == MessageType.QUESTION }

    suspend fun answerMessages() = messages.list(TaskId("tsk_root")).filter { it.type == MessageType.ANSWER }

    /** Another RUNNING work task of the objective, assigned to the agent. */
    suspend fun workTask(id: String, parent: String = "tsk_root"): TaskRecord {
        val task = TaskRecord(
            id = TaskId(id), rootId = TaskId("tsk_root"), parentId = TaskId(parent), kind = TaskKind.WORK, title = "Task $id",
            instructions = "Do it.", assignee = agent.id, requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING,
            createdAt = START, updatedAt = START, deadlineAt = START + 15 * BrokerFixture.MINUTE,
        )
        b.tasks.upsert(task)
        return task
    }

    companion object {
        val ASK_OWNER: ToolId = TeamTools.ASK_OWNER_ID
    }
}
