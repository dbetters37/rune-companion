package com.aicontrolcenter.app.routines

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * "Alarms and reminders" as the Routines screen reads it (WP10c): Android's own answer for this app on Android 12 and
 * later, always allowed on Android 9 to 11, and Android's page for this package only (nothing of another app).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExactAlarmsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun reset() = ShadowAlarmManager.setCanScheduleExactAlarms(false)

    @Test
    fun `on Android 12 and later the state is Android's answer, and the page is this app's`() {
        val access = AndroidExactAlarmAccess(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(ExactAlarmState.NOT_ALLOWED, access.state())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(ExactAlarmState.ALLOWED, access.state())
        val page = access.settingsIntent()!!
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, page.action)
        assertEquals("package:${context.packageName}", page.dataString)
        assertNull(page.extras, "no extra: nothing but the page is asked for")
    }

    @Test
    fun `on Android 9 to 11 alarms are always exact and there is no page`() {
        val access = AndroidExactAlarmAccess(context, sdkInt = Build.VERSION_CODES.R)
        assertEquals(ExactAlarmState.ALWAYS, access.state())
        assertNull(access.settingsIntent())
    }

    @Test
    fun `the same check decides whether the routine's alarm is exact`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(AndroidRoutineAlarms(context).exact(), AndroidExactAlarmAccess(context).state() == ExactAlarmState.ALLOWED)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(AndroidRoutineAlarms(context).exact(), AndroidExactAlarmAccess(context).state() == ExactAlarmState.ALLOWED)
    }
}
