package com.aicontrolcenter.device.screen

import android.content.Context
import android.view.autofill.AutofillManager
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.device.platform.QueriedIntent
import com.aicontrolcenter.device.platform.activitiesFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds [ProtectedPackages] for this device at start-up: the static floor
 * ([ConsequentialControls.PROTECTED_PACKAGES] and this app) plus every app that handles one of
 * [ConsequentialControls.PROTECTED_HANDLER_ACTIONS] here (OEM settings, installers, permission and
 * device-admin screens), plus the default autofill service, which is the password manager or
 * credential provider the owner chose.
 *
 * The lock screen has no package of its own to resolve (it is part of the system UI, which the
 * static list protects); the screen operator also refuses everything while it is showing.
 */
object ProtectedPackagesResolver {
    /** Blocking PackageManager queries: call it off the main thread, or use [resolveOn]. */
    fun resolve(context: Context): ProtectedPackages {
        val app = context.applicationContext ?: context
        val pm = app.packageManager
        val found = sortedSetOf<String>()
        for (query in QueriedIntent.protectedHandlers) {
            try {
                pm.activitiesFor(query.intent()).mapNotNullTo(found) { it.activityInfo?.packageName }
            } catch (_: RuntimeException) {
                // One failed query must not leave the rest unprotected.
            }
        }
        defaultAutofillService(app)?.let(found::add)
        return ProtectedPackages(ownPackage = app.packageName, resolved = found)
    }

    suspend fun resolveOn(context: Context, dispatcher: CoroutineDispatcher = Dispatchers.IO): ProtectedPackages =
        withContext(dispatcher) { resolve(context) }

    private fun defaultAutofillService(context: Context): String? =
        try {
            context.getSystemService(AutofillManager::class.java)?.autofillServiceComponentName?.packageName
        } catch (_: RuntimeException) {
            null
        }
}
