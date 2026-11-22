package com.aicontrolcenter.device.scan

import android.content.Intent
import android.content.pm.PackageManager
import com.aicontrolcenter.device.platform.activitiesFor
import com.aicontrolcenter.device.platform.servicesFor
import com.aicontrolcenter.device.platform.startableBy

/**
 * Which packages expose each intent-based integration surface right now, as answered by
 * PackageManager. Disabled apps and components are absent, as are activities this app could not
 * start (not exported, or guarded by a permission it lacks): Android would not deliver to them.
 *
 * One device-wide snapshot serves a whole scan (seven queries in total instead of several per
 * app); [query] with a package name restricts the same queries to that one app.
 */
internal class IntentSurfaces(
    val shareText: Set<String>,
    val shareImage: Set<String>,
    val processText: Set<String>,
    val assistant: Set<String>,
) {
    companion object {
        /**
         * The action a `VoiceInteractionService` declares; apps that can be chosen as the
         * device's digital assistant ship one. Queried by name so nothing is bound or started.
         */
        private const val VOICE_INTERACTION_SERVICE = "android.service.voice.VoiceInteractionService"

        /**
         * @param callerPackage this app; only activities it may start count as a surface.
         * @param onlyPackage restricts every query to one app, or null for the whole device.
         */
        fun query(pm: PackageManager, callerPackage: String, onlyPackage: String? = null): IntentSurfaces {
            fun intent(action: String, mimeType: String? = null) =
                Intent(action).setType(mimeType).setPackage(onlyPackage)

            // MATCH_DEFAULT_ONLY mirrors startActivity(), which only delivers an intent without a
            // component to activities whose filter includes CATEGORY_DEFAULT.
            val deliverable = PackageManager.MATCH_DEFAULT_ONLY.toLong()
            fun activities(intent: Intent, flags: Long = deliverable): Set<String> =
                pm.activitiesFor(intent, flags).startableBy(pm, callerPackage).mapTo(LinkedHashSet()) { it.packageName }

            // Declaring the service is the signal; it is never bound, so its bind permission is irrelevant.
            val assistantServices = pm.servicesFor(intent(VOICE_INTERACTION_SERVICE)).mapNotNull { it.serviceInfo?.packageName }

            return IntentSurfaces(
                shareText = activities(intent(Intent.ACTION_SEND, "text/plain")),
                shareImage = activities(intent(Intent.ACTION_SEND, "image/*")),
                // Text toolbars start PROCESS_TEXT handlers by component, so DEFAULT is not required.
                processText = activities(intent(Intent.ACTION_PROCESS_TEXT, "text/plain"), flags = 0L),
                assistant = activities(intent(Intent.ACTION_ASSIST)) +
                    activities(intent(Intent.ACTION_VOICE_COMMAND)) +
                    assistantServices,
            )
        }
    }
}
