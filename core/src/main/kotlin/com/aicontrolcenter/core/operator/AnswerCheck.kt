package com.aicontrolcenter.core.operator

import java.text.Normalizer

/*
 * Agents' questions to the owner (Phase 6, C1, Tier B): the pure rules of rules 13 and 32.
 * The owner's side (`OwnerServices.answer`, WP5) and the question tool use them; screens show
 * the same limits.
 */

/** What an answer the owner is about to send looks like ([AnswerCheck.classify]). */
enum class AnswerRisk {
    /** Ordinary text. */
    NONE,

    /**
     * A 4 to 8 digit code: an answer that is nothing but 4 to 8 digits (written as figures or as
     * words, with or without spaces, dots, commas, dashes or underscores between them), or one that
     * holds a code standing on its own ("482913", "G-482913", "482 913"), whatever the question
     * says; or one that holds a shorter or looser code in answer to a question that mentions a
     * code, a text message, a verification, a PIN, a password, a notification, what a screen shows
     * or the like. Sent only when the owner taps "Send it anyway" a second time (rule 32).
     */
    CODE,

    /** Looks like an API key or another secret: never sent (rule 32). */
    KEY,
}

/**
 * Rule 32: answers that look like secrets are blocked, not just warned about. Pure; the
 * constant-time comparison with the keys stored in the vault is `OwnerKeyCheck.matchesStoredKey`
 * (owner-only, WP4), checked beside this. A guard against slips, not a proof: the approval the
 * broker asks for after any answer (rule 32, second part) stays the last word.
 */
object AnswerCheck {
    /**
     * Prefixes of well-known API keys and tokens, matched without regard to case at the start of a
     * word and followed by at least [MIN_KEY_BODY_CHARS] key characters: xAI, OpenAI and
     * compatible, Anthropic, OpenRouter, Google, Groq, GitHub, GitLab, Stripe, Slack and Hugging
     * Face. AWS access key ids (`AKIA` or `ASIA` and 16 capitals or digits) are matched by their
     * exact shape instead, so "Asia-Pacific-Region" is not a key.
     */
    val KEY_PREFIXES: List<String> = listOf(
        "sk-ant-", "sk-or-", "sk-proj-", "sk-", "xai-", "AIza", "ya29.", "gsk_", "ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_",
        "glpat-", "sk_live_", "sk_test_", "rk_live_", "rk_test_", "xoxb-", "xoxp-", "xoxa-", "xoxr-", "xapp-", "hf_",
    )

    /** An AWS access key id: `AKIA` (long-term) or `ASIA` (temporary) and 16 capitals or digits, case as written. */
    private val awsKeyId = Regex("(?<![A-Za-z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Za-z0-9])")

    /**
     * Words in a question, or in the answer itself (a pasted text message: "Your verification code is
     * 482913"), that make a short number in the answer a likely code (case ignored; a word may go on:
     * "verif…", "messages").
     */
    val CODE_WORDS: List<String> = listOf(
        "code", "sms", "text message", "message", "verification", "verify", "pin", "password", "passcode", "otp", "one-time", "one time",
        "digit", "number", "2fa", "two-factor", "two factor", "authenticat", "security", "log in", "login", "sign in", "sign-in", "bank", "confirm",
    )

    /**
     * Words that make a short number in the answer a likely code when the question uses them (case
     * ignored; a word may go on): the agent words the question, so asking for "the six characters
     * your latest notification shows" counts as asking for a code. Only the question is read for
     * these: in the owner's own sentence ("show the 2024 figures") they are ordinary words.
     */
    val QUESTION_CODE_WORDS: List<String> = listOf("notification", "show", "character", "screen", "receiv", "display", "appear")

    /** The shortest key after a known prefix that counts: shorter ones are ordinary words ("sk-ip"). */
    const val MIN_KEY_BODY_CHARS: Int = 12

    /** The shortest run of base64 or hex characters (with a letter and a digit) that counts as a key. */
    const val MIN_SECRET_RUN_CHARS: Int = 32

    /** A code has 4 to 8 digits. */
    val CODE_DIGITS: IntRange = 4..8

    private val keyBody = Regex("[A-Za-z0-9_\\-]{$MIN_KEY_BODY_CHARS,}")

    /** Runs of characters used by base64, base64url and hex. */
    private val secretRun = Regex("[A-Za-z0-9+/=_\\-]{$MIN_SECRET_RUN_CHARS,}")

    /** Digits, each next one after at most three spaces, dots, commas, dashes or underscores: "482913", "4.8.2-9 1_3". */
    private val digitRun = Regex("[0-9](?:[\\s.,_\\-]{0,3}[0-9])*")

    /**
     * Digits as a code word lets them be written: each next one after at most three characters that
     * are not letters or digits ("4/8/2", "4 · 8"), or after one "x" ("4x8x2").
     */
    private val looseDigitRun = Regex("[0-9](?:(?:[^\\p{L}0-9]{1,3}|[xX])?[0-9])*")

    /** A code of letters and digits under a code word: 5 to 8 of them, with at least two digits and one letter ("K7Q2M9"). */
    private val mixedCode = Regex("(?<![\\p{L}0-9])(?=(?:[A-Za-z]*[0-9]){2})(?=[0-9]*[A-Za-z])[A-Za-z0-9]{5,8}(?![\\p{L}0-9])")

    /**
     * A code standing on its own in the answer, whatever the question says (security review of wave
     * 2a, SEC-W2A-3: the question's wording is the agent's): 6 to 8 digits in one run ("482913"), a
     * short letter prefix, a dash and 5 to 8 digits ("G-482913", "FB-48291"), or two groups of 3 or 4
     * digits split by one space or dash ("482 913", "4829-1300"). Never touching a letter or a digit,
     * nor another group of digits one space, dot or dash away, so a phone number in groups, a longer
     * number or a year is no code.
     */
    private val standaloneCode = Regex(
        "(?<![\\p{L}\\p{N}])(?<![\\p{N}][ .\\-])" +
            "(?:[0-9]{6,8}|[A-Za-z]{1,3}-[0-9]{5,8}|[0-9]{3,4}[ \\-][0-9]{3,4})" +
            "(?![\\p{L}\\p{N}])(?![ .\\-][\\p{N}])",
    )

    /** What may surround a bare code: separators only. */
    private val separatorsOnly = Regex("[\\s.,_\\-]*")

    /** Digits written as words ("four eight two"), case ignored. */
    private val numberWords: Map<String, Char> = mapOf(
        "zero" to '0', "oh" to '0', "nought" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4',
        "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9',
    )

    private val word = Regex("[A-Za-z]+")

    /** How risky sending [answer] to the agent that asked [question] is. A key wins over a code. */
    fun classify(question: String, answer: String): AnswerRisk = when {
        looksLikeKey(answer) -> AnswerRisk.KEY
        looksLikeCode(question, answer) -> AnswerRisk.CODE
        else -> AnswerRisk.NONE
    }

    /**
     * True when [text] holds a known key shape (prefix case ignored), also when it was typed in
     * groups with spaces between them ("sk-proj-abcd efgh ijkl …"), or a long run of base64 or hex
     * with letters and digits.
     */
    fun looksLikeKey(text: String): Boolean {
        val compact = Normalizer.normalize(text, Normalizer.Form.NFKC)
        for (prefix in KEY_PREFIXES) {
            var from = compact.indexOf(prefix, ignoreCase = true)
            while (from >= 0) {
                val startsWord = from == 0 || !compact[from - 1].isLetterOrDigit()
                if (startsWord && (keyBody.matchesAt(compact, from + prefix.length) || typedInGroups(compact, from + prefix.length))) return true
                from = compact.indexOf(prefix, from + 1, ignoreCase = true)
            }
        }
        if (awsKeyId.containsMatchIn(compact) || awsKeyId.containsMatchIn(compact.filterNot(Char::isWhitespace))) return true
        return secretRun.findAll(compact).any { run -> run.value.any(Char::isDigit) && run.value.any(Char::isLetter) }
    }

    /**
     * True when what follows a key prefix at [from] is a key typed in groups: key characters in at
     * least four groups split by whitespace, every group but the first and the last of one length
     * (3 or more) and neither of those longer, [MIN_KEY_BODY_CHARS] key characters in all ("abcd
     * efgh ijkl", or "p roj- abcd efgh" after "sk-"). Words of a sentence rarely all have one length.
     */
    private fun typedInGroups(text: String, from: Int): Boolean {
        val groups = ArrayList<String>()
        var i = from
        while (true) {
            val group = keyChars.matchAt(text, i)?.value.orEmpty()
            groups += group
            i += group.length
            val gap = spaceGap.matchAt(text, i) ?: break
            if (keyChars.matchAt(text, i + gap.value.length) == null) break
            i += gap.value.length
        }
        if (groups.size < 4) return false
        val middle = groups.subList(1, groups.size - 1)
        val length = middle.first().length
        return length >= 3 && middle.all { it.length == length } && groups.first().length <= length && groups.last().length <= length &&
            groups.sumOf { it.length } >= MIN_KEY_BODY_CHARS
    }

    private val keyChars = Regex("[A-Za-z0-9_\\-]+")

    /** What may stand between the groups of a key typed in groups: one or two spaces or tabs. */
    private val spaceGap = Regex("[ \\t]{1,2}")

    /**
     * True when [answer] is a bare code (4 to 8 digits and separators only, figures or number
     * words, digits of any script) or holds a code standing on its own ([standaloneCode]), whatever
     * [question] says; or when [question] or [answer] mentions a code word ([CODE_WORDS], and
     * [QUESTION_CODE_WORDS] in the question) and [answer] holds a run of 4 to 8 digits (any one to
     * three non-letters, or an "x", between them; number words read as digits) or a code of 5 to 8
     * letters and digits.
     */
    fun looksLikeCode(question: String, answer: String): Boolean {
        val digits = spelledAsDigits(asciiDigits(answer))
        if (separatorsOnly.replace(digits, "").all { it in '0'..'9' } && digits.count { it in '0'..'9' } in CODE_DIGITS) return true
        if (standaloneCode.containsMatchIn(digits)) return true
        if (!mentionsCode(question, CODE_WORDS + QUESTION_CODE_WORDS) && !mentionsCode(answer, CODE_WORDS)) return false
        if (looseDigitRun.findAll(digits).any { run -> run.value.count { it in '0'..'9' } in CODE_DIGITS }) return true
        return mixedCode.containsMatchIn(digits)
    }

    /** True when [text] holds one of [words] (case ignored). */
    private fun mentionsCode(text: String, words: List<String>): Boolean {
        val lower = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        return words.any { word -> Regex("(?<![a-z])" + Regex.escape(word)).containsMatchIn(lower) }
    }

    /** [text] in NFKC (full-width "４" is "4"), with every other decimal digit of any script ("٤") written as an ASCII digit. */
    private fun asciiDigits(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).map { c -> if (c !in '0'..'9' && Character.isDigit(c)) '0' + Character.digit(c, 10) else c }
            .joinToString("")

    /** [text] with every number word ("four", "Eight") replaced by its digit. */
    private fun spelledAsDigits(text: String): String =
        word.replace(text) { match -> numberWords[match.value.lowercase()]?.toString() ?: match.value }
}

/** The bounds and wording rules of an agent's question (rule 13), checked when an [OwnerRequest] is built. */
object QuestionRules {
    const val MAX_QUESTION_CHARS: Int = 1_000
    const val MAX_OPTIONS: Int = 4
    const val MAX_OPTION_CHARS: Int = 120
    const val MAX_ANSWER_CHARS: Int = 2_000

    /**
     * Words a suggested answer may not use, so that no option reads like an approval, a
     * permission or the go-ahead for something consequential (rule 13; security review of wave 1,
     * F9): their inflections count too ("allowed", "approving", "granted", "denies", "paid",
     * "bought", "deleted"; "OK" and "okay"). "yes do it", "do it" and "go ahead" are matched as phrases, with any
     * punctuation between the words. An answer is only text whatever it says (rules 2 and 33);
     * this keeps the owner from believing they confirmed a payment or a deletion.
     */
    val APPROVAL_WORDS: List<String> = listOf(
        "allow", "approve", "permit", "grant", "always", "once", "deny", "confirm", "proceed", "ok", "accept", "agree", "authorise",
        "consent", "pay", "buy", "purchase", "install", "uninstall", "send", "delete", "erase",
        // Second review of F9: the go-ahead for the owner's other consequential actions, and for carrying on.
        "order", "transfer", "wire", "remove", "wipe", "trash", "bin", "overwrite", "submit", "post", "publish", "share", "email",
        "reply", "message", "text", "call", "subscribe", "book", "sign", "renew", "continue",
    )

    private val approvalForms: Map<String, Regex> = mapOf(
        "allow" to Regex("allow(s|ed|ing|ance)?"),
        "approve" to Regex("approv(e|es|ed|ing|al|als)"),
        "permit" to Regex("permit(s|ted|ting)?|permission(s)?"),
        "grant" to Regex("grant(s|ed|ing)?"),
        "always" to Regex("always"),
        "once" to Regex("once"),
        "deny" to Regex("den(y|ies|ied|ying|ial|ials)"),
        "confirm" to Regex("confirm(s|ed|ing|ation|ations)?"),
        "proceed" to Regex("proceed(s|ed|ing)?"),
        "ok" to Regex("ok|okay|okayed|okd"),
        "accept" to Regex("accept(s|ed|ing|ance)?"),
        "agree" to Regex("agree(s|d|ing|ment)?"),
        "authorise" to Regex("authori[sz](e|es|ed|ing|ation)"),
        "consent" to Regex("consent(s|ed|ing)?"),
        "pay" to Regex("pay(s|ing|ment|ments)?|paid"),
        "buy" to Regex("buy(s|ing)?|bought"),
        "purchase" to Regex("purchas(e|es|ed|ing)"),
        "install" to Regex("install(s|ed|ing|ation|ations)?"),
        "uninstall" to Regex("uninstall(s|ed|ing)?"),
        "send" to Regex("send(s|ing)?|sent"),
        "delete" to Regex("delet(e|es|ed|ing|ion)"),
        "erase" to Regex("eras(e|es|ed|ing|ure)"),
        "order" to Regex("order(s|ed|ing)?"),
        "transfer" to Regex("transfer(s|red|ring)?"),
        "wire" to Regex("wir(e|es|ed|ing)"),
        "remove" to Regex("remov(e|es|ed|ing|al)"),
        "wipe" to Regex("wip(e|es|ed|ing)"),
        "trash" to Regex("trash(es|ed|ing)?"),
        "bin" to Regex("bin(s|ned|ning)?"),
        "overwrite" to Regex("overwrit(e|es|ing|ten)|overwrote"),
        "submit" to Regex("submit(s|ted|ting)?|submission(s)?"),
        "post" to Regex("post(s|ed|ing)?"),
        "publish" to Regex("publish(es|ed|ing)?"),
        "share" to Regex("shar(e|es|ed|ing)"),
        "email" to Regex("e?mail(s|ed|ing)?"),
        "reply" to Regex("repl(y|ies|ied|ying)"),
        "message" to Regex("messag(e|es|ed|ing)"),
        "text" to Regex("text(s|ed|ing)?"),
        "call" to Regex("call(s|ed|ing)?"),
        "subscribe" to Regex("subscrib(e|es|ed|ing)|subscription(s)?"),
        "book" to Regex("book(s|ed|ing)?"),
        "sign" to Regex("sign(s|ed|ing)?"),
        "renew" to Regex("renew(s|ed|ing|al)?"),
        "continue" to Regex("continu(e|es|ed|ing)"),
    )

    private val approvalPhrases: Map<String, Regex> = mapOf(
        "yes do it" to Regex("(?<![a-z])yes[^a-z]+do[^a-z]+it(?![a-z])"),
        "do it" to Regex("(?<![a-z])do[^a-z]+it(?![a-z])"),
        "go ahead" to Regex("(?<![a-z])(go|goes|going|went|gone)[^a-z]+ahead(?![a-z])"),
        "go for it" to Regex("(?<![a-z])(go|goes|going|went)[^a-z]+for[^a-z]+it(?![a-z])"),
        "go on" to Regex("(?<![a-z])(go|goes|going|went)[^a-z]+on(?![a-z])"),
        "carry on" to Regex("(?<![a-z])carr(y|ies|ied|ying)[^a-z]+on(?![a-z])"),
        "let's go" to Regex("(?<![a-z])let[^a-z]*s[^a-z]+go(?![a-z])"),
        "check out" to Regex("(?<![a-z])check(s|ed|ing)?[^a-z]*out(?![a-z])"),
        "top up" to Regex("(?<![a-z])top(s|ped|ping)?[^a-z]*up(?![a-z])"),
    )

    /**
     * The approval word [text] uses (the phrase, for "yes do it", "do it", "go ahead", "go for it",
     * "go on", "carry on", "let's go", "check out" and "top up"), or null when it uses none. Case,
     * accents and look-alike letters of other alphabets are ignored ([skeleton]): "Cоnfirm" with a
     * Cyrillic "о" is "confirm".
     */
    fun approvalWordIn(text: String): String? {
        val lower = skeleton(text)
        approvalPhrases.entries.firstOrNull { (_, phrase) -> phrase.containsMatchIn(lower) }?.let { return it.key }
        val words = lower.split(Regex("[^a-z]+")).filter(String::isNotEmpty)
        for (word in words) {
            approvalForms.entries.firstOrNull { (_, form) -> form.matches(word) }?.let { return it.key }
        }
        return null
    }

    /**
     * [text] as the owner reads it, in lower-case ASCII where it looks like ASCII: NFKC, every
     * letter that looks like a Latin one in Cyrillic or Greek written as that Latin letter, accents
     * removed, lower case.
     */
    fun skeleton(text: String): String {
        val mapped = buildString {
            for (c in Normalizer.normalize(text, Normalizer.Form.NFKC)) append(LOOK_ALIKES[c] ?: c)
        }
        return Normalizer.normalize(mapped, Normalizer.Form.NFD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase(java.util.Locale.ROOT)
    }

    /**
     * True when one word of [text] mixes letters of different alphabets ("Cоnfirm" with a Cyrillic
     * "о"): a suggested answer that does is refused, whatever the letters are.
     */
    fun mixesAlphabets(text: String): Boolean = Normalizer.normalize(text, Normalizer.Form.NFKC).split(Regex("[^\\p{L}\\p{M}]+")).any { word ->
        word.mapNotNull { c -> if (Character.isLetter(c)) Character.UnicodeScript.of(c.code) else null }
            .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }.toSet().size > 1
    }

    /** Cyrillic and Greek letters that look like Latin ones, upper and lower case, and the Latin letter each looks like. */
    private val LOOK_ALIKES: Map<Char, Char> = buildMap {
        // Cyrillic.
        "аa вb еe ёe кk мm нh оo рp сc тt уy хx іi їi јj ѕs ԁd ԛq ԝw һh ӏl ьb".split(' ').forEach { put(it[0], it[1]); put(it[0].uppercaseChar(), it[1].uppercaseChar()) }
        // Greek.
        "αa βb εe ζz ηn ιi κk μu νv οo ρp τt υu χx ωw".split(' ').forEach { put(it[0], it[1]) }
        "ΑA ΒB ΕE ΖZ ΗH ΙI ΚK ΜM ΝN ΟO ΡP ΤT ΥY ΧX".split(' ').forEach { put(it[0], it[1]) }
        // Latin letters that are not ASCII but read as one.
        "ıi ȷj ɡg ɑa ɩi ʟl ꞵb".split(' ').forEach { put(it[0], it[1]) }
    }

    /** Why [question] with [options] cannot be put to the owner, or null when it can. */
    fun questionProblem(question: String, options: List<String>): String? {
        if (question.isBlank()) return "A question cannot be empty."
        if (question.length > MAX_QUESTION_CHARS) return "A question can be at most $MAX_QUESTION_CHARS characters long."
        if (options.size > MAX_OPTIONS) return "A question can suggest at most $MAX_OPTIONS answers."
        for (option in options) {
            if (option.isBlank()) return "A suggested answer cannot be empty."
            if (option.length > MAX_OPTION_CHARS) return "A suggested answer can be at most $MAX_OPTION_CHARS characters long."
            if (mixesAlphabets(option)) return "A suggested answer cannot mix letters of different alphabets in one word."
            approvalWordIn(option)?.let { word ->
                return "A suggested answer cannot use the word \"$word\": a question gives no permission and approves nothing."
            }
        }
        if (options.map { normalise(it) }.distinct().size != options.size) return "Two suggested answers are the same."
        return null
    }

    /**
     * True when [a] and [b] ask nearly the same: the same words after case, punctuation and
     * spacing are ignored, or at least 80% of the words of the longer one shared (rule 13: a
     * near-identical second question is refused).
     */
    fun nearlySame(a: String, b: String): Boolean {
        val wordsA = normalise(a).split(' ').filter(String::isNotEmpty)
        val wordsB = normalise(b).split(' ').filter(String::isNotEmpty)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return wordsA == wordsB
        if (wordsA == wordsB) return true
        val shared = wordsA.toSet().intersect(wordsB.toSet()).size
        return shared * 5 >= maxOf(wordsA.toSet().size, wordsB.toSet().size) * 4
    }

    private fun normalise(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
