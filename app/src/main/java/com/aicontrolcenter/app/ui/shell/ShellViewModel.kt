package com.aicontrolcenter.app.ui.shell

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.dashboard.AttentionItem
import com.aicontrolcenter.app.ui.dashboard.AttentionTarget
import com.aicontrolcenter.app.ui.dashboard.attentionItems
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the navigation chrome needs to know: how many things need attention in each section. */
@Immutable
data class ShellUiState(val badges: Map<Section, Int> = emptyMap()) {
    fun badge(section: Section): Int = badges[section] ?: 0

    /** Attention hidden behind the phone's "More" item. */
    val moreBadge: Int get() = badges.filterKeys { it !in Section.bottomBar }.values.sum()
}

/**
 * Feeds the attention badges of the bottom bar, the rail and the "More" sheet. The counts come
 * from the same function as the dashboard's list, so a badge always matches what is listed.
 */
class ShellViewModel(directory: RegistryDirectory) : ViewModel() {
    val uiState: StateFlow<ShellUiState> = directory.observe()
        .map { snapshot -> ShellUiState(badgesFor(snapshot.attentionItems())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellUiState())

    companion object {
        /** Counts attention items by the section in which each is fixed. */
        fun badgesFor(items: List<AttentionItem>): Map<Section, Int> =
            items.groupingBy { it.target.section }.eachCount()
    }
}

/** The section in which an attention item is dealt with. */
val AttentionTarget.section: Section
    get() = when (this) {
        is AttentionTarget.Agent, AttentionTarget.Agents, AttentionTarget.UnavailableAgents -> Section.AGENTS
        is AttentionTarget.AiApp, is AttentionTarget.Provider -> Section.MODELS
    }
