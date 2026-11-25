@file:Phase6Stub("WP10b")

package com.aicontrolcenter.app.ui.recovery

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Recovery screen shows (WP10b fills it). */
@Immutable
data class RecoveryUiState(val isLoading: Boolean = true)

/**
 * The Recovery screen (R5, rules 9, 27, 30; WP10b): why the data cannot be opened (`DatabaseState.ownerText`), Start over
 * with the typed word ([confirmWord], `OwnerRecovery.confirms`; what is set aside and what is reset, in words), "Try
 * again", "Close and try later". Never reads the graph (rule 9).
 */
class RecoveryViewModel(
    @Suppress("unused") private val state: DatabaseState,
    @Suppress("unused") private val recovery: OwnerRecovery?,
) : ViewModel() {
    /** "START OVER", typed in a field the keyboard does not learn (`SecretTextField(state = …)`, rule 27). */
    val confirmWord: TextFieldState = TextFieldState()

    val uiState: StateFlow<RecoveryUiState> = MutableStateFlow(RecoveryUiState()).asStateFlow()
}
