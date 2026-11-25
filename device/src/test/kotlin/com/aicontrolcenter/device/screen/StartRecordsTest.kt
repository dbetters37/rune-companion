package com.aicontrolcenter.device.screen

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationStartInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android's record of how this app was started (`ActivityManager.getHistoricalProcessStartReasons`, API 35), read for
 * the "screen control was switched off" notice (Phase 6, D7). On Android 14 the method exists but is hidden, and a
 * class that calls it logs "Accessing hidden method ... (blocked, linking, denied)" when Android links it at start,
 * although the call is guarded (device gate of wave 2, finding 3). The call lives in a class of its own, marked for
 * API 35, which older Android never loads.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StartRecordsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun startInfo(forceStopped: Boolean): ApplicationStartInfo {
        val info = ShadowActivityManager.ApplicationStartInfoBuilder.newBuilder().setReason(ApplicationStartInfo.START_REASON_LAUNCHER).build()
        // Android sets it itself; its setter is hidden in the SDK.
        ApplicationStartInfo::class.java.getMethod("setForceStopped", Boolean::class.javaPrimitiveType).invoke(info, forceStopped)
        return info
    }

    @Test
    fun `from Android 15 on the latest start is read, and before it nothing is asked`() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).addHistoricalProcessStartReason(startInfo(forceStopped = true))
        assertEquals(listOf(StartRecord(wasForceStopped = true)), AndroidStartRecords(app, sdkInt = 35)())
        assertEquals(listOf(StartRecord(wasForceStopped = true)), AndroidStartRecords(app, sdkInt = 36)())
        assertEquals(emptyList(), AndroidStartRecords(app, sdkInt = 34)())
        assertEquals(emptyList(), AndroidStartRecords(app, sdkInt = 28)())
    }

    @Test
    fun `a start that was not a force stop, and no record at all, read as such`() {
        assertEquals(emptyList(), AndroidStartRecords(app, sdkInt = 35)())
        shadowOf(app.getSystemService(ActivityManager::class.java)).addHistoricalProcessStartReason(startInfo(forceStopped = false))
        assertEquals(listOf(StartRecord(wasForceStopped = false)), AndroidStartRecords(app, sdkInt = 35)())
    }

    @Test
    fun `the API 35 call is in a class of its own, marked for API 35, so older Android never links it`() {
        val outer = classBytes(AndroidStartRecords::class.java.name)
        assertFalse(METHOD in outer, "AndroidStartRecords itself must not name $METHOD")
        val helper = assertNotNull(classBytesOrNull(AndroidStartRecords::class.java.name + "\$Api35"), "the helper class AndroidStartRecords.Api35")
        assertTrue(METHOD in helper, "the helper makes the call")
        assertTrue("Landroidx/annotation/RequiresApi;" in helper, "the helper is marked @RequiresApi")
    }

    /** The class file's bytes as Latin-1 text, so that names in its constant pool can be looked for. */
    private fun classBytesOrNull(name: String): String? =
        javaClass.classLoader!!.getResourceAsStream(name.replace('.', '/') + ".class")?.use { String(it.readBytes(), Charsets.ISO_8859_1) }

    private fun classBytes(name: String): String = assertNotNull(classBytesOrNull(name), name)

    private companion object {
        const val METHOD = "getHistoricalProcessStartReasons"
    }
}
