package com.aicontrolcenter.app.health

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.RecoveryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Wave 3 hand-off (WP8; handoff-WP10b.md 3.3): when the owner opens the app again after "Close and try later" while the
 * process lived on, the main activity asks the gate to look again by itself, so the Recovery screen's "it looks again by
 * itself" is true; a rotation does not look again, and nothing builds the graph while the data does not open.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReopenRechecksTest {
    private val app: AiControlCenterApp get() = ApplicationProvider.getApplicationContext()

    private class CountingRecovery : OwnerRecovery {
        val checks = AtomicInteger()

        override suspend fun state(): DatabaseState {
            checks.incrementAndGet()
            return DatabaseState.KEY_DAMAGED
        }

        override suspend fun startOver(): RecoveryResult = RecoveryResult.Refused("Not in this test.")
    }

    private fun eventually(condition: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    /** Lets queued work run for a short while (a look that should not happen has had its chance). */
    private fun settle() = repeat(20) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    @Test
    fun `opening the app again while the data did not open looks again, and a rotation does not`() {
        val recovery = CountingRecovery()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val gate = AppGate(recovery, scope)
            app.installGate(gate)
            eventually { gate.state.value is AppGate.State.NeedsRecovery }
            assertEquals(1, recovery.checks.get(), "the process's first look")

            // The owner opens the app again: a new activity, no saved state.
            val opened = Robolectric.buildActivity(MainActivity::class.java).setup()
            eventually { recovery.checks.get() == 2 && gate.state.value is AppGate.State.NeedsRecovery }
            assertEquals(2, recovery.checks.get(), "opening the app again looks again by itself")
            assertIs<AppGate.State.NeedsRecovery>(gate.state.value)

            // A rotation keeps what the screen showed: no new look.
            opened.recreate()
            settle()
            assertEquals(2, recovery.checks.get())
            assertNull(app.graphIfBuilt(), "nothing builds the graph while the data does not open")
            opened.pause().stop().destroy()
        } finally {
            scope.cancel()
        }
    }
}
