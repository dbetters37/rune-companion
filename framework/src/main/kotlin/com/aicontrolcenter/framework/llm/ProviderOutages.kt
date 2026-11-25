package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Notices a provider that stopped answering (Phase 6, D28): [THRESHOLD] calls in a row that ended with a failure of
 * the kind that passes by itself (no connection, no answer in time, a server error, "too busy") within [WINDOW_MS]
 * mean it is not answering, and the gateway stores it as OFFLINE with "Not answering since 10:42". Agents then wait
 * for it (rule 25) and the re-checks look again ([DefaultProviderRechecks]). An answer clears the count.
 *
 * "Slow down" (429) is not counted: the provider answers, it only asks to wait, and the caller waits for it.
 * Neither is anything the owner has to fix (a rejected key, a wrong model): those are reported as they are.
 */
internal class ProviderOutages(private val window: Long = WINDOW_MS, private val threshold: Int = THRESHOLD) {
    private val lock = Mutex()
    private val failures = HashMap<ProviderId, ArrayDeque<Long>>()

    /**
     * Notes a call to [id] that ended with [error] at [at]. Returns the time of the first failure of the run when
     * this one makes [threshold] within [window], so the provider should be marked as not answering since then;
     * null otherwise.
     */
    suspend fun failed(id: ProviderId, error: LlmException, at: Long): Long? {
        if (!counts(error)) return null
        return lock.withLock {
            val times = failures.getOrPut(id) { ArrayDeque() }
            times.addLast(at)
            while (times.isNotEmpty() && at - times.first() > window) times.removeFirst()
            if (times.size >= threshold) times.first() else null
        }
    }

    /** [id] answered: its run of failures is over. */
    suspend fun answered(id: ProviderId) {
        lock.withLock { failures.remove(id) }
    }

    /** Building the sentence. */
    companion object {
        const val THRESHOLD: Int = 3
        const val WINDOW_MS: Long = 5 * 60_000L

        /** Failures that pass by themselves and say the provider is not answering. */
        fun counts(error: LlmException): Boolean = when (error) {
            is LlmException.Network, is LlmException.Timeout, is LlmException.Server, is LlmException.Overloaded -> true
            else -> false
        }

        /** "Not answering since 10:42", in the owner's zone. */
        fun sentence(since: Long, zone: ZoneId): String =
            "Not answering since " + DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(since).atZone(zone))
    }
}
