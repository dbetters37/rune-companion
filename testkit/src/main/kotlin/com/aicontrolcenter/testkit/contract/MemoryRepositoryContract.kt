package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sample memories with every field set, including content hostile to naive storage. */
object MemorySamples {
    val researcher = MemoryScope.Agent(AgentId("agt_researcher"))
    val writer = MemoryScope.Agent(AgentId("agt_writer"))
    val commons = MemoryScope.Shared(SpaceId("spc_commons"))
    val task = MemoryScope.Task(TaskId("tsk_1"), AgentId("agt_researcher"))

    fun provenance(at: Long = 1_000) = Provenance(
        origin = ProvenanceOrigin.AGENT, agentId = AgentId("agt_researcher"), taskId = TaskId("tsk_1"),
        conversationId = ConversationId("cnv_1"), modelId = ModelId("mdl_1"), modelName = "model-x-2026",
        derivedFrom = listOf(MemoryId("mem_z"), MemoryId("mem_a")), source = "Tool: Web page, \"quoted\"\nline", recordedAt = at,
    )

    fun memory(
        id: String,
        scope: MemoryScope = researcher,
        updatedAt: Long = 1_000,
        kind: MemoryKind = MemoryKind.FACT,
        tier: MemoryTier = MemoryTier.LONG_TERM,
        title: String = "Title $id",
        content: String = "Content of $id, with \"quotes\", commas; and a newline\nand emoji 🧠",
        tags: Set<String> = setOf("tag-$id", "shared tag"),
        expiresAt: Long? = null,
    ) = MemoryRecord(
        id = MemoryId(id), scope = scope, kind = kind, tier = tier, title = title, content = content, tags = tags,
        importance = 0.7, confidence = 0.9, provenance = provenance(updatedAt), createdAt = updatedAt - 10, updatedAt = updatedAt,
        lastAccessedAt = updatedAt + 1, accessCount = 3, expiresAt = expiresAt, revision = 2,
    )

    fun link(id: String, from: String, to: String, relation: LinkRelation = LinkRelation.RELATES_TO, at: Long = 2_000) =
        MemoryLink(MemoryLinkId(id), MemoryId(from), MemoryId(to), relation, weight = 0.64, provenance = provenance(at), createdAt = at)
}

abstract class MemoryRepositoryContract {
    protected abstract fun createRepository(): MemoryRepository

    private val s = MemorySamples

    @Test
    fun `stores every field of a memory in every kind of scope`() = runTest {
        val repo = createRepository()
        val records = listOf(
            s.memory("mem_1", s.researcher, 300),
            s.memory("mem_2", s.commons, 200, MemoryKind.PREFERENCE),
            s.memory("mem_3", s.task, 100, MemoryKind.NOTE, MemoryTier.SHORT_TERM, expiresAt = 9_999),
            s.memory("mem_4", s.writer, 50).copy(tags = emptySet(), lastAccessedAt = null, accessCount = 0, title = "", provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = 1)),
        )
        records.forEach { repo.upsert(it) }
        records.forEach { assertEquals(it, repo.get(it.id)) }
        assertEquals(records, repo.list())
        assertEquals(records, repo.observe().first())
        assertNull(repo.get(MemoryId("mem_missing")))
        assertEquals(listOf(records[0], records[2]), repo.getAll(listOf(MemoryId("mem_3"), MemoryId("mem_1"), MemoryId("mem_missing"), MemoryId("mem_1"))))
    }

    @Test
    fun `lists newest first and filters by scope, kind, tier and expiry`() = runTest {
        val repo = createRepository()
        repo.upsert(s.memory("mem_old", s.researcher, 100))
        repo.upsert(s.memory("mem_new", s.researcher, 300, MemoryKind.DECISION))
        repo.upsert(s.memory("mem_mid", s.commons, 200, MemoryKind.DECISION))
        repo.upsert(s.memory("mem_tmp", s.task, 250, tier = MemoryTier.SHORT_TERM, expiresAt = 500))
        repo.upsert(s.memory("mem_tie_b", s.writer, 50))
        repo.upsert(s.memory("mem_tie_a", s.writer, 50))
        fun ids(list: List<MemoryRecord>) = list.map { it.id.value }
        assertEquals(listOf("mem_new", "mem_tmp", "mem_mid", "mem_old", "mem_tie_a", "mem_tie_b"), ids(repo.list()))
        assertEquals(listOf("mem_new", "mem_mid"), ids(repo.list(limit = 3).filter { it.kind == MemoryKind.DECISION }))
        assertEquals(listOf("mem_new", "mem_tmp"), ids(repo.list(limit = 2)))
        assertEquals(listOf("mem_new", "mem_mid", "mem_old"), ids(repo.list(MemoryFilter(scopes = setOf(s.researcher, s.commons)))))
        assertTrue(repo.list(MemoryFilter(scopes = emptySet())).isEmpty())
        assertEquals(listOf("mem_new", "mem_mid"), ids(repo.list(MemoryFilter(kinds = setOf(MemoryKind.DECISION)))))
        assertEquals(listOf("mem_tmp"), ids(repo.list(MemoryFilter(tier = MemoryTier.SHORT_TERM))))
        assertTrue("mem_tmp" !in ids(repo.list(MemoryFilter(excludeExpiredAt = 500))))
        assertTrue("mem_tmp" in ids(repo.list(MemoryFilter(excludeExpiredAt = 499))))
        assertEquals(listOf("mem_mid"), ids(repo.observe(MemoryFilter(scopes = setOf(s.commons))).first()))
    }

    @Test
    fun `deleting a memory removes its links in both directions and its embeddings`() = runTest {
        val repo = createRepository()
        listOf("mem_a", "mem_b", "mem_c").forEach { repo.upsert(s.memory(it)) }
        repo.upsertLink(s.link("lnk_1", "mem_a", "mem_b"))
        repo.upsertLink(s.link("lnk_2", "mem_c", "mem_a", LinkRelation.SUPPORTS))
        repo.upsertLink(s.link("lnk_3", "mem_b", "mem_c"))
        repo.putEmbedding(MemoryId("mem_a"), "emb", floatArrayOf(1f, 0f))
        repo.delete(MemoryId("mem_a"))
        assertNull(repo.get(MemoryId("mem_a")))
        assertEquals(listOf("lnk_3"), repo.links().map { it.id.value })
        assertTrue(repo.linksOf(MemoryId("mem_a")).isEmpty())
        assertTrue(repo.embeddings("emb").isEmpty())
        repo.delete(MemoryId("mem_a"))
    }

    @Test
    fun `links keep every field, are listed within a filter, and need both ends`() = runTest {
        val repo = createRepository()
        repo.upsert(s.memory("mem_a", s.researcher))
        repo.upsert(s.memory("mem_b", s.researcher))
        repo.upsert(s.memory("mem_c", s.commons))
        val ab = s.link("lnk_ab", "mem_a", "mem_b", LinkRelation.DERIVED_FROM, at = 10)
        val ac = s.link("lnk_ac", "mem_a", "mem_c", LinkRelation.SHARED_COPY_OF, at = 20)
        repo.upsertLink(ab)
        repo.upsertLink(ac)
        assertEquals(listOf(ab, ac), repo.links())
        assertEquals(listOf(ab), repo.links(MemoryFilter(scopes = setOf(s.researcher))))
        assertEquals(listOf(ab), repo.observeLinks(MemoryFilter(scopes = setOf(s.researcher))).first())
        assertEquals(listOf(ac), repo.linksOf(MemoryId("mem_c")))
        assertEquals(listOf(ab, ac), repo.linksOf(MemoryId("mem_a")))
        repo.upsertLink(ab.copy(weight = 0.2))
        assertEquals(0.2, repo.links().first { it.id == ab.id }.weight)
        assertFails { repo.upsertLink(s.link("lnk_x", "mem_a", "mem_missing")) }
        repo.deleteLink(ab.id)
        assertEquals(listOf(ac), repo.links())
    }

    @Test
    fun `deleting a scope or expired memories removes only those, with their links`() = runTest {
        val repo = createRepository()
        repo.upsert(s.memory("mem_t1", s.task, tier = MemoryTier.SHORT_TERM, expiresAt = 100))
        repo.upsert(s.memory("mem_t2", s.task, tier = MemoryTier.SHORT_TERM, expiresAt = 200))
        repo.upsert(s.memory("mem_keep", s.researcher))
        repo.upsert(s.memory("mem_w", s.writer))
        repo.upsertLink(s.link("lnk_1", "mem_keep", "mem_t1"))
        assertEquals(1, repo.deleteExpired(now = 150))
        assertEquals(listOf("mem_keep", "mem_t2", "mem_w").sorted(), repo.list().map { it.id.value }.sorted())
        assertTrue(repo.links().isEmpty())
        assertEquals(1, repo.deleteScope(s.task))
        assertEquals(0, repo.deleteScope(s.task))
        assertEquals(1, repo.deleteScope(s.writer))
        assertEquals(listOf("mem_keep"), repo.list().map { it.id.value })
    }

    @Test
    fun `access is counted per memory, also under concurrency`() = runTest {
        val repo = createRepository()
        repo.upsert(s.memory("mem_a").copy(accessCount = 0, lastAccessedAt = null))
        repo.upsert(s.memory("mem_b").copy(accessCount = 0, lastAccessedAt = null))
        withContext(Dispatchers.Default) {
            (1..50).map { n -> launch { repo.recordAccess(listOf(MemoryId("mem_a"), MemoryId("mem_missing")), at = n.toLong()) } }.joinAll()
        }
        repo.recordAccess(listOf(MemoryId("mem_b"), MemoryId("mem_b")), at = 7)
        assertEquals(50, repo.get(MemoryId("mem_a"))!!.accessCount)
        assertEquals(1, repo.get(MemoryId("mem_b"))!!.accessCount, "an id listed twice in one call counts once")
        assertEquals(7L, repo.get(MemoryId("mem_b"))!!.lastAccessedAt)
    }

    @Test
    fun `text search needs every word, ignores case, covers tags and respects the filter`() = runTest {
        val repo = createRepository()
        repo.upsert(s.memory("mem_1", s.researcher, 300, title = "Ollama setup", content = "Runs on the Desk PC at port 11434", tags = setOf("infra")))
        repo.upsert(s.memory("mem_2", s.commons, 200, title = "Tea", content = "The owner likes Earl Grey", tags = setOf("preference")))
        repo.upsert(s.memory("mem_3", s.researcher, 100, title = "Desk lamp", content = "Broken", tags = setOf("home")))
        fun ids(list: List<MemoryRecord>) = list.map { it.id.value }
        assertEquals(listOf("mem_1", "mem_3"), ids(repo.searchText("desk")))
        assertEquals(listOf("mem_1"), ids(repo.searchText("DESK  11434")))
        assertEquals(listOf("mem_2"), ids(repo.searchText("preference earl")))
        assertEquals(listOf("mem_1"), ids(repo.searchText("desk", limit = 1)))
        assertTrue(repo.searchText("desk", MemoryFilter(scopes = setOf(s.commons))).isEmpty())
        assertTrue(repo.searchText("   ").isEmpty())
        assertTrue(repo.searchText("100%_ match").isEmpty(), "wildcard characters are matched literally")
    }

    @Test
    fun `embeddings are stored per embedder, replaced, and reported missing`() = runTest {
        val repo = createRepository()
        repo.upsert(s.memory("mem_a", s.researcher, 200))
        repo.upsert(s.memory("mem_b", s.commons, 100))
        repo.putEmbedding(MemoryId("mem_a"), "hash-v1", floatArrayOf(0.6f, 0.8f, -0.0f))
        repo.putEmbedding(MemoryId("mem_a"), "other", floatArrayOf(1f))
        repo.putEmbedding(MemoryId("mem_missing"), "hash-v1", floatArrayOf(1f))
        assertContentEquals(floatArrayOf(0.6f, 0.8f, -0.0f), repo.embeddings("hash-v1").getValue(MemoryId("mem_a")))
        assertEquals(setOf(MemoryId("mem_a")), repo.embeddings("hash-v1").keys)
        assertEquals(listOf(MemoryId("mem_b")), repo.missingEmbeddings("hash-v1"))
        assertTrue(repo.missingEmbeddings("hash-v1", MemoryFilter(scopes = setOf(s.researcher))).isEmpty())
        assertTrue(repo.embeddings("hash-v1", MemoryFilter(scopes = setOf(s.commons))).isEmpty())
        repo.putEmbedding(MemoryId("mem_a"), "hash-v1", floatArrayOf(0f, 1f))
        assertContentEquals(floatArrayOf(0f, 1f), repo.embeddings("hash-v1").getValue(MemoryId("mem_a")))
        val big = FloatArray(1024) { it / 1024f }
        repo.putEmbedding(MemoryId("mem_b"), "hash-v1", big)
        assertContentEquals(big, repo.embeddings("hash-v1").getValue(MemoryId("mem_b")))
    }

    @Test
    fun `spaces are listed oldest first and deleting one deletes its memories`() = runTest {
        val repo = createRepository()
        val b = KnowledgeSpace(SpaceId("spc_b"), "Research, \"shared\"", "What the team found", createdAt = 200, updatedAt = 200)
        val a = KnowledgeSpace(SpaceId("spc_a"), "Home", "", createdAt = 100, updatedAt = 150)
        repo.upsertSpace(b)
        repo.upsertSpace(a)
        assertEquals(listOf(a, b), repo.observeSpaces().first())
        assertEquals(b, repo.getSpace(b.id))
        repo.upsert(s.memory("mem_in", MemoryScope.Shared(b.id)))
        repo.upsert(s.memory("mem_out", s.researcher))
        repo.upsertLink(s.link("lnk", "mem_out", "mem_in"))
        assertEquals(1, repo.deleteSpace(b.id))
        assertNull(repo.getSpace(b.id))
        assertEquals(listOf("mem_out"), repo.list().map { it.id.value })
        assertTrue(repo.links().isEmpty())
    }

    @Test
    fun `open observers see later changes`() = runTest {
        val repo = createRepository()
        val memories = collectIn(backgroundScope, repo.observe())
        val links = collectIn(backgroundScope, repo.observeLinks())
        val spaces = collectIn(backgroundScope, repo.observeSpaces())
        awaitUntil { memories.isNotEmpty() && links.isNotEmpty() && spaces.isNotEmpty() }
        repo.upsert(s.memory("mem_a"))
        repo.upsert(s.memory("mem_b"))
        awaitUntil { memories.last().size == 2 }
        repo.upsertLink(s.link("lnk", "mem_a", "mem_b"))
        awaitUntil { links.last().size == 1 }
        repo.upsertSpace(KnowledgeSpace(SpaceId("spc"), "S", createdAt = 1, updatedAt = 1))
        awaitUntil { spaces.last().size == 1 }
        repo.delete(MemoryId("mem_a"))
        awaitUntil { memories.last().size == 1 && links.last().isEmpty() }
    }
}
