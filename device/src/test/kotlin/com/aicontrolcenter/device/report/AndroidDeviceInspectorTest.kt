package com.aicontrolcenter.device.report

import android.app.ActivityManager
import android.content.pm.ConfigurationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.device.DeviceFeature
import com.aicontrolcenter.core.device.DeviceReport
import com.aicontrolcenter.core.device.LocalModelGuidance
import com.aicontrolcenter.device.testing.FakeDevice
import com.aicontrolcenter.testkit.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowStatFs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidDeviceInspectorTest {

    private val device = FakeDevice()
    private val activityManager = shadowOf(device.application.getSystemService(ActivityManager::class.java))
    private val clock = FakeClock(current = 1_800_000_000_000L)
    private val inspector = AndroidDeviceInspector(device.application, clock, Dispatchers.Unconfined)

    private val gib = 1024L * 1024 * 1024

    private fun DeviceReport.feature(id: String): DeviceFeature = features.single { it.id == id }

    private fun inspectorSeeing(advancedProtection: AdvancedProtectionReader) =
        AndroidDeviceInspector(device.application, clock, Dispatchers.Unconfined, advancedProtection)

    private fun describeFlagship() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel 9")
        ShadowBuild.setVersionRelease("15")
        ShadowBuild.setVersionSecurityPatch("2026-10-05")
        ShadowBuild.setSystemOnChipManufacturer("Google")
        ShadowBuild.setSystemOnChipModel("Tensor G4")
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        ShadowBuild.setSupported64BitAbis(arrayOf("arm64-v8a"))
        activityManager.setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = 12 * gib - 300_000_000
                availMem = 5 * gib
            },
        )
        activityManager.setIsLowRamDevice(false)
        activityManager.setDeviceConfigurationInfo(ConfigurationInfo().apply { reqGlEsVersion = 0x0003_0002 })
        // 300 000 blocks are free but only 250 000 of them are available to apps.
        ShadowStatFs.registerStats(device.application.dataDir, 1_000_000, 300_000, 250_000)
        device.shadowPackageManager.setSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, true)
        device.shadowPackageManager.addSystemAvailableFeature(
            FeatureInfo().apply {
                name = PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
                version = (1 shl 22) or (3 shl 12)
            },
        )
        device.install("com.google.android.aicore", label = "AICore")
        device.install("com.termux", label = "Termux") { launcher() }
        device.install("com.termux.api", label = "Termux:API")
    }

    @Test
    fun `fills every field of the report from the platform`() = runTest {
        describeFlagship()

        val report = inspector.inspect()

        assertEquals("Google", report.manufacturer)
        assertEquals("Pixel 9", report.model)
        assertEquals("15", report.androidRelease)
        assertEquals(35, report.sdkInt)
        assertEquals("2026-10-05", report.securityPatch)
        assertEquals("Google", report.socManufacturer)
        assertEquals("Tensor G4", report.socModel)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), report.supportedAbis)
        assertEquals(Runtime.getRuntime().availableProcessors(), report.cpuCores)
        assertTrue(report.cpuCores > 0)
        assertEquals(12 * gib - 300_000_000, report.totalRamBytes)
        assertEquals(5 * gib, report.availableRamBytes)
        assertFalse(report.isLowRamDevice)
        assertEquals(1_000_000L * ShadowStatFs.BLOCK_SIZE, report.storageTotalBytes)
        assertEquals(250_000L * ShadowStatFs.BLOCK_SIZE, report.storageFreeBytes, "free space is what apps may actually use")
        assertEquals("3.2", report.openGlEsVersion)
        assertEquals(1_800_000_000_000L, report.generatedAt)
        assertEquals(LocalModelGuidance.LARGE, report.localModelGuidance)
    }

    @Test
    fun `reports the product-relevant features of a capable phone as present`() = runTest {
        describeFlagship()

        val report = inspector.inspect()

        assertEquals(
            listOf(
                DeviceFeatureIds.ABI_64_BIT, DeviceFeatureIds.OPENGL_ES_3_1, DeviceFeatureIds.VULKAN, DeviceFeatureIds.AICORE,
                DeviceFeatureIds.TERMUX, DeviceFeatureIds.TERMUX_API, DeviceFeatureIds.STANDARD_MEMORY_CLASS,
            ),
            report.features.map { it.id },
        )
        assertTrue(report.features.all { it.available }, report.features.filterNot { it.available }.toString())
        assertTrue("arm64-v8a" in report.feature(DeviceFeatureIds.ABI_64_BIT).detail)
        assertTrue("3.2" in report.feature(DeviceFeatureIds.OPENGL_ES_3_1).detail)
        assertTrue("Vulkan 1.3" in report.feature(DeviceFeatureIds.VULKAN).detail)
        val aiCore = report.feature(DeviceFeatureIds.AICORE).detail
        assertTrue("present" in aiCore && "does not show" in aiCore, "presence must not be worded as usability: $aiCore")
        val termux = report.feature(DeviceFeatureIds.TERMUX).detail
        assertTrue(termux.startsWith("Installed") && "cannot be seen" in termux, "what runs inside Termux is not known: $termux")
        assertTrue(report.feature(DeviceFeatureIds.TERMUX_API).detail.startsWith("Installed"))
    }

    @Test
    fun `advanced protection is not reported where android has no such mode`() = runTest {
        // Android 15: the platform class does not exist yet, so the reader must not touch it.
        assertNull(PlatformAdvancedProtectionReader(device.application).isEnabled())
        assertTrue(inspector.inspect().features.none { it.id == DeviceFeatureIds.ADVANCED_PROTECTION })
    }

    @Test
    fun `advanced protection switched off is the good case`() = runTest {
        val feature = inspectorSeeing { false }.inspect().feature(DeviceFeatureIds.ADVANCED_PROTECTION)

        assertEquals("advanced_protection", feature.id)
        assertTrue(feature.available)
        assertTrue(feature.detail.startsWith("Advanced Protection is off"), feature.detail)
        assertTrue("accessibility services that are not assistive tools" in feature.detail && "sideloading" in feature.detail)
    }

    @Test
    fun `advanced protection switched on is reported as a restriction`() = runTest {
        val report = inspectorSeeing { true }.inspect()
        val feature = report.feature(DeviceFeatureIds.ADVANCED_PROTECTION)

        assertFalse(feature.available)
        assertTrue(feature.detail.startsWith("Advanced Protection is on"), feature.detail)
        assertTrue("accessibility services that are not assistive tools" in feature.detail && "sideloading" in feature.detail)
        assertEquals(1, feature.detail.count { it == '.' }, "one sentence")
        assertEquals(DeviceFeatureIds.ADVANCED_PROTECTION, report.features.last().id)
        assertEquals(8, report.features.size)
    }

    @Test
    fun `an advanced protection state android cannot give is left out, not guessed`() = runTest {
        val report = inspectorSeeing { null }.inspect()
        assertEquals(7, report.features.size)
        assertTrue(report.features.none { it.id == DeviceFeatureIds.ADVANCED_PROTECTION })
    }

    @Test
    fun `reports missing features and unknown values honestly on a modest phone`() = runTest {
        ShadowBuild.setManufacturer("Acme")
        ShadowBuild.setModel("Lite 2")
        ShadowBuild.setVersionSecurityPatch("")
        ShadowBuild.setSystemOnChipManufacturer("unknown")
        ShadowBuild.setSystemOnChipModel("unknown")
        ShadowBuild.setSupportedAbis(arrayOf("armeabi-v7a", "armeabi"))
        ShadowBuild.setSupported64BitAbis(emptyArray())
        activityManager.setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = 2 * gib
                availMem = gib / 2
            },
        )
        activityManager.setIsLowRamDevice(true)
        activityManager.setDeviceConfigurationInfo(ConfigurationInfo().apply { reqGlEsVersion = 0x0003_0000 })

        val report = inspector.inspect()

        assertNull(report.securityPatch)
        assertNull(report.socManufacturer)
        assertNull(report.socModel)
        assertTrue(report.isLowRamDevice)
        assertEquals("3.0", report.openGlEsVersion)
        assertEquals(LocalModelGuidance.MINIMAL, report.localModelGuidance)
        assertEquals(7, report.features.size)
        assertTrue(report.features.none { it.available }, report.features.filter { it.available }.toString())
        assertTrue(report.features.all { it.title.isNotBlank() && it.detail.endsWith(".") })
        assertTrue("32-bit" in report.feature(DeviceFeatureIds.ABI_64_BIT).detail)
        assertTrue("3.0" in report.feature(DeviceFeatureIds.OPENGL_ES_3_1).detail)
        assertTrue("low-RAM" in report.feature(DeviceFeatureIds.STANDARD_MEMORY_CLASS).detail)
        assertTrue(report.feature(DeviceFeatureIds.TERMUX).detail.startsWith("Not installed"))
        assertTrue("not found" in report.feature(DeviceFeatureIds.AICORE).detail)
    }

    @Test
    fun `an unreported opengl version is unknown, not zero`() = runTest {
        activityManager.setDeviceConfigurationInfo(ConfigurationInfo())

        val report = inspector.inspect()

        assertNull(report.openGlEsVersion)
        val feature = report.feature(DeviceFeatureIds.OPENGL_ES_3_1)
        assertFalse(feature.available)
        assertTrue("did not report" in feature.detail)
    }

    @Test
    fun `opengl es 3_1 is the threshold for gpu inference`() = runTest {
        activityManager.setDeviceConfigurationInfo(ConfigurationInfo().apply { reqGlEsVersion = 0x0003_0001 })
        val report = inspector.inspect()
        assertEquals("3.1", report.openGlEsVersion)
        assertTrue(report.feature(DeviceFeatureIds.OPENGL_ES_3_1).available)
    }

    @Test
    fun `each inspection is measured afresh`() = runTest {
        describeFlagship()
        val first = inspector.inspect()

        clock.advance(60_000)
        device.uninstall("com.termux")
        activityManager.setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = 12 * gib - 300_000_000
                availMem = 3 * gib
            },
        )
        val second = inspector.inspect()

        assertTrue(first.feature(DeviceFeatureIds.TERMUX).available)
        assertFalse(second.feature(DeviceFeatureIds.TERMUX).available)
        assertEquals(3 * gib, second.availableRamBytes)
        assertEquals(first.generatedAt + 60_000, second.generatedAt)
    }

    @Test
    fun `works on the default background dispatcher`() = runTest {
        describeFlagship()
        val report = AndroidDeviceInspector(device.application, clock).inspect()
        assertEquals("Pixel 9", report.model)
        assertEquals(7, report.features.size)
    }
}
