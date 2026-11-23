package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.tools.CapabilityConstraint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** A folder the owner shared, with who may use it. */
@Immutable
data class FolderUi(
    val id: FolderId,
    val name: String,
    val added: String,
    val readOnly: Boolean,
    val agents: List<AgentAccessUi>,
) {
    val agentsWithAccess: Int get() = agents.count { it.level != AccessLevel.NONE }
}

@Immutable
data class FoldersUiState(
    val isLoading: Boolean = true,
    val folders: List<FolderUi> = emptyList(),
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
)

/**
 * The Folders tab: folders the owner picked with Android's folder picker (access is kept by
 * Android until the folder is removed here), each with its read-only switch and which agents may
 * read it or change it. Everything goes through [OwnerFolders] and [OwnerGrants], which log it.
 */
class FoldersViewModel(
    private val folders: OwnerFolders,
    private val grants: OwnerGrants,
    directory: RegistryDirectory,
    private val familyOfTool: (ToolId) -> ToolFamily?,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<FoldersUiState> = combine(folders.observeAll(), grants.observeAll(), directory.observe(), local) { list, all, snapshot, local ->
        val now = clock.now()
        val agents = snapshot.agents.map { it.profile }
        FoldersUiState(
            isLoading = false,
            folders = list.map { folder ->
                FolderUi(
                    id = folder.id,
                    name = folder.displayName,
                    added = "Added ${formatCompactTimestamp(folder.addedAt, now, zone)}",
                    readOnly = folder.readOnly,
                    agents = agentAccessOf(agents, all, CapabilityConstraint.Folder(folder.id), KIND, { familiesOf(it, familyOfTool) }, now),
                )
            },
            isBusy = local.isBusy,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoldersUiState())

    /** Keeps the folder Android's picker returned ([treeUri]); null when the owner cancelled the picker. */
    fun add(treeUri: String?) {
        if (treeUri == null) return
        run {
            when (val result = folders.add(treeUri)) {
                is OwnerResult.Done -> "“${result.value.displayName}” is shared. No agent may use it until you choose who below."
                is OwnerResult.Refused -> result.reason
            }
        }
    }

    /** Revokes every permission for the folder, forgets it, and gives Android's access back. */
    fun remove(id: FolderId) = run {
        if (folders.remove(id)) "Folder removed. Every permission for it was removed first." else "That folder was already removed."
    }

    fun setReadOnly(id: FolderId, readOnly: Boolean) = run {
        val updated = folders.setReadOnly(id, readOnly) ?: return@run "That folder was removed."
        if (updated.readOnly) "“${updated.displayName}” is read-only: no agent can change anything in it." else "“${updated.displayName}” can be changed by agents allowed to."
    }

    fun setAccess(folderId: FolderId, agentId: AgentId, level: AccessLevel) = run {
        applyAccess(grants, agentId, CapabilityConstraint.Folder(folderId), KIND, level) ?: when (level) {
            AccessLevel.NONE -> "No access any more. Its permissions for this folder were removed."
            AccessLevel.READ -> "May now read this folder."
            AccessLevel.CHANGE -> "May now read and change this folder. Deleting still asks you every time."
        }
    }

    /** Android's folder picker could not be opened (no app on this phone provides it). */
    fun pickerUnavailable() = local.update {
        it.copy(message = UserMessage("Android's folder picker could not be opened on this phone, so no folder can be added.", ++messageCounter))
    }

    fun messageShown() = local.update { it.copy(message = null) }

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
        val KIND = AccessKind(
            read = OperatorCapabilities.FOLDERS_READ,
            change = OperatorCapabilities.FOLDERS_WRITE,
            family = ToolFamily.FOLDERS,
            labels = listOf("No access", "Read", "Read and change"),
        )
    }
}
