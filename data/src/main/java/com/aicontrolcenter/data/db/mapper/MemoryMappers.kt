package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceEvent
import com.aicontrolcenter.data.db.entity.KnowledgeSpaceEntity
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryLinkEntity
import com.aicontrolcenter.data.db.entity.MemoryRow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.text.Normalizer

/** The stored names of the three kinds of [MemoryScope], in the `scope_type` column. */
internal enum class MemoryScopeType { AGENT, SHARED, TASK }

/** A scope spread over the four scope columns of `memories`; unused ids are null. */
internal data class ScopeColumns(val type: MemoryScopeType, val agentId: String?, val spaceId: String?, val taskId: String?)

internal fun MemoryScope.toColumns(): ScopeColumns = when (this) {
    is MemoryScope.Agent -> ScopeColumns(MemoryScopeType.AGENT, agentId.value, null, null)
    is MemoryScope.Shared -> ScopeColumns(MemoryScopeType.SHARED, null, spaceId.value, null)
    is MemoryScope.Task -> ScopeColumns(MemoryScopeType.TASK, agentId.value, null, taskId.value)
}

/**
 * What `search_text` holds: title, content and tags, separated by line breaks and spaces, and
 * folded by [foldForSearch]. Query words are folded the same way, so matching ignores case in
 * every alphabet (not only the ASCII letters SQLite's `LIKE` folds) and ignores accents, as
 * the memory engine's keyword matching does: "cafe" finds "Café" and "MUNCHEN" finds
 * "München". Lower-casing is not full case folding: "STRASSE" does not find "straße".
 *
 * Rows keep the text they were written with, so changing this needs a migration that rebuilds
 * the column; `StoredNamesGoldenTest` pins it.
 */
internal fun memorySearchText(title: String, content: String, tags: Set<String>): String =
    foldForSearch(title + "\n" + content + "\n" + tags.joinToString(" "))

/** Query words as [memorySearchText] spells them: folded, split at whitespace, each once. */
internal fun memorySearchWords(query: String): List<String> =
    foldForSearch(query).split(WHITESPACE).filter { it.isNotBlank() }.distinct()

/**
 * Compatibility decomposition (NFKD), then non-spacing marks removed, then lower case by full
 * Unicode rules: the folding of the memory engine's `TextAnalyzer`, so the repository's text
 * search and recall's keyword matching agree on what the same word is.
 */
internal fun foldForSearch(text: String): String {
    if (text.all { it.code < 0x80 }) return text.lowercase()
    return COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase()
}

private val WHITESPACE = Regex("\\s+")
private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** The `history_json` column: a memory's later edits, shares and promotions, oldest first. */
internal val memoryHistorySerializer: KSerializer<List<ProvenanceEvent>> = ListSerializer(ProvenanceEvent.serializer())

internal fun MemoryRecord.toEntity(): MemoryEntity {
    val scope = scope.toColumns()
    return MemoryEntity(
        id = id.value,
        scopeType = scope.type.name,
        scopeAgentId = scope.agentId,
        scopeSpaceId = scope.spaceId,
        scopeTaskId = scope.taskId,
        kind = kind.name,
        tier = tier.name,
        title = title,
        content = content,
        tagsJson = encodeColumn(ColumnSerializers.stringSet, tags),
        importance = importance,
        confidence = confidence,
        provenanceJson = encodeColumn(Provenance.serializer(), provenance),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessedAt = lastAccessedAt,
        accessCount = accessCount,
        expiresAt = expiresAt,
        revision = revision,
        historyJson = encodeColumn(memoryHistorySerializer, history),
        searchText = memorySearchText(title, content, tags),
    )
}

/**
 * The record a row holds. A kind this build does not know (written by a later version) reads
 * as [MemoryKind.NOTE], as `MemoryKind` prescribes; the query filters count such rows as notes
 * too. An unknown scope type or tier makes the row unreadable instead: guessing either could
 * show a memory to an agent it does not belong to, or make it expire.
 *
 * @throws com.aicontrolcenter.data.StoredDataException naming table, column and row.
 */
internal fun MemoryRow.toDomain(): MemoryRecord {
    val row = RowRef(MemoryEntity.TABLE, id)
    return MemoryRecord(
        id = MemoryId(id),
        scope = scope(row),
        kind = MemoryKind.entries.firstOrNull { it.name == kind } ?: MemoryKind.NOTE,
        tier = row.enum<MemoryTier>("tier", tier),
        title = title,
        content = content,
        tags = row.json("tags_json", ColumnSerializers.stringSet, tagsJson),
        importance = importance,
        confidence = confidence,
        provenance = row.json("provenance_json", Provenance.serializer(), provenanceJson),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessedAt = lastAccessedAt,
        accessCount = accessCount,
        expiresAt = expiresAt,
        revision = revision,
        history = row.json("history_json", memoryHistorySerializer, historyJson),
    )
}

private fun MemoryRow.scope(row: RowRef): MemoryScope = when (row.enum<MemoryScopeType>("scope_type", scopeType)) {
    MemoryScopeType.AGENT -> MemoryScope.Agent(AgentId(row.present("scope_agent_id", scopeAgentId, "private memory has an agent")))
    MemoryScopeType.SHARED -> MemoryScope.Shared(SpaceId(row.present("scope_space_id", scopeSpaceId, "shared memory has a space")))
    MemoryScopeType.TASK -> MemoryScope.Task(
        taskId = TaskId(row.present("scope_task_id", scopeTaskId, "task memory has a task")),
        agentId = AgentId(row.present("scope_agent_id", scopeAgentId, "task memory has an agent")),
    )
}

internal fun MemoryLink.toEntity(): MemoryLinkEntity = MemoryLinkEntity(
    id = id.value,
    fromId = from.value,
    toId = to.value,
    relation = relation.name,
    weight = weight,
    provenanceJson = encodeColumn(Provenance.serializer(), provenance),
    createdAt = createdAt,
)

/** An unknown relation makes the link unreadable: it is left out rather than shown as something else. */
internal fun MemoryLinkEntity.toDomain(): MemoryLink {
    val row = RowRef(MemoryLinkEntity.TABLE, id)
    return MemoryLink(
        id = MemoryLinkId(id),
        from = MemoryId(fromId),
        to = MemoryId(toId),
        relation = row.enum<LinkRelation>("relation", relation),
        weight = weight,
        provenance = row.json("provenance_json", Provenance.serializer(), provenanceJson),
        createdAt = createdAt,
    )
}

internal fun KnowledgeSpace.toEntity(): KnowledgeSpaceEntity =
    KnowledgeSpaceEntity(id = id.value, name = name, description = description, createdAt = createdAt, updatedAt = updatedAt)

internal fun KnowledgeSpaceEntity.toDomain(): KnowledgeSpace =
    KnowledgeSpace(id = SpaceId(id), name = name, description = description, createdAt = createdAt, updatedAt = updatedAt)
