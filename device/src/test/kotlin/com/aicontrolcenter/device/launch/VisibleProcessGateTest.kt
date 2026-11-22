package com.aicontrolcenter.device.launch

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VisibleProcessGateTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun gateWhen(importance: Int): Boolean {
        val process = RunningAppProcessInfo().apply {
            pid = Process.myPid()
            this.importance = importance
        }
        shadowOf(application.getSystemService(ActivityManager::class.java)).setProcesses(listOf(process))
        return VisibleProcessGate.mayStartActivities()
    }

    @Test
    fun `allows activity starts only while this process is on screen`() {
        val expectations = mapOf(
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND to true,
            RunningAppProcessInfo.IMPORTANCE_VISIBLE to true,
            // Numerically between the two above, yet a foreground service shows no screen.
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE to false,
            RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE to false,
            RunningAppProcessInfo.IMPORTANCE_SERVICE to false,
            RunningAppProcessInfo.IMPORTANCE_CACHED to false,
            RunningAppProcessInfo.IMPORTANCE_GONE to false,
        )
        assertEquals(expectations, expectations.mapValues { (importance, _) -> gateWhen(importance) })
    }
}
