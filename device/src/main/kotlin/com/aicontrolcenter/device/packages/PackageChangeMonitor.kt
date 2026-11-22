package com.aicontrolcenter.device.packages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** What happened to a package. */
enum class PackageChangeKind {
    /** Newly installed. */
    ADDED,

    /** Uninstalled. */
    REMOVED,

    /** Updated to a new version; its intent filters may have changed. */
    REPLACED,

    /** The app or one of its components was enabled or disabled. */
    CHANGED,
}

/** One change to another app's package. This app's own package is never reported. */
data class PackageChange(val packageName: String, val kind: PackageChangeKind)

/**
 * Reports installs, removals, updates and enable/disable changes of other apps while this
 * process is alive, so the registry can refresh an entry the moment it changes.
 *
 * Android delivers these broadcasts only to receivers registered at runtime, and only for
 * packages visible to this app (see the manifest `<queries>` block). They are best effort: the
 * registry must still reconcile on start and when the app returns to the foreground.
 */
class PackageChangeMonitor(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    /**
     * Cold: each collector registers its own receiver and unregisters it when collection stops.
     * Events are buffered without limit so a slow collector loses none during a bulk update.
     */
    val changes: Flow<PackageChange> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                packageChangeOf(intent, ownPackage = appContext.packageName)?.let { trySend(it) }
            }
        }
        // These are protected broadcasts that only the system can send, so the receiver does not
        // need to be reachable by other apps. An explicit flag is mandatory from targetSdk 34.
        ContextCompat.registerReceiver(appContext, receiver, packageFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.buffer(Channel.UNLIMITED)

    internal companion object {
        fun packageFilter() = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }

        /**
         * Translates one package broadcast, or returns null for broadcasts that carry no news:
         * an update arrives as REMOVED then ADDED (both flagged as replacing) then REPLACED, and
         * only the last is reported. This app's own package is ignored; libraries toggle its
         * components at runtime, which would otherwise look like a change worth a rescan.
         */
        fun packageChangeOf(intent: Intent, ownPackage: String): PackageChange? {
            val packageName = intent.data?.schemeSpecificPart
            if (packageName.isNullOrEmpty() || packageName == ownPackage) return null
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            val kind = when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> if (replacing) null else PackageChangeKind.ADDED
                Intent.ACTION_PACKAGE_REMOVED -> if (replacing) null else PackageChangeKind.REMOVED
                Intent.ACTION_PACKAGE_REPLACED -> PackageChangeKind.REPLACED
                Intent.ACTION_PACKAGE_CHANGED -> PackageChangeKind.CHANGED
                else -> null
            }
            return kind?.let { PackageChange(packageName, it) }
        }
    }
}
