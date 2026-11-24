package com.aicontrolcenter.device.screen

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.device.platform.QueriedIntent
import com.aicontrolcenter.device.testing.FakeDevice
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAutofillManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts of the screen operator that ask Android: protected handlers, service state, certificates, keyguard, launcher. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ScreenPlatformTest {
    private val device = FakeDevice()

    /** Declares that [packageName] has an activity handling [filter]. */
    private fun handles(packageName: String, activity: String, filter: IntentFilter) {
        if (runCatching { device.packageManager.getPackageInfo(packageName, 0) }.isFailure) device.install(packageName, system = true)
        val component = ComponentName(packageName, "$packageName.$activity")
        device.shadowPackageManager.addActivityIfNotPresent(component)
        device.shadowPackageManager.addIntentFilterForActivity(component, filter)
    }

    @Test
    fun `every protected handler action has a query, and the resolver protects whoever handles them`() {
        assertEquals(ConsequentialControls.PROTECTED_HANDLER_ACTIONS, QueriedIntent.protectedHandlers.map { it.action })

        handles("com.oem.settings", "SecuritySettings", IntentFilter("android.settings.SECURITY_SETTINGS").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        handles(
            "com.oem.installer",
            "Install",
            IntentFilter("android.intent.action.INSTALL_PACKAGE").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("content")
                addDataType("application/vnd.android.package-archive")
            },
        )
        handles(
            "com.oem.installer",
            "Uninstall",
            IntentFilter("android.intent.action.UNINSTALL_PACKAGE").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            },
        )
        handles(
            "com.oem.appinfo",
            "Details",
            IntentFilter("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            },
        )
        handles("com.oem.permissions", "Grant", IntentFilter("android.content.pm.action.REQUEST_PERMISSIONS").apply { addCategory(Intent.CATEGORY_DEFAULT) })
        handles("com.example.notes", "Main", IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        Shadow.extract<ShadowAutofillManager>(device.application.getSystemService(AutofillManager::class.java))
            .setAutofillServiceComponentName(ComponentName("com.vault.passwords", "com.vault.passwords.Autofill"))

        val protected = ProtectedPackagesResolver.resolve(device.application)

        assertEquals(device.ownPackage, protected.ownPackage)
        assertEquals(setOf("com.oem.settings", "com.oem.installer", "com.oem.appinfo", "com.oem.permissions", "com.vault.passwords"), protected.resolved)
        assertTrue(protected.isProtected("com.oem.installer"))
        assertTrue(protected.isProtected(device.ownPackage), "this app is always protected")
        assertTrue(protected.isProtected("com.android.settings"), "the static floor stays")
        assertFalse(protected.isProtected("com.example.notes"))
    }

    @Test
    fun `protected packages are resolved on their own thread, and every reader gets the full set`() {
        val caller = Thread.currentThread()
        val release = CountDownLatch(1)
        var resolvedOn: Thread? = null
        val full = com.aicontrolcenter.core.operator.ProtectedPackages("com.aicontrolcenter.app", resolved = setOf("com.oem.settings"))
        val resolving = ResolvingProtectedPackages {
            resolvedOn = Thread.currentThread()
            release.await()
            full
        }
        assertFalse(resolving.isResolved, "the queries run in the background, not in the constructor (A7)")
        val reader = Executors.newSingleThreadExecutor()
        try {
            val read = reader.submit<Boolean> { resolving().isProtected("com.oem.settings") }
            Thread.sleep(50)
            assertFalse(read.isDone, "a reader waits for the full set rather than checking against the floor")
            release.countDown()
            assertTrue(read.get(5, TimeUnit.SECONDS))
            assertTrue(resolving.isResolved)
            assertTrue(resolvedOn != null && resolvedOn != caller, "resolved on its own thread")
            assertTrue(resolving() === full)
        } finally {
            reader.shutdownNow()
        }

        // Should the background resolution fail, a reader resolves again rather than answering with less.
        var attempts = 0
        val flaky = ResolvingProtectedPackages {
            if (attempts++ == 0) throw IllegalStateException("PackageManager died") else full
        }
        assertTrue(flaky() === full)
    }

    @Test
    fun `the service counts as switched on only when Android lists this component`() {
        val availability = AndroidScreenAvailability(device.application) { null }
        val me = OperatorAccessibilityService.componentName(device.application)
        val resolver = device.application.contentResolver
        assertFalse(availability.enabledInSettings())

        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.other/.Reader")
        assertFalse(availability.enabledInSettings())

        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.other/.Reader:${me.flattenToShortString()}")
        assertTrue(availability.enabledInSettings())
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, me.flattenToString())
        assertTrue(availability.enabledInSettings())

        var changes = 0
        availability.watch { changes++ }
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        resolver.notifyChange(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), null)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(changes > 0, "a change in the setting is noticed")
    }

    @Test
    fun `Advanced Protection is read through the device report's reader`() {
        assertTrue(AndroidScreenAvailability(device.application) { true }.advancedProtectionOn())
        assertFalse(AndroidScreenAvailability(device.application) { false }.advancedProtectionOn())
        assertFalse(AndroidScreenAvailability(device.application) { null }.advancedProtectionOn(), "unknown is not on")
    }

    @Test
    fun `restricted settings are guessed from how this app was installed`() {
        val availability = AndroidScreenAvailability(device.application) { null }
        val own = device.ownPackage

        device.shadowPackageManager.setInstallSourceInfo(own, null, null, null, null, null, PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
        assertTrue(availability.restrictedSettingsLikely(), "opened from a file")

        device.install("com.android.vending", system = true)
        device.shadowPackageManager.setInstallSourceInfo(own, "com.android.vending", null, null, "com.android.vending", null, PackageInstaller.PACKAGE_SOURCE_STORE)
        assertFalse(availability.restrictedSettingsLikely(), "a pre-installed store")

        device.install("com.thirdparty.store")
        device.shadowPackageManager.setInstallSourceInfo(own, "com.thirdparty.store", null, null, "com.thirdparty.store", null, PackageInstaller.PACKAGE_SOURCE_STORE)
        assertTrue(availability.restrictedSettingsLikely(), "Android 15: a store that is not pre-installed")

        device.shadowPackageManager.setInstallSourceInfo(own, null, null, null, null, null, PackageInstaller.PACKAGE_SOURCE_OTHER)
        assertTrue(availability.restrictedSettingsLikely(), "Android 15: adb or an unknown installer")
    }

    @Test
    fun `certificates, the lock screen and the launcher come from Android`() {
        val facts = AndroidDeviceFacts(device.application)
        device.install("com.example.notes", signedBy = listOf("notes key"))
        device.install("com.example.unsigned")

        val certificate = facts.signingCertSha256("com.example.notes")
        assertEquals(64, certificate?.length)
        assertNull(facts.signingCertSha256("com.example.unsigned"))
        assertNull(facts.signingCertSha256("com.example.missing"))
        device.setSigners("com.example.notes", listOf("someone else's key"))
        assertTrue(certificate != facts.signingCertSha256("com.example.notes"))

        val keyguard = shadowOf(device.application.getSystemService(android.app.KeyguardManager::class.java))
        keyguard.setKeyguardLocked(false)
        assertFalse(facts.keyguardLocked())
        keyguard.setKeyguardLocked(true)
        assertTrue(facts.keyguardLocked())

        assertNull(facts.launcherPackage())
        handles("com.android.launcher3", "Launcher", IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        })
        assertEquals("com.android.launcher3", facts.launcherPackage())
    }
}
