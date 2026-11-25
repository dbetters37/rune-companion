package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.backup.BackupCounts
import com.aicontrolcenter.core.backup.RestoreSettings
import java.util.Locale

/**
 * What the owner reads about the backup file (plain language; WP10 builds the screens in wave 3 and uses these as
 * they are). Steps are what [com.aicontrolcenter.core.backup.BackupProgress.step] says; problems are
 * [com.aicontrolcenter.core.backup.BackupProblem.text] (core).
 */
object BackupTexts {
    // --- Save screen.

    /** Above the generated passphrase, shown once. */
    const val WRITE_IT_DOWN = "Write this down; nobody can open the file without it, including you."

    /** Under "Use my own". */
    const val OWN_PASSPHRASE_RULE = "Use at least 4 different words of 4 letters or more, or at least 16 characters that do not follow a pattern."

    /** Entry ignores case, spaces and dashes (Passphrases.normalise). */
    const val PASSPHRASE_ENTRY = "Capital letters, spaces and dashes do not matter when you type it in."

    /** Before saving (rule 21: allowed in local-only mode). */
    const val CHOOSE_PLACE = "The app you choose to keep the file may send it off the phone."

    /** While saving. */
    const val KEEP_OPEN = "Keep the app open while the file is saved."

    /** What is in the file and what is not (rule 8). */
    const val WHAT_IS_IN = "The backup holds your agents, providers (without their keys), models, prices, AI apps, memory spaces, memories and chats."
    const val WHAT_IS_NOT =
        "Not in the backup: API keys, permissions you gave, questions and approvals, apps allowed for screen control, shared folders, " +
            "the activity log, usage, routines and folder pick-up rules, and what tools returned."
    const val OBJECTIVES_NOT_IN = "Finished objectives are not in the backup; copy or share a result you want to keep."

    // --- Restore screen.

    const val RESTORE_ONLY_FRESH = "A backup can only be restored into a fresh app, before you add your own agents, chats, objectives or memories."
    const val STARTER_REPLACED = "The starter team and the empty memory spaces on this phone are replaced by the agents and spaces in the backup."
    const val KEYS_AGAIN = "Provider keys are not in the backup: enter them again after restoring."
    const val RESTARTS = "When the restore is done, the app starts again."

    /** The records were restored, but the settings file could not be written (AND-W2B-4). */
    const val SETTINGS_NOT_RESTORED =
        "Your agents, memories and chats were restored, but the backup's settings could not be stored: this phone's own settings are kept. Check them in Settings."

    /** RestoreSettings.ALWAYS_OFF_AFTER_RESTORE as one line. */
    val ALWAYS_OFF: String =
        "After the restore these are off: ${RestoreSettings.ALWAYS_OFF_AFTER_RESTORE.joinToString(", ")}. Switch them on again in Settings if you want them."

    // --- Progress steps ([com.aicontrolcenter.core.backup.BackupProgress.step]).

    const val STEP_GATHERING = "Gathering what to save"

    /** The key is derived (indeterminate: the screen shows Cancel). */
    const val STEP_LOCKING = "Locking the file with your passphrase"
    const val STEP_WRITING = "Writing the file"
    const val STEP_UNLOCKING = "Checking the passphrase"
    const val STEP_CHECKING = "Checking the file"
    const val STEP_RESTORING = "Restoring"

    // --- Notes after a save or with a restore's summary.

    fun droppedMemories(n: Int): String =
        if (n == 1) "1 memory is left out because its agent or space is not in the backup, or it belongs to an objective."
        else "$n memories are left out because their agent or space is not in the backup, or they belong to an objective."

    fun droppedPrices(n: Int): String =
        if (n == 1) "1 price is left out because it is not a valid price." else "$n prices are left out because they are not valid prices."

    /** "12 agents, 3 providers, …" (only what is there), for the save report and the activity log. */
    fun counts(counts: BackupCounts): String {
        val parts = listOf(
            counts.agents to ("agent" to "agents"), counts.providers to ("provider" to "providers"), counts.models to ("model" to "models"),
            counts.modelPrices to ("price" to "prices"), counts.aiApps to ("AI app" to "AI apps"), counts.spaces to ("memory space" to "memory spaces"),
            counts.memories to ("memory" to "memories"), counts.memoryLinks to ("memory link" to "memory links"),
            counts.conversations to ("chat" to "chats"), counts.conversationEntries to ("chat message" to "chat messages"),
        ).filter { it.first > 0 }.map { (n, words) -> "%,d %s".format(Locale.ROOT, n, if (n == 1) words.first else words.second) }
        return if (parts.isEmpty()) "Nothing" else parts.joinToString(", ")
    }

    // --- Activity log (USER_ACTION, counts only, never a passphrase, a file name or a record's text).

    const val LOG_SAVED = "Backup saved"
    const val LOG_NOT_SAVED = "Backup not saved"
    const val LOG_OPENED = "Backup file opened"
    const val LOG_NOT_OPENED = "Backup file not opened"
    const val LOG_RESTORED = "Backup restored"
    const val LOG_NOT_RESTORED = "Backup not restored"
}
