package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import com.aicontrolcenter.testkit.InMemoryOwnerRequestRepository
import com.aicontrolcenter.testkit.InMemoryPermissionRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.InMemorySharedFolderRepository
import com.aicontrolcenter.testkit.InMemoryTaskRepository
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject

/**
 * The permission broker over in-memory stores, on a clock that follows the test's virtual time,
 * so a request's expiry and the broker's wait agree. One agent ("Operator"), one shared folder
 * ("Reports") and one authorised app ("Notes"); the phone operator is switched on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrokerFixture(val test: TestScope, private val queue: (OwnerRequestQueue) -> OwnerRequestQueue = { it }) {
    val clock = Clock { START + test.testScheduler.currentTime }
    val ids = SequentialIdGenerator()
    val log = InMemoryActivityLog()
    val activity = ActivityRecorder(log, ids, clock)
    val agents = InMemoryAgentRepository()
    val settings = InMemorySettingsRepository(FrameworkSettings(operatorEnabled = true, accessibilityOperatorEnabled = true, approvalTimeoutMinutes = 30))
    val capabilities = CapabilityRegistry(Capabilities.builtIn + OperatorCapabilities.all)
    val grants = InMemoryPermissionRepository()
    val requests = InMemoryOwnerRequestRepository(grants)
    val folders = InMemorySharedFolderRepository()
    val apps = InMemoryOperatorAppRepository()
    val tasks = InMemoryTaskRepository()
    val screenStopped = MutableStateFlow(false)
    val protectedPackages = ProtectedPackages(OWN_PACKAGE)
    val facts = OperatorFacts(agents, settings, capabilities, folders.asDirectory(), apps.asAuthorisedApps(), screenStopped) { protectedPackages }
    val waiters = RequestWaiters()
    val broker = PermissionBroker(facts, queue(requests), grants, waiters, tasks, activity, ids, clock)
    val owner = DefaultOwnerRequests(requests, waiters, facts, activity, ids, clock)

    val folder = SharedFolder(FolderId("fld_reports"), "content://tree/reports", "Reports", 10)
    val app = OperatorApp("com.example.notes", "Notes", 20, "ab".repeat(32))
    lateinit var agent: AgentProfile

    val readFolder = spec("folders.read", OperatorCapabilities.FOLDERS_READ, RiskLevel.SAFE)
    val writeFolder = spec("folders.write", OperatorCapabilities.FOLDERS_WRITE, RiskLevel.MODIFYING)
    val deleteInFolder = spec("folders.delete", OperatorCapabilities.FOLDERS_WRITE, RiskLevel.CONSEQUENTIAL)
    val readWorkspace = spec("workspace.read", OperatorCapabilities.WORKSPACE_READ, RiskLevel.SAFE)
    val tap = spec("screen.tap", OperatorCapabilities.SCREEN_OPERATE, RiskLevel.MODIFYING, category = ToolCategory.ACCESSIBILITY)
    val home = spec("screen.home", OperatorCapabilities.SCREEN_OPERATE, RiskLevel.MODIFYING, category = ToolCategory.ACCESSIBILITY)
    val readPage = spec("web.read_page", OperatorCapabilities.WEB_READ, RiskLevel.SAFE, sendsDataOffDevice = true, category = ToolCategory.WEB)
    val clockTool = spec("clock.now", Capabilities.BASIC_INFO, RiskLevel.SAFE, category = ToolCategory.SYSTEM)

    val inFolder = CapabilityConstraint.Folder(folder.id)
    val inNotes = CapabilityConstraint.App(app.packageName)

    suspend fun setUp(): BrokerFixture {
        agent = Samples.agent(id = "agt_op", name = "Operator").copy(
            toolIds = listOf(readFolder, writeFolder, deleteInFolder, readWorkspace, tap, home, readPage, clockTool).map { it.id }.toSet(),
        )
        agents.upsert(agent)
        folders.upsert(folder)
        apps.upsert(app)
        return this
    }

    fun context(task: String? = "tsk_work", conversation: String? = null, who: AgentProfile = agent) = ToolContext(
        who, task?.let(::TaskId), TaskId("tsk_root"), listOf(AgentId("agt_director")), conversation?.let(::ConversationId),
    )

    fun assessment(
        risk: RiskLevel = RiskLevel.SAFE,
        constraint: CapabilityConstraint = inFolder,
        summary: String = "Write \"notes.txt\" in Reports",
        details: List<String> = listOf("12 bytes"),
        dataGoesTo: String? = null,
        leadsTo: String? = null,
    ) = ActionAssessment(risk, summary, details, constraint, dataGoesTo, leadsTo = leadsTo)

    /** Starts [spec]'s authorisation and lets it run until it waits or ends. */
    fun start(spec: ToolSpec, assessment: ActionAssessment = assessment(), context: ToolContext = context()): Deferred<Authorization> =
        test.async { broker.authorize(spec, assessment, context) }.also { test.runCurrent() }

    /** The one request waiting for the owner. */
    suspend fun pending(): OwnerRequest = requests.observePending().first().single()

    suspend fun allRequests(): List<OwnerRequest> = requests.observeRecent(100).first()

    /** A WORK task the agent is running, for the broker to mark while it waits. */
    suspend fun runningTask(id: String = "tsk_work", deadlineAt: Long? = START + 60_000, parent: String = "tsk_root", status: TaskStatus = TaskStatus.RUNNING): TaskRecord {
        val task = TaskRecord(
            id = TaskId(id), rootId = TaskId("tsk_root"), parentId = TaskId(parent), kind = TaskKind.WORK, title = "Tidy the reports",
            instructions = "Tidy them.", assignee = agent.id, requestedBy = TaskRequester.Owner, status = status,
            statusDetail = "Operator is working.", createdAt = START, updatedAt = START, deadlineAt = deadlineAt,
        )
        tasks.upsert(task)
        return task
    }

    fun events(type: ActivityType): List<ActivityEvent> = log.all.filter { it.type == type }

    companion object {
        const val START = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val OWN_PACKAGE = "com.aicontrolcenter.app"

        fun spec(
            id: String,
            capability: Capability,
            risk: RiskLevel,
            sendsDataOffDevice: Boolean = false,
            category: ToolCategory = ToolCategory.FILES,
        ) = ToolSpec(
            ToolId(id), id.replace('.', '_'), "Title of $id", "A test tool.", JsonObject(emptyMap()), category, capability, risk, sendsDataOffDevice,
        )
    }
}
