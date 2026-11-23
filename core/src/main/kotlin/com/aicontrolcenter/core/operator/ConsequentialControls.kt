package com.aicontrolcenter.core.operator

import java.util.Locale

/**
 * The single source of the operator's risk vocabulary: which on-screen controls make an action
 * consequential, which apps are never operated, and the bulk-file threshold. Used by the tools in
 * `:framework` (assessment) and by the accessibility service in `:device` (refusal), which do not
 * depend on each other. Multilingual matching is not attempted; false positives only cost the
 * owner one extra confirmation.
 */
object ConsequentialControls {
    /**
     * Replacing, editing or deleting more than this many existing files is consequential: counted
     * per agent and task over [BULK_WINDOW_MS], not per call, so many small calls add up.
     */
    const val BULK_FILE_LIMIT: Int = 20

    /** The sliding window over which [BULK_FILE_LIMIT] is counted: 10 minutes. */
    const val BULK_WINDOW_MS: Long = 10 * 60_000L

    /** Word stems that mark a control as consequential when found in its text, description or view id. */
    val WORDS: List<String> = listOf(
        "buy", "pay", "purchase", "order", "checkout", "subscribe", "send", "post", "share", "publish",
        "reply", "call", "transfer", "install", "uninstall", "delete", "remove", "confirm", "accept", "sign",
    )

    /** Forms the suffix rules below do not produce. */
    private val IRREGULAR: Map<String, String> = mapOf(
        "paid" to "pay", "bought" to "buy", "sent" to "send", "subscription" to "subscribe",
        "subscriptions" to "subscribe", "signature" to "sign", "signatures" to "sign",
    )

    private val SUFFIXES = listOf(
        "s", "es", "d", "ed", "er", "ers", "ing", "ings", "ment", "ments", "ion", "ions", "ation", "ations", "ance", "able",
    )

    /** For stems ending in "e": "remove" → "removing", "removal"; "delete" → "deletion". */
    private val E_DROP_SUFFIXES = listOf("ing", "ion", "ions", "al", "able", "er", "ers")

    /**
     * The stem from [WORDS] that [text] contains, or null. Matching is case-insensitive and per
     * word: text is split at anything that is not a letter and at camelCase humps, so "Buy now",
     * "btn_buy" and "buyNow" match "buy", and "Payment" matches "pay". A word also matches when
     * it starts or ends with a form of a stem, which covers prefixes and compounds:
     * "Repayment", "Unsubscribe", "Autopay", "Resend", "paynow". Two adjacent words also match as
     * one ("Check out" → "checkout"). Accepted false positives: "Signal", "Design", "Recall",
     * "Postpone", "Payload".
     */
    fun matchedWord(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val words = words(text)
        for (word in words) stemIn(word)?.let { return it }
        for (i in 0 until words.size - 1) stemOf(words[i] + words[i + 1])?.let { return it }
        return null
    }

    /**
     * [matchedWord] over everything that labels a control: its text, description, merged
     * [ScreenElement.label] (child texts, state description, tooltip, action labels), then its
     * view id. `:device` passes the node it actually acts on (the clickable ancestor it clicks).
     */
    fun matchedWord(element: ScreenElement): String? =
        matchedWord(element.text) ?: matchedWord(element.description) ?: matchedWord(element.label)
            ?: matchedWord(element.resourceId?.substringAfter(":id/"))

    /** True when nothing on the control says what it does: no text, description or label. */
    fun isUnlabelled(element: ScreenElement): Boolean =
        element.text.isNullOrBlank() && element.description.isNullOrBlank() && element.label.isNullOrBlank()

    /**
     * Why acting on [element] is consequential, or null when it is not: a matched word, or a
     * clickable control with no label, whose effect cannot be shown to the owner.
     */
    fun consequentialReason(element: ScreenElement): String? {
        matchedWord(element)?.let { return "the control is labelled with \"$it\"" }
        if (element.clickable && isUnlabelled(element)) return "the control has no label, so what it does cannot be shown"
        return null
    }

    /**
     * Apps no agent may operate through the screen, whatever the owner authorised: system
     * settings and their permission, installer, sharing and credential screens, the system UI
     * (notifications, lock screen, dialogs) and Google's account and payment sheets. This app
     * itself is protected through [isProtectedPackage].
     */
    val PROTECTED_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.intentresolver",
        "com.android.credentialmanager",
        "com.google.android.gms",
        "com.miui.securitycenter",
        "com.miui.packageinstaller",
        "com.lbe.security.miui",
        "com.huawei.systemmanager",
    )

    /**
     * Intent actions whose handlers `:device` resolves at start-up and adds to the protected set
     * ([ProtectedPackages]), so OEM settings, installers and permission screens are covered on
     * every device, not only those named in [PROTECTED_PACKAGES].
     */
    val PROTECTED_HANDLER_ACTIONS: List<String> = listOf(
        "android.settings.SETTINGS",
        "android.settings.ACCESSIBILITY_SETTINGS",
        "android.settings.SECURITY_SETTINGS",
        "android.settings.APPLICATION_DETAILS_SETTINGS",
        "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
        "android.intent.action.INSTALL_PACKAGE",
        "android.intent.action.UNINSTALL_PACKAGE",
        "android.content.pm.action.REQUEST_PERMISSIONS",
        "android.app.action.ADD_DEVICE_ADMIN",
        "android.app.action.SET_NEW_PASSWORD",
    )

    /** Every package of this app and its build variants starts with this. */
    const val OWN_PACKAGE_PREFIX: String = "com.aicontrolcenter."

    /**
     * True for [PROTECTED_PACKAGES], [extra] (resolved at run time, see [ProtectedPackages]) and
     * this app ([ownPackage] or anything under [OWN_PACKAGE_PREFIX]).
     */
    fun isProtectedPackage(packageName: String, ownPackage: String? = null, extra: Set<String> = emptySet()): Boolean =
        packageName in PROTECTED_PACKAGES || packageName in extra || packageName == ownPackage ||
            packageName.startsWith(OWN_PACKAGE_PREFIX) || packageName + "." == OWN_PACKAGE_PREFIX

    private fun stemOf(word: String): String? {
        IRREGULAR[word]?.let { return it }
        return WORDS.firstOrNull { stem -> isFormOf(word, stem) }
    }

    /** [stemOf] the whole word, else of a start or end of it (at least three letters, leaving at least one). */
    private fun stemIn(word: String): String? {
        stemOf(word)?.let { return it }
        for (cut in 3 until word.length) stemOf(word.substring(0, cut))?.let { return it }
        for (cut in 1..word.length - 3) stemOf(word.substring(cut))?.let { return it }
        return null
    }

    private fun isFormOf(word: String, stem: String): Boolean {
        if (word == stem) return true
        if (!word.startsWith(stem.take(stem.length - 1))) return false
        if (SUFFIXES.any { word == stem + it }) return true
        if (stem.endsWith("e") && E_DROP_SUFFIXES.any { word == stem.dropLast(1) + it }) return true
        // Doubled final consonant: "transferred", "transferring".
        if (listOf("ed", "ing", "er").any { word == stem + stem.last() + it }) return true
        // Consonant + y: "replies", "replied".
        if (stem.length > 1 && stem.endsWith("y") && stem[stem.length - 2] !in "aeiou") {
            if (listOf("ies", "ied").any { word == stem.dropLast(1) + it }) return true
        }
        return false
    }

    /** Lower-case runs of letters, split at camelCase humps. */
    private fun words(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) out += current.toString().lowercase(Locale.ROOT)
            current.setLength(0)
        }
        var previous: Char? = null
        for (c in text) {
            if (!c.isLetter()) {
                flush()
            } else {
                if (c.isUpperCase() && previous != null && previous.isLowerCase()) flush()
                current.append(c)
            }
            previous = c
        }
        flush()
        return out
    }
}

/**
 * Which apps are never operated, read or handed control: the static floor
 * ([ConsequentialControls.PROTECTED_PACKAGES] and this app) plus what `:device` resolves at
 * start-up ([ConsequentialControls.PROTECTED_HANDLER_ACTIONS]). The screen operator, app
 * actions, the broker and the owner ports all ask this one object.
 */
class ProtectedPackages(
    /** This app's package. */
    val ownPackage: String?,
    /** Handlers resolved on this device; added to, never replacing, the static list. */
    val resolved: Set<String> = emptySet(),
) {
    fun isProtected(packageName: String): Boolean = ConsequentialControls.isProtectedPackage(packageName, ownPackage, resolved)
}
