package com.aicontrolcenter.device.screen

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.testkit.InMemoryOperatorAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Stop on a real DataStore file: it survives a restart, and the notification's Stop reaches it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScreenStopTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @After
    fun forgetProcessSwitch() = runBlocking { StopSwitch.resetProcess() }

    private suspend fun <T> withSwitch(fileName: String, block: suspend (StopSwitch) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val switch = StopSwitch(DataStoreStopStore.create(context, scope, fileName), scope)
            switch.awaitLoaded()
            return block(switch)
        } finally {
            // Closes the file, as the process ending would.
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `Stop and Resume survive a restart`() = runBlocking {
        val file = "stop-test-1"
        assertFalse(withSwitch(file) { it.stopped.value }, "nothing stored: not stopped")

        withSwitch(file) { it.stopAndSave().join() }
        assertTrue(withSwitch(file) { it.stopped.value }, "Stop holds after a restart")

        withSwitch(file) { it.resumeAndSave().join() }
        assertFalse(withSwitch(file) { it.stopped.value }, "so does Resume")

        withSwitch(file) { switch ->
            switch.stop()
            switch.resume()
            switch.stopAndSave().join()
        }
        assertTrue(withSwitch(file) { it.stopped.value }, "the last of several changes is what is stored")
    }

    @Test
    fun `the notification's Stop stops the process switch and saves it`() = runBlocking {
        val switch = StopSwitch.process(context)
        switch.awaitLoaded()
        switch.resume()
        assertFalse(switch.stopped.value)

        ScreenStopReceiver().onReceive(context, Intent(ScreenStopReceiver.ACTION_STOP))
        assertTrue(switch.stopped.value)

        ScreenStopReceiver().onReceive(context, Intent("something.else"))
        assertTrue(switch.stopped.value, "other intents change nothing; the receiver never resumes")

        val pending = ScreenOperatorLayer.stopPendingIntent(context)
        val sent = shadowOf(pending).savedIntent
        assertEquals(ScreenStopReceiver.ACTION_STOP, sent.action)
        assertEquals(ScreenStopReceiver::class.java.name, sent.component?.className)
        assertTrue(shadowOf(pending).isBroadcast)
        assertTrue(shadowOf(pending).isImmutable)

        StopSwitch.resetProcess()
        val restarted = StopSwitch.process(context)
        assertNotSame(switch, restarted)
        restarted.awaitLoaded()
        assertTrue(restarted.stopped.first(), "the receiver's Stop was saved")
    }

    @Test
    fun `the layer keeps the operator and the owner's control apart`() = runBlocking {
        val layer = ScreenOperatorLayer(context, InMemoryOperatorAppRepository(), ProtectedPackages(context.packageName))

        assertFalse(layer.operator is ScreenControl, "nothing holding the operator can stop or resume")
        layer.control.stop()
        assertTrue(layer.operator.stopped.value)
        layer.control.resume()
        assertFalse(layer.operator.stopped.value)
        assertEquals(OperatorAccessibilityService::class.java.name, ScreenOperatorLayer.serviceComponent(context).className)
        assertEquals(context.packageName, ScreenOperatorLayer.serviceComponent(context).packageName)
        assertEquals("package:${context.packageName}", ScreenOperatorLayer.appInfoIntent(context).dataString)
    }
}
