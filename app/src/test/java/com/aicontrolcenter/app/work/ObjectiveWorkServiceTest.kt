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
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    fun `the service is wanted only while something runs and the app is on screen`() {
        assertTrue(ObjectiveWorkController.shouldRun(anythingRunning = true, inForeground = true))
        assertFalse(ObjectiveWorkController.shouldRun(anythingRunning = true, inForeground = false), "Android forbids starting it from the background")
        assertFalse(ObjectiveWorkController.shouldRun(anythingRunning = false, inForeground = true))
    }
}
