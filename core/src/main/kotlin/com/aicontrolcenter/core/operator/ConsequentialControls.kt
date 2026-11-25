package com.aicontrolcenter.core.operator

import java.text.Normalizer
import java.util.Locale

/**
 * The single source of the operator's risk vocabulary: which on-screen controls make an action
 * consequential, which apps are never operated, and the bulk-file threshold. Used by the tools in
 * `:framework` (assessment). Multilingual matching is not attempted; false positives only cost
 * the owner one extra confirmation.
 *
 * A word list cannot be complete, so it is not the owner's only protection: every authorised app
 * asks before every screen action by default ([ScreenActionPolicy.ASK_EVERY_ACTION]); this
 * classification decides only for apps the owner switched to
 * [ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY].
 */
object ConsequentialControls {
    /**
     * Replacing, editing or deleting more than this many existing files is consequential: counted
     * over [BULK_WINDOW_MS], not per call, so many small calls add up, and counted per agent and
     * task, per agent across its tasks and per root objective across its agents, so neither a
     * new task nor a helper agent starts again at zero.
     */
    const val BULK_FILE_LIMIT: Int = 20

    /** The sliding window over which [BULK_FILE_LIMIT] is counted: 10 minutes. */
    const val BULK_WINDOW_MS: Long = 10 * 60_000L

    /**
     * Word stems that mark a control as consequential when found in its text, description,
     * merged label or view id. They also match inside a word ("Autopay", "Resend").
     */
    val WORDS: List<String> = listOf(
        "buy", "pay", "purchase", "order", "checkout", "subscribe", "send", "post", "share", "publish",
        "reply", "call", "transfer", "install", "uninstall", "delete", "remove", "confirm", "accept", "sign",
    )

    /**
     * More stems for buying, sending, deleting, consenting and starting things. They match whole
     * words and their forms only ("Booking", "Cleared"), not inside other words, so "Display",
     * "Facebook" and "Nuclear" do not ask.
     */
    val WHOLE_WORDS: List<String> = listOf(
        "sell", "bid", "withdraw", "swap", "request", "book", "reserve", "donate", "tip", "upgrade", "trial", "submit",
        "forward", "comment", "report", "block", "invite", "decline", "join", "upload", "allow", "approve", "agree",
        "authorize", "authorise", "grant", "trash", "bin", "empty", "clear", "discard", "erase", "wipe", "reset",
        "archive", "run", "play", "live",
    )

    /**
     * Words of a control that answers yes to whatever its window asks, or moves it on
     * ("Delete 248 photos?" · OK, "Total: 249.00 EUR" · Next). Such a control is consequential
     * when its window or row mentions a consequential word (in a text that is not a control
     * itself), shows a price or an amount (on a control too), or holds a filled-in field
     * ([consequentialReason]).
     */
    val POSITIVE_WORDS: Set<String> = setOf(
        "ok", "okay", "yes", "continue", "done", "proceed", "confirm", "allow", "accept", "agree",
        "next", "finish", "go", "apply", "save", "start", "place", "sure",
    )

    /** Yes-answers of two words, matched on two adjacent words ("Got it"). */
    val POSITIVE_PHRASES: Set<String> = setOf("got it")

    /** Words that name a field for a one-time or security code: typing one can confirm a payment or a sign-in by itself. */
    val CODE_FIELD_WORDS: Set<String> = setOf("code", "codes", "otp", "passcode", "verify", "verification", "cvv", "cvc", "tan")

    /** Forms the suffix rules below do not produce. */
    private val IRREGULAR: Map<String, String> = mapOf(
        "paid" to "pay", "bought" to "buy", "sent" to "send", "subscription" to "subscribe",
        "subscriptions" to "subscribe", "signature" to "sign", "signatures" to "sign", "sold" to "sell",
        "withdrawal" to "withdraw", "withdrawals" to "withdraw", "withdrawn" to "withdraw", "withdrew" to "withdraw",
        "submission" to "submit", "ran" to "run", "authorization" to "authorize", "authorisation" to "authorise",
    )

    private val SUFFIXES = listOf(
        "s", "es", "d", "ed", "er", "ers", "ing", "ings", "ment", "ments", "ion", "ions", "ation", "ations", "ance", "able",
    )

    /** For stems ending in "e": "remove" → "removing", "removal"; "delete" → "deletion"; "reserve" → "reservation". */
    private val E_DROP_SUFFIXES = listOf("ing", "ion", "ions", "ation", "ations", "al", "able", "er", "ers")

    /** ISO 4217 codes (and a few crypto tickers) that make a number a price: "249.00 EUR". */
    private val CURRENCY_CODES = listOf(
        "usd", "eur", "gbp", "jpy", "cny", "rmb", "inr", "rub", "chf", "cad", "aud", "nzd", "sek", "nok", "dkk", "pln", "czk",
        "huf", "brl", "mxn", "zar", "krw", "sgd", "hkd", "aed", "sar", "ils", "thb", "idr", "myr", "php", "vnd", "ngn",
        "kes", "egp", "uah", "ron", "btc", "eth", "usdt", "usdc", "rs",
    )

    /** Currency words that are not ISO codes: the Polish złoty and the Nordic crowns ("5,00 zł", "20 kr"). */
    private val CURRENCY_WORDS = listOf("zł", "zl", "kr")

    /**
     * A price: a number with a currency sign ("$4.99", "4,99 €"), with a currency code or word
     * before or after it ("EUR 249.00", "249.00 EUR", "5,00 zł", "20 kr"), or in the price form
     * "20,-" ("20.-", "20,–"). Word ends are spelled out with look-arounds instead of `\b`, which
     * regex engines read differently next to letters such as "ł".
     */
    private val PRICE: Regex = run {
        val codes = (CURRENCY_CODES + CURRENCY_WORDS).joinToString("|")
        val wordStart = """(?<![\p{L}\p{N}])"""
        val wordEnd = """(?![\p{L}])"""
        Regex(
            """\p{Sc}\s?\d[\d.,']*""" +
                """|\d[\d.,' ]*\s?\p{Sc}""" +
                """|$wordStart(?:$codes)\.?\s?\d[\d.,']*""" +
                """|\d[\d.,' ]*\s?(?:$codes)$wordEnd""" +
                """|\d[\d.' ]*[.,][-–—](?![\d\p{L}])""",
            RegexOption.IGNORE_CASE,
        )
    }

    /**
     * A number written like an amount of money without its currency: two decimals ("249.00",
     * "5,00") or grouped thousands ("5,000", "1.299,00", "10 000"). Not a date, a time, a
     * version of three parts, a phone number or a plain count ("24.12.2024", "12:30", "1.2.3",
     * "555 123 4567", "248 photos").
     */
    private val AMOUNT = Regex("""(?<![\p{L}\p{N}.,])(?:\d{1,3}(?:[.,' ]\d{3})+(?:[.,]\d{1,2})?|\d+[.,]\d{2})(?![.,' ]?\d)(?!\p{L})""")

    /**
     * [text] made comparable with the English stems: compatibility forms unified (NFKC: full-width
     * and mathematical letters become plain ones), invisible characters removed, letters that look
     * like Latin ones (Cyrillic "Ѕ", "е", Greek "Ρ") turned into them, and accents dropped. So
     * "Ρay", "Ѕеnd", "B\u200Buy" and "Ｂｕｙ" read as "Pay", "Send" and "Buy".
     */
    fun fold(text: String): String {
        val compatible = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = StringBuilder(compatible.length)
        compatible.codePoints().forEach { cp ->
            if (PreviewText.isHidden(cp)) return@forEach
            out.appendCodePoint(CONFUSABLES[cp] ?: cp)
        }
        return COMBINING.replace(Normalizer.normalize(out, Normalizer.Form.NFD), "")
    }

    /**
     * The stem from [WORDS] or [WHOLE_WORDS] that [text] contains, or null. Matching is
     * case-insensitive and per word, on [fold]ed text: text is split at anything that is not a
     * letter and at camelCase humps, so "Buy now", "btn_buy" and "buyNow" match "buy", and
     * "Payment" matches "pay". A word also matches when it starts or ends with a form of a stem
     * of [WORDS], which covers prefixes and compounds: "Repayment", "Unsubscribe", "Autopay",
     * "Resend", "paynow". Two adjacent words also match as one ("Check out" → "checkout").
     * Accepted false positives: "Signal", "Design", "Recall", "Postpone", "Payload".
     */
    fun matchedWord(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val words = words(fold(text))
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

    /** The first price in [text] ("$4.99", "€9.99/month", "249.00 EUR", "5,00 zł", "20 kr", "20,-"), or null. */
    fun priceIn(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return PRICE.find(fold(text))?.value?.trim()
    }

    /** The first number in [text] that is written like an amount of money ("249.00", "5,000"), or null. Looked for only around a yes-control. */
    fun amountIn(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return AMOUNT.find(fold(text))?.value?.trim()
    }

    /** [priceIn] over a control's text, description and merged label. */
    fun priceIn(element: ScreenElement): String? = priceIn(element.text) ?: priceIn(element.description) ?: priceIn(element.label)

    /** True when nothing on the control says what it does: no text, description or label. */
    fun isUnlabelled(element: ScreenElement): Boolean =
        element.text.isNullOrBlank() && element.description.isNullOrBlank() && element.label.isNullOrBlank()

    /** True when the control's own words include a yes-word such as OK, Yes, Continue, Next or Save ([POSITIVE_WORDS], [POSITIVE_PHRASES]). */
    fun isPositive(element: ScreenElement): Boolean = listOf(element.text, element.description, element.label).any { t ->
        if (t == null) return@any false
        val words = words(fold(t))
        words.any { it in POSITIVE_WORDS } || (0 until words.size - 1).any { "${words[it]} ${words[it + 1]}" in POSITIVE_PHRASES }
    }

    /**
     * Why acting on [element] in [window] is consequential, or null when it is not:
     * - a consequential word on the control ([matchedWord]);
     * - a price on the control ([priceIn]);
     * - a clickable control with no label, whose effect cannot be shown to the owner;
     * - a yes-control ([isPositive]: OK, Yes, Continue, Next, Save, Done, Proceed…) whose row or
     *   window title, or any text of its window that is not a control itself, mentions a
     *   consequential word: the "OK" of "Delete 248 photos?" (a "Delete" button elsewhere in the
     *   window does not make every OK a delete);
     * - a yes-control whose row, window title or window shows a price or a number written like
     *   an amount ([amountIn]) anywhere but in a field, also on a control: the "Next" under
     *   "Total: 249.00 EUR", and the "OK" beside a tappable summary row that carries the total
     *   itself;
     * - a yes-control in a window with a filled-in field (the "Next" beside an amount of 5000:
     *   it would pass on what was typed), or in a window that holds more than was read, so what
     *   it answers cannot be told.
     *
     * [window] is the window the control is in, read just before; without it only the control's
     * own row ([ScreenElement.context]) is looked at for a yes-control.
     */
    fun consequentialReason(element: ScreenElement, window: ScreenSnapshot? = null): String? {
        matchedWord(element)?.let { return "the control is labelled with \"$it\"" }
        priceIn(element)?.let { return "the control shows a price (\"${PreviewText.exact(it)}\")" }
        if (element.clickable && isUnlabelled(element)) return "the control has no label, so what it does cannot be shown"
        if (isPositive(element)) {
            val sameApp = window?.elements?.filter { it.packageName == element.packageName }.orEmpty()
            val around = buildList {
                add(element.context)
                add(window?.windowTitle)
                sameApp.filter { !it.clickable && !it.editable }.forEach {
                    add(it.text)
                    add(it.description)
                }
            }.filterNotNull()
            around.firstNotNullOfOrNull { matchedWord(it) }?.let { return "it answers a screen that mentions \"$it\"" }
            // A price or an amount counts on a control too: a tappable summary row carries its text itself, or in its merged
            // label. A field is judged below, by whether it is filled in (its hint may read "0.00").
            val shown = around + sameApp.filter { it.clickable && !it.editable }.flatMap { listOfNotNull(it.text, it.description, it.label) }
            shown.firstNotNullOfOrNull { priceIn(it) }?.let { return "it answers a screen that shows a price (\"${PreviewText.exact(it)}\")" }
            shown.firstNotNullOfOrNull { amountIn(it) }?.let { return "it answers a screen that shows an amount (\"${PreviewText.exact(it)}\")" }
            // An empty field reports its hint as its text on some Android versions: that is not filled in.
            window?.elements?.firstOrNull { it.editable && it.packageName == element.packageName && !it.text.isNullOrBlank() && it.text != it.hint }
                ?.let { return "it answers a screen with a filled-in field" }
            if (window?.truncated == true) return "it answers a screen that holds more than could be read"
        }
        return null
    }

    /**
     * Why typing into [field] is consequential by itself, or null: the field's description, label
     * or view id holds a consequential word, or the field takes a one-time or security code
     * ([CODE_FIELD_WORDS]), which many apps submit as soon as it is complete. Its hint is read only
     * for the code (a "Send a message" hint does not send), and what it holds now is not read.
     */
    fun typingReason(field: ScreenElement): String? {
        val id = field.resourceId?.substringAfter(":id/")
        (matchedWord(field.description) ?: matchedWord(field.label) ?: matchedWord(id))?.let { return "the field is labelled with \"$it\"" }
        val code = listOfNotNull(field.hint, field.description, field.label, id).flatMap { words(fold(it)) }.firstOrNull { it in CODE_FIELD_WORDS }
        return code?.let { "the field asks for a code (\"$it\"), and entering one can confirm something by itself" }
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
        // Phase 6 (K17, S26): Android's own file and photo pickers. What the owner picks there
        // decides which files an app gets, so no agent may operate them.
        "android.intent.action.OPEN_DOCUMENT",
        "android.intent.action.OPEN_DOCUMENT_TREE",
        "android.intent.action.CREATE_DOCUMENT",
        "android.provider.action.PICK_IMAGES",
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

    /** The stem [word] is a form of: from [WORDS], or, when [wholeWords], from [WHOLE_WORDS] too. */
    private fun stemOf(word: String, wholeWords: Boolean = true): String? {
        IRREGULAR[word]?.let { stem -> if (wholeWords || stem in WORDS) return stem }
        WORDS.firstOrNull { stem -> isFormOf(word, stem) }?.let { return it }
        return if (wholeWords) WHOLE_WORDS.firstOrNull { stem -> isFormOf(word, stem) } else null
    }

    /** [stemOf] the whole word, else ([WORDS] only) of a start or end of it (at least three letters, leaving at least one). */
    private fun stemIn(word: String): String? {
        stemOf(word)?.let { return it }
        for (cut in 3 until word.length) stemOf(word.substring(0, cut), wholeWords = false)?.let { return it }
        for (cut in 1..word.length - 3) stemOf(word.substring(cut), wholeWords = false)?.let { return it }
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

    private val COMBINING = Regex("\\p{M}+")

    /**
     * Letters of other scripts that look like Latin ones, mapped to them. Not Unicode's full
     * confusables table: the Cyrillic, Greek and Latin look-alikes that can spell the English
     * stems, which is what matters here.
     */
    private val CONFUSABLES: Map<Int, Int> = buildMap {
        fun map(from: String, to: String) {
            require(from.codePointCount(0, from.length) == to.length)
            var i = 0
            from.codePoints().forEach { cp -> put(cp, to[i++].code) }
        }
        // Cyrillic.
        map("АВЕЅІЈКМНОРСТХУԁаеоірсхуѕјһԛԝԜ", "ABESIJKMHOPCTXYdaeoipcxysjhqwW")
        map("ӏƖЁёЇїҮүԌԍ", "lIEeIiYyGg")
        // Greek.
        map("ΑΒΕΖΗΙΚΜΝΟΡΤΥΧαιονρτυχκϲϳ", "ABEZHIKMNOPTYXaiovptuxkcj")
        // Latin and IPA look-alikes.
        map("ıȷɑɡɩʏᴀʙᴄᴅᴇɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ", "ijagiyABCDEGHIJKLMNOPRSTUVWYZ")
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
