package com.aicontrolcenter.device.report

import android.content.Context
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.annotation.RequiresApi

/** Answers whether Android's Advanced Protection mode is switched on for this device. */
internal fun interface AdvancedProtectionReader {
    /** The state as Android reports it, or null when this device cannot say. */
    fun isEnabled(): Boolean?
}

/**
 * Asks `AdvancedProtectionManager`, which exists from Android 16 (API 36) and requires the
 * normal permission `QUERY_ADVANCED_PROTECTION_MODE` declared in this module's manifest.
 *
 * Unknown is a legitimate answer: older Android versions have no such mode, some device types
 * ship without the service, and a failed call must not take the whole device report down.
 */
internal class PlatformAdvancedProtectionReader(private val context: Context) : AdvancedProtectionReader {

    override fun isEnabled(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        return try {
            Api36.isEnabled(context)
        } catch (_: RuntimeException) {
            // A SecurityException if a manifest merge removed the permission, or whatever an
            // unreachable system service throws: either way the state is not known.
            null
        }
    }

    /** Keeps the API 36 class out of the verifier's way on older Android versions. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private object Api36 {
        fun isEnabled(context: Context): Boolean? =
            context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled
    }
}
