package com.aicontrolcenter.app.work

import android.app.AlarmManager
import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.AgentFacingWalk
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.health.AndroidStartupHooks
import com.aicontrolcenter.app.health.CrashNotes
import com.aicontrolcenter.app.health.SafeStart
import com.aicontrolcenter.app.health.StartLedger
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.OwnerKeyCheck
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.memory.MaintenanceReport
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.recovery.DatabaseHealth
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import com.aicontrolcenter.testkit.FakeBackgroundHealth
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeExitHistory
import com.aicontrolcenter.testkit.FakeStorageWatch
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.ZoneOffset
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The upkeep job (Phase 6, R9, D36, rule 1): what it reaches, when it does nothing, and that it never goes online. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpkeepJobTest {
    @get:Rule val folder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val open = mutableListOf<DataLayer>()
    private val clock = FakeClock(1_795_000_000_000)

    @After
    fun close() = runBlocking { open.forEach { it.close() } }

    /** Counts calls into the memory service it stands in front of. */
    private class CountingMemory(private val inner: MemoryService) : MemoryService by inner {
        var maintained = 0

        override suspend fun maintain(): MaintenanceReport {
            maintained++
            return inner.maintain()
        }
    }

    private fun ports(data: DataLayer, memory: MemoryService, version: Long = 9, check: DatabaseHealth? = null, gate: LocalMaintenanceGate = LocalMaintenanceGate()): UpkeepPorts {
        val base = UpkeepPorts(
            memory = memory, activityLog = data.activityLog, usageHousekeeping = data.usageHousekeeping,
            settings = ReadOnlySettings(data.settings), gate = gate, clock = clock, zone = { ZoneOffset.UTC },
            noBackupFilesDir = folder.root, versionCode = version,
        )
        return if (check == null) base else UpkeepPorts(
            base.memory, base.activityLog, base.usageHousekeeping, base.settings, base.gate, base.clock, base.zone, base.noBackupFilesDir, base.versionCode,
            databaseCheck = check,
        )
    }

    private fun dataLayer() = DataLayer(context, databaseEncryption = DatabaseEncryption.None).also(open::add)

    private fun memory(data: DataLayer) = CountingMemory(
        DefaultMemoryService(
            repository = data.memories, agents = data.agents, settings = ReadOnlySettings(data.settings), embedder = HashingEmbedder(),
            activity = ActivityRecorder(data.activityLog, RandomIdGenerator, WallClock), ids = RandomIdGenerator, clock = WallClock,
            config = MemoryConfig(appVersion = "test"), computeContext = Dispatchers.Unconfined,
        ),
    )

    private fun startedOk(version: Long) = StartLedger.inFolder(folder.root).update { SafeStart.atOk(SafeStart.atStart(it, version, 1, emptyList()), version, 2) }

    /** Records every connection anything in the process tries to open through the platform's proxy choice. */
    private class RecordingProxySelector : ProxySelector() {
        val asked: MutableList<URI> = Collections.synchronizedList(mutableListOf())

        override fun select(uri: URI): List<Proxy> {
            asked += uri
            return listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    @Test
    fun `a run does memory upkeep, prunes the activity log and usage, and never opens a connection`() = runTest {
        val data = dataLayer()
        data.settings.update { it.copy(activityRetentionDays = 30) }
        val old = Samples.event().copy(at = clock.current - 40L * 24 * 60 * 60 * 1000)
        val recent = Samples.event().copy(id = com.aicontrolcenter.core.EventId("evt_recent"), at = clock.current - 1000)
        data.activityLog.record(old)
        data.activityLog.record(recent)
        val memory = memory(data)
        startedOk(9)
        var stepRan = false
        val upkeep = UpkeepGraph(ports(data, memory), steps = listOf(UpkeepStep { stepRan = true }))

        val previous = ProxySelector.getDefault()
        val recording = RecordingProxySelector()
        ProxySelector.setDefault(recording)
        try {
            upkeep.run()
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertNull(upkeep.lastSkip)
        assertEquals(1, memory.maintained)
        assertEquals(listOf(recent.id), data.activityLog.recent().map { it.id }.filter { it == recent.id || it == old.id })
        assertTrue(stepRan, "the steps other packages add run after the job's own")
        assertEquals(emptyList(), recording.asked, "the upkeep job never reaches a provider, a web page or anything else online")
    }

    @Test
    fun `after an update, in safe start, during a restore and when the data does not open, the job does nothing`() = runTest {
        val data = dataLayer()
        val memory = memory(data)

        // No start reached "ok" at this version (the app was updated and not opened yet).
        startedOk(8)
        val updated = UpkeepGraph(ports(data, memory, version = 9))
        updated.run()
        assertEquals(UpkeepGraph.Skip.NOT_STARTED_AT_THIS_VERSION, updated.lastSkip)

        StartLedger.inFolder(folder.root).update { SafeStart.atOk(it, 9, 3).copy(safeStart = true) }
        val safe = UpkeepGraph(ports(data, memory))
        safe.run()
        assertEquals(UpkeepGraph.Skip.SAFE_START, safe.lastSkip)

        StartLedger.inFolder(folder.root).update(SafeStart::left)
        val gate = LocalMaintenanceGate()
        val restoring = UpkeepGraph(ports(data, memory, gate = gate))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val restore = backgroundScope.launch(Dispatchers.Default) { gate.runExclusive { started.complete(Unit); release.await() } }
        started.await()
        restoring.run()
        assertEquals(UpkeepGraph.Skip.RESTORING, restoring.lastSkip)
        release.complete(Unit)
        restore.join()

        val broken = UpkeepGraph(ports(data, memory, check = DatabaseHealth { throw IllegalStateException("file is not a database") }))
        broken.run()
        assertEquals(UpkeepGraph.Skip.DATABASE, broken.lastSkip)
        assertEquals(0, memory.maintained, "nothing ran in any of these")

        val fine = UpkeepGraph(ports(data, memory))
        fine.run()
        assertNull(fine.lastSkip)
        assertEquals(1, memory.maintained)
    }

    @Test
    fun `the default database check opens the database through the activity log, as the app's check does`() = runTest {
        val data = dataLayer()
        assertEquals(DatabaseState.OK, ports(data, memory(data)).databaseCheck.check())
        val failing = UpkeepPorts(
            memory(data), object : com.aicontrolcenter.core.activity.ActivityLog by data.activityLog {
                override suspend fun recent(filter: com.aicontrolcenter.core.activity.ActivityFilter, limit: Int) =
                    throw com.aicontrolcenter.data.security.DatabaseKeyException("gone", problem = com.aicontrolcenter.data.security.KeyProblem.MISSING)
            },
            data.usageHousekeeping, ReadOnlySettings(data.settings), LocalMaintenanceGate(), clock, { ZoneOffset.UTC }, folder.root, 9,
        )
        assertEquals(DatabaseState.KEY_MISSING, failing.databaseCheck.check())
    }

    @Test
    fun `a failing step does not stop the others`() = runTest {
        val data = dataLayer()
        startedOk(9)
        var later = false
        val upkeep = UpkeepGraph(ports(data, memory(data)), steps = listOf(UpkeepStep { throw IllegalStateException("broken") }, UpkeepStep { later = true }))
        upkeep.run()
        assertTrue(later)
    }

    @Test
    fun `the job runs the application's upkeep, finishes, and asks to run again when Android stops it`() {
        val service = Robolectric.buildService(UpkeepJobService::class.java).create().get()
        assertTrue(service.onStartJob(null), "work goes on after onStartJob returns")
        assertTrue(service.onStopJob(null), "stopped by Android: run again later")
        service.onDestroy()
    }

    /** Rule 1: from the job, nothing reaches the graph, a model, a tool, a provider, the vault or the owner's stores. */
    @Test
    fun `the upkeep the job reaches holds no graph, orchestrator, gateway, executor, chat, provider manager, vault or owner store`() {
        val app = context as AiControlCenterApp
        val upkeep = (app as UpkeepHost).upkeep
        val forbidden = listOf(
            AppGraph::class.java, Orchestrator::class.java, ModelGateway::class.java, ToolExecutor::class.java, AgentChat::class.java,
            ProviderManager::class.java, SecretVault::class.java, PermissionRepository::class.java, OwnerRequestRepository::class.java,
            OwnerKeyCheck::class.java, AiControlCenterApp::class.java, DataLayer::class.java,
        )
        val walk = AgentFacingWalk(ownerOnly = forbidden, checkActions = false)
        val result = walk.walk(upkeep, "upkeep")
        assertEquals(emptyList(), result.violations)
        assertTrue(result.objects > 10, "the walk saw the upkeep's ports (${result.objects} objects)")
        // The walk sees what it looks for: the application's graph holder is caught.
        val caught = walk.walk(object { val app = app }, "probe")
        assertTrue(caught.violations.isNotEmpty())
        assertNull(app.graphIfBuilt(), "building the upkeep built no graph")
    }

    @Test
    fun `a fresh app with nothing running starts no service and no alarm, and schedules only the upkeep job`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val testApp = TestApp(scope)
            GraphServices.start(context, testApp.graph, scope)
            testApp.graph.onAppForegrounded()
            val hooks = AndroidStartupHooks(
                StartLedger.inFolder(folder.root), FakeExitHistory(), FakeStorageWatch(), FakeBackgroundHealth(), CrashNotes.inFolder(folder.root),
                versionCode = 9, clock = clock, scheduleUpkeep = { UpkeepJobService.schedule(context) }, recorder = null, io = Dispatchers.Unconfined,
            )
            hooks.beforeSteps()
            hooks.afterSteps()
            assertNull(shadowOf(context).nextStartedService, "no foreground service with nothing running")
            assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
            val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs
            assertEquals(listOf(UpkeepJobService.JOB_ID), jobs.map { it.id })
            assertFalse(jobs.single().isPersisted)
        } finally {
            scope.cancel()
        }
        assertNotNull(context)
    }
}
