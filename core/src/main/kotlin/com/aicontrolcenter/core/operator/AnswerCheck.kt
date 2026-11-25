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
     * A 4 to 8 digit code, in answer to a question that mentions a code, a text message, a
     * verification, a PIN or a password: sent only when the owner taps "Send it anyway" a second
     * time (rule 32).
     */
    CODE,

    /** Looks like an API key or another secret: never sent (rule 32). */
    KEY,
}

/**
 * Rule 32: answers that look like secrets are blocked, not just warned about. Pure; the
 * constant-time comparison with the keys stored in the vault is
 * `ProviderManager.matchesStoredKey` (WP4), checked beside this.
 */
object AnswerCheck {
    /** Prefixes of well-known API keys (xAI, OpenAI and compatible, Anthropic, OpenRouter, Google, Groq, GitHub). */
    val KEY_PREFIXES: List<String> = listOf("sk-ant-", "sk-or-", "sk-", "xai-", "AIza", "gsk_", "ghp_", "github_pat_")

    /** Words in a question that make a short number in the answer a likely code (case ignored). */
    val CODE_WORDS: List<String> = listOf("code", "sms", "text message", "verification", "verify", "pin", "password", "passcode", "otp", "one-time")

    /** The shortest key after a known prefix that counts: shorter ones are ordinary words ("sk-ip"). */
    const val MIN_KEY_BODY_CHARS: Int = 12

    /** The shortest run of base64 or hex characters (with a letter and a digit) that counts as a key. */
    const val MIN_SECRET_RUN_CHARS: Int = 32

    private val keyBody = Regex("[A-Za-z0-9_\\-]{$MIN_KEY_BODY_CHARS,}")

    /** Runs of characters used by base64, base64url and hex. */
    private val secretRun = Regex("[A-Za-z0-9+/=_\\-]{$MIN_SECRET_RUN_CHARS,}")

    /** 4 to 8 digits, optionally in groups split by one space or dash, not part of a longer number. */
    private val shortCode = Regex("(?<![0-9])[0-9](?:[ \\-]?[0-9]){3,7}(?![0-9])")

    /** How risky sending [answer] to the agent that asked [question] is. A key wins over a code. */
    fun classify(question: String, answer: String): AnswerRisk = when {
        looksLikeKey(answer) -> AnswerRisk.KEY
        looksLikeCode(question, answer) -> AnswerRisk.CODE
        else -> AnswerRisk.NONE
    }

    /** True when [text] holds a known key shape, or a long run of base64 or hex with letters and digits. */
    fun looksLikeKey(text: String): Boolean {
        for (prefix in KEY_PREFIXES) {
            var from = text.indexOf(prefix)
            while (from >= 0) {
                val startsWord = from == 0 || !text[from - 1].isLetterOrDigit()
                if (startsWord && keyBody.matchesAt(text, from + prefix.length)) return true
                from = text.indexOf(prefix, from + 1)
            }
        }
        return secretRun.findAll(text).any { run -> run.value.any(Char::isDigit) && run.value.any(Char::isLetter) }
    }

    /** True when [question] mentions a code word and [answer] holds a 4 to 8 digit number. */
    fun looksLikeCode(question: String, answer: String): Boolean {
        val asked = question.lowercase()
        val mentionsCode = CODE_WORDS.any { word -> Regex("(?<![a-z])" + Regex.escape(word)).containsMatchIn(asked) }
        if (!mentionsCode) return false
        return shortCode.findAll(answer).any { match -> match.value.count(Char::isDigit) in 4..8 }
    }
}

/** The bounds and wording rules of an agent's question (rule 13), checked when an [OwnerRequest] is built. */
object QuestionRules {
    const val MAX_QUESTION_CHARS: Int = 1_000
    const val MAX_OPTIONS: Int = 4
    const val MAX_OPTION_CHARS: Int = 120
    const val MAX_ANSWER_CHARS: Int = 2_000

    /**
     * Words a suggested answer may not use, so that no option reads like an approval or a
     * permission: their inflections count too ("allowed", "approving", "granted", "denies").
     * "yes do it" is matched as a phrase, with any punctuation between the words.
     */
    val APPROVAL_WORDS: List<String> = listOf("allow", "approve", "permit", "grant", "always", "once", "deny")

    private val approvalForms: Map<String, Regex> = mapOf(
        "allow" to Regex("allow(s|ed|ing|ance)?"),
        "approve" to Regex("approv(e|es|ed|ing|al|als)"),
        "permit" to Regex("permit(s|ted|ting)?|permission(s)?"),
        "grant" to Regex("grant(s|ed|ing)?"),
        "always" to Regex("always"),
        "once" to Regex("once"),
        "deny" to Regex("den(y|ies|ied|ying|ial|ials)"),
    )

    private val yesDoIt = Regex("(?<![a-z])yes[^a-z]+do[^a-z]+it(?![a-z])")

    /** The approval word [text] uses ("yes do it" for the phrase), or null when it uses none. Case is ignored. */
    fun approvalWordIn(text: String): String? {
        val lower = text.lowercase()
        if (yesDoIt.containsMatchIn(lower)) return "yes do it"
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
