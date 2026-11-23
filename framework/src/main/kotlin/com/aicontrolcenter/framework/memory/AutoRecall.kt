package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.framework.llm.RecalledMemoriesBlock
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Automatic recall: before an agent's first model call of a turn, finds the stored memories
 * that relate to the newest owner or task message and writes them as a data block, which
 * [com.aicontrolcenter.framework.agents.AgentRunner] puts in front of the text of that message in
 * the request it sends (never into the system message, and never into the stored conversation).
 *
 * Decisions worth knowing:
 *  - Only agents whose memory policy has auto-recall on, with a recall limit above zero, are
 *    looked up for, as the agent is stored at the start of the turn: the recall runs as that
 *    agent, so it sees only what its policy lets it read.
 *  - Recall helps a turn but is never needed for it. A failure or a slow memory store is
 *    logged as a warning and the turn goes ahead without memories; it never fails the turn.
 *  - The time-out holds even when the memory store does not stop when it is cancelled (a long
 *    computation that never suspends): the lookup runs in its own coroutine in [lookupContext]
 *    and the turn stops waiting for it at the time-out. While a lookup given up on is still
 *    running, no new one is started for the same agent, so a store that is too slow does not
 *    pile up work turn after turn.
 *  - The block is framed by [RecalledMemoriesBlock] tags, its memories are neutralised
 *    ([MemoryText]) so none can close it, forge an entry or pass for a heading, and it is capped
 *    in size: memories are written by many hands, and a long one must not crowd the conversation
 *    out of the model's context. A memory that does not fit is left out; the agent can still search.
 *  - One MEMORY activity event per turn that used memories, with their count and titles only.
 *    Content never goes into the log.
 */
class AutoRecall(
    private val memory: MemoryService,
    private val activity: ActivityRecorder,
    /** Decides the calendar days shown for memories; read on every turn. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * Where the lookup runs. Off the caller's thread by default, so the time-out can end the
     * wait whatever the memory store does; [kotlin.coroutines.EmptyCoroutineContext] runs it on
     * the caller's dispatcher (tests in virtual time).
     */
    private val lookupContext: CoroutineContext = Dispatchers.Default,
) {
    /**
     * What was recalled for one turn: [section], the framed block to put in front of the newest
     * owner or task message (see [RecalledMemoriesBlock]), and the memories in it.
     */
    class Recalled(val section: String, val memories: List<MemoryRecord>)

    /** Lookups given up on at the time-out that may still be running, by agent. */
    private val abandoned = ConcurrentHashMap<AgentId, Deferred<Recalled?>>()

    /**
     * Memories for [agent]'s turn on [messages], or null when there are none to add: auto-recall
     * is off, there is no owner or task message with text, nothing matched, or recall failed.
     * Throws only when the caller is cancelled.
     */
    suspend fun recall(agent: AgentProfile, messages: List<ChatMessage>, taskId: TaskId? = null, rootTaskId: TaskId? = null): Recalled? {
        val policy = agent.memory
        if (!policy.autoRecall || policy.recallLimit <= 0) return null
        val query = messages.lastOrNull { it.role == ChatRole.USER }?.text?.trim()?.take(MAX_QUERY_CHARS)
        if (query.isNullOrEmpty()) return null
        // A cancelled lookup is no longer active but may still be computing until it completes.
        if (abandoned[agent.id]?.isCompleted == false) {
            return warn(agent, taskId, rootTaskId, "the memory store is still busy with an earlier lookup that took too long")
        }
        // Not a child of the turn: a lookup that ignores cancellation must not hold the turn up when it is given up on.
        val lookup = CoroutineScope(currentCoroutineContext().minusKey(Job) + lookupContext + Job()).async {
            lookUp(agent, query, policy.recallLimit)
        }
        var settled = false
        val recalled = try {
            // Wrapped, so that "nothing matched" (null inside) is not mistaken for the time-out (null outside).
            val looked = withTimeoutOrNull(timeoutMs) { Lookup(lookup.await()) }
            settled = looked != null
            if (looked == null) {
                abandon(agent.id, lookup)
                val limit = if (timeoutMs % 1000 == 0L) "${timeoutMs / 1000} seconds" else "$timeoutMs milliseconds"
                return warn(agent, taskId, rootTaskId, "the memory store took longer than $limit")
            }
            looked.recalled ?: return null
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            return warn(agent, taskId, rootTaskId, "of an error in the memory store (${e::class.simpleName})")
        } finally {
            if (!settled) lookup.cancel()
        }
        log(agent, taskId, rootTaskId, recalled.memories)
        return recalled
    }

    private class Lookup(val recalled: Recalled?)

    private fun abandon(agentId: AgentId, lookup: Deferred<Recalled?>) {
        lookup.cancel()
        if (lookup.isCompleted) return
        abandoned[agentId] = lookup
        lookup.invokeOnCompletion { abandoned.remove(agentId, lookup) }
    }

    private suspend fun lookUp(agent: AgentProfile, query: String, limit: Int): Recalled? {
        val hits = memory.recall(MemoryActor.Agent(agent), RecallQuery(text = query, limit = limit))
        if (hits.isEmpty()) return null
        val names = memory.observeSpaces().first().associate { it.id to it.name }
        // Best first, as many as fit the budget; the first always does, cut to the per-memory cap.
        val kept = ArrayList<MemoryRecord>()
        var used = 0
        for (hit in hits.take(limit)) {
            val size = minOf(hit.record.content.length, MAX_CONTENT_CHARS) + hit.record.title.length
            if (kept.isNotEmpty() && used + size > MAX_SECTION_CHARS) break
            kept += hit.record
            used += size
        }
        val section = RecalledMemoriesBlock.frame(MemoryText.entries(kept, agent.id, names, zone(), MAX_CONTENT_CHARS))
        return Recalled(section, kept)
    }

    private suspend fun log(agent: AgentProfile, taskId: TaskId?, rootTaskId: TaskId?, memories: List<MemoryRecord>) {
        val count = if (memories.size == 1) "1 stored memory" else "${memories.size} stored memories"
        val titles = memories.joinToString(", ") { "\"${MemoryText.safeTitle(it.title)}\"" }
        val detail = "${agent.name} was given $count for this turn: $titles".let {
            if (it.length <= MAX_DETAIL_CHARS) "$it." else it.take(MAX_DETAIL_CHARS - 1).trimEnd() + "…"
        }
        quietly {
            activity.record(
                ActivityType.MEMORY, "Memories recalled", detail, agentId = agent.id, taskId = taskId, rootTaskId = rootTaskId,
                data = buildJsonObject {
                    put("count", memories.size)
                    put("memoryIds", memories.joinToString(",") { it.id.value })
                },
            )
        }
    }

    private suspend fun warn(agent: AgentProfile, taskId: TaskId?, rootTaskId: TaskId?, why: String): Recalled? {
        quietly {
            activity.record(
                ActivityType.MEMORY, "Memory recall failed",
                "${agent.name} went ahead without recalled memories because $why.",
                severity = Severity.WARNING, agentId = agent.id, taskId = taskId, rootTaskId = rootTaskId,
            )
        }
        return null
    }

    /** A log entry that cannot be written must not change the turn. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            rethrowIfCancelled(e)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Each memory's content in the prompt is cut to this. */
        const val MAX_CONTENT_CHARS = 1_000

        /** Roughly 1,500 tokens for the whole block, whatever the recall limit. */
        const val MAX_SECTION_CHARS = 6_000
        private const val MAX_QUERY_CHARS = 1_000
        private const val MAX_DETAIL_CHARS = 400
    }
}
