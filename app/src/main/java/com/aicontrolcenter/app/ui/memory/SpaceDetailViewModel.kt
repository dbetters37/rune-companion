package com.aicontrolcenter.app.ui.memory

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.agents.AgentOperationException
import com.aicontrolcenter.core.agents.AgentValidationException
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.agents.label
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/** One agent's access to the space. Reading and writing are separate grants. */
@Immutable
data class AgentAccessUi(
    val agentId: AgentId,
    val name: String,
    val colorIndex: Int,
    /** "Specialist", "Director". */
    val role: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val enabled: Boolean,
)

@Immutable
data class SpaceDetailUi(
    val space: KnowledgeSpace,
    val colorIndex: Int,
    val memoryCount: Int,
    val access: List<AgentAccessUi>,
    val updated: String,
) {
    val readerCount: Int get() = access.count { it.canRead }
    val writerCount: Int get() = access.count { it.canWrite }
}

@Immutable
data class SpaceDetailUiState(
    val isLoading: Boolean = true,
    /** Null after loading: the space does not exist (deleted here or elsewhere). */
    val space: SpaceDetailUi? = null,
    val isBusy: Boolean = false,
    /** Why the last rename was refused; shown under the form. */
    val renameError: String? = null,
    /** Set once after a rename was saved, so the form can close. */
    val renamed: Boolean = false,
    /** Set once after this screen deleted the space; the route leaves. */
    val deleted: Boolean = false,
    val message: UserMessage? = null,
)

/**
 * One knowledge space: renaming it, deleting it with its memories, and deciding per agent who
 * may read and who may write it.
 *
 * Access lives in each agent's [MemoryPolicy], so a toggle here is an ordinary agent update
 * through [AgentManager]: validated, recorded in the activity log, and the same change the
 * agent editor would make. Toggles are applied one after another, each against the agent as
 * stored at that moment, so quick taps on several agents never overwrite each other.
 */
class SpaceDetailViewModel(
    private val spaceId: SpaceId,
    private val memory: MemoryService,
    private val directory: RegistryDirectory,
    private val agentManager: AgentManager,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private data class Local(
        val isBusy: Boolean = false,
        val renameError: String? = null,
        val renamed: Boolean = false,
        val deleted: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private val accessLock = Mutex()
    private var messageCounter = 0L

    val uiState: StateFlow<SpaceDetailUiState> = combine(
        memory.observeSpaces(),
        memory.observe(MemoryActor.Owner),
        directory.observe(),
        local,
    ) { spaces, memories, snapshot, local ->
        val space = spaces.firstOrNull { it.id == spaceId }
        SpaceDetailUiState(
            isLoading = false,
            space = space?.let {
                SpaceDetailUi(
                    space = it,
                    colorIndex = spaceColorIndex(it.id),
                    memoryCount = countBySpace(memories)[it.id] ?: 0,
                    access = snapshot.agents.map { entry ->
                        val profile = entry.profile
                        AgentAccessUi(
                            agentId = profile.id,
                            name = profile.name,
                            colorIndex = profile.colorIndex,
                            role = profile.kind.label,
                            canRead = spaceId in profile.memory.readableSpaces,
                            canWrite = spaceId in profile.memory.writableSpaces,
                            enabled = profile.enabled,
                        )
                    },
                    updated = formatCompactTimestamp(it.updatedAt, clock.now(), zone),
                )
            },
            isBusy = local.isBusy,
            renameError = local.renameError,
            renamed = local.renamed,
            deleted = local.deleted,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpaceDetailUiState())

    fun setCanRead(agentId: AgentId, allowed: Boolean) = changeAccess(agentId) { policy ->
        policy.copy(readableSpaces = if (allowed) policy.readableSpaces + spaceId else policy.readableSpaces - spaceId)
    }

    fun setCanWrite(agentId: AgentId, allowed: Boolean) = changeAccess(agentId) { policy ->
        policy.copy(writableSpaces = if (allowed) policy.writableSpaces + spaceId else policy.writableSpaces - spaceId)
    }

    fun rename(name: String, description: String) {
        if (local.value.isBusy) return
        if (name.isBlank()) {
            local.update { it.copy(renameError = "Give the space a name.") }
            return
        }
        local.update { it.copy(isBusy = true, renameError = null) }
        viewModelScope.launch {
            try {
                memory.updateSpace(MemoryActor.Owner, spaceId, name.trim(), description.trim())
                local.update { it.copy(isBusy = false, renamed = true, message = message("Saved.")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: MemoryValidationException) {
                local.update { it.copy(isBusy = false, renameError = refused.message ?: "That name cannot be used.") }
            } catch (refused: MemoryPermissionException) {
                local.update { it.copy(isBusy = false, renameError = refused.message ?: "Not allowed.") }
            } catch (failure: Exception) {
                local.update { it.copy(isBusy = false, message = message("Could not save: ${failure.message ?: "storage error"}")) }
            }
        }
    }

    fun renameHandled() = local.update { it.copy(renamed = false, renameError = null) }

    /** Deletes the space and every memory in it; the memory service also takes it out of every agent's policy. */
    fun delete() {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                memory.deleteSpace(MemoryActor.Owner, spaceId)
                local.update { it.copy(isBusy = false, deleted = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(isBusy = false, message = message("Could not delete the space: ${failure.message ?: "storage error"}")) }
            }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun changeAccess(agentId: AgentId, transform: (MemoryPolicy) -> MemoryPolicy) {
        viewModelScope.launch {
            val failure: String? = accessLock.withLock {
                try {
                    // Read the agent as stored now, not as last shown, so a concurrent edit is kept.
                    val profile = directory.snapshot().agentsById[agentId]?.profile ?: return@withLock "That agent no longer exists."
                    val policy = transform(profile.memory)
                    if (policy != profile.memory) agentManager.update(agentId, profile.toDraft().copy(memory = policy))
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (invalid: AgentValidationException) {
                    "The agent could not be saved: ${invalid.issues.joinToString(" ") { it.message }} Fix it in its editor first."
                } catch (refused: AgentOperationException) {
                    refused.message ?: "That is not possible right now."
                } catch (failure: Exception) {
                    "Could not change access: ${failure.message ?: "storage error"}"
                }
            }
            if (failure != null) local.update { it.copy(message = message(failure)) }
        }
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)
}
