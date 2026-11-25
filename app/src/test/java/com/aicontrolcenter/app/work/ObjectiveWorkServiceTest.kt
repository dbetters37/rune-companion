package com.aicontrolcenter.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.testing.GatedLlmClient
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.shadows.ShadowPowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The service that keeps objectives alive in the background: its notification, its Pause button, and stopping itself. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ObjectiveWorkServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val app = TestApp(scope)
    private val context: AiControlCenterApp get() = ApplicationProvider.getApplicationContext()

    @Before
    fun install() {
        runBlocking {
            app.graph.start().join()
            // The Director's planning never returns, so the objective keeps running until it is paused.
            val model = app.serveModel(GatedLlmClient(listOf("never")))
            for (agent in app.agents.getAll()) app.graph.agentManager.assignModel(agent.id, model.id)
        }
        context.installGraph(app.graph)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun close() = scope.cancel()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `while an objective runs it is in the foreground with the objective's progress, and its Pause button pauses`() {
        val objective = runBlocking { app.graph.orchestrator.startObjective("Compare offline note apps") }
        val controller = Robolectric.buildService(ObjectiveWorkService::class.java).create().startCommand(0, 1)
        idle()
        val service = controller.get()
        assertNotNull(shadowOf(service).lastForegroundNotification, "it went into the foreground at once")
        val shown = assertNotNull(shadowOf(manager).getNotification(WorkNotifications.NOTIFICATION_ID))
        assertEquals("Director is working on: Compare offline note apps", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Planning the work", shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Pause", shown.actions.single().title.toString())
        assertTrue(shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(WorkNotifications.CHANNEL_ID, shown.channelId)
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.withIntent(Intent(context, ObjectiveWorkService::class.java).setAction(ObjectiveWorkService.ACTION_PAUSE_ALL)).startCommand(0, 2)
        idle()

        assertEquals(TaskStatus.PAUSED, runBlocking { app.tasks.get(objective.id) }?.status)
        assertTrue(shadowOf(service).isStoppedBySelf, "nothing runs any more")
    }

    @Test
    fun `started with nothing running, it stops itself straight away`() {
        val controller = Robolectric.buildService(ObjectiveWorkService::class.java).create().startCommand(0, 1)
        idle()
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `the service is wanted only while something runs and the app is on screen, or exempt from battery optimisation`() {
        assertTrue(ObjectiveWorkController.shouldRun(anythingRunning = true, inForeground = true))
        assertFalse(ObjectiveWorkController.shouldRun(anythingRunning = true, inForeground = false), "Android forbids starting it from the background")
        assertFalse(ObjectiveWorkController.shouldRun(anythingRunning = false, inForeground = true))
        // F29: an app the owner exempted from battery optimisation may start it from the background.
        assertTrue(ObjectiveWorkController.shouldRun(anythingRunning = true, inForeground = false, batteryExempt = true))
        assertFalse(ObjectiveWorkController.shouldRun(anythingRunning = false, inForeground = false, batteryExempt = true))
    }

    private val shadowApp get() = shadowOf(context)

    private fun startedServices(): List<Intent> = generateSequence { shadowApp.nextStartedService }.toList()

    @Test
    fun `a chat answer starts the service from the background when the app is exempt, and not when it is not`() {
        startedServices()
        val conversation = runBlocking { app.graph.agentChat.startConversation(app.agents.getAll().first().id) }
        ObjectiveWorkController(context, app.graph, scope).start()
        scope.async { app.graph.agentChat.send(conversation.id, "Are you there?") }
        idle()
        assertTrue(app.graph.agentChat.liveTurns.value.isNotEmpty())
        assertEquals(emptyList(), startedServices(), "in the background and battery-optimised: Android would refuse")
        app.backgroundHealth.current.value = BackgroundState(notificationsAllowed = true, batteryOptimised = false, backgroundRestricted = false, standbyBucket = null)
        idle()
        assertEquals(ObjectiveWorkService::class.java.name, startedServices().single().component?.className)
        runBlocking { app.graph.agentChat.cancel(conversation.id) }
    }

    @Test
    fun `while an agent answers in a chat the service stays, names the agent, and Stop ends the answer and the service`() {
        val conversation = runBlocking { app.graph.agentChat.startConversation(app.agents.getAll().first().id) }
        val agentName = runBlocking { app.agents.getAll().first().name }
        val reply = scope.async { app.graph.agentChat.send(conversation.id, "Are you there?") }
        idle()
        val controller = Robolectric.buildService(ObjectiveWorkService::class.java).create().startCommand(0, 1)
        idle()
        val service = controller.get()
        assertFalse(shadowOf(service).isStoppedBySelf)
        val shown = assertNotNull(shadowOf(manager).getNotification(WorkNotifications.NOTIFICATION_ID))
        assertEquals("$agentName is answering", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Stop", shown.actions.single().title.toString())
        assertEquals(ObjectiveWorkService.ACTION_STOP_CHATS, shadowOf(shown.actions.single().actionIntent).savedIntent.action)

        controller.withIntent(Intent(context, ObjectiveWorkService::class.java).setAction(ObjectiveWorkService.ACTION_STOP_CHATS)).startCommand(0, 2)
        idle()
        assertEquals(TurnResult.Cancelled, runBlocking { reply.await() })
        idle()
        assertTrue(shadowOf(service).isStoppedBySelf, "nothing runs any more")
    }

    @Test
    fun `the phone is kept awake while the Director works, says so, and lets go when the objective pauses`() {
        ShadowPowerManager.clearWakeLocks()
        val objective = runBlocking { app.graph.orchestrator.startObjective("Compare offline note apps") }
        idle()
        // The Director's planning is a model call in progress: WORKING in the monitor (the plan's open question).
        assertTrue(app.graph.activityMonitor.states.value.values.any { it.activity == AgentActivity.WORKING }, "${app.graph.activityMonitor.states.value}")
        val controller = Robolectric.buildService(ObjectiveWorkService::class.java).create().startCommand(0, 1)
        idle()
        val lock = assertNotNull(ShadowPowerManager.getLatestWakeLock())
        assertEquals(WakeHold.TAG, shadowOf(lock).tag)
        assertTrue(lock.isHeld)
        val shown = assertNotNull(shadowOf(manager).getNotification(WorkNotifications.NOTIFICATION_ID))
        assertEquals(WorkNotifications.AWAKE_LINE, shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())

        runBlocking { app.graph.orchestrator.pause(objective.id) }
        idle()
        assertFalse(lock.isHeld, "paused: nothing to keep the phone awake for")
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun `an agent whose chat ends while its task waits for the owner keeps nothing awake (AND-W2B-1)`() {
        ShadowPowerManager.clearWakeLocks()
        // The service is held up by a routine pass that has not returned: nothing else runs, nothing else works.
        val holdUp = kotlinx.coroutines.CompletableDeferred<Unit>()
        ObjectiveWorkService.runDue = { holdUp.await() }
        try {
            Robolectric.buildService(ObjectiveWorkService::class.java).create()
                .withIntent(Intent(context, ObjectiveWorkService::class.java).setAction(RoutinesWiring.ACTION_RUN_DUE_ROUTINES)).startCommand(0, 1)
            idle()
            assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld != true, "nothing works yet")
            val monitor = app.graph.activityMonitor as com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
            val researcher = runBlocking { app.agents.getAll().first { it.name == "Researcher" }.id }

            // The task run asks the owner and waits; the owner chats with the same agent meanwhile.
            val task = monitor.runStarted(researcher, "Task: compare apps", com.aicontrolcenter.core.TaskId("tsk_wait"))
            monitor.report(task, AgentActivity.WAITING_FOR_APPROVAL, "Waiting for your answer: Write a file")
            idle()
            assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld != true, "only waiting for the owner: not held")
            val chat = monitor.runStarted(researcher, "Chat")
            monitor.report(chat, AgentActivity.WORKING, "Calling the model")
            idle()
            val lock = assertNotNull(ShadowPowerManager.getLatestWakeLock())
            assertEquals(WakeHold.TAG, shadowOf(lock).tag)
            assertTrue(lock.isHeld, "the chat works: held")

            monitor.runEnded(chat)
            idle()
            assertEquals(AgentActivity.WAITING_FOR_APPROVAL, monitor.states.value[researcher]?.activity)
            assertFalse(lock.isHeld, "the agent's only run left waits for the owner: let go")
            monitor.runEnded(task)
        } finally {
            holdUp.complete(Unit)
            idle()
            ObjectiveWorkService.runDue = { it.routinesAssembly.runDue() }
        }
    }

    @Test
    fun `with the owner's setting off the phone is never kept awake`() {
        ShadowPowerManager.clearWakeLocks()
        runBlocking {
            app.settings.update { it.copy(keepAwakeWhileAgentsWork = false) }
            app.graph.orchestrator.startObjective("Compare offline note apps")
        }
        Robolectric.buildService(ObjectiveWorkService::class.java).create().startCommand(0, 1)
        idle()
        assertTrue(ShadowPowerManager.getLatestWakeLock()?.isHeld != true)
        val shown = assertNotNull(shadowOf(manager).getNotification(WorkNotifications.NOTIFICATION_ID))
        assertEquals(null, shown.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
    }

    @Test
    fun `a routine's alarm starts the service in the foreground and runs the due routines once`() {
        var runs = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        ObjectiveWorkService.runDue = { runs++; gate.await() }
        try {
            val controller = Robolectric.buildService(ObjectiveWorkService::class.java).create()
                .withIntent(Intent(context, ObjectiveWorkService::class.java).setAction(RoutinesWiring.ACTION_RUN_DUE_ROUTINES)).startCommand(0, 1)
            idle()
            assertNotNull(shadowOf(controller.get()).lastForegroundNotification, "in the foreground first")
            assertEquals(1, runs)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "held up while the routines start")
            gate.complete(Unit)
            idle()
            assertEquals(1, runs)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf, "nothing started: it stops")
        } finally {
            ObjectiveWorkService.runDue = { it.routinesAssembly.runDue() }
        }
    }

    @Test
    fun `when the phone is almost full running objectives pause with that reason`() {
        app.storage.freeBytes = 50L * 1024 * 1024
        val objective = runBlocking { app.graph.orchestrator.startObjective("Compare offline note apps") }
        val controller = Robolectric.buildService(ObjectiveWorkService::class.java).create().startCommand(0, 1)
        idle()
        assertEquals(TaskStatus.PAUSED, runBlocking { app.tasks.get(objective.id) }?.status)
        assertTrue(app.activityLog.all.any { it.title == WorkResult.STORAGE_FULL && it.rootTaskId == objective.id }, app.activityLog.all.map { it.title }.toString())
        val result = shadowOf(manager).allNotifications.single { it.channelId == ResultNotifications.CHANNEL_ID }
        assertEquals(WorkResult.STORAGE_FULL, result.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(shadowOf(controller.get()).isStoppedBySelf, "stopped")
    }

    @Test
    fun `the notice reads summaries, the oldest running objective, its plan, and how many more run`() {
        val first = runBlocking { app.graph.orchestrator.startObjective("First objective") }
        val second = runBlocking { app.graph.orchestrator.startObjective("Second objective") }
        val rows = runBlocking { app.tasks.observeObjectiveRows(10).first() }
        val progress = runBlocking { app.tasks.observeProgress(setOf(first.id, second.id)).first() }
        val notice = assertNotNull(WorkNotice.of(setOf(first.id, second.id), rows, progress))
        assertEquals(first.id, notice.objectiveId)
        assertEquals("Director is working on: First objective", notice.title)
        assertEquals("Planning the work · 1 more objective running", notice.text)
        assertEquals(null, WorkNotice.of(emptySet(), rows, progress))
    }
}
