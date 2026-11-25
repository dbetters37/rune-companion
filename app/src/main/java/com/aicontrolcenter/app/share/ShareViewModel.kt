@file:Phase6Stub("WP16")

package com.aicontrolcenter.app.share

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One shared text: the text itself, or a file's (with its name). Read by the activity, within the caps of rule 34. */
@Immutable
data class SharedItem(val name: String?, val text: String)

/** What the share screen shows (WP16 fills it). */
@Immutable
data class ShareUiState(val isLoading: Boolean = true)

/**
 * The share screen's state (rule 34; WP16): the size, a space picker (spaces the owner can write; "Workspace" as a file),
 * the line "Storing is free. Only what agents recall is sent to a model. A cloud model would receive this text;
 * local-only mode and space access keep it on the phone.", and Save, which stores through the owner's memory path with
 * provenance "Shared from another app" (split into parts within a memory's size) or into the workspace. Nothing is
 * stored before Save.
 */
class ShareViewModel(
    @Suppress("unused") private val items: List<SharedItem>,
    @Suppress("unused") private val memory: MemoryService,
    @Suppress("unused") private val workspace: Workspace,
    @Suppress("unused") private val settings: SettingsView,
) : ViewModel() {
    val uiState: StateFlow<ShareUiState> = MutableStateFlow(ShareUiState()).asStateFlow()
}
