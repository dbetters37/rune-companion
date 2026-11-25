package com.aicontrolcenter.core.health

/*
 * When the app cannot open its data (Phase 6, K14, R5, rules 9 and 30): a Recovery screen
 * instead of a stuck app. The Recovery path never touches the app's graph (the agents, the
 * orchestrator, the gateway): it is reached before the graph is built.
 */

/** Whether the database opened at start, and if not, why. */
enum class DatabaseState(
    /** A plain sentence for the Recovery screen. */
    val ownerText: String,
) {
    OK("Your data opened normally."),
    KEY_MISSING("The key that unlocks your data is missing on this phone."),
    KEY_DAMAGED("The key that unlocks your data is damaged."),
    KEYSTORE_UNAVAILABLE("The phone's secure key storage did not answer. Restarting the phone may help."),
    NOT_A_DATABASE("The file that holds your data is damaged."),
    MIGRATION_FAILED("Your data could not be updated for this version of the app."),
    OTHER("Your data could not be opened."),
    ;

    /** True when the app cannot work and the Recovery screen shows. */
    val needsRecovery: Boolean get() = this != OK
}

/** What [OwnerRecovery.startOver] did. */
sealed interface RecoveryResult {
    /**
     * The old data was put aside in [setAsideFolder] (inside the app's own no-backup folder, never
     * deleted by the app) and a fresh, empty start is ready; the app restarts its process next (rule 30).
     */
    data class Done(val setAsideFolder: String) : RecoveryResult

    /** Nothing was changed; [reason] is a sentence for the owner. */
    data class Refused(val reason: String) : RecoveryResult
}

/**
 * The owner's way out when the data cannot be opened (Phase 6, K14). Owner-only: only the
 * Recovery screen holds it (both architecture tests list it); no intent opens that screen (rule 23).
 * Implemented in `:app` by `FileOwnerRecovery` (WP2).
 */
interface OwnerRecovery {
    /** Opens the database once (off the main thread) and says whether it worked. */
    suspend fun state(): DatabaseState

    /**
     * Start over (rule 9), after the owner typed [CONFIRM_WORD] once in a field the keyboard does
     * not learn: moves the database, its sealed key and the API-key vault file into
     * `noBackupFilesDir/set-aside-<date>/`, starts the next key generation (the old keystore
     * entries stay, so the set-aside copy stays openable), releases Android's folder grants and
     * resets the first-run bookkeeping and both screen-control switches. Never deletes. Refused
     * while the database opens normally.
     */
    suspend fun startOver(): RecoveryResult

    /** The word the owner types to confirm. */
    companion object {
        const val CONFIRM_WORD: String = "START OVER"

        /** True when [typed] is [CONFIRM_WORD], ignoring case and surrounding spaces. */
        fun confirms(typed: CharSequence): Boolean = typed.toString().trim().equals(CONFIRM_WORD, ignoreCase = true)
    }
}
