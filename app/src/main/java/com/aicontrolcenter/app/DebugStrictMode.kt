package com.aicontrolcenter.app

import android.annotation.SuppressLint
import android.os.Build
import android.os.StrictMode

/**
 * StrictMode for debug builds (Phase 6, D45; wave 3, WP8): finds work the main thread should not do (reading or writing
 * the disk, the network, slow calls) and leaks (unclosed databases, cursors and streams, registrations, activities).
 * Log only: a finding is a log line under the tag "StrictMode", never a crash, a dialog or a flash. Release builds never
 * install it, and neither does a JVM test under Robolectric, where every test's own disk work would be reported.
 *
 * At a cold start the main thread should find nothing to report: the app's gate opens the database and builds the
 * graph off it (`AppGate`'s prepare step), and the crash notes' and start ledger's folders are asked for on first use.
 */
internal object DebugStrictMode {
    /** Whether to install it: debug builds on a phone or an emulator, never in a release build or under Robolectric. */
    fun shouldInstall(debug: Boolean, fingerprint: String? = Build.FINGERPRINT): Boolean = debug && fingerprint != ROBOLECTRIC

    /** Every main-thread check Android has, reported in the log only. */
    fun threadPolicy(): StrictMode.ThreadPolicy = StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build()

    /**
     * The leak and misuse checks, reported in the log only. Not every check: untagged sockets and cleartext traffic would
     * report each request to a model the owner runs on their own network, which is allowed and expected.
     */
    @SuppressLint("NewApi") // [sdkInt] is the guard
    fun vmPolicy(sdkInt: Int = Build.VERSION.SDK_INT): StrictMode.VmPolicy {
        val builder = StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectActivityLeaks()
            .detectFileUriExposure()
            .detectContentUriWithoutPermission()
        if (sdkInt >= Build.VERSION_CODES.S) {
            builder.detectUnsafeIntentLaunch().detectIncorrectContextUse()
        }
        return builder.penaltyLog().build()
    }

    /** Installs both policies on the main thread (the application's `onCreate`). */
    fun install(debug: Boolean = BuildConfig.DEBUG) {
        if (!shouldInstall(debug)) return
        StrictMode.setThreadPolicy(threadPolicy())
        StrictMode.setVmPolicy(vmPolicy())
    }

    private const val ROBOLECTRIC = "robolectric"
}
