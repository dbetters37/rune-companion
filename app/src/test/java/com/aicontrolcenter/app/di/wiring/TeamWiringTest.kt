package com.aicontrolcenter.app.di.wiring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.teamAtWork
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.ReadOnlyRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.orchestration.OwnerNotes
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * WP7's wiring ([TeamAssembly]): the owner's notes are the real [OwnerNotes], built apart from the orchestrator; the
 * orchestrator publishes its timed waits; the maintenance gate stops new objectives and chat turns while a backup is
 * restored; and an objective's options, all of them, survive the Room store and the recovery after a restart (security
 * review of wave 1, F12).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TeamWiringTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val data = DataLayer(context, databaseName = "team-wiring-test.db", databaseEncryption = DatabaseEncryption.None)

    @After
    fun close() {
        runBlocking { data.close() }
    }

    @Test
    fun `the owner's notes are OwnerNotes, apart from the orchestrator, and reach the team's store`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val objective = app.teamAtWork()
        val graph = app.graph
        assertIs<OwnerNotes>(graph.ownerConversation)
        assertNotSame<Any>(graph.ownerConversation, graph.orchestrator)
        val added = graph.ownerConversation.addNote(objective.id, null, "Only free apps, please.")
        assertIs<OwnerResult.Done<*>>(added, "$added")
        val note = app.messages.list(objective.id).single { it.type == MessageType.GUIDANCE }
        assertEquals(Participant.Owner, note.from)
        graph.orchestrator.cancel(objective.id)
        assertEquals(OwnerResult.Refused(OwnerNotes.FINISHED), graph.ownerConversation.addNote(objective.id, null, "Too late"))
    }

    @Test
    fun `the orchestrator publishes its timed waits, and nothing starts while a backup is restored`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val graph = app.graph
        assertTrue(graph.orchestrator.timedWaits !== TimedWaits.None, "the background service reads the orchestrator's waits")
        assertEquals(null, graph.orchestrator.timedWaits.nextDueAt.value)

        val conversation = graph.agentChat.startConversation(app.agents.getAll().first().id)
        val refused = graph.maintenanceGate.runExclusive {
            val objective = runCatching { graph.orchestrator.startObjective("Research note apps") }
            val turn = graph.agentChat.send(conversation.id, "Hello")
            objective to turn
        }
        val (objective, turn) = assertIs<OwnerResult.Done<Pair<Result<*>, TurnResult>>>(refused).value
        assertEquals(MaintenanceGate.RESTORING, objective.exceptionOrNull()?.message)
        assertEquals(TurnResult.Failed(MaintenanceGate.RESTORING, retryable = true), turn)
        assertTrue(app.tasks.withStatus(TaskStatus.entries.toSet()).isEmpty(), "no objective was stored")
        assertTrue(app.conversations.getEntries(conversation.id).isEmpty(), "no message was stored")
        // Once the restore is over, both work.
        graph.orchestrator.startObjective("Research note apps")
        assertIs<TurnResult.Completed>(graph.agentChat.send(conversation.id, "Hello"))
    }

    @Test
    fun `an objective's options, all of them, survive Room and the recovery after a restart`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val director = AgentProfile(AgentId("agt_director"), "Director", AgentKind.DIRECTOR, createdAt = 1, updatedAt = 1)
        data.agents.upsert(director)
        val snapshot = RegistrySnapshot(agents = listOf(AgentEntry(director, AgentAvailability.Ready(ModelId("mdl_any"), false), AgentStats(), isDesignatedDirector = true)))
        val directory = object : RegistryDirectory {
            override fun observe(): Flow<RegistrySnapshot> = flowOf(snapshot)

            override suspend fun snapshot(): RegistrySnapshot = snapshot
        }
        val settings = ReadOnlySettings(InMemorySettingsRepository(FrameworkSettings(directorAgentId = director.id)))
        val ids = SequentialIdGenerator()
        // Each "app" runs its jobs on a dispatcher nobody advances: killed right after the objective was stored.
        fun orchestrator(scope: CoroutineScope) = DefaultOrchestrator(
            data.tasks, data.messages, data.conversations, ReadOnlyRegistry.agents(data.agents), directory, settings,
            app.graph.agentsAssembly.agentRunner, app.graph.toolRegistry, DefaultAgentActivityMonitor(app.clock),
            ActivityRecorder(data.activityLog, ids, app.clock), ids, app.clock, scope,
        )
        val options = ObjectiveOptions(reviewResults = false, saveKnowledge = true, maxTasks = 4, screenActionsAllowed = false, approvalTimeoutMinutes = 90)
        val killed = TestScope(StandardTestDispatcher())
        val objective = orchestrator(killed).startObjective("Research note apps", options)
        killed.cancel()
        assertEquals(options, data.tasks.get(objective.id)?.options, "through Room")

        val restarted = TestScope(StandardTestDispatcher())
        val after = orchestrator(restarted)
        assertEquals(listOf(objective.id), after.recoverInterrupted(null, resumeAllowed = false))
        val recovered = data.tasks.get(objective.id)!!
        assertEquals(TaskStatus.INTERRUPTED, recovered.status)
        assertEquals(options, recovered.options, "through the recovery")
        after.resume(objective.id)
        assertEquals(TaskStatus.RUNNING, data.tasks.get(objective.id)?.status)
        assertEquals(options, data.tasks.get(objective.id)?.options, "through the resume")
        restarted.cancel()
    }
}
