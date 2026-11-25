@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.pickup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.wiring.PickUpImports
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Folder pick-up list shows (WP10c fills it). */
@Immutable
data class PickUpRulesUiState(val isLoading: Boolean = true)

/** What the pick-up rule editor shows (WP10c fills it). */
@Immutable
data class PickUpRuleEditorUiState(val isLoading: Boolean = true)

/**
 * The Folder pick-up list (WP10c). Owner-only: the owner's rules, the files each picked up (read only), the owner's
 * folders (names, read-only state), the spaces (names), and the master switch read only.
 */
class PickUpRulesViewModel(
    @Suppress("unused") private val rules: OwnerPickUpRules,
    @Suppress("unused") private val imports: PickUpImports,
    @Suppress("unused") private val folders: OwnerFolders,
    @Suppress("unused") private val memory: MemoryService,
    @Suppress("unused") private val settings: SettingsView,
    @Suppress("unused") private val clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<PickUpRulesUiState> = MutableStateFlow(PickUpRulesUiState()).asStateFlow()
}

/**
 * A pick-up rule to make or change (WP10c): "Make it read-only" goes through the owner's folders port (`setReadOnly`),
 * which switches off any rule a writable folder would endanger.
 */
class PickUpRuleEditorViewModel(
    @Suppress("unused") private val ruleId: PickUpRuleId?,
    @Suppress("unused") private val folderId: FolderId?,
    @Suppress("unused") private val rules: OwnerPickUpRules,
    @Suppress("unused") private val folders: OwnerFolders,
    @Suppress("unused") private val memory: MemoryService,
) : ViewModel() {
    val uiState: StateFlow<PickUpRuleEditorUiState> = MutableStateFlow(PickUpRuleEditorUiState()).asStateFlow()
}
