package com.aicontrolcenter.app.ui.memory

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.theme.AppColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.registry.RegistrySnapshot

/*
 * The single place where memory values become words. Every memory screen calls these, so a
 * relation reads "Contradicts" in the detail screen, the link picker and the graph legend alike.
 */

val MemoryKind.label: String
    get() = when (this) {
        MemoryKind.FACT -> "Fact"
        MemoryKind.PREFERENCE -> "Preference"
        MemoryKind.EVENT -> "Event"
        MemoryKind.FINDING -> "Finding"
        MemoryKind.DECISION -> "Decision"
        MemoryKind.PROCEDURE -> "Procedure"
        MemoryKind.INSIGHT -> "Insight"
        MemoryKind.SUMMARY -> "Summary"
        MemoryKind.NOTE -> "Note"
    }

/** The relation read from the memory a link starts at: "this memory supports …". */
val LinkRelation.label: String
    get() = when (this) {
        LinkRelation.RELATES_TO -> "Relates to"
        LinkRelation.SUPPORTS -> "Supports"
        LinkRelation.CONTRADICTS -> "Contradicts"
        LinkRelation.DERIVED_FROM -> "Derived from"
        LinkRelation.PART_OF -> "Part of"
        LinkRelation.REFERENCES -> "References"
        LinkRelation.SUPERSEDES -> "Replaces"
        LinkRelation.SHARED_COPY_OF -> "Shared copy of"
    }

/** The same relation read from the memory a link ends at: "this memory is supported by …". */
val LinkRelation.inverseLabel: String
    get() = when (this) {
        LinkRelation.RELATES_TO -> "Relates to"
        LinkRelation.SUPPORTS -> "Supported by"
        LinkRelation.CONTRADICTS -> "Contradicted by"
        LinkRelation.DERIVED_FROM -> "Source of"
        LinkRelation.PART_OF -> "Contains"
        LinkRelation.REFERENCES -> "Referenced by"
        LinkRelation.SUPERSEDES -> "Replaced by"
        LinkRelation.SHARED_COPY_OF -> "Shared as"
    }

/** Relations the owner may create by hand. A shared copy is only ever made by sharing. */
val ManualLinkRelations: List<LinkRelation> = LinkRelation.entries.filter { it != LinkRelation.SHARED_COPY_OF }

val MemoryTier.label: String
    get() = when (this) {
        MemoryTier.LONG_TERM -> "Long-term"
        MemoryTier.SHORT_TERM -> "Short-term"
    }

/** Importance in three words, so a number never has to be interpreted. */
fun importanceLabel(importance: Double): String = when {
    importance >= 0.75 -> "High importance"
    importance >= 0.4 -> "Medium importance"
    else -> "Low importance"
}

/** Whose memory it is, which decides who may see it. */
enum class ScopeKind { PRIVATE, SHARED, TASK }

/** A memory's scope ready to render: what to call it and which accent marks it. */
@Immutable
data class ScopeUi(
    val kind: ScopeKind,
    /** "Researcher", "Team knowledge", "Researcher · task". */
    val label: String,
    /** Index into the accent palette: the agent's own accent, or one derived from the space id. */
    val colorIndex: Int,
    val agentId: AgentId? = null,
    val spaceId: SpaceId? = null,
) {
    /** The full sentence a screen reader hears and the detail screen shows. */
    val description: String
        get() = when (kind) {
            ScopeKind.PRIVATE -> "Private memory of $label"
            ScopeKind.SHARED -> "Shared space $label"
            ScopeKind.TASK -> "Task memory of ${label.removeSuffix(TASK_SUFFIX)}"
        }

    companion object {
        const val TASK_SUFFIX = " · task"
    }
}

/**
 * A stable accent for a space. Spaces have no colour of their own; deriving one from the id
 * keeps a space the same colour in every list and in the graph. String.hashCode is specified
 * by the JVM, so it does not change between runs or devices.
 */
fun spaceColorIndex(id: SpaceId): Int = Math.floorMod(id.value.hashCode(), AppColors.AGENT_ACCENT_COUNT)

/**
 * What memory screens name things by: agents, spaces and models. Built from the registry and
 * the space list; anything no longer there is named as gone rather than shown as an id.
 */
@Immutable
data class MemoryNames(
    val agents: Map<AgentId, AgentProfile> = emptyMap(),
    val spaces: Map<SpaceId, KnowledgeSpace> = emptyMap(),
    val models: Map<ModelId, String> = emptyMap(),
) {
    fun agentName(id: AgentId): String = agents[id]?.name ?: DELETED_AGENT

    fun spaceName(id: SpaceId): String = spaces[id]?.name ?: DELETED_SPACE

    fun scope(scope: MemoryScope): ScopeUi = when (scope) {
        is MemoryScope.Agent -> ScopeUi(ScopeKind.PRIVATE, agentName(scope.agentId), agents[scope.agentId]?.colorIndex ?: 0, agentId = scope.agentId)
        is MemoryScope.Shared -> ScopeUi(ScopeKind.SHARED, spaceName(scope.spaceId), spaceColorIndex(scope.spaceId), spaceId = scope.spaceId)
        is MemoryScope.Task -> ScopeUi(
            ScopeKind.TASK,
            agentName(scope.agentId) + ScopeUi.TASK_SUFFIX,
            agents[scope.agentId]?.colorIndex ?: 0,
            agentId = scope.agentId,
        )
    }

    companion object {
        const val DELETED_AGENT = "Deleted agent"
        const val DELETED_SPACE = "Deleted space"

        fun of(snapshot: RegistrySnapshot, spaces: List<KnowledgeSpace>): MemoryNames = MemoryNames(
            agents = snapshot.agents.associate { it.profile.id to it.profile },
            spaces = spaces.associateBy { it.id },
            models = snapshot.models.associate { it.id to it.displayName },
        )
    }
}

/** The first line of a memory, trimmed for a list row. The full text is only on the detail screen. */
internal fun previewOf(content: String, maxLength: Int = 160): String {
    val flat = content.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= maxLength) flat else flat.take(maxLength - 1).trimEnd() + "…"
}
