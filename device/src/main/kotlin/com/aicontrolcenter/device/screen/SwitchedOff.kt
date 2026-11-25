package com.aicontrolcenter.device.screen

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.aicontrolcenter.core.operator.ScreenServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether screen control was ready before, kept across restarts (Phase 6, D7). Device
 * SharedPreferences, never the database: the plan keeps "screen service was ready" outside it.
 */
internal interface ReadyFlagStore {
    fun wasReady(): Boolean

    fun setReady(ready: Boolean)
}

/** [ReadyFlagStore] in this app's own SharedPreferences file `screen_service`. */
internal class PreferencesReadyFlagStore(context: Context) : ReadyFlagStore {
    private val appContext = context.applicationContext ?: context

    /** Opened on first use, off the main thread (the watch runs on the layer's io dispatcher). */
    private val preferences by lazy { appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }

    override fun wasReady(): Boolean = preferences.getBoolean(KEY, false)

    override fun setReady(ready: Boolean) {
        if (wasReady() != ready) preferences.edit().putBoolean(KEY, ready).apply()
    }

    companion object {
        const val FILE_NAME = "screen_service"
        const val KEY = "was_ready"
    }
}

/** What Android says about how this process was started, newest start first; only what the notice needs. */
internal data class StartRecord(val wasForceStopped: Boolean)

/**
 * Android's record of this app's latest start (API 35+, `ActivityManager.getHistoricalProcessStartReasons`
 * and `ApplicationStartInfo.wasForceStopped`); empty before 35 or when it cannot be read.
 */
internal class AndroidStartRecords(private val context: Context, private val sdkInt: Int = Build.VERSION.SDK_INT) : () -> List<StartRecord> {
    @SuppressLint("NewApi") // [sdkInt] is the guard
    override fun invoke(): List<StartRecord> {
        if (sdkInt < Build.VERSION_CODES.VANILLA_ICE_CREAM) return emptyList()
        return try {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            Api35.latestStart(manager)
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /**
     * The API 35 call, in a class of its own that Android 14 and older never load. The method exists there but is
     * hidden, and linking a class that calls it logs "Accessing hidden method ... (blocked, linking, denied)" at every
     * start, even though the call is never made (device gate of wave 2, finding 3).
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private object Api35 {
        fun latestStart(manager: ActivityManager): List<StartRecord> =
            manager.getHistoricalProcessStartReasons(1).map { StartRecord(it.wasForceStopped()) }
    }
}

/**
 * Tells the owner when Android switched screen control off by itself (Phase 6, K7, D7): Android
 * removes an app's accessibility service when the app is stopped (force-stopped), and the owner
 * must turn it on again in Android's accessibility settings. The switch-off is Android's rule; this
 * only explains it and links there.
 *
 * Fed every service state ([onState]). The service being READY is remembered ([ReadyFlagStore]).
 * The first decisive state of a process (not CHECKING or STARTING) that says the service is not
 * enabled (NOT_ENABLED, or RESTRICTED: a sideloaded app's service is reported so) while the flag
 * says it was ready means Android switched it off after the app was stopped: [switchedOff] is true,
 * and stays so until the service is ready again. The owner switching it off while the app runs
 * (READY, then not enabled in the same process) clears the flag and says nothing.
 *
 * The sentence says "force-stopped" only on Android 15+ when the latest start of this app says it
 * was force-stopped ([StartRecord.wasForceStopped]; on older versions Android does not tell a force
 * stop from a swipe from Recents); otherwise "stopped".
 */
internal class SwitchedOffWatch(
    private val store: ReadyFlagStore,
    private val sdkInt: Int,
    private val startRecords: () -> List<StartRecord>,
) {
    private val off = MutableStateFlow(false)
    private val text = MutableStateFlow<String?>(null)
    private var decided = false
    private var readyHere = false

    /** True while Android has screen control switched off after the app was stopped. */
    val switchedOff: StateFlow<Boolean> = off.asStateFlow()

    /** The sentence for the owner while [switchedOff]; null otherwise. */
    val sentence: StateFlow<String?> = text.asStateFlow()

    @Synchronized
    fun onState(state: ScreenServiceState) {
        when (state) {
            ScreenServiceState.READY -> {
                decided = true
                readyHere = true
                store.setReady(true)
                off.value = false
                text.value = null
            }
            ScreenServiceState.NOT_ENABLED, ScreenServiceState.RESTRICTED -> {
                if (readyHere) {
                    // Switched off while the app runs: the owner's own choice, or Android's for a reason it shows itself.
                    readyHere = false
                    store.setReady(false)
                } else if (!decided && store.wasReady()) {
                    text.value = if (forceStopped()) FORCE_STOPPED else STOPPED
                    off.value = true
                }
                decided = true
            }
            ScreenServiceState.ADVANCED_PROTECTION -> decided = true
            ScreenServiceState.CHECKING, ScreenServiceState.STARTING -> Unit
        }
    }

    private fun forceStopped(): Boolean =
        sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM && (runCatching { startRecords().firstOrNull()?.wasForceStopped }.getOrNull() == true)

    /** The owner's sentences. */
    companion object {
        const val STOPPED =
            "Android switched screen control off after the app was stopped. Turn it on again in Android's accessibility settings."
        const val FORCE_STOPPED =
            "Android switched screen control off after the app was force-stopped. Turn it on again in Android's accessibility settings."
    }
}
