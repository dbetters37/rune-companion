package com.aicontrolcenter.device

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ControlChannel
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.LaunchResult
import com.aicontrolcenter.core.registry.controlChannels
import com.aicontrolcenter.device.packages.PackageChange
import com.aicontrolcenter.device.packages.PackageChangeKind
import com.aicontrolcenter.device.testing.FakeDevice
import com.aicontrolcenter.framework.registry.DefaultAiAppRegistry
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAiAppRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the framework's registry on top of the real device layer (with its default background
 * dispatchers), the way the app's composition root wires them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceLayerRegistryTest {

    private val device = FakeDevice()
    private val layer = DeviceLayer(device.application, startGate = { true })
    private val clock = FakeClock(autoAdvanceMs = 1)
    private val ids = SequentialIdGenerator()
    private val repository = InMemoryAiAppRepository()
    private val registry = DefaultAiAppRegistry(
        repository, layer.scanner, ActivityRecorder(InMemoryActivityLog(), ids, clock), ids, clock,
    )

    private suspend fun registered(packageName: String) = assertNotNull(repository.getByPackage(packageName), packageName)

    @Test
    fun `discovery, hand-off and status tracking work end to end`() = runTest {
        device.install("com.openai.chatgpt", label = "ChatGPT", version = "1.0") {
            launcher()
            sharesText()
        }
        device.install("com.termux", label = "Termux") { launcher() }
        device.install("com.example.notes", label = "Notes") { launcher() }

        val report = registry.discover()
        assertEquals(listOf("com.openai.chatgpt", "com.termux"), report.added.map { it.packageName })
        val chatGpt = registered("com.openai.chatgpt")
        assertEquals(DiscoverySource.CATALOG, chatGpt.source)
        assertEquals("OpenAI", chatGpt.vendor)
        assertEquals(setOf(ControlChannel.LAUNCH, ControlChannel.INTENT_HANDOFF), chatGpt.controlChannels())
        assertEquals(setOf(ControlChannel.LAUNCH), registered("com.termux").controlChannels())

        assertEquals(LaunchResult.Started, layer.launcher.handOffText(chatGpt, "Draft a reply"))
        val shared = assertNotNull(shadowOf(device.application).nextStartedActivity)
        assertEquals(Intent.ACTION_SEND, shared.action)
        assertEquals("com.openai.chatgpt", shared.component?.packageName)
        assertEquals("Draft a reply", shared.getStringExtra(Intent.EXTRA_TEXT))

        // Rescanning an unchanged device changes nothing.
        val unchanged = registry.discover()
        assertTrue(unchanged.added.isEmpty() && unchanged.updated.isEmpty() && unchanged.missing.isEmpty())
        assertEquals(0, registry.refreshStatuses())

        // Disabled in Android settings: listed, marked, unusable.
        device.disableInSettings("com.openai.chatgpt")
        assertEquals(1, registry.refreshStatuses())
        val disabled = registered("com.openai.chatgpt")
        assertEquals(AiAppStatus.DISABLED_BY_SYSTEM, disabled.status)
        assertEquals(emptySet(), disabled.controlChannels())
        assertIs<LaunchResult.NotPossible>(layer.launcher.launch(disabled))
        assertIs<LaunchResult.NotPossible>(layer.launcher.handOffText(disabled, "hello"))

        // Re-enabled and updated: capabilities and version come back from the device.
        device.enableInSettings("com.openai.chatgpt")
        device.setVersion("com.openai.chatgpt", "2.0")
        assertEquals(1, registry.refreshStatuses())
        val updated = registered("com.openai.chatgpt")
        assertEquals(AiAppStatus.INSTALLED, updated.status)
        assertEquals("2.0", updated.versionName)
        assertEquals(setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT), updated.capabilities)
        assertEquals(chatGpt.id, updated.id)

        // Uninstalled: stays listed as not installed and can no longer be opened.
        device.uninstall("com.termux")
        val afterRemoval = registry.discover()
        assertEquals(listOf("com.termux"), afterRemoval.missing.map { it.packageName })
        val gone = registered("com.termux")
        assertEquals(AiAppStatus.NOT_INSTALLED, gone.status)
        assertIs<LaunchResult.NotPossible>(layer.launcher.launch(gone))
        assertNull(shadowOf(device.application).peekNextStartedActivity())
    }

    @Test
    fun `a package broadcast lets the registry refresh the moment an app disappears`() = runTest {
        device.install("com.openai.chatgpt", label = "ChatGPT") { launcher() }
        registry.discover()

        layer.packageChanges.test {
            device.uninstall("com.openai.chatgpt")
            device.application.sendBroadcast(
                Intent(Intent.ACTION_PACKAGE_REMOVED, Uri.fromParts("package", "com.openai.chatgpt", null)),
            )
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(PackageChange("com.openai.chatgpt", PackageChangeKind.REMOVED), awaitItem())
            assertEquals(1, registry.refreshStatuses())
            assertEquals(AiAppStatus.NOT_INSTALLED, registered("com.openai.chatgpt").status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the device report comes from the same entry point`() = runTest {
        val report = layer.deviceInspector.inspect()
        assertEquals(35, report.sdkInt)
        assertEquals(7, report.features.size)
        assertTrue(report.generatedAt > 0)
    }

    @Test
    fun `any launchable app can be registered manually, but not this app`() = runTest {
        device.manifestOf(device.ownPackage).launcher()
        device.install("com.example.notes", label = "Notes", version = "4.1") {
            launcher()
            processesText()
        }
        device.install("com.example.service", label = "Background service")

        assertEquals(listOf("com.example.notes"), registry.unregisteredInstalledApps().map { it.packageName })

        val notes = registry.registerManually("com.example.notes")
        assertEquals(DiscoverySource.MANUAL, notes.source)
        assertEquals("Notes", notes.label)
        assertEquals("4.1", notes.versionName)
        assertEquals(setOf(ControlChannel.LAUNCH, ControlChannel.ROUND_TRIP_TEXT), notes.controlChannels())
        assertTrue(registry.unregisteredInstalledApps().isEmpty())

        assertFailsWith<IllegalArgumentException> { registry.registerManually(device.ownPackage) }
        assertFailsWith<IllegalArgumentException> { registry.registerManually("not.installed.anywhere") }

        // A later scan keeps the manual entry even though the scan itself would not find it.
        val report = registry.discover()
        assertTrue(report.missing.isEmpty())
        assertEquals(DiscoverySource.MANUAL, registered("com.example.notes").source)
    }
}
