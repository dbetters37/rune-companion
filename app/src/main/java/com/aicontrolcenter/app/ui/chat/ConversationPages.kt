package com.aicontrolcenter.app.ui.chat

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.llm.ChatRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A conversation's stored entries read in parts (Phase 6, P5 and D31): the chat shows the newest
 * [PAGE] entries and reads earlier ones only when the owner asks ("Show earlier"). Read only: it
 * can neither add nor remove an entry.
 */
interface ConversationPages {
    /** The newest entry's number, or null while there is none; again whenever it changes. */
    fun observeLatestSeq(id: ConversationId): Flow<Int?>

    /** The entries after number [seq], in order. */
    suspend fun entriesAfter(id: ConversationId, seq: Int): List<ConversationEntry>

    /** The [limit] newest entries before number [seq], in order. */
    suspend fun entriesBefore(id: ConversationId, seq: Int, limit: Int): List<ConversationEntry>

    companion object {
        /** How many entries the chat shows at first, and how many more each "Show earlier" adds. */
        const val PAGE: Int = 50

        /** Over the store, which answers each part with one query on the entries' numbers (Room). */
        fun of(store: ConversationRepository): ConversationPages = object : ConversationPages {
            override fun observeLatestSeq(id: ConversationId) = store.observeLatestSeq(id)

            override suspend fun entriesAfter(id: ConversationId, seq: Int) = store.entriesAfter(id, seq)

            override suspend fun entriesBefore(id: ConversationId, seq: Int, limit: Int) = store.entriesBefore(id, seq, limit)
        }

        /** Over the chat's own transcript, for callers that have no store at hand (tests); it reads every entry. */
        fun of(chat: AgentChat): ConversationPages = object : ConversationPages {
            override fun observeLatestSeq(id: ConversationId) = chat.observeEntries(id).map { it.lastOrNull()?.seq }.distinctUntilChanged()

            override suspend fun entriesAfter(id: ConversationId, seq: Int) = chat.observeEntries(id).first().filter { it.seq > seq }

            override suspend fun entriesBefore(id: ConversationId, seq: Int, limit: Int) =
                chat.observeEntries(id).first().filter { it.seq < seq }.takeLast(limit.coerceAtLeast(0))
        }
    }
}

/** The part of a conversation the chat shows: [entries] in order, and whether older ones exist. */
data class EntryWindow(val entries: List<ConversationEntry>, val hasEarlier: Boolean) {
    companion object {
        val EMPTY = EntryWindow(emptyList(), hasEarlier = false)
    }
}

/**
 * Reads what the chat shows (P5): with [from] null, the newest [ConversationPages.PAGE] entries,
 * moved back to the start of the turn the first of them belongs to (by at most one more page), so
 * a reply is shown with the owner's message that asked for it and its whole cost; with [from], every
 * entry from that number on. Tool results whose call lies before the part are left out (their call
 * comes back with "Show earlier").
 */
internal suspend fun ConversationPages.window(id: ConversationId, latest: Int?, from: Int?): EntryWindow {
    if (latest == null) return EntryWindow.EMPTY
    val entries = if (from == null) toTurnStart(id, entriesBefore(id, latest + 1, ConversationPages.PAGE)) else entriesAfter(id, from - 1)
    val first = entries.firstOrNull() ?: return EntryWindow.EMPTY
    val hasEarlier = entriesBefore(id, first.seq, 1).isNotEmpty()
    return EntryWindow(if (hasEarlier) entries.dropWhile { it.message.role == ChatRole.TOOL } else entries, hasEarlier)
}

/**
 * [entries] with the entries before them back to the owner's message that started the first one's turn,
 * looking back at most one page; unchanged when they already start with it or it is further back.
 */
internal suspend fun ConversationPages.toTurnStart(id: ConversationId, entries: List<ConversationEntry>): List<ConversationEntry> {
    val first = entries.firstOrNull() ?: return entries
    if (first.startsTurn) return entries
    val before = entriesBefore(id, first.seq, ConversationPages.PAGE)
    val start = before.indexOfLast { it.startsTurn }
    return if (start >= 0) before.subList(start, before.size) + entries else entries
}

private val ConversationEntry.startsTurn: Boolean get() = error == null && message.role == ChatRole.USER
