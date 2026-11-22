package com.aicontrolcenter.device.report

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo
import android.os.Build
import android.os.StatFs
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.device.DeviceReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Measures this phone with public Android APIs: `Build`, `ActivityManager`, `StatFs`,
 * `PackageManager` and, from Android 16, `AdvancedProtectionManager`. The only permission
 * involved is the normal one for reading the Advanced Protection state; nothing is sent
 * anywhere and no other app's data is read.
 *
 * Values the platform cannot supply are reported as null or 0 rather than guessed, which the
 * report's consumers already treat as "unknown".
 */
class AndroidDeviceInspector internal constructor(
    context: Context,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    private val advancedProtection: AdvancedProtectionReader,
) : DeviceInspector {

    constructor(
        context: Context,
        clock: Clock = WallClock,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(context, clock, dispatcher, PlatformAdvancedProtectionReader(context.applicationContext ?: context))

    private val appContext: Context = context.applicationContext ?: context

    override suspend fun inspect(): DeviceReport = withContext(dispatcher) {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val isLowRam = activityManager.isLowRamDevice
        val glEs = activityManager.deviceConfigurationInfo
        val glEsVersionName = glEs.versionNameOrNull()
        val storage = storageOf(appContext.dataDir.path)

        DeviceReport(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.knownOrNull(),
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.knownOrNull() else null,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.knownOrNull() else null,
            supportedAbis = Build.SUPPORTED_ABIS.orEmpty().toList(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            isLowRamDevice = isLowRam,
            storageTotalBytes = storage.totalBytes,
            storageFreeBytes = storage.freeBytes,
            openGlEsVersion = glEsVersionName,
            features = DeviceFeatureProbe(appContext.packageManager, advancedProtection)
                .probe(glEs.reqGlEsVersion, glEsVersionName, isLowRam),
            generatedAt = clock.now(),
        )
    }

    private class Storage(val totalBytes: Long, val freeBytes: Long)

    /** Size of the volume holding this app's data, where imported models would be stored. */
    private fun storageOf(path: String): Storage = try {
        val stat = StatFs(path)
        // availableBytes, not freeBytes: the latter counts blocks reserved for the system.
        Storage(totalBytes = stat.totalBytes, freeBytes = stat.availableBytes)
    } catch (_: IllegalArgumentException) {
        // StatFs throws this when the path cannot be read (for example a volume being unmounted).
        Storage(totalBytes = 0, freeBytes = 0)
    }

    private fun ConfigurationInfo.versionNameOrNull(): String? =
        if (reqGlEsVersion == ConfigurationInfo.GL_ES_VERSION_UNDEFINED) null else glEsVersion

    /** Android uses the literal "unknown" (and occasionally an empty string) for absent values. */
    private fun String?.knownOrNull(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !it.equals(Build.UNKNOWN, ignoreCase = true) }
}
