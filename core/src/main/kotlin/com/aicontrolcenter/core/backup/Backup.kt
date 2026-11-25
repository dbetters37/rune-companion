package com.aicontrolcenter.core.backup

import com.aicontrolcenter.core.operator.OwnerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Locale
import java.util.Random

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
     * ([Passphrases.normalise] first). Never stores or logs the passphrase.
     */
    suspend fun save(passphrase: CharSequence, open: suspend () -> OutputStream, progress: (BackupProgress) -> Unit = {}): BackupResult<SaveReport>

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
 * Crockford's base32 alphabet (100 bits), shown once as five groups of four. "Use my own" needs
 * at least 16 characters or at least 4 words, and is not a common password.
 */
object Passphrases {
    /** Crockford's base32: digits and capitals without I, L, O and U. */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val GENERATED_LENGTH: Int = 20
    const val GROUP_LENGTH: Int = 4
    const val MIN_OWN_CHARS: Int = 16
    const val MIN_OWN_WORDS: Int = 4

    /** The shortest word that counts towards [MIN_OWN_WORDS]. */
    const val MIN_WORD_CHARS: Int = 3

    /** A new passphrase, grouped for reading: "7KQ2-M9XD-…" (100 bits from [random]). */
    fun generate(random: Random = SecureRandom()): String =
        (0 until GENERATED_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.chunked(GROUP_LENGTH).joinToString("-") { it.joinToString("") }

    /**
     * The form the key is derived from: spaces and dashes removed, capitals, and the letters
     * people confuse read as Crockford does (O as 0, I and L as 1). Applied to every passphrase,
     * generated or own, on save and on restore, so "7kq2 m9xd …" opens what "7KQ2-M9XD-…" saved.
     */
    fun normalise(input: CharSequence): String {
        val out = StringBuilder(input.length)
        for (c in input.toString().uppercase(Locale.ROOT)) {
            when {
                c.isWhitespace() || c == '-' || c == '‐' || c == '‑' || c == '‒' || c == '–' || c == '—' -> Unit
                c == 'O' -> out.append('0')
                c == 'I' || c == 'L' -> out.append('1')
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** True when [input] is a generated passphrase (20 characters of [ALPHABET] once normalised). */
    fun isGeneratedForm(input: CharSequence): Boolean = normalise(input).let { n -> n.length == GENERATED_LENGTH && n.all { it in ALPHABET } }

    /** Why [input] cannot be the owner's own passphrase (a sentence), or null when it can. */
    fun check(input: CharSequence): String? {
        val text = input.toString().trim()
        val words = text.split(Regex("[\\s\\-_.,;:]+")).filter { it.length >= MIN_WORD_CHARS }
        val normalised = normalise(text)
        val longEnough = normalised.length >= MIN_OWN_CHARS || words.size >= MIN_OWN_WORDS
        return when {
            text.isEmpty() -> "Choose a passphrase."
            !longEnough -> "Use at least $MIN_OWN_CHARS characters, or at least $MIN_OWN_WORDS words."
            normalised.toSet().size < 5 -> "This passphrase repeats too few characters to be safe."
            isSequence(normalised) || isSequence(text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }) -> "This passphrase is too easy to guess."
            mostlyCommon(normalised) -> "This passphrase is too common to be safe."
            else -> null
        }
    }

    /** True when [normalised] holds a common password and fewer than 8 other characters. */
    private fun mostlyCommon(normalised: String): Boolean {
        var rest = normalised
        for (common in COMMON.sortedByDescending { it.length }) rest = rest.replace(common, "")
        return rest.length < normalised.length && rest.length < 8
    }

    private fun isSequence(text: String): Boolean =
        text.length >= 4 && (text.zipWithNext().all { (a, b) -> b - a == 1 } || text.zipWithNext().all { (a, b) -> a - b == 1 })

    /** A small built-in list of common passwords, normalised. A passphrase that is one of these with little added is refused. */
    private val COMMON: List<String> = listOf(
        "password", "passw0rd", "123456", "12345678", "123456789", "1234567890", "qwerty", "qwertyuiop", "asdfghjkl",
        "letmein", "iloveyou", "admin", "welcome", "monkey", "dragon", "football", "baseball", "sunshine", "princess",
        "trustno1", "abc123", "changeme", "secret", "correcthorsebatterystaple", "opensesame", "masterkey",
    ).map { normalise(it) }
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
 * The backup file format, `aicc-backup` version 1 (Phase 6, WP3; frozen at the end of wave 1
 * after a security review). The full description, byte by byte, is in the builder's guide
 * (`phase6-contracts.md`, "K13 and the backup file"); these are its constants.
 *
 * Layout, all integers big-endian: [MAGIC] (8 bytes) · format version (u16) · header length
 * (u32, at most [MAX_HEADER_BYTES]) · header (UTF-8 JSON: format, version, schema version, app
 * version, created at, KDF name, iterations and base64 salt, cipher, chunk size) · chunks. Each
 * chunk: flags (u8, bit 0 = last chunk) · ciphertext length (u32) · nonce ([NONCE_BYTES]) ·
 * AES-256-GCM ciphertext with its 128-bit tag. AAD of chunk i: SHA-256 of every byte before the
 * first chunk (32 bytes) · i (u64) · flags (u8). Key: [KDF] over the UTF-8 bytes of
 * [Passphrases.normalise] with the header's salt and iterations, [KEY_BITS] bits. The plaintext
 * of the chunks, joined, is a sequence of records, each a length (u32) and a UTF-8 JSON object
 * with a "type"; the first is "manifest" and the last "end" with the record count.
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
    const val MAX_ITERATIONS: Int = 10_000_000
    const val SALT_BYTES: Int = 16
    const val KEY_BITS: Int = 256
    const val CIPHER: String = "AES/GCM/NoPadding"
    const val NONCE_BYTES: Int = 12
    const val TAG_BITS: Int = 128
    const val CHUNK_BYTES: Int = 1 shl 20
    const val MAX_HEADER_BYTES: Int = 4_096
    const val MAX_RECORD_BYTES: Int = 8 shl 20
    const val MAX_FILE_BYTES: Long = 1L shl 30
    const val MAX_AGENTS: Int = 500
    const val MAX_MEMORIES: Int = 200_000
    const val FLAG_LAST: Int = 1
}
