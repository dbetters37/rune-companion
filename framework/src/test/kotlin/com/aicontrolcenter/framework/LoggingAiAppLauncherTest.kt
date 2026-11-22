package com.aicontrolcenter.framework

import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.LaunchResult
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.framework.registry.LoggingAiAppLauncher
import com.aicontrolcenter.testkit.FakeAiAppLauncher
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoggingAiAppLauncherTest {
    private val world = TestWorld()
    private val fake = FakeAiAppLauncher()
    private val launcher = LoggingAiAppLauncher(fake, world.activity)
    private val app = Samples.aiApp()

    @Test
    fun `a text hand-off is logged as information leaving the device without the text itself`() = runTest {
        assertEquals(LaunchResult.Started, launcher.handOffText(app, "my private prompt ü"))
        assertEquals(listOf(app.packageName to "my private prompt ü"), fake.handedOff)
        val event = world.log.recent(ActivityFilter(egressOnly = true)).single()
        assertEquals(ActivityType.DATA_EGRESS, event.type)
        assertEquals("app:${app.packageName}", event.egress?.destinationHost)
        assertEquals(Locality.CLOUD, event.egress?.locality)
        assertEquals(20L, event.egress?.approxBytes) // 18 ASCII characters and a two-byte "ü"
        assertTrue("private prompt" !in event.toString(), "the log must not contain the text that was sent")
    }

    @Test
    fun `opening an app is logged but is not egress, and refusals are logged as such`() = runTest {
        launcher.launch(app)
        assertNull(world.log.all.single().egress)
        val shareless = app.copy(capabilities = setOf(AiAppCapability.LAUNCH))
        assertIs<LaunchResult.NotPossible>(launcher.handOffText(shareless, "x"))
        assertEquals("Could not hand text to app", world.log.all.last().title)
        assertTrue(world.log.recent(ActivityFilter(egressOnly = true)).isEmpty())
    }
}
