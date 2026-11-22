package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ImageContent
import com.aicontrolcenter.core.llm.ProviderData
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class ConversationRepositoryContract {
    protected abstract fun createRepository(): ConversationRepository

    private fun conversation(id: String, agent: String = "agt_a", updatedAt: Long = 100) =
        Conversation(ConversationId(id), AgentId(agent), "Title, \"quoted\" 🗂", createdAt = 50, updatedAt = updatedAt)

    private fun entry(conversation: String, message: ChatMessage, at: Long = 1) =
        ConversationEntry(ConversationId(conversation), seq = -1, message = message, at = at)

    @Test
    fun `conversations are listed most recently updated first and can be filtered by agent`() = runTest {
        val repo = createRepository()
        repo.upsertConversation(conversation("cnv_old", updatedAt = 100))
        repo.upsertConversation(conversation("cnv_new", updatedAt = 300))
        repo.upsertConversation(conversation("cnv_other", agent = "agt_b", updatedAt = 200))
        assertEquals(listOf("cnv_new", "cnv_other", "cnv_old"), repo.observeConversations().first().map { it.id.value })
        assertEquals(listOf("cnv_new", "cnv_old"), repo.observeConversations(AgentId("agt_a")).first().map { it.id.value })
        assertEquals(conversation("cnv_old"), repo.getConversation(ConversationId("cnv_old")))
        assertNull(repo.getConversation(ConversationId("cnv_missing")))
        repo.upsertConversation(conversation("cnv_old", updatedAt = 900).copy(title = "Renamed"))
        assertEquals("cnv_old", repo.observeConversations().first().first().id.value)
        assertEquals("Renamed", repo.getConversation(ConversationId("cnv_old"))?.title)
    }

    @Test
    fun `entries keep every field and are numbered in the order they were appended`() = runTest {
        val repo = createRepository()
        repo.upsertConversation(conversation("cnv_1"))
        val user = ChatMessage.user("Look at this,\n\"please\"", listOf(ImageContent("image/png", "aGVsbG8=")))
        val assistant = ChatMessage(
            role = ChatRole.ASSISTANT, text = "Checking…",
            toolCalls = listOf(ToolCallRequest("call_1", "clock_now", buildJsonObject { put("zone", "UTC") })),
            reasoning = "The owner wants the time.",
            providerData = ProviderData(ProviderKind.ANTHROPIC, buildJsonArray { add(buildJsonObject { put("type", "thinking"); put("signature", "c2ln") }) }),
        )
        val tool = ChatMessage.toolResult("call_1", "clock_now", "2026-11-22T10:00:00Z", isError = false)
        val stored = listOf(
            repo.appendEntry(entry("cnv_1", user, at = 10)),
            repo.appendEntry(entry("cnv_1", assistant, at = 20).copy(modelName = "model-x", usage = TokenUsage(120, 30, 100))),
            repo.appendEntry(entry("cnv_1", tool, at = 30)),
            repo.appendEntry(entry("cnv_1", ChatMessage.assistant(""), at = 40).copy(error = "The provider is overloaded.")),
        )
        assertEquals(listOf(0, 1, 2, 3), stored.map { it.seq })
        assertEquals(stored, repo.getEntries(ConversationId("cnv_1")))
        assertEquals(stored, repo.observeEntries(ConversationId("cnv_1")).first())
        assertEquals(assistant, stored[1].message)
        assertTrue(repo.getEntries(ConversationId("cnv_missing")).isEmpty())
    }

    @Test
    fun `concurrent appends get distinct consecutive numbers`() = runTest {
        val repo = createRepository()
        repo.upsertConversation(conversation("cnv_1"))
        withContext(Dispatchers.Default) {
            (1..60).map { n -> launch { repo.appendEntry(entry("cnv_1", ChatMessage.user("m$n"))) } }.joinAll()
        }
        assertEquals((0..59).toList(), repo.getEntries(ConversationId("cnv_1")).map { it.seq })
    }

    @Test
    fun `appending to a conversation that does not exist fails`() = runTest {
        val repo = createRepository()
        assertFailsWith<IllegalArgumentException> { repo.appendEntry(entry("cnv_missing", ChatMessage.user("x"))) }
    }

    @Test
    fun `deleting removes entries too, per conversation and per agent`() = runTest {
        val repo = createRepository()
        listOf(conversation("cnv_1"), conversation("cnv_2"), conversation("cnv_3", agent = "agt_b")).forEach { repo.upsertConversation(it) }
        listOf("cnv_1", "cnv_2", "cnv_3").forEach { repo.appendEntry(entry(it, ChatMessage.user("hi"))) }
        repo.deleteConversation(ConversationId("cnv_1"))
        assertNull(repo.getConversation(ConversationId("cnv_1")))
        assertTrue(repo.getEntries(ConversationId("cnv_1")).isEmpty())
        repo.deleteByAgent(AgentId("agt_a"))
        assertEquals(listOf("cnv_3"), repo.observeConversations().first().map { it.id.value })
        assertTrue(repo.getEntries(ConversationId("cnv_2")).isEmpty())
        assertEquals(1, repo.getEntries(ConversationId("cnv_3")).size)
        repo.deleteConversation(ConversationId("cnv_1"))
    }

    @Test
    fun `open observers see new conversations and entries`() = runTest {
        val repo = createRepository()
        val lists = collectIn(backgroundScope, repo.observeConversations())
        val entries = collectIn(backgroundScope, repo.observeEntries(ConversationId("cnv_1")))
        awaitUntil { lists.isNotEmpty() && entries.isNotEmpty() }
        repo.upsertConversation(conversation("cnv_1"))
        awaitUntil { lists.last().size == 1 }
        repo.appendEntry(entry("cnv_1", ChatMessage.user("hi")))
        awaitUntil { entries.last().size == 1 }
        repo.deleteConversation(ConversationId("cnv_1"))
        awaitUntil { lists.last().isEmpty() && entries.last().isEmpty() }
    }
}
