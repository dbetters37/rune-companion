@file:Phase6Stub("WP11a")

package com.aicontrolcenter.app.ui.questions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the question screen shows (WP11a fills it). */
@Immutable
data class QuestionUiState(val isLoading: Boolean = true)

/**
 * The question screen's state and the owner's answer (rule 13, rule 32; WP11a). Owner-only: it holds the owner's
 * request port, the only way to answer (`OwnerRequests.answer`, with `sendAnyway` for "Send it anyway").
 *
 * @param questions the owner's requests: `pending`, `observeRecent`, `answer`, `closeIfAbandoned`.
 * @param directory agent names, read only.
 */
class QuestionViewModel(
    @Suppress("unused") private val requestId: RequestId,
    @Suppress("unused") private val questions: OwnerRequests,
    @Suppress("unused") private val directory: RegistryDirectory,
    @Suppress("unused") private val clock: Clock,
) : ViewModel() {
    /** The owner's answer (rule 27: the field's text lives here, typed straight into it). */
    val answer: TextFieldState = TextFieldState()

    val uiState: StateFlow<QuestionUiState> = MutableStateFlow(QuestionUiState()).asStateFlow()
}
