package com.aicontrolcenter.app.di.wiring

import android.content.Context
import android.content.Intent
import com.aicontrolcenter.app.BuildConfig
import com.aicontrolcenter.app.di.StartupHooks
import com.aicontrolcenter.app.health.AndroidStartupHooks
import com.aicontrolcenter.app.health.CrashNotes
import com.aicontrolcenter.app.health.SafeStartControl
import com.aicontrolcenter.app.health.StartLedger
import com.aicontrolcenter.app.work.UpkeepJobService
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.ExitHistory
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.StorageWatch
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.device.health.AndroidBackgroundHealth
import com.aicontrolcenter.device.health.AndroidExitHistory
import com.aicontrolcenter.device.health.AndroidStorageWatch
import com.aicontrolcenter.device.health.BackgroundSettingsIntents

/*
 * Background work and recovery, Android side (Phase 6, WP2 owns this file in wave 2; rules 1, 4, 5, 9, 10, 26, 30, 31).
 *
 * Built once per process by the application class (`AiControlCenterApp.background`), before and without the
 * composition root: the Recovery path and the upkeep job use it without ever building the graph. The same
 * instance is handed to `createProductionPorts`, so the graph and the application share it.
 */

/**
 * Android's screens the owner needs to let the app work in the background (WP2 item 4, A3). Each returns null when
 * the phone has no such screen; the owner's screen then says so in plain words.
 */
interface BackgroundScreens {
    /** Android's "allow this app to run in the background" request (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). */
    fun batteryExemption(): Intent?

    /** The app's page in Android's settings (`ACTION_APPLICATION_DETAILS_SETTINGS`), the fallback for the rest. */
    fun appDetails(): Intent?

    /** No screens: what tests and the wave-1 app use. */
    object None : BackgroundScreens {
        override fun batteryExemption(): Intent? = null

        override fun appDetails(): Intent? = null
    }
}

/**
 * What the background package hands the rest of the app. Every member has a do-nothing default, which is the
 * behaviour of 0.5.2.
 *
 * @param health whether Android lets the app work in the background (notifications, battery, standby bucket).
 * @param exits why the app's main process ended before (Android 11 and later).
 * @param storage the phone's free room.
 * @param startup what start-up does before, during and after its steps (the start ledger, safe start, the cause
 *   of an interruption, the upkeep job's schedule).
 * @param recovery owner-only: the database check and Start over. Null until WP2 builds it; the gate then opens at once.
 * @param screens Android's screens for the background check.
 */
class BackgroundPorts(
    val health: BackgroundHealth = BackgroundHealth.None,
    val exits: ExitHistory = ExitHistory.None,
    val storage: StorageWatch = StorageWatch.None,
    val startup: StartupHooks = StartupHooks.None,
    val recovery: OwnerRecovery? = null,
    val screens: BackgroundScreens = BackgroundScreens.None,
    /** Safe start for the owner's banner (WP10, Tier B): on after repeated failed starts or crashes. */
    val safeStart: SafeStartControl = SafeStartControl.None,
)

object BackgroundWiring {
    /**
     * The production background port. Called once per process, by the application class; it builds the graph never and
     * opens nothing (every member reads Android or a file only when first asked; the gate opens the database, off the
     * main thread).
     */
    fun production(context: Context, data: DataLayer): BackgroundPorts {
        val app = context.applicationContext
        val health = AndroidBackgroundHealth(app)
        val exits = AndroidExitHistory(app)
        val storage = AndroidStorageWatch(app)
        val hooks = AndroidStartupHooks(
            // Both folders are asked for on first use, off the main thread (D45): this runs on it, from the main activity.
            ledger = StartLedger.inFolder { app.noBackupFilesDir },
            exits = exits,
            storage = storage,
            health = health,
            crashNotes = CrashNotes.inFolder { app.noBackupFilesDir },
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            clock = WallClock,
            scheduleUpkeep = { UpkeepJobService.schedule(app) },
            recorder = ActivityRecorder(data.activityLog, RandomIdGenerator, WallClock),
        )
        return BackgroundPorts(
            health = health,
            exits = exits,
            storage = storage,
            startup = hooks,
            recovery = data.ownerRecovery,
            screens = AndroidBackgroundScreens(app),
            safeStart = hooks.safeStart,
        )
    }
}

/** [BackgroundScreens] over Android's settings screens (`:device`). */
private class AndroidBackgroundScreens(private val context: Context) : BackgroundScreens {
    override fun batteryExemption(): Intent = BackgroundSettingsIntents.batteryExemption(context)

    override fun appDetails(): Intent = BackgroundSettingsIntents.appDetails(context)
}
