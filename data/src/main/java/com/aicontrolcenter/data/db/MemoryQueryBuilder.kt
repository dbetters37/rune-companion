package com.aicontrolcenter.data.db

import androidx.room.RoomRawQuery
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.data.db.entity.MemoryEmbeddingEntity
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryLinkEntity
import com.aicontrolcenter.data.db.entity.MemoryRow
import com.aicontrolcenter.data.db.mapper.MemoryScopeType

/** A complete SELECT with its bind arguments in placeholder order. */
internal data class MemoryQuery(val sql: String, val args: List<SqlArg>) {

    fun toRoomRawQuery(): RoomRawQuery = RoomRawQuery(sql) { statement ->
        args.forEachIndexed { position, arg ->
            when (arg) {
                is SqlArg.Text -> statement.bindText(position + 1, arg.value)
                is SqlArg.Integer -> statement.bindLong(position + 1, arg.value)
            }
        }
    }
}

/**
 * How a text search is split between SQL and Kotlin. Every word must occur either way; see
 * [MemoryQueryBuilder.planSearch] for why some words are not left to SQL.
 */
internal data class SearchPlan(val inSql: List<String>, val inKotlin: List<String>)

/**
 * Turns a [MemoryFilter] (and a search) into SQL over `memories`, so that filtering, ordering
 * and limits happen in the database. Only the conditions the filter sets appear, and every
 * value is a bound argument.
 *
 * - Scopes are grouped by type, so that a set of private scopes is one `IN` over the owning
 *   agent's index and a set of spaces one `IN` over the space index. Task scopes are grouped by
 *   their agent, one `IN` of task ids per agent, so many tasks of one agent do not deepen the
 *   expression. (The memory engine reads task memory with `taskMemoryOf` and does not pass task
 *   scopes here at all.) An empty set of scopes matches nothing, as `MemoryFilter` defines.
 * - Kinds: a stored kind this build does not know is read as a note, so a filter that includes
 *   [MemoryKind.NOTE] also includes every unknown kind.
 * - Lists are ordered `updated_at DESC, id ASC`, the order of the listing index.
 */
internal object MemoryQueryBuilder {
    /** More words than this are checked in Kotlin; SQLite limits expression depth and variables. */
    const val MAX_SQL_WORDS = 32

    /** Longer patterns are checked in Kotlin; SQLite refuses `LIKE` patterns beyond 50,000 bytes. */
    const val MAX_LIKE_PATTERN_BYTES = 10_000

    /** The character that makes `%`, `_` and itself literal in the `LIKE` patterns built here. */
    const val LIKE_ESCAPE = '\\'

    private const val NEWEST_FIRST = "updated_at DESC, id ASC"

    private class Condition(val sql: String, val args: List<SqlArg> = emptyList())

    /** The memories in [filter], newest first, at most [limit] (none when null). */
    fun list(filter: MemoryFilter, limit: Int?): MemoryQuery =
        select("SELECT ${MemoryRow.COLUMNS} FROM ${MemoryEntity.TABLE}", conditions(filter), "ORDER BY $NEWEST_FIRST", limit)

    /** The memories in [filter] whose search text contains each of [words], newest first. */
    fun search(words: List<String>, filter: MemoryFilter, limit: Int?): MemoryQuery =
        select("SELECT ${MemoryRow.COLUMNS} FROM ${MemoryEntity.TABLE}", conditions(filter) + words.map(::containsWord), "ORDER BY $NEWEST_FIRST", limit)

    /** Id and search text of the memories that [search] would find, without a limit. */
    fun searchCandidates(words: List<String>, filter: MemoryFilter): MemoryQuery =
        select("SELECT id, search_text FROM ${MemoryEntity.TABLE}", conditions(filter) + words.map(::containsWord), "ORDER BY $NEWEST_FIRST", null)

    /** Links whose both ends are memories in [filter], oldest first. */
    fun links(filter: MemoryFilter): MemoryQuery {
        val inFilter = select("SELECT id FROM ${MemoryEntity.TABLE}", conditions(filter), order = null, limit = null)
        return MemoryQuery(
            "SELECT * FROM ${MemoryLinkEntity.TABLE} WHERE from_id IN (${inFilter.sql}) AND to_id IN (${inFilter.sql}) " +
                "ORDER BY created_at ASC, id ASC",
            inFilter.args + inFilter.args,
        )
    }

    /** The vectors of [embedderId] for the memories in [filter], newest memory first. */
    fun embeddings(embedderId: String, filter: MemoryFilter): MemoryQuery = select(
        "SELECT e.memory_id AS memory_id, e.dimensions AS dimensions, e.vector AS vector " +
            "FROM ${MemoryEmbeddingEntity.TABLE} AS e JOIN ${MemoryEntity.TABLE} AS m ON m.id = e.memory_id",
        listOf(Condition("e.embedder_id = ?", listOf(SqlArg.Text(embedderId)))) + conditions(filter, "m."),
        "ORDER BY m.updated_at DESC, m.id ASC",
        limit = null,
    )

    /** Ids of the memories in [filter] without a vector of [embedderId], newest first. */
    fun missingEmbeddings(embedderId: String, filter: MemoryFilter): MemoryQuery = select(
        "SELECT m.id FROM ${MemoryEntity.TABLE} AS m",
        conditions(filter, "m.") + Condition(
            "NOT EXISTS (SELECT 1 FROM ${MemoryEmbeddingEntity.TABLE} AS e WHERE e.memory_id = m.id AND e.embedder_id = ?)",
            listOf(SqlArg.Text(embedderId)),
        ),
        "ORDER BY m.updated_at DESC, m.id ASC",
        limit = null,
    )

    /**
     * Splits [words] into those SQL checks and those Kotlin checks on the candidates SQL found.
     * SQL gets the [MAX_SQL_WORDS] longest words (the most selective) whose pattern fits the
     * `LIKE` limit; a whole pasted paragraph as a query, or one enormous "word", would otherwise
     * make SQLite refuse the statement.
     */
    fun planSearch(words: List<String>): SearchPlan {
        val (fitting, tooLong) = words.partition { likeContaining(it).encodeToByteArray().size <= MAX_LIKE_PATTERN_BYTES }
        val bySelectivity = fitting.sortedByDescending { it.length }
        return SearchPlan(inSql = bySelectivity.take(MAX_SQL_WORDS), inKotlin = bySelectivity.drop(MAX_SQL_WORDS) + tooLong)
    }

    /** A `LIKE` pattern matching any text that contains [word] literally. */
    fun likeContaining(word: String): String {
        val escaped = buildString(word.length + 2) {
            for (char in word) {
                if (char == LIKE_ESCAPE || char == '%' || char == '_') append(LIKE_ESCAPE)
                append(char)
            }
        }
        return "%$escaped%"
    }

    private fun containsWord(word: String) =
        Condition("search_text LIKE ? ESCAPE '$LIKE_ESCAPE'", listOf(SqlArg.Text(likeContaining(word))))

    private fun select(head: String, conditions: List<Condition>, order: String?, limit: Int?): MemoryQuery {
        require(limit == null || limit >= 0) { "limit must not be negative, was $limit" }
        val sql = StringBuilder(head)
        val args = mutableListOf<SqlArg>()
        if (conditions.isNotEmpty()) sql.append(" WHERE ").append(conditions.joinToString(" AND ") { it.sql })
        conditions.forEach { args += it.args }
        order?.let { sql.append(' ').append(it) }
        limit?.let {
            sql.append(" LIMIT ?")
            args += SqlArg.Integer(it.toLong())
        }
        return MemoryQuery(sql.toString(), args)
    }

    /** The conditions of [filter] on columns of `memories`, each prefixed with [p] (an alias and a dot, or nothing). */
    private fun conditions(filter: MemoryFilter, p: String = ""): List<Condition> = listOfNotNull(
        filter.scopes?.let { scopeCondition(it, p) },
        kindCondition(filter.kinds, p),
        filter.tier?.let { Condition("${p}tier = ?", listOf(SqlArg.Text(it.name))) },
        filter.excludeExpiredAt?.let { Condition("(${p}expires_at IS NULL OR ${p}expires_at > ?)", listOf(SqlArg.Integer(it))) },
    )

    private fun scopeCondition(scopes: Set<MemoryScope>, p: String): Condition {
        if (scopes.isEmpty()) return Condition("0")
        val agents = scopes.filterIsInstance<MemoryScope.Agent>().map { it.agentId.value }.sorted()
        val spaces = scopes.filterIsInstance<MemoryScope.Shared>().map { it.spaceId.value }.sorted()
        val tasksByAgent = scopes.filterIsInstance<MemoryScope.Task>()
            .groupBy({ it.agentId.value }, { it.taskId.value })
            .toSortedMap()
            .mapValues { (_, taskIds) -> taskIds.sorted() }
        val parts = listOfNotNull(
            agents.takeIf { it.isNotEmpty() }?.let { ids ->
                Condition("(${p}scope_type = ? AND ${p}scope_agent_id IN (${placeholders(ids.size)}))", type(MemoryScopeType.AGENT) + ids.map(SqlArg::Text))
            },
            spaces.takeIf { it.isNotEmpty() }?.let { ids ->
                Condition("(${p}scope_type = ? AND ${p}scope_space_id IN (${placeholders(ids.size)}))", type(MemoryScopeType.SHARED) + ids.map(SqlArg::Text))
            },
            tasksByAgent.takeIf { it.isNotEmpty() }?.let { byAgent ->
                Condition(
                    "(${p}scope_type = ? AND (" +
                        byAgent.values.joinToString(" OR ") { taskIds -> "(${p}scope_agent_id = ? AND ${p}scope_task_id IN (${placeholders(taskIds.size)}))" } +
                        "))",
                    type(MemoryScopeType.TASK) + byAgent.flatMap { (agentId, taskIds) -> listOf(SqlArg.Text(agentId)) + taskIds.map(SqlArg::Text) },
                )
            },
        )
        return Condition(parts.joinToString(" OR ", prefix = "(", postfix = ")") { it.sql }, parts.flatMap { it.args })
    }

    private fun kindCondition(kinds: Set<MemoryKind>, p: String): Condition? {
        if (kinds.isEmpty() || kinds.size == MemoryKind.entries.size) return null
        val chosen = kinds.sortedBy { it.ordinal }
        val inChosen = "${p}kind IN (${placeholders(chosen.size)})"
        val chosenArgs = chosen.map { SqlArg.Text(it.name) }
        if (MemoryKind.NOTE !in kinds) return Condition(inChosen, chosenArgs)
        val known = MemoryKind.entries
        return Condition("($inChosen OR ${p}kind NOT IN (${placeholders(known.size)}))", chosenArgs + known.map { SqlArg.Text(it.name) })
    }

    private fun type(type: MemoryScopeType): List<SqlArg> = listOf(SqlArg.Text(type.name))

    private fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")
}
