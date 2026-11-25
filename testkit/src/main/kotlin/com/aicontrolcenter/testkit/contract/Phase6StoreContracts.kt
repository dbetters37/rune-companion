package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityCursor
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.chat.InterruptedTurn
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6, K8 (R3, P2): turns cut off by the app stopping, and reading parts of a long chat. */
abstract class ConversationTurnContract {
    protected abstract fun createRepository(): ConversationRepository

    private fun conversation(id: String, agent: String = "agt_a") =
        Conversation(ConversationId(id), AgentId(agent), "Chat $id", createdAt = 10, updatedAt = 20)

    private fun cid(id: String) = ConversationId(id)

    @Test
    fun `a turn's mark stays until it ends, whatever else changes`() = runTest {
        val repo = createRepository()
        listOf(conversation("cnv_1"), conversation("cnv_2"), conversation("cnv_3", agent = "agt_b")).forEach { repo.upsertConversation(it) }
        assertEquals(emptyList(), repo.interruptedTurns())
        repo.markTurnStarted(cid("cnv_2"), at = 300)
        repo.markTurnStarted(cid("cnv_1"), at = 300)
        repo.markTurnStarted(cid("cnv_3"), at = 100)
        assertEquals(listOf(InterruptedTurn(cid("cnv_3"), 100), InterruptedTurn(cid("cnv_1"), 300), InterruptedTurn(cid("cnv_2"), 300)), repo.interruptedTurns())
        // A rename, an entry, a new updated time: the mark stays.
        repo.upsertConversation(conversation("cnv_1").copy(title = "Renamed", updatedAt = 999))
        repo.appendEntry(ConversationEntry(cid("cnv_1"), seq = -1, message = ChatMessage.user("hi"), at = 400))
        assertTrue(InterruptedTurn(cid("cnv_1"), 300) in repo.interruptedTurns())
        // A new turn replaces the time; ending removes it; ending twice is harmless.
        repo.markTurnStarted(cid("cnv_1"), at = 500)
        repo.markTurnEnded(cid("cnv_2"))
        repo.markTurnEnded(cid("cnv_2"))
        assertEquals(listOf(InterruptedTurn(cid("cnv_3"), 100), InterruptedTurn(cid("cnv_1"), 500)), repo.interruptedTurns())
        // Deleting the conversation deletes its mark; an unknown conversation is never marked.
        repo.deleteConversation(cid("cnv_1"))
        repo.deleteByAgent(AgentId("agt_b"))
        repo.markTurnStarted(cid("cnv_missing"), at = 1)
        assertEquals(emptyList(), repo.interruptedTurns())
        assertNull(repo.getConversation(cid("cnv_missing")))
    }

    @Test
    fun `parts of a long conversation are read by sequence number`() = runTest {
        val repo = createRepository()
        repo.upsertConversation(conversation("cnv_1"))
        assertNull(repo.observeLatestSeq(cid("cnv_1")).first())
        val stored = (0 until 10).map { repo.appendEntry(ConversationEntry(cid("cnv_1"), seq = -1, message = ChatMessage.user("m$it"), at = it.toLong())) }
        assertEquals(9, repo.observeLatestSeq(cid("cnv_1")).first())
        assertEquals(stored.drop(7), repo.entriesAfter(cid("cnv_1"), 6))
        assertEquals(emptyList(), repo.entriesAfter(cid("cnv_1"), 9))
        assertEquals(stored.subList(3, 6), repo.entriesBefore(cid("cnv_1"), 6, 3))
        assertEquals(stored.subList(0, 2), repo.entriesBefore(cid("cnv_1"), 2, 5))
        assertEquals(emptyList(), repo.entriesBefore(cid("cnv_1"), 0, 5))
        assertEquals(emptyList(), repo.entriesBefore(cid("cnv_1"), 6, 0))
        assertEquals(emptyList(), repo.entriesAfter(cid("cnv_missing"), -1))
    }

    @Test
    fun `the latest sequence number is observed as entries arrive`() = runTest {
        val repo = createRepository()
        repo.upsertConversation(conversation("cnv_1"))
        val seen = collectIn(backgroundScope, repo.observeLatestSeq(cid("cnv_1")))
        awaitUntil { seen.isNotEmpty() }
        repo.appendEntry(ConversationEntry(cid("cnv_1"), seq = -1, message = ChatMessage.user("a"), at = 1))
        repo.appendEntry(ConversationEntry(cid("cnv_1"), seq = -1, message = ChatMessage.user("b"), at = 2))
        awaitUntil { seen.last() == 1 }
        repo.deleteConversation(cid("cnv_1"))
        awaitUntil { seen.last() == null }
    }
}

/** Phase 6, K10 (D41, Tier B text search): the log's text filter and its pages. */
abstract class ActivityPageContract {
    protected abstract fun createLog(): ActivityLog

    private fun ids(events: List<com.aicontrolcenter.core.activity.ActivityEvent>) = events.map { it.id.value }

    @Test
    fun `the text filter finds title and detail, ignoring case, and means itself`() = runTest {
        val log = createLog()
        log.record(Samples.event(id = "evt_a", at = 1).copy(title = "Report ready", detail = "The weekly report"))
        log.record(Samples.event(id = "evt_b", at = 2).copy(title = "Model call", detail = "Sent the REPORT to xAI"))
        log.record(Samples.event(id = "evt_c", at = 3).copy(title = "Discount 50% off_now", detail = ""))
        log.record(Samples.event(id = "evt_d", at = 4, type = ActivityType.TOOL_CALL).copy(title = "Discount 50 percent off", detail = "x"))
        log.record(Samples.event(id = "evt_e", at = 5).copy(title = "Path C:\\temp", detail = ""))
        assertEquals(listOf("evt_b", "evt_a"), ids(log.recent(ActivityFilter(text = "report"))))
        assertEquals(listOf("evt_c"), ids(log.recent(ActivityFilter(text = "50% OFF_"))), "% and _ are plain characters")
        assertEquals(listOf("evt_e"), ids(log.recent(ActivityFilter(text = "c:\\TEMP"))), "so is a backslash")
        assertEquals(listOf("evt_d"), ids(log.recent(ActivityFilter(text = "discount", types = setOf(ActivityType.TOOL_CALL)))))
        assertEquals(listOf("evt_b", "evt_a"), ids(log.observe(ActivityFilter(text = "Report")).first()))
        assertEquals(5, log.recent(ActivityFilter(text = "")).size, "a blank text filters nothing")
        assertEquals(emptyList(), log.recent(ActivityFilter(text = "nowhere")))
    }

    @Test
    fun `pages follow the log's order from a cursor`() = runTest {
        val log = createLog()
        listOf("evt_1" to 100L, "evt_2a" to 200L, "evt_2b" to 200L, "evt_2c" to 200L, "evt_3" to 300L).forEach { (id, at) -> log.record(Samples.event(id = id, at = at)) }
        val first = log.observePage(limit = 2).first()
        assertEquals(listOf("evt_3", "evt_2c"), ids(first))
        val second = log.observePage(before = ActivityCursor.after(first.last()), limit = 2).first()
        assertEquals(listOf("evt_2b", "evt_2a"), ids(second), "same time: latest recorded first")
        val third = log.observePage(before = ActivityCursor.after(second.last()), limit = 2).first()
        assertEquals(listOf("evt_1"), ids(third))
        assertEquals(emptyList(), log.observePage(before = ActivityCursor.after(third.last()), limit = 2).first())
        // Filters apply to pages; a cursor whose event is gone starts below its time.
        assertEquals(listOf("evt_2b"), ids(log.observePage(ActivityFilter(text = "model"), ActivityCursor.after(first.last()), 1).first()))
        log.prune(olderThan = 250)
        assertEquals(emptyList(), log.observePage(before = ActivityCursor(200, com.aicontrolcenter.core.EventId("evt_2a")), limit = 5).first())
        assertEquals(listOf("evt_3"), ids(log.observePage(before = ActivityCursor(301, com.aicontrolcenter.core.EventId("evt_gone")), limit = 5).first()))
    }

    @Test
    fun `an open page sees new events`() = runTest {
        val log = createLog()
        val page = collectIn(backgroundScope, log.observePage(ActivityFilter(text = "ready"), limit = 2))
        awaitUntil { page.isNotEmpty() }
        log.record(Samples.event(id = "evt_1", at = 1).copy(title = "Not this"))
        log.record(Samples.event(id = "evt_2", at = 2).copy(title = "Ready 1"))
        log.record(Samples.event(id = "evt_3", at = 3).copy(title = "ready 2"))
        log.record(Samples.event(id = "evt_4", at = 4).copy(title = "READY 3"))
        awaitUntil { ids(page.last()) == listOf("evt_4", "evt_3") }
    }
}

/** Phase 6, K9 (rule 16): memory counts without reading the memories. */
abstract class MemoryCountContract {
    protected abstract fun createRepository(): MemoryRepository

    private val s = MemorySamples

    @Test
    fun `counts follow the filter and spaces are counted one by one`() = runTest {
        val repo = createRepository()
        val other = MemoryScope.Shared(SpaceId("spc_other"))
        repo.upsertAll(listOf(
            s.memory("mem_1", scope = s.commons), s.memory("mem_2", scope = s.commons, kind = MemoryKind.EVENT),
            s.memory("mem_3", scope = other, expiresAt = 50), s.memory("mem_4", scope = s.researcher, tier = MemoryTier.SHORT_TERM),
            s.memory("mem_5", scope = s.task),
        ))
        assertEquals(5, repo.observeCount().first())
        assertEquals(3, repo.observeCount(MemoryFilter(scopes = setOf(s.commons, other))).first())
        assertEquals(0, repo.observeCount(MemoryFilter(scopes = emptySet())).first())
        assertEquals(1, repo.observeCount(MemoryFilter(kinds = setOf(MemoryKind.EVENT))).first())
        assertEquals(1, repo.observeCount(MemoryFilter(tier = MemoryTier.SHORT_TERM)).first())
        assertEquals(4, repo.observeCount(MemoryFilter(excludeExpiredAt = 100)).first())
        assertEquals(mapOf(SpaceId("spc_commons") to 2, SpaceId("spc_other") to 1), repo.observeCountsBySpace().first(), "expired ones count; spaces only")
    }

    @Test
    fun `open counts follow changes`() = runTest {
        val repo = createRepository()
        val count = collectIn(backgroundScope, repo.observeCount(MemoryFilter(scopes = setOf(s.commons))))
        val bySpace = collectIn(backgroundScope, repo.observeCountsBySpace())
        awaitUntil { count.isNotEmpty() && bySpace.isNotEmpty() }
        assertEquals(0, count.last())
        repo.upsert(s.memory("mem_1", scope = s.commons))
        repo.upsert(s.memory("mem_2", scope = s.commons))
        awaitUntil { count.last() == 2 && bySpace.last() == mapOf(SpaceId("spc_commons") to 2) }
        repo.delete(com.aicontrolcenter.core.MemoryId("mem_1"))
        awaitUntil { count.last() == 1 && bySpace.last() == mapOf(SpaceId("spc_commons") to 1) }
        repo.deleteScope(s.commons)
        awaitUntil { count.last() == 0 && bySpace.last().isEmpty() }
    }
}

/**
 * Phase 6 (section 4, K16): every setting added in Phase 6 is stored and read back exactly. A
 * store that keeps the Phase 5 fields only fails here (the DataStore codec reads each field on
 * its own, WP1b).
 */
abstract class SettingsRoundTripContract {
    protected abstract fun createRepository(): SettingsRepository

    @Test
    fun `every Phase 6 setting is stored and read back`() = runTest {
        val repo = createRepository()
        val changed = FrameworkSettings(
            keepAwakeWhileAgentsWork = false, notifyWhenWorkEnds = false, maxModelCallsPerDay = 75, maxTokensPerDay = 123_456,
            maxSpendPerDayMicros = 2_500_000, agentQuestionsEnabled = false, ownerQuestionTimeoutMinutes = 45, usageRetentionDays = 90,
            calmMotion = true, routinesEnabled = true, folderPickUpEnabled = true,
        )
        repo.update { changed }
        assertEquals(changed, repo.get())
        assertEquals(changed, repo.settings.first())
        repo.update { it.copy(maxSpendPerDayMicros = null, usageRetentionDays = 0) }
        assertNull(repo.get().maxSpendPerDayMicros, "no money limit is stored as no limit")
        assertEquals(0, repo.get().usageRetentionDays)
        assertEquals(changed.copy(maxSpendPerDayMicros = null, usageRetentionDays = 0), repo.settings.first())
    }
}
