package com.aicontrolcenter.app.ui.memory

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
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

/** One knowledge space in the list: what is in it and who may use it. */
@Immutable
data class SpaceRowUi(
    val id: SpaceId,
    val name: String,
    val description: String,
    val colorIndex: Int,
    val memoryCount: Int,
    /** Names of the agents that may read it, in registry order. */
    val readers: List<String>,
    val writers: List<String>,
)

@Immutable
data class SpacesUiState(
    val isLoading: Boolean = true,
    val spaces: List<SpaceRowUi> = emptyList(),
    val isCreating: Boolean = false,
    /** Why the last create was refused (a duplicate name, an empty one); shown under the form. */
    val createError: String? = null,
    /** Set once after a space was created; the route opens it so access can be given straight away. */
    val createdSpaceId: SpaceId? = null,
    val message: UserMessage? = null,
)

/** Counts each space's memories. Only shared memories belong to a space. */
internal fun countBySpace(memories: List<MemoryRecord>): Map<SpaceId, Int> =
    memories.mapNotNull { (it.scope as? MemoryScope.Shared)?.spaceId }.groupingBy { it }.eachCount()

/**
 * The list of shared knowledge spaces and creating new ones. Space names are unique ignoring
 * case; the memory service enforces that and its refusal is shown under the form as it is.
 */
class SpacesViewModel(
    private val memory: MemoryService,
    directory: RegistryDirectory,
) : ViewModel() {
    private data class Local(
        val isCreating: Boolean = false,
        val createError: String? = null,
        val createdSpaceId: SpaceId? = null,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<SpacesUiState> = combine(
        memory.observeSpaces(),
        memory.observe(MemoryActor.Owner),
        directory.observe(),
        local,
    ) { spaces, memories, snapshot, local ->
        val counts = countBySpace(memories)
        val agents = snapshot.agents.map { it.profile }
        SpacesUiState(
            isLoading = false,
            spaces = spaces.sortedBy { it.name.lowercase() }.map { space ->
                SpaceRowUi(
                    id = space.id,
                    name = space.name,
                    description = space.description,
                    colorIndex = spaceColorIndex(space.id),
                    memoryCount = counts[space.id] ?: 0,
                    readers = agents.filter { space.id in it.memory.readableSpaces }.map { it.name },
                    writers = agents.filter { space.id in it.memory.writableSpaces }.map { it.name },
                )
            },
            isCreating = local.isCreating,
            createError = local.createError,
            createdSpaceId = local.createdSpaceId,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpacesUiState())

    fun create(name: String, description: String) {
        if (local.value.isCreating) return
        if (name.isBlank()) {
            local.update { it.copy(createError = "Give the space a name.") }
            return
        }
        local.update { it.copy(isCreating = true, createError = null) }
        viewModelScope.launch {
            try {
                val space = memory.createSpace(MemoryActor.Owner, name.trim(), description.trim())
                local.update { it.copy(isCreating = false, createdSpaceId = space.id, message = message("Created \"${space.name}\".")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: MemoryValidationException) {
                local.update { it.copy(isCreating = false, createError = refused.message ?: "That name cannot be used.") }
            } catch (refused: MemoryPermissionException) {
                local.update { it.copy(isCreating = false, createError = refused.message ?: "Not allowed.") }
            } catch (failure: Exception) {
                local.update { it.copy(isCreating = false, message = message("Could not create the space: ${failure.message ?: "storage error"}")) }
            }
        }
    }

    /** Clears a create error once the owner edits the name or closes the form. */
    fun clearCreateError() = local.update { it.copy(createError = null) }

    fun createdHandled() = local.update { it.copy(createdSpaceId = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun message(text: String) = UserMessage(text, ++messageCounter)
}
