package com.aicontrolcenter.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.data.db.MemoryQueryBuilder
import com.aicontrolcenter.data.db.SqlArg
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import com.aicontrolcenter.testkit.contract.MemorySamples
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Room memory store guarantees beyond the shared contract suite. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomMemoryBehaviourTest {
    @get:Rule val db = InMemoryDatabaseRule()
    private val repo by lazy { RoomMemoryRepository(db.database) }
    private val s = MemorySamples
    private val sqlite get() = db.database.openHelper.writableDatabase

    private fun ids(list: List<MemoryRecord>) = list.map { it.id.value }

    private fun count(sql: String, vararg args: Any?): Int =
        sqlite.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun `foreign keys are enforced on the connection the app writes with`() = runTest {
        repo.upsert(s.memory("mem_a"))
        repo.upsert(s.memory("mem_b"))
        // Bypassing the repository's own checks: only the database stands in the way.
        assertFailsWith<SQLiteConstraintException> {
            sqlite.execSQL(
                "INSERT INTO memory_links (id, from_id, to_id, relation, weight, provenance_json, created_at) " +
                    "VALUES ('lnk_x', 'mem_a', 'mem_missing', 'RELATES_TO', 1.0, '{}', 1)",
            )
        }
        assertFailsWith<SQLiteConstraintException> {
            sqlite.execSQL("INSERT INTO memory_embeddings (memory_id, embedder_id, dimensions, vector) VALUES ('mem_missing', 'e', 0, x'')")
        }
        repo.upsertLink(s.link("lnk_ab", "mem_a", "mem_b"))
        repo.upsertLink(s.link("lnk_ba", "mem_b", "mem_a"))
        repo.putEmbedding(MemoryId("mem_a"), "emb", floatArrayOf(1f))

        // Removing only the memory row, as the app never does, still takes links and vectors along.
        sqlite.execSQL("DELETE FROM memories WHERE id = 'mem_a'")
        assertEquals(0, count("SELECT COUNT(*) FROM memory_links"))
        assertEquals(0, count("SELECT COUNT(*) FROM memory_embeddings"))
    }

    @Test
    fun `a link to a missing memory is refused with a message naming ids only, and nothing is stored`() = runTest {
        repo.upsert(s.memory("mem_a"))
        val failure = assertFailsWith<IllegalArgumentException> { repo.upsertLink(s.link("lnk_x", "mem_missing", "mem_a")) }
        assertEquals("Link lnk_x needs both ends to exist, but memory mem_missing or mem_a does not.", failure.message)
        assertTrue(repo.linksOf(MemoryId("mem_a")).isEmpty())
    }

    @Test
    fun `saving a memory again updates it in place and keeps its links, embeddings and accesses`() = runTest {
        repo.upsert(s.memory("mem_a"))
        repo.upsert(s.memory("mem_b"))
        repo.upsertLink(s.link("lnk", "mem_a", "mem_b"))
        repo.putEmbedding(MemoryId("mem_a"), "emb", floatArrayOf(0.5f))

        val edited = s.memory("mem_a").copy(content = "Edited", revision = 3, updatedAt = 5_000)
        repo.upsert(edited)

        assertEquals(edited, repo.get(MemoryId("mem_a")))
        assertEquals(listOf("lnk"), repo.linksOf(MemoryId("mem_a")).map { it.id.value })
        assertContentEquals(floatArrayOf(0.5f), repo.embeddings("emb").getValue(MemoryId("mem_a")))
        assertEquals(listOf("mem_a"), ids(repo.searchText("edited")))
        assertTrue(repo.searchText("content").none { it.id.value == "mem_a" }, "the search text follows the edit")
    }

    @Test
    fun `search ignores case in every alphabet, not only ASCII`() = runTest {
        repo.upsert(s.memory("mem_de", title = "Über Äpfel", content = "Grüße aus München", tags = setOf("ÖSTERREICH")))
        repo.upsert(s.memory("mem_ru", title = "Москва", content = "Столица России", tags = emptySet()))
        repo.upsert(s.memory("mem_el", title = "Ωμέγα", content = "ΣΟΦΊΑ", tags = emptySet()))

        assertEquals(listOf("mem_de"), ids(repo.searchText("über ÄPFEL")))
        assertEquals(listOf("mem_de"), ids(repo.searchText("MÜNCHEN österreich")))
        assertEquals(listOf("mem_ru"), ids(repo.searchText("москва СТОЛИЦА")))
        assertEquals(listOf("mem_el"), ids(repo.searchText("ωΜΈΓΑ σοφία")))
        assertTrue(repo.searchText("muenchen").isEmpty())
    }

    @Test
    fun `search ignores accents the way recall's keyword matching does`() = runTest {
        repo.upsert(s.memory("mem_cafe", title = "Café Central", content = "Melange und Torte in München", tags = setOf("Wien")))
        repo.upsert(s.memory("mem_plain", title = "Cafe Sperl", content = "Near the Naschmarkt", tags = emptySet()))
        // Decomposed input (e followed by a combining accent) is the same word as precomposed é.
        repo.upsert(s.memory("mem_decomposed", title = "Noe\u0308l", content = "Christmas", tags = emptySet()))

        assertEquals(listOf("mem_cafe", "mem_plain"), ids(repo.searchText("cafe")).sorted())
        assertEquals(listOf("mem_cafe", "mem_plain"), ids(repo.searchText("CAFÉ")).sorted())
        assertEquals(listOf("mem_cafe"), ids(repo.searchText("munchen central")))
        assertEquals(listOf("mem_decomposed"), ids(repo.searchText("noël")))
        assertEquals(listOf("mem_decomposed"), ids(repo.searchText("NOEL")))
        // What the memory engine sends: the query's words, folded by its own TextAnalyzer.
        assertEquals(listOf("mem_cafe"), ids(repo.searchText("cafe central munchen")))
    }

    @Test
    fun `percent, underscore and the escape character are matched literally`() = runTest {
        repo.upsert(s.memory("mem_pct", title = "Discount", content = "Saves 100% today", tags = emptySet()))
        repo.upsert(s.memory("mem_us", title = "Variable", content = "Uses snake_case names", tags = emptySet()))
        repo.upsert(s.memory("mem_bs", title = "Path", content = """C:\Users\owner""", tags = emptySet()))
        repo.upsert(s.memory("mem_plain", title = "Plain", content = "1000 snakeXcase C:Users", tags = emptySet()))

        assertEquals(listOf("mem_pct"), ids(repo.searchText("100%")))
        assertEquals(listOf("mem_us"), ids(repo.searchText("snake_case")))
        assertEquals(listOf("mem_bs"), ids(repo.searchText("""c:\users""")))
        assertEquals(listOf("mem_bs"), ids(repo.searchText("""\""")))
        assertEquals(listOf("mem_pct"), ids(repo.searchText("%")))
        assertEquals(listOf("mem_us"), ids(repo.searchText("_")))
    }

    @Test
    fun `a query with more words than SQL takes, or with an enormous word, still needs every word`() = runTest {
        val words = (1..MemoryQueryBuilder.MAX_SQL_WORDS + 8).map { "word$it" }
        repo.upsert(s.memory("mem_all", updatedAt = 300, content = words.joinToString(" "), tags = emptySet()))
        repo.upsert(s.memory("mem_most", updatedAt = 200, content = words.dropLast(1).joinToString(" "), tags = emptySet()))
        repo.upsert(s.memory("mem_all_older", updatedAt = 100, content = words.reversed().joinToString(" "), tags = emptySet()))

        val query = words.joinToString(" ")
        assertEquals(listOf("mem_all", "mem_all_older"), ids(repo.searchText(query)))
        assertEquals(listOf("mem_all"), ids(repo.searchText(query, limit = 1)))
        assertTrue(repo.searchText(query, MemoryFilter(scopes = setOf(s.commons))).isEmpty())

        val huge = "x".repeat(MemoryQueryBuilder.MAX_LIKE_PATTERN_BYTES * 2)
        repo.upsert(s.memory("mem_huge", content = "a $huge b", tags = emptySet()))
        assertEquals(listOf("mem_huge"), ids(repo.searchText("B $huge")))
        assertTrue(repo.searchText("${huge}y").isEmpty())
    }

    @Test
    fun `the query planner uses the indexes for listing, scopes, kinds and expiry`() {
        fun plan(filter: MemoryFilter): String {
            val query = MemoryQueryBuilder.list(filter, limit = 10)
            val args: Array<Any?> = query.args.map { arg ->
                when (arg) {
                    is SqlArg.Text -> arg.value
                    is SqlArg.Integer -> arg.value
                }
            }.toTypedArray()
            return sqlite.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN " + query.sql, args)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }.joinToString(" | ")
        }

        val everything = plan(MemoryFilter())
        assertTrue("index_memories_updated_at_id" in everything && "TEMP B-TREE" !in everything, everything)
        val oneAgent = plan(MemoryFilter(scopes = setOf(s.researcher, s.writer)))
        assertTrue("index_memories_scope_agent_id_scope_type_scope_task_id" in oneAgent, oneAgent)
        val oneSpace = plan(MemoryFilter(scopes = setOf(s.commons)))
        assertTrue("index_memories_scope_space_id" in oneSpace, oneSpace)
        // Either index of the owning agent: SQLite may prefer the one that also yields the listing order.
        val oneTask = plan(MemoryFilter(scopes = setOf(s.task)))
        assertTrue("USING INDEX index_memories_scope_agent_id_scope_type_" in oneTask, oneTask)
    }

    @Test
    fun `an agent's task memory is read newest first from an index that stops at the limit`() {
        // The statement of MemoryDao.taskMemoryOf.
        val sql = "SELECT ${com.aicontrolcenter.data.db.entity.MemoryRow.COLUMNS} FROM memories WHERE scope_type = 'TASK' AND scope_agent_id = ? " +
            "AND (? IS NULL OR expires_at IS NULL OR expires_at > ?) ORDER BY updated_at DESC, id ASC LIMIT ?"
        val plan = sqlite.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", arrayOf<Any?>("agt_researcher", 5L, 5L, 10L))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString(" | ")
        assertTrue("index_memories_scope_agent_id_scope_type_updated_at_id" in plan && "TEMP B-TREE" !in plan, plan)
        val agents = sqlite.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN SELECT id FROM memories WHERE scope_agent_id = ? AND scope_type IN ('AGENT', 'TASK')", arrayOf<Any?>("agt_researcher"))).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString(" | ")
        assertTrue("USING" in agents && "index_memories_scope_agent_id" in agents, agents)
    }

    @Test
    fun `a filter naming more than a thousand tasks of one agent is one condition, not one per task`() = runTest {
        val agent = AgentId("agt_researcher")
        repo.upsert(s.memory("mem_t7", MemoryScope.Task(TaskId("tsk_7"), agent), 300))
        repo.upsert(s.memory("mem_t1199", MemoryScope.Task(TaskId("tsk_1199"), agent), 200))
        repo.upsert(s.memory("mem_other", MemoryScope.Task(TaskId("tsk_7"), AgentId("agt_writer")), 100))
        val scopes = (1..1_200).map { MemoryScope.Task(TaskId("tsk_$it"), agent) }.toSet<MemoryScope>()
        assertEquals(listOf("mem_t7", "mem_t1199"), ids(repo.list(MemoryFilter(scopes = scopes))))
        assertEquals(listOf("mem_t7"), ids(repo.searchText("title mem_t7", MemoryFilter(scopes = scopes))))
    }

    @Test
    fun `deleting a scope deletes exactly that scope`() = runTest {
        val agent = AgentId("agt_researcher")
        repo.upsert(s.memory("mem_private", MemoryScope.Agent(agent)))
        repo.upsert(s.memory("mem_task_1", MemoryScope.Task(TaskId("tsk_1"), agent)))
        repo.upsert(s.memory("mem_task_2", MemoryScope.Task(TaskId("tsk_2"), agent)))
        repo.upsert(s.memory("mem_other_agent_task", MemoryScope.Task(TaskId("tsk_1"), AgentId("agt_writer"))))
        repo.upsert(s.memory("mem_space", MemoryScope.Shared(SpaceId("agt_researcher"))))

        assertEquals(1, repo.deleteScope(MemoryScope.Task(TaskId("tsk_1"), agent)))
        assertEquals(1, repo.deleteScope(MemoryScope.Agent(agent)))
        assertEquals(listOf("mem_other_agent_task", "mem_space", "mem_task_2"), ids(repo.list()).sorted())
    }

    @Test
    fun `filters by many scopes, all kinds at once, and an unknown kind as a note`() = runTest {
        repo.upsert(s.memory("mem_task", s.task, 300))
        repo.upsert(s.memory("mem_shared", s.commons, 200, MemoryKind.INSIGHT))
        repo.upsert(s.memory("mem_note", s.researcher, 100, MemoryKind.NOTE))
        repo.upsert(s.memory("mem_future", s.researcher, 50, MemoryKind.FACT))
        sqlite.execSQL("UPDATE memories SET kind = 'DREAM' WHERE id = 'mem_future'")

        assertEquals(MemoryKind.NOTE, repo.get(MemoryId("mem_future"))!!.kind)
        assertEquals(listOf("mem_note", "mem_future"), ids(repo.list(MemoryFilter(kinds = setOf(MemoryKind.NOTE)))))
        assertEquals(listOf("mem_shared"), ids(repo.list(MemoryFilter(kinds = setOf(MemoryKind.INSIGHT)))))
        assertEquals(4, repo.list(MemoryFilter(kinds = MemoryKind.entries.toSet())).size)
        assertEquals(
            listOf("mem_task", "mem_shared", "mem_note", "mem_future"),
            ids(repo.list(MemoryFilter(scopes = setOf(s.task, s.commons, s.researcher, s.writer, MemoryScope.Task(TaskId("tsk_9"), AgentId("agt_researcher")))))),
        )
    }

    @Test
    fun `rows that cannot be read are left out of every result and reported without content`() = runTest {
        repo.upsert(s.memory("mem_good", updatedAt = 200))
        repo.upsert(s.memory("mem_bad_scope", updatedAt = 100, content = "private words"))
        repo.upsert(s.memory("mem_no_agent", updatedAt = 90, content = "private words"))
        repo.upsertLink(s.link("lnk_good", "mem_good", "mem_bad_scope"))
        repo.upsertLink(s.link("lnk_bad", "mem_good", "mem_no_agent"))
        repo.putEmbedding(MemoryId("mem_good"), "emb", floatArrayOf(1f, 2f))
        sqlite.execSQL("UPDATE memories SET scope_type = 'GALAXY' WHERE id = 'mem_bad_scope'")
        sqlite.execSQL("UPDATE memories SET scope_agent_id = NULL WHERE id = 'mem_no_agent'")
        sqlite.execSQL("UPDATE memory_links SET relation = 'ENTANGLED' WHERE id = 'lnk_bad'")
        sqlite.execSQL("UPDATE memory_embeddings SET dimensions = 3 WHERE memory_id = 'mem_good'")

        assertEquals(listOf("mem_good"), ids(repo.list()))
        assertNull(repo.get(MemoryId("mem_bad_scope")))
        assertEquals(listOf("mem_good"), ids(repo.searchText("content")))
        assertTrue(repo.searchText("private").isEmpty())
        assertEquals(listOf("lnk_good"), repo.linksOf(MemoryId("mem_good")).map { it.id.value })
        assertTrue(repo.embeddings("emb").isEmpty(), "a vector of the wrong length is left out")
        // The damaged rows stay untouched until something deletes them.
        assertEquals(3, count("SELECT COUNT(*) FROM memories"))
        assertEquals(1, repo.deleteScope(s.researcher))
    }

    @Test
    fun `a memory too large to read back is refused before anything is stored`() = runTest {
        // Two bytes per character in UTF-8: just under and just over the limit.
        val fits = "é".repeat((RoomMemoryRepository.MAX_TEXT_BYTES - 4_096) / 2)
        repo.upsert(s.memory("mem_large", content = fits))
        assertEquals(fits, repo.get(MemoryId("mem_large"))!!.content)

        val tooLarge = s.memory("mem_huge", content = "é".repeat(RoomMemoryRepository.MAX_TEXT_BYTES / 2 + 1))
        val failure = assertFailsWith<IllegalArgumentException> { repo.upsert(tooLarge) }
        assertFalse(failure.message!!.contains("é"), "the message must not contain the content")
        assertTrue(failure.message!!.startsWith("Memory mem_huge holds "), failure.message)
        assertNull(repo.get(MemoryId("mem_huge")))
    }

    @Test
    fun `more ids than one statement may bind are read, counted and deleted`() = runTest {
        val many = (1..1_200).map { s.memory("mem_%04d".format(it), updatedAt = 1_000L + it) }
        many.forEach { repo.upsert(it) }
        val ids = many.map { it.id }

        assertEquals(many.reversed(), repo.getAll(ids))
        repo.recordAccess(ids, at = 9_000)
        assertEquals(1_200, count("SELECT COUNT(*) FROM memories WHERE access_count = 4 AND last_accessed_at = 9000"))
        many.forEach { repo.putEmbedding(it.id, "emb", floatArrayOf(it.updatedAt.toFloat())) }
        assertEquals(1_200, repo.embeddings("emb").size)

        repo.upsertSpace(KnowledgeSpace(SpaceId("spc_commons"), "Commons", createdAt = 1, updatedAt = 1))
        assertEquals(0, repo.deleteSpace(SpaceId("spc_commons")))
        assertEquals(1_200, repo.deleteScope(s.researcher))
        assertEquals(0, count("SELECT COUNT(*) FROM memory_embeddings"))
    }

    @Test
    fun `vectors are stored as little-endian float32 with their dimension count`() = runTest {
        repo.upsert(s.memory("mem_a"))
        repo.putEmbedding(MemoryId("mem_a"), "emb", floatArrayOf(1f, -0f, Float.NaN))
        sqlite.query(SimpleSQLiteQuery("SELECT dimensions, hex(vector) FROM memory_embeddings")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
            assertEquals("0000803F" + "00000080" + "0000C07F", cursor.getString(1))
        }
        val back = repo.embeddings("emb").getValue(MemoryId("mem_a"))
        assertEquals(listOf(1f.toRawBits(), (-0f).toRawBits(), Float.NaN.toRawBits()), back.map { it.toRawBits() })
    }
}
