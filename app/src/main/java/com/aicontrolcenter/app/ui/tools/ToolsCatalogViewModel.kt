package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.approvals.toRiskLabel
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ConstraintKind
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One tool in the catalogue: what it does, how risky it is, what it needs and who has it. */
@Immutable
data class CatalogToolUi(
    val id: ToolId,
    val title: String,
    /** What it does, for the owner. */
    val plainWords: String,
    val riskLevel: RiskLevel,
    val risk: StatusLabel,
    /** Calling it sends something off this phone (blocked in local-only mode). */
    val sendsDataOffPhone: Boolean,
    /** The permission it needs, by its title. */
    val permission: String,
    /** "Allowed for every agent", or how the permission is given. */
    val permissionNote: String,
    /** The agents that have it switched on, by name. */
    val agents: List<AgentFaceUi>,
)

/** One family of tools, in catalogue order. */
@Immutable
data class CatalogFamilyUi(
    val family: ToolFamily,
    val tools: List<CatalogToolUi>,
    /** Why none of these can be used right now (a switch in Settings, local-only mode), or null. */
    val blockedBecause: String?,
) {
    /** Agents with at least one tool of the family switched on. */
    val agentCount: Int get() = tools.flatMap { it.agents }.map { it.id }.distinct().size
}

@Immutable
data class ToolsCatalogUiState(
    val isLoading: Boolean = true,
    val families: List<CatalogFamilyUi> = emptyList(),
) {
    val toolCount: Int get() = families.sumOf { it.tools.size }
}

/**
 * The Tools tab: every registered tool by family, with what it does in plain words, its risk,
 * whether it sends anything off the phone, the permission it needs and which agents have it
 * switched on. Read-only: tools are switched on per agent in the agent editor, and permissions
 * are given and revoked in the Permissions tab.
 */
class ToolsCatalogViewModel(
    directory: RegistryDirectory,
    private val tools: List<ToolSpec>,
    private val capabilityInfo: (Capability) -> CapabilityInfo?,
) : ViewModel() {
    val uiState: StateFlow<ToolsCatalogUiState> = directory.observe()
        .map { snapshot -> ToolsCatalogUiState(isLoading = false, families = catalogOf(tools, snapshot, capabilityInfo)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ToolsCatalogUiState())

    companion object {
        /** The catalogue for [tools] with the agents and settings of [snapshot]. Families without a tool are left out. */
        fun catalogOf(tools: List<ToolSpec>, snapshot: RegistrySnapshot, capabilityInfo: (Capability) -> CapabilityInfo?): List<CatalogFamilyUi> {
            val agents = snapshot.agents.map { it.profile }.sortedBy { it.name.lowercase() }
            val settings = snapshot.settings
            return tools.groupBy { ToolFamily.of(it) }.toSortedMap().map { (family, specs) ->
                val operatorFamily = specs.any { isOperatorTool(it.id) && it.id.value != "permissions.request" }
                CatalogFamilyUi(
                    family = family,
                    tools = specs.sortedBy { it.title.lowercase() }.map { spec ->
                        val info = capabilityInfo(spec.capability)
                        CatalogToolUi(
                            id = spec.id,
                            title = spec.title,
                            plainWords = plainWordsFor(spec),
                            riskLevel = spec.risk,
                            risk = spec.risk.toRiskLabel(),
                            sendsDataOffPhone = spec.sendsDataOffDevice,
                            permission = info?.title ?: spec.capability.id,
                            permissionNote = permissionNote(info),
                            agents = agents.filter { spec.id in it.toolIds }.map { AgentFaceUi(it.id, it.name, it.colorIndex) },
                        )
                    },
                    blockedBecause = when {
                        operatorFamily && !settings.operatorEnabled ->
                            "Phone operator is off in Settings, so no agent can use these tools."
                        family == ToolFamily.SCREEN && !settings.accessibilityOperatorEnabled ->
                            "Screen control is off in Settings, so no agent can use these tools."
                        family == ToolFamily.WEB && settings.localOnlyMode ->
                            "Local-only mode is on, so these are blocked: they send a web address off this phone."
                        else -> null
                    },
                )
            }
        }

        /** How [info] is given, in words. */
        fun permissionNote(info: CapabilityInfo?): String = when {
            info == null -> "A permission this app does not describe"
            info.grantedByDefault -> "Allowed for every agent"
            info.constrainedBy == ConstraintKind.FOLDER -> "Needs your permission, folder by folder"
            info.constrainedBy == ConstraintKind.APP -> "Needs your permission, app by app"
            else -> "Needs your permission"
        }
    }
}
