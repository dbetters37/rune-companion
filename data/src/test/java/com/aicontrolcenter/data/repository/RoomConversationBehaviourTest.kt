package com.aicontrolcenter.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ImageContent
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryRow
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What the Room conversation store guarantees beyond the shared contract suite. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RoomConversationBehaviourTest {
    @get:Rule val db = InMemoryDatabaseRule()
    private val repo by lazy { RoomConversationRepository(db.database) }
    private val dao get() = db.database.conversationDao()

    private fun conversation(id: String, agent: String = "agt_a", updatedAt: Long = 100) =
        Conversation(ConversationId(id), AgentId(agent), "Chat", createdAt = 50, updatedAt = updatedAt)

    private fun entry(conversation: String, text: String, seq: Int = 0) =
        ConversationEntry(ConversationId(conversation), seq, ChatMessage.user(text), at = 1)

    private fun rowCount(table: String): Int =
        db.database.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM $table")).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun `a message several times the size of a cursor window is read back intact, in order with its neighbours`() = runTest {
        repo.upsertConversation(conversation("cnv_1"))
        // Not a repeating pattern, so a piece that is lost, repeated or out of place changes the text.
        val base64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val photo = ImageContent("image/jpeg", String(CharArray(3 * 1024 * 1024) { base64[(it * 31 + it / 977) % 64] }))
        // Two, four and three bytes per character: the cuts between pieces fall inside characters.
        val caption = "é🗂漢".repeat(200_000)
        val large = ChatMessage.user(caption, listOf(photo))
        val largeBytes = large.let { entry("cnv_1", "").copy(message = it).toEntity().messageJson.toByteArray().size }
        assertTrue(largeBytes > ConversationEntryRow.HEAD_BYTES + 3 * ConversationEntryRow.PART_BYTES, "the message must span several pieces")

        val stored = listOf(
            repo.appendEntry(entry("cnv_1", "before")),
            repo.appendEntry(entry("cnv_1", "").copy(message = large)),
            repo.appendEntry(entry("cnv_1", "after")),
        )

        assertEquals(stored, repo.getEntries(ConversationId("cnv_1")))
        assertEquals(stored, repo.observeEntries(ConversationId("cnv_1")).first())
    }

    @Test
    fun `a message exactly as long as the part delivered with its row, and one byte longer, are read back intact`() = runTest {
        repo.upsertConversation(conversation("cnv_1"))
        val emptyMessageBytes = entry("cnv_1", "").toEntity().messageJson.toByteArray().size
        val stored = listOf(0, 1).map { extra ->
            repo.appendEntry(entry("cnv_1", "x".repeat(ConversationEntryRow.HEAD_BYTES - emptyMessageBytes + extra)))
        }
        assertEquals(stored, repo.getEntries(ConversationId("cnv_1")))
    }

    @Test
    fun `entries are numbered per conversation and the number passed in is ignored`() = runTest {
        repo.upsertConversation(conversation("cnv_1"))
        repo.upsertConversation(conversation("cnv_2"))
        assertEquals(0, repo.appendEntry(entry("cnv_1", "a", seq = 7)).seq)
        assertEquals(1, repo.appendEntry(entry("cnv_1", "b", seq = 7)).seq)
        assertEquals(0, repo.appendEntry(entry("cnv_2", "c", seq = 0)).seq)
        assertEquals(listOf("a", "b"), repo.getEntries(ConversationId("cnv_1")).map { it.message.text })
        assertEquals(listOf("c"), repo.getEntries(ConversationId("cnv_2")).map { it.message.text })
    }

    @Test
    fun `saving a conversation again keeps its entries`() = runTest {
        repo.upsertConversation(conversation("cnv_1"))
        val stored = listOf(repo.appendEntry(entry("cnv_1", "a")), repo.appendEntry(entry("cnv_1", "b")))

        // What every turn does: the title or the time of the last update changes.
        repo.upsertConversation(conversation("cnv_1", updatedAt = 900).copy(title = "Renamed"))

        assertEquals(stored, repo.getEntries(ConversationId("cnv_1")))
        assertEquals(2, repo.appendEntry(entry("cnv_1", "c")).seq)
    }

    @Test
    fun `conversations updated at the same moment are listed in a stable order`() = runTest {
        listOf("cnv_b", "cnv_c", "cnv_a").forEach { repo.upsertConversation(conversation(it, updatedAt = 100)) }
        repo.upsertConversation(conversation("cnv_z", updatedAt = 200))
        assertEquals(listOf("cnv_z", "cnv_a", "cnv_b", "cnv_c"), repo.observeConversations().first().map { it.id.value })
    }

    @Test
    fun `an entry that cannot be decoded is skipped and the entries around it keep their numbers`() = runTest {
        repo.upsertConversation(conversation("cnv_1"))
        val first = repo.appendEntry(entry("cnv_1", "a"))
        // Written straight through the DAO, the way a later build or disk damage would leave them.
        val damaged = ConversationEntryEntity("cnv_1", seq = 0, at = 2, modelName = null, messageJson = "", usageJson = null, error = null)
        assertEquals(1, dao.append(damaged.copy(messageJson = """{"role":"NARRATOR","text":"x"}""")))
        assertEquals(2, dao.append(damaged.copy(messageJson = """{"role":"USER","text":"x"}""", usageJson = """{"inputTokens": tru""")))
        val last = repo.appendEntry(entry("cnv_1", "b"))

        assertEquals(3, last.seq)
        assertEquals(listOf(first, last), repo.getEntries(ConversationId("cnv_1")))
        assertEquals(listOf(first, last), repo.observeEntries(ConversationId("cnv_1")).first())
    }

    @Test
    fun `a failed append stores nothing`() = runTest {
        assertFailsWith<IllegalArgumentException> { repo.appendEntry(entry("cnv_missing", "a")) }
        assertEquals(0, rowCount("conversation_entries"))
        assertEquals(0, rowCount("conversations"))
    }

    @Test
    fun `deleted conversations leave no rows behind, even where foreign keys are not enforced`() = runTest {
        // The content of a conversation the owner deleted must be gone whatever the connection's settings.
        db.database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
        listOf(conversation("cnv_1"), conversation("cnv_2"), conversation("cnv_3", agent = "agt_b")).forEach { repo.upsertConversation(it) }
        listOf("cnv_1", "cnv_1", "cnv_2", "cnv_3").forEach { repo.appendEntry(entry(it, "secret")) }

        repo.deleteConversation(ConversationId("cnv_1"))
        assertEquals(2, rowCount("conversation_entries"))
        repo.deleteByAgent(AgentId("agt_a"))
        assertEquals(1, rowCount("conversation_entries"))
        assertEquals(1, rowCount("conversations"))
        assertEquals(1, repo.getEntries(ConversationId("cnv_3")).size)
    }

    @Test
    fun `a conversation with the id of a deleted one starts empty`() = runTest {
        repo.upsertConversation(conversation("cnv_1"))
        repo.appendEntry(entry("cnv_1", "old"))
        repo.deleteConversation(ConversationId("cnv_1"))

        repo.upsertConversation(conversation("cnv_1"))
        assertEquals(emptyList(), repo.getEntries(ConversationId("cnv_1")))
        assertEquals(0, repo.appendEntry(entry("cnv_1", "new")).seq)
    }
}
