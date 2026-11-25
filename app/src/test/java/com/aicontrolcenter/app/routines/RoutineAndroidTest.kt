package com.aicontrolcenter.app.routines

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.work.ObjectiveWorkService
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android side of routines (Phase 6, Appendix A; security review of wave 1, F11, F12; rules 4 and 23): the alarm,
 * the two receivers that only signal, and "Tap to start", which has no action and opens the app without an extra.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoutineAndroidTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val alarmManager get() = app.getSystemService(AlarmManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun routineNotifications(): List<Notification> = shadowOf(notifications).allNotifications.filter { it.channelId == RoutineNotifications.CHANNEL_ID }

    @Test
    fun `the alarm's receiver starts the foreground service with the one action and nothing else`() {
        RoutineAlarmReceiver().onReceive(app, Intent(app, RoutineAlarmReceiver::class.java).setAction(AndroidRoutineAlarms.ACTION_ROUTINE_ALARM))
        val started = assertNotNull(shadowOf(app).nextStartedService)
        assertEquals(ComponentName(app, ObjectiveWorkService::class.java), started.component)
        assertEquals(RoutinesWiring.ACTION_RUN_DUE_ROUTINES, started.action)
        assertNull(started.extras, "no extra travels with it")
        assertNull(started.data)
        assertNull(shadowOf(app).nextStartedService, "started once")
        assertTrue(routineNotifications().isEmpty())
    }

    @Test
    fun `the alarm's receiver answers nothing but its own alarm, and the boot receiver nothing but its two broadcasts`() {
        RoutineAlarmReceiver().onReceive(app, Intent("com.example.SOMETHING_ELSE"))
        RoutineAlarmReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        BootReceiver().onReceive(app, Intent("com.example.SOMETHING_ELSE"))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `when Android refuses to start the work, nothing starts and Tap to start only opens the app`() {
        val refusing = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent) =
                throw ForegroundServiceStartNotAllowedException("startForegroundService() not allowed due to mAllowStartForeground false")
        }
        RoutineAlarmReceiver().onReceive(refusing, Intent(app, RoutineAlarmReceiver::class.java).setAction(AndroidRoutineAlarms.ACTION_ROUTINE_ALARM))
        assertNull(shadowOf(app).nextStartedService)
        val shown = routineNotifications().single()
        assertTapToStart(shown, RoutineNotifications.DUE_TEXT)
    }

    @Test
    fun `a routine waiting for a tap is named, with no action, and opens the app without an extra`() {
        AndroidRoutineNotices(app).waitingForTap("Morning news")
        val shown = routineNotifications().single()
        assertTapToStart(shown, RoutineNotifications.waitingText("Morning news"))
        assertTrue("Morning news" in shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        AndroidRoutineNotices(app).noneWaiting()
        assertTrue(notifications.activeNotifications.none { it.id == RoutineNotifications.NOTIFICATION_ID })
    }

    private fun assertTapToStart(shown: Notification, text: String) {
        assertTrue(shown.actions.isNullOrEmpty(), "no action on it decides or starts anything")
        assertFalse(shown.allowSystemGeneratedContextualActions, "Android adds no suggested action either")
        assertEquals(text, shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, shown.visibility)
        assertEquals(RoutineNotifications.PUBLIC_TEXT, shown.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val opens = shadowOf(shown.contentIntent).savedIntent
        assertEquals(ComponentName(app, MainActivity::class.java), opens.component)
        assertNull(opens.extras, "rule 23: no new extra on MainActivity")
        assertNull(opens.action)
        assertNull(opens.data)
        assertTrue(shadowOf(shown.contentIntent).isActivity, "it opens the app; no receiver or service is reached from it")
    }

    @Test
    fun `the alarm is exact when the owner allowed it, inexact otherwise, and always exact on Android 9 to 11`() {
        val at = 1_800_000_000_000L
        AndroidRoutineAlarms(app).arm(at)
        val exact = shadowOf(alarmManager).scheduledAlarms.single()
        assertEquals(at, exact.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, exact.type)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, exact.windowLengthMs)
        assertTrue(exact.isAllowWhileIdle)
        val fires = shadowOf(exact.operation).savedIntent
        assertEquals(ComponentName(app, RoutineAlarmReceiver::class.java), fires.component)
        assertEquals(AndroidRoutineAlarms.ACTION_ROUTINE_ALARM, fires.action)
        assertNull(fires.extras)
        assertTrue(shadowOf(exact.operation).isBroadcast)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AndroidRoutineAlarms(app).arm(at + 1)
        val inexact = shadowOf(alarmManager).scheduledAlarms.single()
        assertEquals(at + 1, inexact.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, inexact.windowLengthMs, "replaced, now inexact")
        assertTrue(inexact.isAllowWhileIdle)
        assertTrue(AndroidRoutineAlarms(app, sdkInt = Build.VERSION_CODES.R).exact(), "Android 9 to 11 need no permission for exact alarms")

        AndroidRoutineAlarms(app).cancel()
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
        assertNull(AndroidRoutineAlarms(app).remembered())
    }

    @Test
    fun `after a restart the boot receiver sets the alarm again at the time last chosen, and starts nothing`() {
        val at = 1_800_000_000_000L
        AndroidRoutineAlarms(app).arm(at)
        // Android clears every alarm when the phone restarts.
        alarmManager.cancel(AndroidRoutineAlarms.operation(app))
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(at, shadowOf(alarmManager).scheduledAlarms.single().triggerAtMs)
        assertNull(shadowOf(app).nextStartedService, "nothing starts at boot")
        // Once the owner allows exact alarms, it is set again, exact.
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        AndroidRoutineAlarms(app).arm(at)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, shadowOf(alarmManager).scheduledAlarms.single().windowLengthMs)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BootReceiver().onReceive(app, Intent("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, shadowOf(alarmManager).scheduledAlarms.single().windowLengthMs)
        // Without a time chosen (no routine is due), a restart sets nothing.
        AndroidRoutineAlarms(app).cancel()
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `the two receivers are disabled after installing and enabled only while a routine exists`() {
        val pm = app.packageManager
        val components = AndroidRoutineAlarms.RECEIVERS.map { ComponentName(app, it) }
        for (component in components) {
            assertFalse(pm.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled, "disabled in the manifest: ${component.className}")
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getComponentEnabledSetting(component))
        }
        AndroidRoutineAlarms(app).receiversEnabled(true)
        assertTrue(components.all { pm.getComponentEnabledSetting(it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED })
        AndroidRoutineAlarms(app).receiversEnabled(false)
        assertTrue(components.all { pm.getComponentEnabledSetting(it) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED })
    }
}
