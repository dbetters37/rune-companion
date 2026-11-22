package com.aicontrolcenter.device.platform

import android.content.Intent

/**
 * Every implicit intent this module asks PackageManager about device-wide.
 *
 * Since Android 11 such a query is answered only for packages that a matching `<intent>` in the
 * manifest `<queries>` block makes visible, so this list and that block must agree;
 * `QueriesManifestTest` compares them. Queries pinned to one package (its launch intent, its
 * prompt link) need no entry here: they only ever target a package that one of these intents or
 * a `<package>` element already made visible.
 */
internal enum class QueriedIntent(
    val action: String,
    val mimeType: String? = null,
    val category: String? = null,
) {
    /** Apps with a launcher icon: the manual-registration picker and the name heuristic. */
    LAUNCHER(Intent.ACTION_MAIN, category = Intent.CATEGORY_LAUNCHER),
    SHARE_TEXT(Intent.ACTION_SEND, mimeType = "text/plain"),
    SHARE_IMAGE(Intent.ACTION_SEND, mimeType = "image/*"),
    PROCESS_TEXT(Intent.ACTION_PROCESS_TEXT, mimeType = "text/plain"),
    ASSIST(Intent.ACTION_ASSIST),
    VOICE_COMMAND(Intent.ACTION_VOICE_COMMAND),

    /**
     * The action a `VoiceInteractionService` declares; apps that can be chosen as the device's
     * digital assistant ship one. Queried by name so nothing is bound or started.
     */
    VOICE_INTERACTION_SERVICE("android.service.voice.VoiceInteractionService"),
    ;

    /** A fresh intent with this signature, restricted to [onlyPackage] unless that is null. */
    fun intent(onlyPackage: String? = null): Intent {
        val intent = Intent(action).setType(mimeType).setPackage(onlyPackage)
        category?.let(intent::addCategory)
        return intent
    }
}
