package com.aicontrolcenter.device.screen

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.device.platform.INSTALL_IDENTITY_FLAGS
import com.aicontrolcenter.device.platform.QueriedIntent
import com.aicontrolcenter.device.platform.installIdentity
import com.aicontrolcenter.device.platform.packageInfoOrNull
import com.aicontrolcenter.device.report.AdvancedProtectionReader
import com.aicontrolcenter.device.report.PlatformAdvancedProtectionReader

/** What Android says about the service when it is not connected: why not, as far as can be told. */
internal interface ScreenAvailability {
    /** The owner switched the service on in Android's accessibility settings. */
    fun enabledInSettings(): Boolean

    /** Advanced Protection is on. */
    fun advancedProtectionOn(): Boolean

    /** Best guess that Android's "restricted settings" block applies to this app (Android does not say). */
    fun restrictedSettingsLikely(): Boolean

    /** Calls [onChange] whenever the enabled services may have changed. */
    fun watch(onChange: () -> Unit) {}
}

/**
 * The service state from [connected] and [availability]. Advanced Protection disables services
 * like this one from Android 17 (API 37); on Android 16 it does not, so it is reported only from
 * 37 on and only while the service is not connected.
 */
internal fun screenState(connected: Boolean, availability: ScreenAvailability, sdkInt: Int): ScreenServiceState = when {
    connected -> ScreenServiceState.READY
    sdkInt >= ADVANCED_PROTECTION_DISABLES_SERVICES && availability.advancedProtectionOn() -> ScreenServiceState.ADVANCED_PROTECTION
    availability.enabledInSettings() -> ScreenServiceState.STARTING
    availability.restrictedSettingsLikely() -> ScreenServiceState.RESTRICTED
    else -> ScreenServiceState.NOT_ENABLED
}

/** The first Android version (17) on which Advanced Protection switches off non-tool accessibility services. */
internal const val ADVANCED_PROTECTION_DISABLES_SERVICES = 37

/** [ScreenAvailability] from Android's settings and package records. */
internal class AndroidScreenAvailability(
    private val context: Context,
    private val advancedProtection: AdvancedProtectionReader = PlatformAdvancedProtectionReader(context),
) : ScreenAvailability {

    override fun enabledInSettings(): Boolean {
        val flat = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: RuntimeException) {
            null
        } ?: return false
        val me = OperatorAccessibilityService.componentName(context)
        return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == me }
    }

    override fun advancedProtectionOn(): Boolean = advancedProtection.isEnabled() == true

    /**
     * Android 13+ blocks switching on accessibility for apps installed from a file until the owner
     * allows restricted settings; Android 15+ also for apps not installed by a pre-installed
     * store. Android offers no way to ask, so this reads how the app was installed.
     */
    override fun restrictedSettingsLikely(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return false
        val pm = context.packageManager
        val source = try {
            pm.getInstallSourceInfo(context.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return true
        }
        if (source.packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            source.packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        val installer = source.installingPackageName ?: return true
        val installerInfo = pm.packageInfoOrNull(installer)?.applicationInfo ?: return true
        return installerInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
    }

    override fun watch(onChange: () -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer,
            )
        } catch (_: RuntimeException) {
            // No observer: the state still updates when the service connects and when the UI asks.
        }
    }
}

/** Facts about other apps and the device that the operator re-reads on every call. */
internal interface DeviceFacts {
    /** The SHA-256 of [packageName]'s signing certificate now, as `OperatorApp.signingCertSha256` records it; null if unknown. */
    fun signingCertSha256(packageName: String): String?

    /** The lock screen is showing. */
    fun keyguardLocked(): Boolean

    /** The default home screen app; null when there is none or it cannot be seen. */
    fun launcherPackage(): String?
}

/** [DeviceFacts] from PackageManager and KeyguardManager. */
internal class AndroidDeviceFacts(private val context: Context) : DeviceFacts {
    override fun signingCertSha256(packageName: String): String? =
        context.packageManager.packageInfoOrNull(packageName, INSTALL_IDENTITY_FLAGS)?.installIdentity()?.signingCertSha256

    // Unknown counts as locked: nothing is operated over a lock screen.
    override fun keyguardLocked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true

    override fun launcherPackage(): String? {
        val pm = context.packageManager
        val intent: Intent = QueriedIntent.HOME.intent()
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        // With no default chosen, Android resolves to its chooser in the "android" package.
        return resolved?.activityInfo?.packageName?.takeUnless { it == "android" }
    }
}
