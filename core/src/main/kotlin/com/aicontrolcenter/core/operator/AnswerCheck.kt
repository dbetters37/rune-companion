package com.aicontrolcenter.core.operator

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
     * words, with or without spaces, dots, commas, dashes or underscores between them), whatever
     * the question says, or one that holds such a code in answer to a question that mentions a
     * code, a text message, a verification, a PIN, a password or the like. Sent only when the
     * owner taps "Send it anyway" a second time (rule 32).
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

    /** Words in a question that make a short number in the answer a likely code (case ignored; a word may go on: "verif…"). */
    val CODE_WORDS: List<String> = listOf(
        "code", "sms", "text message", "verification", "verify", "pin", "password", "passcode", "otp", "one-time", "one time",
        "digit", "number", "2fa", "two-factor", "two factor", "authenticat", "security", "log in", "login", "sign in", "sign-in", "bank", "confirm",
    )

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

    /** True when [text] holds a known key shape (prefix case ignored), or a long run of base64 or hex with letters and digits. */
    fun looksLikeKey(text: String): Boolean {
        for (prefix in KEY_PREFIXES) {
            var from = text.indexOf(prefix, ignoreCase = true)
            while (from >= 0) {
                val startsWord = from == 0 || !text[from - 1].isLetterOrDigit()
                if (startsWord && keyBody.matchesAt(text, from + prefix.length)) return true
                from = text.indexOf(prefix, from + 1, ignoreCase = true)
            }
        }
        if (awsKeyId.containsMatchIn(text)) return true
        return secretRun.findAll(text).any { run -> run.value.any(Char::isDigit) && run.value.any(Char::isLetter) }
    }

    /**
     * True when [answer] is a bare code (4 to 8 digits and separators only, figures or number
     * words), whatever [question] says; or when [question] mentions a code word and [answer] holds
     * a run of 4 to 8 digits (separators between them ignored, number words read as digits).
     */
    fun looksLikeCode(question: String, answer: String): Boolean {
        val digits = spelledAsDigits(answer)
        if (separatorsOnly.replace(digits, "").all { it in '0'..'9' } && digits.count { it in '0'..'9' } in CODE_DIGITS) return true
        val asked = question.lowercase()
        val mentionsCode = CODE_WORDS.any { word -> Regex("(?<![a-z])" + Regex.escape(word)).containsMatchIn(asked) }
        if (!mentionsCode) return false
        return digitRun.findAll(digits).any { run -> run.value.count { it in '0'..'9' } in CODE_DIGITS }
    }

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
    )

    private val approvalPhrases: Map<String, Regex> = mapOf(
        "yes do it" to Regex("(?<![a-z])yes[^a-z]+do[^a-z]+it(?![a-z])"),
        "do it" to Regex("(?<![a-z])do[^a-z]+it(?![a-z])"),
        "go ahead" to Regex("(?<![a-z])(go|goes|going|went|gone)[^a-z]+ahead(?![a-z])"),
    )

    /** The approval word [text] uses (the phrase, for "yes do it", "do it" and "go ahead"), or null when it uses none. Case is ignored. */
    fun approvalWordIn(text: String): String? {
        val lower = text.lowercase()
        approvalPhrases.entries.firstOrNull { (_, phrase) -> phrase.containsMatchIn(lower) }?.let { return it.key }
        val words = lower.split(Regex("[^a-z]+")).filter(String::isNotEmpty)
        for (word in words) {
            approvalForms.entries.firstOrNull { (_, form) -> form.matches(word) }?.let { return it.key }
        }
        return null
    }

    /** Why [question] with [options] cannot be put to the owner, or null when it can. */
    fun questionProblem(question: String, options: List<String>): String? {
        if (question.isBlank()) return "A question cannot be empty."
        if (question.length > MAX_QUESTION_CHARS) return "A question can be at most $MAX_QUESTION_CHARS characters long."
        if (options.size > MAX_OPTIONS) return "A question can suggest at most $MAX_OPTIONS answers."
        for (option in options) {
            if (option.isBlank()) return "A suggested answer cannot be empty."
            if (option.length > MAX_OPTION_CHARS) return "A suggested answer can be at most $MAX_OPTION_CHARS characters long."
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
