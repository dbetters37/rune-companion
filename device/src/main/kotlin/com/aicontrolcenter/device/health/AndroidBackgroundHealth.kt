package com.aicontrolcenter.device.health

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StandbyBucket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Whether Android lets the app work in the background (Phase 6, A3; WP2 item 4), read from Android each time [refresh]
 * is called (at start, when the app comes to the front, when the owner returns from one of Android's screens):
 * - notifications allowed (`NotificationManagerCompat.areNotificationsEnabled`, which covers the runtime permission and
 *   the owner's switch);
 * - battery optimisation (`PowerManager.isIgnoringBatteryOptimizations`, API 23): optimised means Doze may hold work
 *   back, and the app may not start its foreground service from the background (F29);
 * - background restriction (`ActivityManager.isBackgroundRestricted`, API 28);
 * - the standby bucket (`UsageStatsManager.getAppStandbyBucket`, API 28): only rare and restricted are warned about.
 *
 * Nothing is changed here: the owner decides on Android's own screens ([BackgroundSettingsIntents]).
 *
 * @param sdkInt the phone's API level; injected so older branches are tested on the JVM (F2).
 */
class AndroidBackgroundHealth(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BackgroundHealth {
    private val appContext = context.applicationContext
    private val current = MutableStateFlow(BackgroundState.UNKNOWN)

    override val state: StateFlow<BackgroundState> = current.asStateFlow()

    override suspend fun refresh() {
        current.value = withContext(io) { read() }
    }

    /** Reads every item now; an item Android does not answer is null ("not known"). */
    fun read(): BackgroundState = BackgroundState(
        notificationsAllowed = ask { NotificationManagerCompat.from(appContext).areNotificationsEnabled() } ?: true,
        batteryOptimised = ask { appContext.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(appContext.packageName)?.not() },
        backgroundRestricted = if (sdkInt >= Build.VERSION_CODES.P) ask { appContext.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted } else null,
        standbyBucket = if (sdkInt >= Build.VERSION_CODES.P) standbyBucket() else null,
    )

    @SuppressLint("NewApi")
    private fun standbyBucket(): StandbyBucket? =
        ask { appContext.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }?.let(::bucketOf)

    private inline fun <T> ask(block: () -> T?): T? =
        try {
            block()
        } catch (_: RuntimeException) {
            null
        }

    companion object {
        /** Android's bucket constants (`UsageStatsManager.STANDBY_BUCKET_*`) as the app's own names. */
        fun bucketOf(bucket: Int): StandbyBucket = when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.ACTIVE
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WORKING_SET
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.FREQUENT
            UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.RARE
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> StandbyBucket.RESTRICTED
            STANDBY_BUCKET_EXEMPTED -> StandbyBucket.EXEMPTED
            else -> StandbyBucket.UNKNOWN
        }

        /** `UsageStatsManager.STANDBY_BUCKET_EXEMPTED` (a system API constant). */
        const val STANDBY_BUCKET_EXEMPTED: Int = 5
    }
}

/**
 * Android's own screens for the background check (Phase 6, A3): the request to stop optimising the battery for the app,
 * and the app's page in Android's settings as the fallback for everything else. They are not looked up first (package
 * visibility would hide Android's settings from such a look-up on some phones): a phone without the screen answers
 * the tap with `ActivityNotFoundException`, which the owner's screen turns into a plain sentence and the other button.
 */
object BackgroundSettingsIntents {
    /**
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for this app (needs the normal permission the app declares). Play
     * restricts this request to some apps; this build is installed by the owner directly.
     */
    fun batteryExemption(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** `ACTION_APPLICATION_DETAILS_SETTINGS`: the app's page in Android's settings (notifications, battery, background use). */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What the owner's screen says when Android has no such screen on this phone. */
    const val NO_SUCH_SCREEN: String = "This phone does not have that screen. Open the app's page in Android's settings instead."
}
