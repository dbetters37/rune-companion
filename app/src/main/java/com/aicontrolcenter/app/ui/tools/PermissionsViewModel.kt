package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.approvals.scopeLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.CapabilityInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** One grant as the Permissions tab lists it. */
@Immutable
data class GrantUi(
    val id: GrantId,
    /** The permission's title, e.g. "Change a shared folder". */
    val permission: String,
    /** "Always", "For the task", "Just once" ([scopeLabel]). */
    val scope: String,
    /** The folder or app it is narrowed to, e.g. "Folder “Documents”"; null when it is not narrowed. */
    val target: String?,
    /** "Granted 14:05 · in answer to a request" or "Granted 14:05 · by you, directly". */
    val granted: String,
    /** In force now. A grant that is not (a one-time grant past its 10 minutes) is greyed out. */
    val isActive: Boolean,
    /** Why it is not in force, when it is not. */
    val inactiveReason: String?,
)

/** One agent and what it was granted. */
@Immutable
data class AgentGrantsUi(val agent: AgentFaceUi, val grants: List<GrantUi>)

@Immutable
data class PermissionsUiState(
    val isLoading: Boolean = true,
    /** Every agent, by name, for the filter. */
    val agents: List<AgentFaceUi> = emptyList(),
    /** The agent the list is narrowed to; null for all agents. */
    val selected: AgentId? = null,
    /** Agents with at least one grant (only [selected] when narrowed, even without grants). */
    val groups: List<AgentGrantsUi> = emptyList(),
    /** Permissions every agent has without asking, by title. */
    val allowedForEveryone: List<String> = emptyList(),
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
) {
    val selectedAgent: AgentFaceUi? get() = agents.firstOrNull { it.id == selected }
}

/**
 * The Allowed tab: per agent, every grant still standing (given in answer to a request or
 * directly from the Folders and Screen tabs), with its scope, what it is narrowed to and when it
 * was given; removing one grant, or all of an agent's. Removing only narrows: nothing here can
 * give a permission.
 */
class PermissionsViewModel(
    private val grants: OwnerGrants,
    directory: RegistryDirectory,
    folders: OwnerFolders,
    apps: OwnerOperatorApps,
    private val capabilityInfo: (Capability) -> CapabilityInfo?,
    allCapabilities: List<CapabilityInfo>,
    private val clock: Clock,
    initialAgent: AgentId? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(val selected: AgentId?, val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local(initialAgent))
    private var messageCounter = 0L
    private val defaults = allCapabilities.filter { it.grantedByDefault }.map { it.title }

    val uiState: StateFlow<PermissionsUiState> = combine(
        grants.observeAll(),
        directory.observe(),
        folders.observeAll(),
        apps.observeAll(),
        local,
    ) { all, snapshot, folderList, appList, local ->
        val now = clock.now()
        val agents = snapshot.agents.map { it.profile }.sortedBy { it.name.lowercase() }.map { AgentFaceUi(it.id, it.name, it.colorIndex) }
        val byAgent = all.groupBy { it.agentId }
        val shown = if (local.selected != null) agents.filter { it.id == local.selected } else agents.filter { it.id in byAgent }
        // Grants of agents that were deleted are still listed, so they can be revoked.
        val orphans = if (local.selected == null) byAgent.keys.filter { id -> agents.none { it.id == id } } else emptyList()
        PermissionsUiState(
            isLoading = false,
            agents = agents,
            selected = local.selected,
            groups = (shown + orphans.map { AgentFaceUi(it, "Removed agent", 0) }).map { agent ->
                AgentGrantsUi(agent, byAgent[agent.id].orEmpty().map { grantUi(it, snapshot, folderList, appList, now) })
            },
            allowedForEveryone = defaults,
            isBusy = local.isBusy,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PermissionsUiState(selected = initialAgent))

    /** Narrows the list to [agentId]'s grants; null shows every agent's. */
    fun select(agentId: AgentId?) = local.update { it.copy(selected = agentId) }

    fun revoke(id: GrantId) = run {
        if (grants.revoke(id)) "Removed. The agent asks you again the next time it needs this." else "That permission had already ended. Nothing changed."
    }

    fun revokeAll(agentId: AgentId) = run {
        val count = grants.revokeAll(agentId)
        if (count == 0) "Nothing to remove." else "${plural(count, "permission")} removed. The agent asks you again when it needs one."
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun grantUi(grant: PermissionGrant, snapshot: RegistrySnapshot, folders: List<SharedFolder>, apps: List<OperatorApp>, now: Long): GrantUi {
        val active = grant.isActiveAt(now)
        return GrantUi(
            id = grant.id,
            permission = capabilityInfo(grant.capability)?.title ?: grant.capability.id,
            scope = grant.scope.scopeLabel,
            target = targetLabel(grant.constraint, folders, apps),
            granted = "Granted ${formatCompactTimestamp(grant.grantedAt, now, zone)} · " +
                if (grant.requestId != null) "in answer to a request" else "by you, directly",
            isActive = active,
            inactiveReason = when {
                active -> null
                grant.scope == GrantScope.ONCE -> "Not used within 10 minutes, so it no longer counts"
                else -> "Ended ${grant.expiresAt?.let { formatCompactTimestamp(it, now, zone) } ?: ""}".trim()
            },
        )
    }

    /** Runs one change at a time and reports its outcome as a message. */
    private fun run(action: suspend () -> String) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val text = try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "The change could not be saved: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isBusy = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    companion object {
        /** "Folder “Documents”", "App “Example Chatbot”", or null for a grant that is not narrowed. */
        fun targetLabel(constraint: CapabilityConstraint, folders: List<SharedFolder>, apps: List<OperatorApp>): String? = when (constraint) {
            CapabilityConstraint.None -> null
            is CapabilityConstraint.Folder ->
                "Folder “${folders.firstOrNull { it.id == constraint.folderId }?.displayName ?: "removed folder"}”"
            is CapabilityConstraint.App ->
                "App “${apps.firstOrNull { it.packageName == constraint.packageName }?.label ?: constraint.packageName}”"
        }
    }
}
