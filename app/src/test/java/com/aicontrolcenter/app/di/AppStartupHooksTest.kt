package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.framework.memory.MemorySeeder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 (WP1d): the start-up hooks of the background package (WP2) and the step for interrupted chat replies (R3).
 * The ledger comes first, the replies are marked before objectives are recovered, the cause and "may resume" reach
 * the orchestrator, the ledger's "started fully" comes last, and a hook that fails stops nothing.
 */
class AppStartupHooksTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val events: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun startup(app: TestApp, scope: CoroutineScope, hooks: StartupHooks): AppStartup {
        val graph = app.graph
        val orchestrator = object : Orchestrator by graph.orchestrator {
            override suspend fun recoverInterrupted(cause: String?, resumeAllowed: Boolean): List<TaskId> {
                events += "recover($cause, $resumeAllowed)"
                return emptyList()
            }
        }
        val chat = object : AgentChat by graph.agentChat {
            override suspend fun recoverInterruptedTurns(): Int {
                events += "chat"
                return 0
            }
        }
        return AppStartup(
            scope = scope,
            agentManager = graph.agentManager,
            aiAppRegistry = graph.aiAppRegistry,
            providerManager = graph.providerManager,
            memorySeeder = MemorySeeder(graph.memoryService, graph.agentManager, app.agents, app.settings, graph.activityRecorder),
            memory = graph.memoryService,
            settings = graph.settingsView,
            activityLog = app.activityLog,
            activityRecorder = graph.activityRecorder,
            packageChanges = app.packageChanges,
            clock = app.clock,
            orchestrator = orchestrator,
            stopShellLeftovers = {
                events += "shell"
                0
            },
            agentChat = chat,
            hooks = hooks,
        )
    }

    @Test
    fun `the hooks run around the steps, and the cause and the resume answer reach the recovery`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val hooks = object : StartupHooks {
            override suspend fun beforeSteps() {
                events += "before"
            }

            override suspend fun interruptCause(): String = "the app stopped because of an error in the app".also { events += "cause" }

            override suspend fun resumeAllowed(): Boolean = false.also { events += "resume" }

            override suspend fun afterSteps() {
                events += "after"
            }
        }

        startup(app, backgroundScope, hooks).start().join()

        assertEquals(
            listOf("before", "shell", "chat", "cause", "resume", "recover(the app stopped because of an error in the app, false)", "after"),
            events.toList(),
        )
        assertEquals(6, app.agents.getAll().size, "the steps in between ran")
        assertTrue(app.activityLog.all.none { it.type == ActivityType.ERROR }, app.activityLog.all.toString())
    }

    @Test
    fun `without hooks, recovery gets no cause and may resume, as before Phase 6`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)

        startup(app, backgroundScope, StartupHooks.None).start().join()

        assertEquals(listOf("shell", "chat", "recover(null, true)"), events.toList())
    }

    @Test
    fun `a failing hook is reported and stops nothing, and an unknown resume answer resumes nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val hooks = object : StartupHooks {
            override suspend fun beforeSteps(): Unit = throw IllegalStateException("ledger unreadable")

            override suspend fun interruptCause(): String = throw IllegalStateException("exit history unreadable")

            override suspend fun resumeAllowed(): Boolean = throw IllegalStateException("ledger unreadable")

            override suspend fun afterSteps(): Unit = throw IllegalStateException("ledger unwritable")
        }

        startup(app, backgroundScope, hooks).start().join()

        assertEquals(listOf("shell", "chat", "recover(null, false)"), events.toList())
        assertEquals(6, app.agents.getAll().size, "every step ran")
        val errors = app.activityLog.all.filter { it.type == ActivityType.ERROR }.map { it.title }.toSet()
        assertEquals(
            setOf(
                "Could not note that the app started",
                "Could not find out why the app stopped before",
                "Could not check whether interrupted work may go on by itself",
                "Could not note that the app started fully",
            ),
            errors,
        )
    }
}
