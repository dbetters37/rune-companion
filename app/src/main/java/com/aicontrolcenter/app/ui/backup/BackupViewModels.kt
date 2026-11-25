@file:Phase6Stub("WP10b")

package com.aicontrolcenter.app.ui.backup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.data.backup.DetailedOwnerBackup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Save a backup screen shows (WP10b fills it). */
@Immutable
data class BackupSaveUiState(val isLoading: Boolean = true)

/** What the Restore screen shows (WP10b fills it). */
@Immutable
data class BackupRestoreUiState(val isLoading: Boolean = true)

/**
 * Saving a backup file (Tier B, rules 8, 21, 27; WP10b): a generated passphrase by default (`SavePassphrase`, shown once
 * with `BackupTexts.WRITE_IT_DOWN`), "Use my own" ([ownPassphrase], `problemForSave`), "keep the app open", the key step
 * with Cancel, `SaveOutcome.notes`. Texts and API: scratchpad/phase6/handoff-wp3.md section 4.
 *
 * @param backup owner-only: the detailed backup port (`AppGraph.ownerBackup`, never null where this screen is shown).
 */
class BackupSaveViewModel(
    @Suppress("unused") private val backup: DetailedOwnerBackup,
) : ViewModel() {
    /** The owner's own passphrase, in a field the keyboard does not learn (`SecretTextField(state = …)`, rule 27). */
    val ownPassphrase: TextFieldState = TextFieldState()

    val uiState: StateFlow<BackupSaveUiState> = MutableStateFlow(BackupSaveUiState()).asStateFlow()
}

/**
 * Restoring a backup file into a fresh app (Tier B, rules 8 and 30; WP10b): the file's passphrase ([passphrase]),
 * `preview` (counts, every setting that changes, what is kept back, `BackupTexts.ALWAYS_OFF`, the notes, a refusal known
 * in advance), then `restoreWithNotes` and the app's restart.
 *
 * @param directory agent names, so the Director is shown by name, never by id.
 */
class BackupRestoreViewModel(
    @Suppress("unused") private val backup: DetailedOwnerBackup,
    @Suppress("unused") private val directory: RegistryDirectory,
) : ViewModel() {
    /** The file's passphrase (`SecretTextField(state = …)`, rule 27). */
    val passphrase: TextFieldState = TextFieldState()

    val uiState: StateFlow<BackupRestoreUiState> = MutableStateFlow(BackupRestoreUiState()).asStateFlow()
}
