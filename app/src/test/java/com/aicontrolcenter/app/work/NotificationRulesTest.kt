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
import com.aicontrolcenter.app.R
import com.aicontrolcenter.app.operator.ScreenSession
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.ObjectiveRow
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rule 4 (N4, S11): notifications never decide and carry no agent-written text. Every notification the background
 * package posts is listed with its actions and the targets of its intents; a question and a task error carrying a
 * marker never put it in any field.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotificationRulesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allow() = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun question(at: Long = 1_000, minutes: Int = 20) = OwnerRequest(
        id = RequestId("req_q"), kind = OwnerRequestKind.QUESTION, agentId = AgentId("agt_researcher"), toolId = ToolId("team.ask_owner"),
        capability = Capability("team.ask_owner"), risk = RiskLevel.SAFE, summary = "A question from Researcher $MARKER",
        reason = "Because $MARKER", taskId = TaskId("tsk_1"), rootId = TaskId("tsk_root"), createdAt = at, expiresAt = at + minutes * 60_000L,
        agentName = "Researcher", toolTitle = "Ask the owner", question = "Which bank? $MARKER", options = listOf("Berlin $MARKER", "Paris"),
    )

    /** Every text a notification shows, its public version's included. */
    private fun texts(notification: Notification): List<String> {
        val extras = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT)
        val own = extras.mapNotNull { notification.extras.getCharSequence(it)?.toString() } + listOfNotNull(notification.tickerText?.toString()) +
            notification.actions.orEmpty().map { it.title.toString() }
        return own + (notification.publicVersion?.let(::texts) ?: emptyList())
    }

    private fun assertClean(notification: Notification) {
        for (text in texts(notification)) assertFalse(MARKER in text, "agent text in a notification: $text")
        assertFalse(notification.allowSystemGeneratedContextualActions, "Android must not suggest answers")
    }

    @Test
    fun `a question's notification is app-written words and the agent's name, with no action, private on the lock screen`() {
        val now = 1_000L
        for (shown in listOf(ApprovalNotifications.build(context, question(now), now), ApprovalNotifications.build(context, question(now), now, reminder = true))) {
            assertClean(shown)
            assertEquals("A question from Researcher", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(shown.actions.isNullOrEmpty())
            assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
            assertEquals("An agent has a question", shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(R.drawable.ic_stat_question, shown.smallIcon.resId)
            val opens = shadowOf(shown.contentIntent).savedIntent
            assertEquals(MainActivity::class.java.name, opens.component?.className)
            assertEquals(setOf(MainActivity.EXTRA_REQUEST_ID), opens.extras?.keySet())
        }
        val first = ApprovalNotifications.build(context, question(now), now)
        assertEquals("A question from Researcher. Open the app to read it.", first.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    private fun row(id: String, status: TaskStatus, detail: String? = null) = ObjectiveRow(
        rootId = TaskId(id), title = "Compare note apps", status = status, statusDetailShort = detail, createdAt = 1, updatedAt = 2,
        finishedAt = null, planned = 3, completed = 1, participants = emptyList(),
    )

    @Test
    fun `result notifications say how work ended in the app's words, only while the app is away, only when the owner wants them`() = runTest {
        val rows = MutableStateFlow(listOf(row("tsk_a", TaskStatus.RUNNING), row("tsk_b", TaskStatus.RUNNING), row("tsk_c", TaskStatus.RUNNING),
            row("tsk_d", TaskStatus.RUNNING), row("tsk_e", TaskStatus.RUNNING), row("tsk_f", TaskStatus.COMPLETED)))
        val foreground = MutableStateFlow(true)
        val settings = InMemorySettingsRepository(FrameworkSettings())
        val posted = mutableListOf<WorkResult>()
        val notifier = ResultNotifier(context, rows, foreground, settings, interruptCause = { "Android closed the app to free memory" }, post = { posted += it })
        val job = backgroundScope.launch { notifier.run() }
        runCurrent()
        assertEquals(emptyList(), posted, "nothing about what had ended before")

        rows.value = rows.value.map { if (it.rootId.value == "tsk_a") it.copy(status = TaskStatus.COMPLETED) else it }
        runCurrent()
        assertEquals(emptyList(), posted, "the owner is looking at the app")

        foreground.value = false
        rows.value = listOf(
            row("tsk_a", TaskStatus.COMPLETED),
            row("tsk_b", TaskStatus.FAILED, detail = "The model said: $MARKER"),
            row("tsk_c", TaskStatus.PAUSED, detail = DailyLimits.PAUSED_TEXT),
            row("tsk_d", TaskStatus.PAUSED, detail = "Paused by the owner."),
            row("tsk_e", TaskStatus.INTERRUPTED, detail = "Interrupted: $MARKER"),
            row("tsk_f", TaskStatus.COMPLETED),
        )
        runCurrent()
        assertEquals(
            listOf(
                "Stopped: it could not be finished" to "Compare note apps. Open the app to see what happened.",
                "Paused: today's limit is reached" to "Compare note apps. Resume after midnight or raise the limit in Settings.",
                "Interrupted: Android closed the app to free memory" to "Compare note apps. Open the app to resume it.",
            ),
            posted.map { it.title to it.text },
        )
        for (result in posted) assertClean(ResultNotifications.build(context, result))

        settings.update { it.copy(notifyWhenWorkEnds = false) }
        rows.value = rows.value.map { if (it.rootId.value == "tsk_d") it.copy(status = TaskStatus.RUNNING) else it }
        runCurrent()
        rows.value = rows.value.map { if (it.rootId.value == "tsk_d") it.copy(status = TaskStatus.COMPLETED) else it }
        runCurrent()
        assertEquals(3, posted.size, "the owner switched them off")
        job.cancel()
    }

    @Test
    fun `a finished objective's notification opens it, has no action, and names nothing on the lock screen`() {
        val result = assertNotNull(WorkResult.of(row("tsk_a", TaskStatus.COMPLETED), TaskStatus.RUNNING, null))
        assertEquals("Finished: Compare note apps", result.title)
        assertTrue(ResultNotifications.post(context, result))
        val shown = manager.activeNotifications.single { it.tag == "tsk_a" && it.id == ResultNotifications.NOTIFICATION_ID }.notification
        assertEquals(ResultNotifications.CHANNEL_ID, shown.channelId)
        assertTrue(shown.actions.isNullOrEmpty())
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals("An objective is finished", shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse("Compare" in texts(shown.publicVersion).joinToString())
        assertEquals(R.drawable.ic_stat_done, shown.smallIcon.resId)
        val opens = shadowOf(shown.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, opens.component?.className)
        assertEquals("tsk_a", opens.getStringExtra(MainActivity.EXTRA_OBJECTIVE_ID))
        assertEquals(setOf(MainActivity.EXTRA_OBJECTIVE_ID), opens.extras?.keySet(), "no new extra (rule 23)")
        val channel = assertNotNull(manager.getNotificationChannel(ResultNotifications.CHANNEL_ID))
        assertEquals("Results", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun `every action of every background notification only pauses or stops, and every tap only opens the app`() {
        val stop = PendingIntent.getBroadcast(context, 7, Intent("test.STOP").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val all = listOf(
            WorkNotifications.build(context, null, awake = true),
            WorkNotifications.buildChat(context, ChatNotice(listOf("Researcher")), awake = false),
            ApprovalNotifications.build(context, question(), 1_000),
            ScreenSessionNotifications.build(context, listOf(ScreenSession("Operator", "org.example.notes", "Notes", waitingForOwner = true)), stop),
            ResultNotifications.build(context, assertNotNull(WorkResult.of(row("tsk_b", TaskStatus.TIMED_OUT), TaskStatus.RUNNING, null))),
            ResultNotifications.build(context, WorkResult.storageFull(TaskId("tsk_c"), "Compare note apps")),
        )
        val allowedActions = mapOf(
            "Pause" to ObjectiveWorkService.ACTION_PAUSE_ALL,
            "Stop" to ObjectiveWorkService.ACTION_STOP_CHATS,
        )
        for (notification in all) {
            assertClean(notification)
            for (action in notification.actions.orEmpty()) {
                val title = action.title.toString()
                assertTrue(title == "Pause" || title == "Stop", "only Pause and Stop: $title")
                if (action.actionIntent !== stop) {
                    val target = shadowOf(action.actionIntent).savedIntent
                    assertEquals(ObjectiveWorkService::class.java.name, target.component?.className)
                    assertEquals(allowedActions[title], target.action)
                }
            }
            notification.contentIntent?.let { tap ->
                val target = shadowOf(tap).savedIntent
                assertEquals(MainActivity::class.java.name, target.component?.className)
                assertTrue(target.extras?.keySet().orEmpty().all { it == MainActivity.EXTRA_OBJECTIVE_ID || it == MainActivity.EXTRA_REQUEST_ID })
            }
        }
        val screen = all[3]
        assertEquals("Operator waits for your answer to act in Notes", screen.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Stop", screen.actions.single().title.toString())
    }

    @Test
    fun `one reminder comes five minutes before a request runs out, never for one that goes sooner`() = runTest {
        val clock = FakeClock(1_000)
        val reminded = mutableListOf<RequestId>()
        val long = question(at = 1_000, minutes = 20)
        val short = question(at = 1_000, minutes = 4).copy(id = RequestId("req_short"))
        val pending = MutableStateFlow(listOf(long, short))
        val notifier = ApprovalNotifier(
            context, pending, clock, backgroundScope, enabled = flowOf(true), post = { _, _, _ -> true }, cancel = {}, leftover = { emptySet() },
            remind = { id, _, _ -> reminded += id; true }, reminderAlarm = ReminderAlarm.None, wakes = flowOf(0L),
        )
        // The phone awake: the wall clock and the coroutines' time go on together.
        fun pass(ms: Long) {
            clock.advance(ms)
            advanceTimeBy(ms)
            runCurrent()
        }
        backgroundScope.launch { notifier.run() }
        runCurrent()
        pass(15 * 60_000L - 1)
        assertEquals(emptyList(), reminded)
        pass(2)
        assertEquals(listOf(RequestId("req_q")), reminded)
        pass(60 * 60_000L)
        assertEquals(listOf(RequestId("req_q")), reminded, "once")

        // Answered before the reminder: none.
        val other = question(at = clock.now(), minutes = 30).copy(id = RequestId("req_other"))
        pending.value = listOf(other)
        runCurrent()
        pending.value = emptyList()
        pass(60 * 60_000L)
        assertEquals(listOf(RequestId("req_q")), reminded)
    }

    @Test
    fun `the reminder comes while the phone sleeps, as an alarm wakes it at the reminder's wall-clock time (AND-W2B-3)`() = runTest {
        val clock = FakeClock(1_000)
        val reminded = mutableListOf<RequestId>()
        val alarms = mutableListOf<Long?>()
        val alarm = object : ReminderAlarm {
            override fun set(at: Long) {
                alarms += at
            }

            override fun cancel() {
                alarms += null
            }
        }
        val wakes = MutableStateFlow(0L)
        val request = question(at = 1_000, minutes = 20)
        val pending = MutableStateFlow(listOf(request))
        val notifier = ApprovalNotifier(
            context, pending, clock, backgroundScope, enabled = flowOf(true), post = { _, _, _ -> true }, cancel = {}, leftover = { emptySet() },
            remind = { id, _, _ -> reminded += id; true }, reminderAlarm = alarm, wakes = wakes,
        )
        backgroundScope.launch { notifier.run() }
        runCurrent()
        assertEquals(listOf<Long?>(request.expiresAt - ApprovalNotifications.REMINDER_BEFORE_MS), alarms, "one alarm at the reminder's time")

        // Deep sleep: the wall clock goes on, the coroutines' time does not.
        clock.advance(16 * 60_000L)
        runCurrent()
        assertEquals(emptyList(), reminded)
        // The alarm wakes the phone: the reminder comes, once, and no alarm is left.
        wakes.value += 1
        runCurrent()
        assertEquals(listOf(RequestId("req_q")), reminded)
        assertEquals(null, alarms.last())
        wakes.value += 1
        advanceTimeBy(60 * 60_000L)
        runCurrent()
        assertEquals(listOf(RequestId("req_q")), reminded, "once")

        // A wake before the reminder's time reminds nothing; one after the request expired neither.
        val later = question(at = clock.now(), minutes = 20).copy(id = RequestId("req_later"))
        pending.value = listOf(later)
        runCurrent()
        clock.advance(10 * 60_000L)
        wakes.value += 1
        runCurrent()
        assertEquals(listOf(RequestId("req_q")), reminded)
        clock.advance(11 * 60_000L)
        wakes.value += 1
        runCurrent()
        assertEquals(listOf(RequestId("req_q")), reminded, "expired before the phone woke: no reminder")
    }

    @Test
    fun `the new status-bar icons are one-colour 24 dp vectors`() {
        val sources = listOf(File("src/main/res/drawable"), File("app/src/main/res/drawable")).first { it.isDirectory }
        for ((name, id) in listOf("ic_stat_done" to R.drawable.ic_stat_done, "ic_stat_stopped" to R.drawable.ic_stat_stopped, "ic_stat_question" to R.drawable.ic_stat_question)) {
            val xml = File(sources, "$name.xml").readText()
            assertTrue(xml.contains("<vector") && xml.contains("android:width=\"24dp\"") && xml.contains("android:height=\"24dp\""), name)
            assertTrue(xml.contains("android:viewportWidth=\"24\"") && xml.contains("android:viewportHeight=\"24\""), name)
            assertEquals(setOf("#FFFFFFFF"), Regex("android:fillColor=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet(), "$name is one colour")
            assertFalse(xml.contains("strokeColor") || xml.contains("gradient"), name)
            val numbers = Regex("-?\\d+(\\.\\d+)?").findAll(Regex("android:pathData=\"([^\"]+)\"").find(xml)!!.groupValues[1]).map { it.value.toDouble() }.toList()
            assertTrue(numbers.isNotEmpty())
            val drawable = assertNotNull(context.getDrawable(id))
            val density = context.resources.displayMetrics.density
            assertEquals((24 * density).toInt(), drawable.intrinsicWidth, name)
        }
    }

    private companion object {
        const val MARKER = "SECRET-MARKER-7Q"
    }
}
