package com.aicontrolcenter.device.health

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.ExitKind
import com.aicontrolcenter.core.health.ExitRecord
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.core.health.StorageLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's side of the app's health (Phase 6, WP2 items 4, 6, 10), at SDK 35 with the older branches injected (F2). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceHealthTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val activityManager get() = context.getSystemService(ActivityManager::class.java)

    private fun exit(process: String, reason: Int, at: Long, description: String? = null) {
        val info = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(process).setReason(reason).setTimestamp(at).setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
            .setPss(120_000).setRss(150_000).apply { description?.let(::setDescription) }.build()
        shadowOf(activityManager).addApplicationExitInfo(info)
    }

    private val main: String get() = context.applicationInfo.processName ?: context.packageName

    @Test
    fun `only the main process's exits count, oldest first, each after the time given, without Android's text`() = runTest {
        exit(main, ApplicationExitInfo.REASON_LOW_MEMORY, at = 1_000)
        // The script process shares the package's history (spike, run 3): never the app's exit (rule 31).
        exit("$main:scripts:com.aicontrolcenter.app.scripts.ScriptProcessService:js0", ApplicationExitInfo.REASON_CRASH, at = 2_000)
        exit("$main:scripts", ApplicationExitInfo.REASON_OTHER, at = 2_500, description = "isolated not needed")
        exit(main, ApplicationExitInfo.REASON_CRASH, at = 3_000, description = "java.lang.IllegalStateException: secret-marker")
        exit(main, ApplicationExitInfo.REASON_ANR, at = 4_000)

        val history = AndroidExitHistory(context, io = Dispatchers.Unconfined)
        val all = history.newSince(0)
        assertEquals(listOf(1_000L, 3_000L, 4_000L), all.map { it.at })
        assertEquals(listOf(ExitKind.LOW_MEMORY, ExitKind.CRASH, ExitKind.ANR), all.map { it.kind })
        assertEquals(ExitRecord(1_000, ExitKind.LOW_MEMORY, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED, 120_000, 150_000), all.first())
        assertFalse(all.toString().contains("secret-marker"), "the description is never kept")
        assertEquals(listOf(4_000L), history.newSince(3_000).map { it.at })
        assertEquals(emptyList(), history.newSince(4_000))
    }

    @Test
    fun `Android 9 and 10 say nothing about exits`() = runTest {
        exit(main, ApplicationExitInfo.REASON_CRASH, at = 1_000)
        assertEquals(emptyList(), AndroidExitHistory(context, sdkInt = Build.VERSION_CODES.Q, io = Dispatchers.Unconfined).newSince(0))
        assertEquals(1, AndroidExitHistory(context, sdkInt = Build.VERSION_CODES.R, io = Dispatchers.Unconfined).newSince(0).size)
    }

    @Test
    fun `every reason has its kind, including Android 17's memory limiter`() {
        val expected = mapOf(
            ApplicationExitInfo.REASON_UNKNOWN to ExitKind.UNKNOWN,
            ApplicationExitInfo.REASON_EXIT_SELF to ExitKind.OTHER,
            ApplicationExitInfo.REASON_SIGNALED to ExitKind.OTHER,
            ApplicationExitInfo.REASON_LOW_MEMORY to ExitKind.LOW_MEMORY,
            ApplicationExitInfo.REASON_CRASH to ExitKind.CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE to ExitKind.CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR to ExitKind.ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to ExitKind.CRASH,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to ExitKind.PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to ExitKind.OTHER,
            ApplicationExitInfo.REASON_USER_REQUESTED to ExitKind.USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED to ExitKind.OTHER,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED to ExitKind.OTHER,
            ApplicationExitInfo.REASON_OTHER to ExitKind.OTHER,
            ApplicationExitInfo.REASON_FREEZER to ExitKind.FREEZER,
            15 to ExitKind.OTHER,
            16 to ExitKind.UPDATED,
            17 to ExitKind.MEMORY_LIMIT,
            18 to ExitKind.OTHER,
            99 to ExitKind.UNKNOWN,
        )
        for ((reason, kind) in expected) assertEquals(kind, AndroidExitHistory.kindOf(reason, null), "reason $reason")
        // Android 17.0 reports the memory limiter as REASON_OTHER with this description (F18).
        assertEquals(ExitKind.MEMORY_LIMIT, AndroidExitHistory.kindOf(ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap"))
        assertEquals(ExitKind.OTHER, AndroidExitHistory.kindOf(ApplicationExitInfo.REASON_OTHER, "isolated not needed"))
        assertEquals(ExitKind.CRASH, AndroidExitHistory.kindOf(ApplicationExitInfo.REASON_CRASH, "MemoryLimiter"))
    }

    @Test
    fun `the background check reads each item from Android`() = runTest {
        val health = AndroidBackgroundHealth(context, io = Dispatchers.Unconfined)
        assertEquals(BackgroundState.UNKNOWN, health.state.value, "nothing is known before the first look")
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(context.packageName, false)
        shadowOf(activityManager).setBackgroundRestricted(true)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        health.refresh()
        assertEquals(BackgroundState(notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = true, standbyBucket = StandbyBucket.RARE), health.state.value)
        assertFalse(health.state.value.allClear)
        assertTrue(health.state.value.standbyWarning)

        power.setIgnoringBatteryOptimizations(context.packageName, true)
        shadowOf(activityManager).setBackgroundRestricted(false)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE)
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(true)
        health.refresh()
        assertEquals(BackgroundState(notificationsAllowed = true, batteryOptimised = false, backgroundRestricted = false, standbyBucket = StandbyBucket.ACTIVE), health.state.value)
        assertTrue(health.state.value.allClear)
    }

    @Test
    fun `before Android 9 restriction and buckets are not known`() {
        val state = AndroidBackgroundHealth(context, sdkInt = Build.VERSION_CODES.O_MR1).read()
        assertNull(state.backgroundRestricted)
        assertNull(state.standbyBucket)
    }

    @Test
    fun `standby buckets map to the app's names`() {
        assertEquals(StandbyBucket.ACTIVE, AndroidBackgroundHealth.bucketOf(UsageStatsManager.STANDBY_BUCKET_ACTIVE))
        assertEquals(StandbyBucket.WORKING_SET, AndroidBackgroundHealth.bucketOf(UsageStatsManager.STANDBY_BUCKET_WORKING_SET))
        assertEquals(StandbyBucket.FREQUENT, AndroidBackgroundHealth.bucketOf(UsageStatsManager.STANDBY_BUCKET_FREQUENT))
        assertEquals(StandbyBucket.RARE, AndroidBackgroundHealth.bucketOf(UsageStatsManager.STANDBY_BUCKET_RARE))
        assertEquals(StandbyBucket.RESTRICTED, AndroidBackgroundHealth.bucketOf(UsageStatsManager.STANDBY_BUCKET_RESTRICTED))
        assertEquals(StandbyBucket.EXEMPTED, AndroidBackgroundHealth.bucketOf(5))
        assertEquals(StandbyBucket.UNKNOWN, AndroidBackgroundHealth.bucketOf(77))
    }

    @Test
    fun `Android's screens for the background check name this app`() {
        val battery = BackgroundSettingsIntents.batteryExemption(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, battery.action)
        assertEquals("package:${context.packageName}", battery.dataString)
        val details = BackgroundSettingsIntents.appDetails(context)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.action)
        assertEquals("package:${context.packageName}", details.dataString)
    }

    @Test
    fun `free room is sorted into levels, and a phone that does not say is unknown`() = runTest {
        var bytes: Long? = 2L * 1024 * 1024 * 1024
        var failure: Exception? = null
        val watch = AndroidStorageWatch(context, measure = { failure?.let { throw it }; bytes }, io = Dispatchers.Unconfined)
        assertEquals(StorageLevel.UNKNOWN, watch.level.value)
        assertEquals(StorageLevel.OK, watch.check())
        bytes = 400L * 1024 * 1024
        assertEquals(StorageLevel.LOW, watch.check())
        assertEquals(StorageLevel.LOW, watch.level.value)
        bytes = 50L * 1024 * 1024
        assertEquals(StorageLevel.CRITICAL, watch.check())
        bytes = null
        assertEquals(StorageLevel.UNKNOWN, watch.check())
        failure = IOException("no such volume")
        assertEquals(StorageLevel.UNKNOWN, watch.check())
        // Android's own answer, whatever it is on the JVM, never throws.
        AndroidStorageWatch(context, io = Dispatchers.Unconfined).check()
    }

}
