package com.aicontrolcenter.app.di.wiring

import android.content.Context
import android.content.Intent
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.StartupHooks
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.ExitHistory
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.StorageWatch
import com.aicontrolcenter.data.DataLayer

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
)

@Phase6Stub("WP2")
object BackgroundWiring {
    /**
     * The production background port. Called once per process, by the application class; it must not build the
     * graph or open the database (the gate does that, off the main thread).
     */
    fun production(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") data: DataLayer,
    ): BackgroundPorts = BackgroundPorts()
}
