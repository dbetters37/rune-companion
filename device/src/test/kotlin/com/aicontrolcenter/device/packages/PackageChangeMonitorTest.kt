package com.aicontrolcenter.device.packages

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.aicontrolcenter.device.packages.PackageChangeKind.ADDED
import com.aicontrolcenter.device.packages.PackageChangeKind.CHANGED
import com.aicontrolcenter.device.packages.PackageChangeKind.REMOVED
import com.aicontrolcenter.device.packages.PackageChangeKind.REPLACED
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PackageChangeMonitorTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val monitor = PackageChangeMonitor(application)

    private fun packageBroadcast(action: String, packageName: String, replacing: Boolean = false) =
        Intent(action, Uri.fromParts("package", packageName, null)).apply {
            if (replacing) putExtra(Intent.EXTRA_REPLACING, true)
        }

    private fun deliver(vararg broadcasts: Intent) {
        broadcasts.forEach(application::sendBroadcast)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The monitor's registrations, recognised by the package-added action in their filter. */
    private fun registrations(): List<ShadowApplication.Wrapper> =
        shadowOf(application).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_PACKAGE_ADDED) }

    @Test
    fun `emits one event per install, removal, update and enable or disable change`() = runTest {
        monitor.changes.test {
            deliver(packageBroadcast(Intent.ACTION_PACKAGE_ADDED, "com.openai.chatgpt"))
            assertEquals(PackageChange("com.openai.chatgpt", ADDED), awaitItem())

            deliver(packageBroadcast(Intent.ACTION_PACKAGE_CHANGED, "com.openai.chatgpt"))
            assertEquals(PackageChange("com.openai.chatgpt", CHANGED), awaitItem())

            // An update is three broadcasts; only the final one is news.
            deliver(
                packageBroadcast(Intent.ACTION_PACKAGE_REMOVED, "com.openai.chatgpt", replacing = true),
                packageBroadcast(Intent.ACTION_PACKAGE_ADDED, "com.openai.chatgpt", replacing = true),
                packageBroadcast(Intent.ACTION_PACKAGE_REPLACED, "com.openai.chatgpt"),
            )
            assertEquals(PackageChange("com.openai.chatgpt", REPLACED), awaitItem())

            deliver(packageBroadcast(Intent.ACTION_PACKAGE_REMOVED, "com.openai.chatgpt"))
            assertEquals(PackageChange("com.openai.chatgpt", REMOVED), awaitItem())

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ignores this app's own package and unrelated broadcasts`() = runTest {
        monitor.changes.test {
            deliver(
                packageBroadcast(Intent.ACTION_PACKAGE_CHANGED, application.packageName),
                packageBroadcast(Intent.ACTION_PACKAGE_REPLACED, application.packageName),
                packageBroadcast(Intent.ACTION_PACKAGE_DATA_CLEARED, "com.openai.chatgpt"),
                Intent(Intent.ACTION_PACKAGE_ADDED),
                Intent(Intent.ACTION_BATTERY_LOW),
                packageBroadcast(Intent.ACTION_PACKAGE_ADDED, "com.termux"),
            )
            assertEquals(PackageChange("com.termux", ADDED), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `keeps every event of a burst for a collector that is busy`() = runTest {
        val received = mutableListOf<String>()
        // Collected on the test dispatcher, so the collector only runs when the test lets it.
        val collection = launch { monitor.changes.collect { received += it.packageName } }
        runCurrent()
        assertEquals(1, registrations().size)

        val packages = (1..500).map { "com.example.app$it" }
        deliver(*packages.map { packageBroadcast(Intent.ACTION_PACKAGE_REPLACED, it) }.toTypedArray())
        assertTrue(received.isEmpty(), "the collector has not run yet, so everything is waiting in the buffer")

        runCurrent()
        assertEquals(packages, received)
        collection.cancelAndJoin()
        assertTrue(registrations().isEmpty())
    }

    @Test
    fun `registers a non-exported receiver only while collected and unregisters on cancellation`() = runTest {
        assertTrue(registrations().isEmpty(), "the flow is cold: nothing is registered before collection")

        monitor.changes.test {
            val registration = registrations().single()
            assertEquals(Context.RECEIVER_NOT_EXPORTED, registration.flags and (Context.RECEIVER_EXPORTED or Context.RECEIVER_NOT_EXPORTED))
            assertNull(registration.broadcastPermission)
            val filter = registration.intentFilter
            assertEquals(
                setOf(
                    Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED,
                    Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_CHANGED,
                ),
                filter.actionsIterator().asSequence().toSet(),
            )
            assertEquals(listOf("package"), filter.schemesIterator().asSequence().toList())
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(registrations().isEmpty(), "the receiver must be unregistered when collection stops")
    }

    @Test
    fun `each collector gets its own registration`() = runTest {
        monitor.changes.test {
            monitor.changes.test {
                assertEquals(2, registrations().size)
                deliver(packageBroadcast(Intent.ACTION_PACKAGE_ADDED, "com.termux"))
                assertEquals(PackageChange("com.termux", ADDED), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, registrations().size)
            assertEquals(PackageChange("com.termux", ADDED), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(registrations().isEmpty())
    }

    @Test
    fun `translates broadcasts without a usable package name to nothing`() {
        val own = "com.aicontrolcenter.app"
        assertNull(PackageChangeMonitor.packageChangeOf(Intent(Intent.ACTION_PACKAGE_ADDED), own))
        assertNull(PackageChangeMonitor.packageChangeOf(Intent(Intent.ACTION_PACKAGE_ADDED, Uri.parse("package:")), own))
        assertNull(PackageChangeMonitor.packageChangeOf(packageBroadcast(Intent.ACTION_PACKAGE_REMOVED, own), own))
        assertNull(PackageChangeMonitor.packageChangeOf(packageBroadcast(Intent.ACTION_PACKAGE_FULLY_REMOVED, "a.b"), own))
        assertEquals(
            PackageChange("a.b", REMOVED),
            PackageChangeMonitor.packageChangeOf(packageBroadcast(Intent.ACTION_PACKAGE_REMOVED, "a.b"), own),
        )
    }
}
