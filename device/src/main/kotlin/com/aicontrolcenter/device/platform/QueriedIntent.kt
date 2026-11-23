package com.aicontrolcenter.device.platform

import android.content.Intent
import android.net.Uri
import com.aicontrolcenter.core.operator.ConsequentialControls

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
    /** Example data whose scheme the handlers' filters require ("package:", "content:"); the manifest declares its scheme. */
    val exampleData: String? = null,
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

    /** The home screen app: the screen operator checks a HOME action like any other app. */
    HOME(Intent.ACTION_MAIN, category = Intent.CATEGORY_HOME),

    // Handlers of these system screens are never operated (ConsequentialControls.PROTECTED_HANDLER_ACTIONS).
    SETTINGS("android.settings.SETTINGS"),
    ACCESSIBILITY_SETTINGS("android.settings.ACCESSIBILITY_SETTINGS"),
    SECURITY_SETTINGS("android.settings.SECURITY_SETTINGS"),
    APPLICATION_DETAILS_SETTINGS("android.settings.APPLICATION_DETAILS_SETTINGS", exampleData = "package:com.example"),
    MANAGE_UNKNOWN_APP_SOURCES("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
    INSTALL_PACKAGE(
        "android.intent.action.INSTALL_PACKAGE",
        mimeType = "application/vnd.android.package-archive",
        exampleData = "content://com.example.files/app.apk",
    ),
    UNINSTALL_PACKAGE("android.intent.action.UNINSTALL_PACKAGE", exampleData = "package:com.example"),
    REQUEST_PERMISSIONS("android.content.pm.action.REQUEST_PERMISSIONS"),
    ADD_DEVICE_ADMIN("android.app.action.ADD_DEVICE_ADMIN"),
    SET_NEW_PASSWORD("android.app.action.SET_NEW_PASSWORD"),
    ;

    /** The URI scheme the manifest entry declares, taken from [exampleData]. */
    val scheme: String? get() = exampleData?.substringBefore(':')

    /** A fresh intent with this signature, restricted to [onlyPackage] unless that is null. */
    fun intent(onlyPackage: String? = null): Intent {
        val intent = Intent(action).setPackage(onlyPackage)
        // setType and setData each clear the other, so data with a type is set in one call.
        if (exampleData != null) intent.setDataAndType(Uri.parse(exampleData), mimeType) else intent.setType(mimeType)
        category?.let(intent::addCategory)
        return intent
    }

    /** Groups of entries. */
    companion object {
        /** One entry per [ConsequentialControls.PROTECTED_HANDLER_ACTIONS], in that order. */
        val protectedHandlers: List<QueriedIntent>
            get() = ConsequentialControls.PROTECTED_HANDLER_ACTIONS.map { action -> entries.single { it.action == action } }
    }
}
