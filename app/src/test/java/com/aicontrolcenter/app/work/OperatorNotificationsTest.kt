package com.aicontrolcenter.app.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.app.operator.ScreenSession
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The "Approvals" notifications (one per waiting request, opening only its Approval screen,
 * removed once answered) and the "is operating" notification with its Stop.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OperatorNotificationsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val app = TestApp(scope)
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val operator = AgentId("agt_operator")

    @Before
    fun allow() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun close() = scope.cancel()

    private fun approvals(): List<Notification> = shadowOf(manager).allNotifications.filter { it.channelId == ApprovalNotifications.CHANNEL_ID }

    private fun tagsOfApprovals(): Set<String?> = manager.activeNotifications.filter { it.id == ApprovalNotifications.NOTIFICATION_ID }.map { it.tag }.toSet()

    @Test
    fun `each waiting request has a notification that only opens its Approval screen, removed once answered`() {
        ApprovalNotifier(context, app.graph.ownerRequests.pending, app.clock, scope).start()
        val channel = assertNotNull(manager.getNotificationChannel(ApprovalNotifications.CHANNEL_ID))
        assertEquals("Approvals", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)

        runBlocking {
            app.raise(sampleApproval(operator, createdAt = app.clock.current, minutes = 240))
            app.raise(samplePermission(operator, agentName = "Operator", createdAt = app.clock.current, minutes = 240))
        }

        assertEquals(setOf("req_approval", "req_permission"), tagsOfApprovals())
        val shown = approvals().first { it.extras.getString(Notification.EXTRA_TITLE) == "Approval needed" }
        val text = shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.startsWith("Operator: Delete 3 files"), text)
        assertTrue("⟨U+202E⟩" in text && '‮' !in text, "the hidden character is written out here too: $text")
        // No way to answer from the notification: no actions, and none suggested by Android.
        assertTrue(shown.actions.isNullOrEmpty())
        assertFalse(shown.allowSystemGeneratedContextualActions)
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals("An agent is waiting for your answer.", shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val opens = shadowOf(shown.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opens.component?.className)
        assertEquals("req_approval", opens.getStringExtra(MainActivity.EXTRA_REQUEST_ID))
        assertTrue(shown.timeoutAfter > 0, "Android removes it at the request's expiry")

        runBlocking { app.graph.ownerRequests.deny(RequestId("req_approval")) }
        assertEquals(setOf("req_permission"), tagsOfApprovals())
        runBlocking { app.graph.ownerRequests.grant(RequestId("req_permission"), com.aicontrolcenter.core.operator.GrantScope.ONCE) }
        assertTrue(tagsOfApprovals().isEmpty())
    }

    @Test
    fun `what to post and what to remove`() {
        val a = sampleApproval(operator, id = "req_a")
        val b = sampleApproval(operator, id = "req_b")
        assertEquals(listOf(a, b) to emptyList(), ApprovalNotifier.changes(emptySet(), listOf(a, b)))
        assertEquals(emptyList<Any>() to listOf(RequestId("req_a")), ApprovalNotifier.changes(setOf(RequestId("req_a"), RequestId("req_b")), listOf(b)))
    }

    @Test
    fun `the operating notification names who operates what, has Stop, and goes when the sessions end`() {
        val sessions = MutableStateFlow<List<ScreenSession>>(emptyList())
        val stop = PendingIntent.getBroadcast(context, 7, Intent("test.STOP").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        ScreenSessionNotifier(context, sessions, { stop }, scope).start()
        assertTrue(manager.activeNotifications.none { it.id == ScreenSessionNotifications.NOTIFICATION_ID })

        sessions.value = listOf(ScreenSession("Operator", "org.example.notes", "Notes"))
        val shown = manager.activeNotifications.single { it.id == ScreenSessionNotifications.NOTIFICATION_ID }.notification
        assertEquals("Operator is operating Notes", shown.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(shown.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val action = shown.actions.single()
        assertEquals("Stop", action.title.toString())
        assertTrue(action.actionIntent === stop, "Stop is the device's own stop intent")

        sessions.value = listOf(ScreenSession("Operator", "org.example.notes", "Notes"), ScreenSession("Writer", "org.example.mail", "Mail"))
        assertEquals("2 agents are operating apps", manager.activeNotifications.single { it.id == ScreenSessionNotifications.NOTIFICATION_ID }.notification.extras.getString(Notification.EXTRA_TITLE))

        sessions.value = emptyList()
        assertTrue(manager.activeNotifications.none { it.id == ScreenSessionNotifications.NOTIFICATION_ID })
    }
}
