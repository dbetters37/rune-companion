package com.aicontrolcenter.app.ui.memory

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatFullDateTime
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** A link as seen from the memory on screen. */
@Immutable
data class LinkUi(
    val id: MemoryLinkId,
    val relation: LinkRelation,
    /** True when the link starts here; the phrase is then read forwards ("Supports"), otherwise backwards ("Supported by"). */
    val outgoing: Boolean,
    val other: MemoryRefUi,
) {
    val phrase: String get() = if (outgoing) relation.label else relation.inverseLabel
}

/** A memory the owner can link this one to. */
@Immutable
data class LinkCandidateUi(val id: MemoryId, val title: String, val scope: ScopeUi)

@Immutable
data class SpaceOptionUi(val id: SpaceId, val name: String)

@Immutable
data class MemoryDetailUi(
    val record: MemoryRecord,
    val scope: ScopeUi,
    val provenance: ProvenanceUi,
    val links: List<LinkUi>,
    val created: String,
    val updated: String,
    /** Null when no agent has recalled it yet. */
    val lastUsed: String?,
    /** For short-term memory: when it will be deleted. */
    val expires: String?,
)

@Immutable
data class MemoryDetailUiState(
    val isLoading: Boolean = true,
    /** Null after loading: the memory does not exist (deleted here or elsewhere, or expired). */
    val memory: MemoryDetailUi? = null,
    val linkCandidates: List<LinkCandidateUi> = emptyList(),
    /** Spaces it could be shared into: every space except the one it is already in. */
    val shareTargets: List<SpaceOptionUi> = emptyList(),
    val isBusy: Boolean = false,
    /** Set once after this screen deleted the memory; the route leaves. */
    val deleted: Boolean = false,
    val message: UserMessage? = null,
)

/**
 * One memory with everything known about it: full content, provenance in words, links in both
 * directions, and use. Acts on it as the owner: edit (elsewhere), share into a space, link and
 * unlink, delete.
 *
 * Links are not part of the memory flow, so a change made here re-reads them through [refresh];
 * a link added elsewhere appears on the next change to any memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryDetailViewModel(
    private val memoryId: MemoryId,
    private val memory: MemoryService,
    directory: RegistryDirectory,
    private val conversations: ConversationRepository?,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private data class Local(val isBusy: Boolean = false, val deleted: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private val refresh = MutableStateFlow(0)
    private var messageCounter = 0L

    private val content = combine(memory.observe(MemoryActor.Owner), memory.observeSpaces(), directory.observe(), refresh) { memories, spaces, snapshot, _ ->
        Triple(memories, spaces, snapshot)
    }.mapLatest { (memories, spaces, snapshot) -> load(memories, spaces, snapshot) }

    val uiState: StateFlow<MemoryDetailUiState> = combine(content, local) { loaded, local ->
        loaded.copy(isBusy = local.isBusy, deleted = local.deleted, message = local.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryDetailUiState())

    private suspend fun load(memories: List<MemoryRecord>, spaces: List<KnowledgeSpace>, snapshot: RegistrySnapshot): MemoryDetailUiState {
        val byId = memories.associateBy { it.id }
        val record = byId[memoryId] ?: return MemoryDetailUiState(isLoading = false)
        val names = MemoryNames.of(snapshot, spaces)
        val links: List<MemoryLink> = try {
            memory.linksOf(MemoryActor.Owner, memoryId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList() // the memory itself is still worth showing
        }
        val conversationTitle = record.provenance.conversationId?.let { id ->
            try {
                conversations?.getConversation(id)?.title
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val linkUi = links.map { link ->
            val outgoing = link.from == memoryId
            val otherId = if (outgoing) link.to else link.from
            val other = byId[otherId]
            LinkUi(link.id, link.relation, outgoing, MemoryRefUi(otherId, other?.title, other?.let { names.scope(it.scope) }))
        }.sortedWith(compareBy<LinkUi> { it.relation.ordinal }.thenBy { it.other.title.orEmpty().lowercase() })
        val here = (record.scope as? MemoryScope.Shared)?.spaceId
        return MemoryDetailUiState(
            isLoading = false,
            memory = MemoryDetailUi(
                record = record,
                scope = names.scope(record.scope),
                provenance = describeProvenance(record, names, conversationTitle, byId, zone),
                links = linkUi,
                created = formatFullDateTime(record.createdAt, zone),
                updated = formatFullDateTime(record.updatedAt, zone),
                lastUsed = record.lastAccessedAt?.let { formatFullDateTime(it, zone) },
                expires = record.expiresAt?.let { formatFullDateTime(it, zone) },
            ),
            linkCandidates = memories.filter { it.id != memoryId }
                .sortedBy { it.title.lowercase() }
                .map { LinkCandidateUi(it.id, it.title, names.scope(it.scope)) },
            shareTargets = spaces.filter { it.id != here }.sortedBy { it.name.lowercase() }.map { SpaceOptionUi(it.id, it.name) },
        )
    }

    /** Copies the memory into [space]; the copy is linked back to this one and appears under its links. */
    fun share(space: SpaceId) = act {
        val name = uiState.value.shareTargets.firstOrNull { it.id == space }?.name
        memory.share(MemoryActor.Owner, memoryId, space)
        if (name != null) "Shared a copy into $name." else "Shared a copy into the space."
    }

    fun link(to: MemoryId, relation: LinkRelation) = act {
        memory.link(MemoryActor.Owner, memoryId, to, relation)
        "Linked."
    }

    fun unlink(id: MemoryLinkId) = act {
        memory.unlink(MemoryActor.Owner, id)
        "Link removed."
    }

    fun delete() = act {
        memory.forget(MemoryActor.Owner, memoryId)
        local.update { it.copy(deleted = true) }
        null
    }

    fun messageShown() = local.update { it.copy(message = null) }

    /**
     * Runs one action at a time and reports its outcome. The memory service's refusals are
     * written for the owner and never contain memory content, so they are shown as they are.
     */
    private fun act(action: suspend () -> String?) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val text: String? = try {
                action().also { refresh.update { it + 1 } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: MemoryValidationException) {
                refused.message ?: "That is not possible."
            } catch (refused: MemoryPermissionException) {
                refused.message ?: "Not allowed."
            } catch (failure: Exception) {
                "Something went wrong: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isBusy = false, message = text?.let(::message) ?: it.message) }
        }
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)
}
