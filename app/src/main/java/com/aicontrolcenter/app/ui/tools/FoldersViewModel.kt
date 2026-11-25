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
import com.aicontrolcenter.core.pickup.PickUpRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
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
    /**
     * The warning for a folder that comes from another app's storage provider (a cloud drive, a file manager):
     * [OwnerFolders.otherAppStorageNote], which changes with [readOnly]; null for the phone's own folders.
     */
    val otherAppNote: String? = null,
    /** A folder pick-up rule reads this folder (shown so the owner knows before making it writable). */
    val hasPickUpRule: Boolean = false,
) {
    val agentsWithAccess: Int get() = agents.count { it.level != AccessLevel.NONE }
}

@Immutable
data class FoldersUiState(
    val isLoading: Boolean = true,
    val folders: List<FolderUi> = emptyList(),
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
    /** Folder pick-up is built and not hidden: offer its list and "Pick up new files" on read-only folders. */
    val pickUpAvailable: Boolean = false,
)

/**
 * The Folders tab: folders the owner picked with Android's folder picker (access is kept by
 * Android until the folder is removed here), each with its read-only switch and which agents may
 * read it or change it. Everything goes through [OwnerFolders] and [OwnerGrants], which log it.
 *
 * A folder from another app's storage provider carries the warning of [OwnerFolders.otherAppStorageNote] with that
 * app's name, which [storageAppName] reads from Android (PackageManager) on [io], never on the main thread, once per
 * folder. Making a folder writable switches off the pick-up rules that read it (the folder port does it in the same
 * step); the message then says so.
 *
 * @param pickUpRules the owner's folder pick-up rules, read only here; null when pick-up is not built or is hidden
 */
class FoldersViewModel(
    private val folders: OwnerFolders,
    private val grants: OwnerGrants,
    directory: RegistryDirectory,
    private val familyOfTool: (ToolId) -> ToolFamily?,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val storageAppName: (String) -> String? = { null },
    private val pickUpRules: Flow<List<PickUpRule>>? = null,
    private val io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {

    private data class Local(val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** The rules as last seen, to tell which ones making a folder writable switched off. */
    private val rules = MutableStateFlow<List<PickUpRule>>(emptyList())

    /** The other apps' names by folder (tree address), read once each off the main thread; a missing name is null. */
    private val appNames = MutableStateFlow<Map<String, String?>>(emptyMap())

    init {
        pickUpRules?.let { flow -> viewModelScope.launch { flow.collect { rules.value = it } } }
        viewModelScope.launch {
            folders.observeAll().collect { list ->
                list.filter { folders.fromOtherAppStorage(it) && it.treeUri !in appNames.value }.forEach { folder ->
                    val name = try {
                        withContext(io) { storageAppName(folder.treeUri) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    appNames.update { it + (folder.treeUri to name) }
                }
            }
        }
    }

    val uiState: StateFlow<FoldersUiState> = combine(
        combine(folders.observeAll(), appNames, rules, ::Triple),
        grants.observeAll(),
        directory.observe(),
        local,
    ) { (list, names, rules), all, snapshot, local ->
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
                    otherAppNote = if (folders.fromOtherAppStorage(folder)) OwnerFolders.otherAppStorageNote(names[folder.treeUri], folder.readOnly) else null,
                    hasPickUpRule = rules.any { it.folderId == folder.id },
                )
            },
            isBusy = local.isBusy,
            message = local.message,
            pickUpAvailable = pickUpRules != null,
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
        val onBefore = rules.value.filter { it.enabled }.map { it.id }.toSet()
        val updated = folders.setReadOnly(id, readOnly) ?: return@run "That folder was removed."
        if (updated.readOnly) {
            "“${updated.displayName}” is read-only: no agent can change anything in it."
        } else {
            val switchedOff = pickUpRules?.let { flow -> flow.first().count { it.id in onBefore && !it.enabled } } ?: 0
            "“${updated.displayName}” can be changed by agents allowed to." + when (switchedOff) {
                0 -> ""
                1 -> " Its folder pick-up rule was switched off: pick-up reads only folders agents cannot change."
                else -> " $switchedOff folder pick-up rules were switched off: pick-up reads only folders agents cannot change."
            }
        }
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
