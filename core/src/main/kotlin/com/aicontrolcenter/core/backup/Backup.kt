package com.aicontrolcenter.core.backup

import com.aicontrolcenter.core.operator.OwnerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.Random
import kotlin.math.ln

/*
 * The backup file (Phase 6, K13, Tier B; rules 8, 21, 27). Configuration and conversation text
 * only, encrypted with a passphrase, restored only into an empty app, never relaxing protection.
 * The file format is [BackupFormat]; the implementation is `RoomOwnerBackup` (WP3, data/backup).
 */

/** How far a save or a restore has got, for the progress bar. [total] null while unknown. */
data class BackupProgress(val step: String, val done: Long, val total: Long?)

/** How many of each thing a backup holds (or a restore wrote). */
data class BackupCounts(
    val agents: Int = 0,
    val providers: Int = 0,
    val models: Int = 0,
    val modelPrices: Int = 0,
    val aiApps: Int = 0,
    val spaces: Int = 0,
    val memories: Int = 0,
    val memoryLinks: Int = 0,
    val conversations: Int = 0,
    val conversationEntries: Int = 0,
)

/** One setting a restore would change: [name] in plain words, its value now ([from]) and after ([to]). */
data class SettingChange(val name: String, val from: String, val to: String)

/** What a backup file holds, read with the passphrase before anything is restored ([OwnerBackup.inspect]). */
data class BackupSummary(
    val createdAt: Long,
    val appVersion: String,
    val schemaVersion: Int,
    val counts: BackupCounts,
    /** Every setting that would change on restore (rule 8: "the screen lists every setting that would change"). */
    val settingChanges: List<SettingChange>,
    val sizeBytes: Long,
)

/** What a save wrote. */
data class SaveReport(val counts: BackupCounts, val sizeBytes: Long)

/** What a restore wrote. The app restarts its process next (rule 30). */
data class RestoreReport(
    val counts: BackupCounts,
    /** Memories left out because their agent or space is not in the file (rule 8), counted. */
    val droppedMemories: Int,
    val settingChanges: List<SettingChange>,
)

/** Why a save, an inspection or a restore did nothing. Each carries a plain sentence for the owner. */
sealed class BackupProblem(val text: String) {
    data object WrongPassphrase : BackupProblem("The passphrase does not open this file. Check it and try again.")

    data object NotABackup : BackupProblem("This file is not an AI Control Center backup.")

    /** Written by a newer version of the app, in a format this one does not read. */
    data class NewerFormat(val version: Int) : BackupProblem("This backup was made by a newer version of the app. Update the app, then try again.")

    /** A part was changed, moved, repeated or cut off: nothing of it is used. */
    data object Damaged : BackupProblem("This file is damaged or incomplete, so nothing was restored.")

    data object TooLarge : BackupProblem("This file is larger than a backup can be.")

    /** Restore writes only into an empty app (rule 8). */
    data object NotEmpty : BackupProblem(
        "A backup can only be restored into a fresh app: this one already has your own agents, chats, objectives or memories.",
    )

    /** An objective runs, a chat answer is under way, or another restore runs. */
    data class Busy(val what: String) : BackupProblem("$what Try again when it has finished.")

    data class NotEnoughSpace(val neededBytes: Long) : BackupProblem("The phone does not have enough free space for this. Free some space and try again.")

    data class WeakPassphrase(val reason: String) : BackupProblem(reason)

    data object CannotOpenFile : BackupProblem("The file could not be opened.")

    /** A record in the file is not valid (failed the same checks as owner input); [what] says which, in plain words. */
    data class Invalid(val what: String) : BackupProblem("The backup holds something this app cannot accept ($what), so nothing was restored.")
}

/** The outcome of a backup operation. */
sealed interface BackupResult<out T> {
    data class Done<T>(val value: T) : BackupResult<T>

    data class Failed(val problem: BackupProblem) : BackupResult<Nothing>
}

/**
 * The owner's backup file (Phase 6, K13, Tier B). Owner-only: only the Backup screen holds it
 * (both architecture tests list it); no intent opens it (rule 23). Allowed in local-only mode
 * with the line "The app you choose may send it off the phone" (rule 21). Every operation is
 * logged as USER_ACTION with counts only.
 */
interface OwnerBackup {
    /**
     * Writes the backup ([BackupFormat]) to the stream [open] gives, encrypted with [passphrase]
     * ([Passphrases.normalise] first). Refused with [BackupProblem.WeakPassphrase] when
     * [Passphrases.problemForSave] (with [generatedShown], the passphrase the screen generated and
     * showed, or null when the owner typed their own) gives a sentence: looking like a generated
     * passphrase is never enough. Never stores or logs the passphrase.
     */
    suspend fun save(
        passphrase: CharSequence,
        generatedShown: String?,
        open: suspend () -> OutputStream,
        progress: (BackupProgress) -> Unit = {},
    ): BackupResult<SaveReport>

    /** Reads the whole file with [passphrase] and says what it holds and what a restore would change. Writes nothing. */
    suspend fun inspect(open: suspend () -> InputStream, passphrase: CharSequence): BackupResult<BackupSummary>

    /**
     * Restores the file into an empty app (rule 8), inside [MaintenanceGate.runExclusive]: all or
     * nothing, in one transaction whose first statement checks that the app is empty. Grants
     * nothing; provider keys are not in the file and are entered again.
     */
    suspend fun restore(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit = {}): BackupResult<RestoreReport>
}

/**
 * Backup passphrases (rule 8, rule 27). The generated one is the default: 20 characters from
 * Crockford's base32 alphabet (100 bits), shown once as five groups of four. "Use my own" must pass
 * [check]: at least 4 different real words of 4 letters or more, or an estimated strength of at
 * least [MIN_OWN_BITS] bits; never a common password or a keyboard walk (security review of wave
 * 1, F7). A save takes a passphrase only through [problemForSave]: the exact one the app generated
 * and showed, or one that passes [check]; looking like a generated one is never enough.
 */
object Passphrases {
    /** Crockford's base32: digits and capitals without I, L, O and U. */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val GENERATED_LENGTH: Int = 20
    const val GROUP_LENGTH: Int = 4
    const val MIN_OWN_CHARS: Int = 16
    const val MIN_OWN_WORDS: Int = 4

    /** The shortest word that counts towards [MIN_OWN_WORDS]. */
    const val MIN_WORD_CHARS: Int = 4

    /** The fewest different letters a word that counts must have ("aaaa" and "abab" do not count). */
    const val MIN_WORD_DISTINCT_LETTERS: Int = 3

    /** The estimated strength an own passphrase without [MIN_OWN_WORDS] real words needs ([strengthBits]). */
    const val MIN_OWN_BITS: Int = 60

    /** The longest own passphrase, once normalised. */
    const val MAX_CHARS: Int = 256

    /** A new passphrase, grouped for reading: "7KQ2-M9XD-…" (100 bits from [random]). */
    fun generate(random: Random = SecureRandom()): String =
        (0 until GENERATED_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(GROUP_LENGTH).joinToString("-") { it.joinToString("") }

    /**
     * The form the key is derived from (format v1, frozen): first Unicode NFKC normalisation (so
     * an accented letter typed precomposed or decomposed, or a full-width digit, is the same
     * passphrase on every keyboard), then upper case by Unicode's full mapping without locale
     * rules, then whitespace and dashes (`-` and U+2010 to U+2015, U+2212) removed and the letters
     * people confuse read as Crockford does (O as 0, I and L as 1). Applied to every passphrase,
     * generated or own, on save and on restore, so "7kq2 m9xd …" opens what "7KQ2-M9XD-…" saved.
     * The key is derived from the UTF-8 bytes of the result ([BackupKeys.passphraseBytes]).
     */
    fun normalise(input: CharSequence): String {
        val composed = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val out = StringBuilder(composed.length)
        for (c in composed.uppercase(Locale.ROOT)) {
            when {
                c.isWhitespace() || c in DASHES -> Unit
                c == 'O' -> out.append('0')
                c == 'I' || c == 'L' -> out.append('1')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private const val DASHES = "-\u2010\u2011\u2012\u2013\u2014\u2015\u2212"

    /**
     * True when [input] has the shape of a generated passphrase (20 characters of [ALPHABET] once
     * normalised): for formatting what the owner types, never a reason to accept it. A save uses
     * [problemForSave].
     */
    fun isGeneratedForm(input: CharSequence): Boolean = normalise(input).let { n -> n.length == GENERATED_LENGTH && n.all { it in ALPHABET } }

    /**
     * Why a save must not use [input] (a sentence), or null when it may: [input] is the passphrase
     * the app generated and showed ([generatedShown], compared after [normalise]), or the owner's
     * own that passes [check]. The only gate of a save (WP3).
     */
    fun problemForSave(input: CharSequence, generatedShown: String?): String? =
        if (generatedShown != null && normalise(input).let { it.isNotEmpty() && it == normalise(generatedShown) }) null else check(input)

    /** Why [input] cannot be the owner's own passphrase (a sentence), or null when it can. */
    fun check(input: CharSequence): String? {
        val text = input.toString().trim()
        val normalised = normalise(text)
        val words = realWords(text)
        return when {
            normalised.isEmpty() -> "Choose a passphrase."
            normalised.length > MAX_CHARS -> "A passphrase can be at most $MAX_CHARS characters long."
            normalised.length < MIN_OWN_CHARS && words.size < MIN_OWN_WORDS ->
                "Use at least $MIN_OWN_CHARS characters, or at least $MIN_OWN_WORDS different words of $MIN_WORD_CHARS letters or more."
            mostlyCommon(normalised) -> "This passphrase is too common to be safe."
            words.size >= MIN_OWN_WORDS -> null
            strengthBits(text) < MIN_OWN_BITS -> "This passphrase is too easy to guess. Add words or characters that do not follow a pattern."
            else -> null
        }
    }

    /**
     * The different real words of [text] (by their [normalise]d form): runs of 4 or more letters
     * with at least 3 different letters, that are not a common password, a keyboard walk or a run
     * of the alphabet ("abcd").
     */
    fun realWords(text: CharSequence): Set<String> =
        Normalizer.normalize(text, Normalizer.Form.NFKC).split(Regex("[^\\p{L}]+")).asSequence()
            .filter { it.length >= MIN_WORD_CHARS && it.lowercase(Locale.ROOT).toSet().size >= MIN_WORD_DISTINCT_LETTERS }
            .map { normalise(it) }
            .filter { it !in WEAK_PARTS && !isRun(it) }
            .toSet()

    /**
     * A rough, deterministic estimate of how many bits a guesser needs for [text], on its
     * [normalise]d form (case does not count there): common passwords and keyboard walks count as
     * one symbol each, a repeated character as one, a run of the alphabet or of digits as its first
     * two, and a stretch of three or more characters seen before as one; each remaining symbol is
     * worth log2 of the alphabet the text draws from (digits 10, letters 23 once I, L and O are
     * folded, other ASCII 33, anything else 64).
     */
    fun strengthBits(text: CharSequence): Int {
        // Patterns are looked for before I, L and O are folded, so "HIJKL" is still a run.
        var n = squash(text).take(MAX_CHARS)
        for (weak in WEAK_PARTS_UNFOLDED) n = n.replace(weak, WEAK_MARK)
        var symbols = 0
        var i = 0
        while (i < n.length) {
            val c = n[i]
            val repeat = i > 0 && n[i - 1] == c && c != WEAK_MARK[0]
            val step = if (i >= 2) n[i - 1] - n[i - 2] else 0
            val run = i >= 2 && (step == 1 || step == -1) && c - n[i - 1] == step && c != WEAK_MARK[0]
            if (repeat || run) {
                i++
                continue
            }
            // The longest stretch from here, of 3 or more, that appeared before (a longer one can only appear if a shorter one does).
            var seen = 0
            while (seen + 1 <= i && i + seen + 1 <= n.length && (seen + 1 < 3 || n.regionMatchesBefore(i, seen + 1))) seen++
            symbols++
            i += if (seen >= 3) seen else 1
        }
        // The alphabet is the folded one the key is derived from.
        n = n.map { c -> if (c == WEAK_MARK[0]) c else when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c } }.joinToString("")
        var pool = 0
        if (n.any { it in '0'..'9' }) pool += 10
        if (n.any { it in 'A'..'Z' }) pool += 23
        if (n.any { it.code in 0x21..0x7E && !it.isLetterOrDigit() }) pool += 33
        if (n.any { it.code > 0x7E && it != WEAK_MARK[0] }) pool += 64
        if (pool == 0) return 0
        return (symbols * ln(pool.toDouble()) / ln(2.0)).toInt()
    }

    /** True when [normalised] holds a common password or a keyboard walk and fewer than 8 other characters. */
    private fun mostlyCommon(normalised: String): Boolean {
        var rest = normalised
        for (weak in WEAK_PARTS_LONGEST_FIRST) rest = rest.replace(weak, "")
        return rest.length < normalised.length && rest.length < 8
    }

    /** True when the [length] characters from [start] appeared somewhere before [start]. */
    private fun String.regionMatchesBefore(start: Int, length: Int): Boolean = substring(0, start).contains(substring(start, start + length))

    /** A run of the alphabet or of digits, up or down ("ABCD", "4321"). */
    private fun isRun(text: String): Boolean =
        text.length >= 3 && (text.zipWithNext().all { (a, b) -> b - a == 1 } || text.zipWithNext().all { (a, b) -> a - b == 1 })

    /** A small built-in list of common passwords, normalised. A passphrase that is one of these with little added is refused. */
    private val COMMON_WORDS: List<String> = listOf(
        "password", "passw0rd", "123456", "12345678", "123456789", "1234567890", "qwerty", "qwertyuiop", "asdfghjkl",
        "letmein", "iloveyou", "admin", "welcome", "monkey", "dragon", "football", "baseball", "sunshine", "princess",
        "trustno1", "abc123", "changeme", "secret", "correcthorsebatterystaple", "opensesame", "masterkey",
    )

    private val COMMON: List<String> = COMMON_WORDS.map { normalise(it) }

    /** Keyboard walks (and their reverse), normalised: one symbol each in [strengthBits]. */
    private val WALK_WORDS: List<String> = listOf(
        "qwerty", "qwertz", "azerty", "qwertyuiop", "asdf", "asdfgh", "asdfghjkl", "zxcv", "zxcvbn", "zxcvbnm", "qwer", "uiop",
        "hjkl", "qaz", "wsx", "edc", "rfv", "1qaz", "2wsx", "1qaz2wsx", "1qaz2wsx3edc", "zaq12wsx", "qazwsx", "qazwsxedc", "qweasd",
        "qweasdzxc", "1q2w3e", "1q2w3e4r", "1q2w3e4r5t", "1q2w3e4r5t6y", "1q2w3e4r5t6y7u8i9o0p", "q1w2e3", "q1w2e3r4", "zaq1", "xsw2",
        "poiu", "lkjh", "mnbv", "asdfjkl",
    )

    private val WALKS: List<String> = WALK_WORDS.flatMap { listOf(it, it.reversed()) }.map { normalise(it) }

    private val WEAK_PARTS: Set<String> = (COMMON + WALKS).filter { it.length >= 3 }.toSet()

    private val WEAK_PARTS_LONGEST_FIRST: List<String> = WEAK_PARTS.sortedByDescending { it.length }

    /** [WEAK_PARTS] before I, L and O are folded, longest first, for [strengthBits]. */
    private val WEAK_PARTS_UNFOLDED: List<String> =
        (COMMON_WORDS + WALK_WORDS.flatMap { listOf(it, it.reversed()) }).map { squash(it) }.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }

    /** [text] as [normalise] makes it, but without folding I, L and O. */
    private fun squash(text: CharSequence): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).uppercase(Locale.ROOT).filterNot { it.isWhitespace() || it in DASHES }

    /** Stands for one common password or keyboard walk in [strengthBits]; not a character a passphrase holds after [normalise]. */
    private const val WEAK_MARK = "\u0000"
}

/**
 * Keeps maintenance (a restore) from running beside work (Phase 6, K13, S24). While [busy], new
 * objectives and chat turns are refused with "The app is restoring a backup."; a restore is
 * refused while an objective runs or a chat answer is under way (WP3, WP7).
 */
interface MaintenanceGate {
    val busy: StateFlow<Boolean>

    /**
     * Runs [block] with [busy] true, then sets it false however [block] ends. Refused, without
     * running [block], while another [runExclusive] runs.
     */
    suspend fun <T> runExclusive(block: suspend () -> T): OwnerResult<T>

    /** Texts. */
    companion object {
        const val RESTORING: String = "The app is restoring a backup."
    }
}

/** The process's one [MaintenanceGate]. */
class LocalMaintenanceGate : MaintenanceGate {
    private val state = MutableStateFlow(false)

    override val busy: StateFlow<Boolean> = state.asStateFlow()

    override suspend fun <T> runExclusive(block: suspend () -> T): OwnerResult<T> {
        if (!state.compareAndSet(expect = false, update = true)) return OwnerResult.Refused(MaintenanceGate.RESTORING)
        try {
            return OwnerResult.Done(block())
        } finally {
            state.value = false
        }
    }
}

/**
 * The backup file format, `aicc-backup` version 1 (Phase 6, WP3), FROZEN after the security review
 * of wave 1 (F5, F6, F13): the byte-by-byte description and the test vectors are in the builder's
 * guide (`phase6-contracts.md`, section 4); [BackupKeys], [BackupPreamble] and [BackupChunks]
 * implement and test everything but the streaming of chunks and the records inside them.
 *
 * Layout, all integers big-endian: [MAGIC] (8 bytes) · format version (u16) · header length (u32,
 * 1 to [MAX_HEADER_BYTES]) · header (UTF-8 JSON, [BackupHeader]) · key check (32 bytes:
 * HMAC-SHA256 under the check key of "aicc-backup v1 key check" · H) · damage digest (32 bytes:
 * SHA-256 of everything before it) · chunks. H = SHA-256 of every byte from the magic to the end of
 * the header. Each chunk: flags (u8, bit 0 = last chunk) · ciphertext length (u32) · nonce
 * ([NONCE_BYTES]) · AES-256-GCM ciphertext with its 128-bit tag; every chunk but the last holds
 * exactly [CHUNK_BYTES] plaintext bytes. AAD of chunk i: H · i (u64) · flags (u8). Keys: [KDF] over
 * the UTF-8 bytes of [Passphrases.normalise] with the header's salt and iterations, split with
 * HKDF-SHA256 into the chunk key and the check key ([BackupKeys.derive]). The plaintext of the
 * chunks, joined, is a sequence of records, each a length (u32) and a UTF-8 JSON object with a
 * "type"; the first is "manifest" and the last "end" with the record count.
 */
object BackupFormat {
    const val MAGIC: String = "AICCBKUP"
    const val NAME: String = "aicc-backup"
    const val VERSION: Int = 1
    const val FILE_EXTENSION: String = "aiccbackup"
    const val MIME_TYPE: String = "application/octet-stream"
    const val KDF: String = "PBKDF2WithHmacSHA256"
    const val DEFAULT_ITERATIONS: Int = 600_000
    const val MIN_ITERATIONS: Int = 100_000

    /** The most a file may ask for (security review F6: a crafted header cannot make the phone work for minutes). */
    const val MAX_ITERATIONS: Int = 2_000_000
    const val SALT_BYTES: Int = 16
    const val KEY_BITS: Int = 256
    const val KEY_BYTES: Int = KEY_BITS / 8

    /** The JCA transformation of the chunks. */
    const val CIPHER: String = "AES/GCM/NoPadding"

    /** The header's name for it. */
    const val CIPHER_NAME: String = "AES-256-GCM"
    const val NONCE_BYTES: Int = 12
    const val TAG_BITS: Int = 128

    /** Plaintext bytes of every chunk but the last, exactly; a header with any other `chunkBytes` is damaged. */
    const val CHUNK_BYTES: Int = 1 shl 20
    const val MAX_HEADER_BYTES: Int = 4_096
    const val MAX_RECORD_BYTES: Int = 8 shl 20
    const val MAX_FILE_BYTES: Long = 1L shl 30

    /** The most chunks a file can have at [MAX_FILE_BYTES]. */
    const val MAX_CHUNKS: Long = MAX_FILE_BYTES / CHUNK_BYTES
    const val MAX_AGENTS: Int = 500
    const val MAX_MEMORIES: Int = 200_000
    const val FLAG_LAST: Int = 1
}
