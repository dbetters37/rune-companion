package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Work that waits to be tried again (Phase 6, R4 part 1, D28, rule 5): a step whose model call
 * failed in a way that passes (a provider not answering, a "slow down" answer), or whose agent
 * cannot run only because its provider is not answering, is stored with a time to try again
 * ([com.aicontrolcenter.core.tasks.TaskRecord.retryAt]) instead of failing. The objective's own
 * time limit bounds how long it waits.
 *
 * [nextDueAt] is the earliest such time over every running objective, for the background service
 * (keep the phone awake within two minutes of it, set a wake alarm for a later one). [wake] makes
 * every waiter look at the wall clock again at once: a wait whose time has come goes on, another
 * one keeps waiting. Waiters also wake when their own delay is over, and then look at the clock
 * too, so a delay that ran late (the phone slept) or early (the clock was changed) never loses a
 * wait or starts one too soon by more than [MIN_LOOK_MS].
 */
internal class TaskWaits(private val runtimes: Runtimes, private val clock: Clock) : TimedWaits {
    private val due = MutableStateFlow<Long?>(null)
    private val wakes = MutableStateFlow(0L)

    override val nextDueAt: StateFlow<Long?> = due.asStateFlow()

    override fun wake() {
        wakes.update { it + 1 }
        runtimes.pokeAll()
    }

    /** Publishes the earliest wait of every objective; called whenever one starts, ends or moves. */
    fun publish() {
        due.value = runtimes.nextDueAt()
    }

    /**
     * Suspends until [at] by the wall clock, published as a wait of [rt] under [key] meanwhile: for
     * planning, a synthesis and delegated work, which wait in their own step (the scheduler waits
     * for planned tasks itself). Cancelling the caller ends the wait.
     */
    suspend fun until(rt: ObjectiveRuntime, key: TaskId, at: Long) {
        rt.dueAt[key] = at
        publish()
        try {
            while (true) {
                val left = at - clock.now()
                if (left <= 0) return
                val seen = wakes.value
                withTimeoutOrNull(left.coerceAtLeast(MIN_LOOK_MS)) { wakes.first { it != seen } }
            }
        } finally {
            rt.dueAt.remove(key)
            publish()
        }
    }

    /** How long a delay may run before the scheduler looks at the clock again: [until]'s rule, for the scheduler's own wait. */
    fun lookAfter(at: Long): Long = (at - clock.now()).coerceAtLeast(MIN_LOOK_MS)

    companion object {
        /** A wait never looks at the clock more often than this, whatever the clock says. */
        const val MIN_LOOK_MS = 1_000L
    }
}

/** What made a step end without its result, when it is not an ordinary failure (Phase 6). */
internal sealed interface Trouble {
    /** Today's limit of model answers, tokens or money is reached ([LlmException.DailyLimitReached]): the objective pauses. */
    data class DailyLimit(val sentence: String) : Trouble

    /** The provider failed in a way that passes ([LlmException.retryable]): the step waits and is tried again. */
    data class Passing(val error: LlmException) : Trouble

    companion object {
        fun of(result: StepResult): Trouble? = when (val error = result.error) {
            null -> null
            is LlmException.DailyLimitReached -> DailyLimit(error.message?.takeIf { it.isNotBlank() } ?: DailyLimits.PAUSED_TEXT)
            else -> if (error.retryable) Passing(error) else null
        }
    }
}

/**
 * Providers that asked the app to slow down (INT-1), shared by every objective: work on such a provider waits together
 * until the time its "slow down" answer set ([heldUntil]), instead of each step probing it with requests of its own.
 * Each such answer after the provider's last wait is a strike. A provider that asks again after its wait has used up
 * its quota for now (a free tier's daily quota answers "slow down" all day) when it said nothing about when to try
 * again, or when it did and asks a fourth time ([ProviderWaits.MAX_WAITS_AFTER_FAILURE]): [slowedDown] then returns
 * false, the step counts the answer as an ordinary failed attempt, and so does every step that gets one until the
 * provider answers or [REPEAT_WINDOW_MS] passes without such an answer. In memory: a restart starts afresh.
 */
internal class ProviderSlowDowns {
    private class SlowDown(val until: Long, val error: LlmException, val lastAt: Long, val strikes: Int)

    private val states = ConcurrentHashMap<ProviderId, SlowDown>()

    /** Until when work on [provider] waits, with the answer that asked for it; null when it does not wait (any more). */
    fun heldUntil(provider: ProviderId?, now: Long): Pair<Long, LlmException>? {
        val state = states[provider ?: return null] ?: return null
        return if (state.until > now) state.until to state.error else null
    }

    /**
     * [provider] answered "slow down" ([error]) at [now], and the step would wait until [waitUntil] if [mayWait]. True
     * when it waits, and then every step on that provider waits until then too; false when it may not, or the provider
     * has used up its quota for now.
     */
    @Synchronized
    fun slowedDown(provider: ProviderId?, error: LlmException.RateLimited, now: Long, waitUntil: Long, mayWait: Boolean): Boolean {
        val id = provider ?: return mayWait
        val before = states[id]?.takeIf { now - maxOf(it.lastAt, it.until) <= REPEAT_WINDOW_MS }
        // The same answer reached several steps whose calls were already on their way: they wait together.
        if (before != null && before.until > now) {
            if (mayWait) states[id] = SlowDown(maxOf(before.until, waitUntil), error, now, before.strikes)
            return mayWait
        }
        val strikes = (before?.strikes ?: 0) + 1
        val allowed = if (error.retryAfterMs != null) ProviderWaits.MAX_WAITS_AFTER_FAILURE else 1
        val waits = mayWait && strikes <= allowed
        states[id] = SlowDown(if (waits) waitUntil else now, error, now, strikes)
        return waits
    }

    /** [provider] answered: work on it no longer waits, and a later "slow down" starts afresh. */
    fun answered(provider: ProviderId?) {
        states.remove(provider ?: return)
    }

    companion object {
        /** After this long without another "slow down" answer, the next one starts afresh. */
        const val REPEAT_WINDOW_MS = 30 * 60_000L
    }
}

/** How long work waits for a provider, and what its status line says meanwhile. */
internal object ProviderWaits {
    /** The first wait of a task in a row. */
    const val FIRST_WAIT_MS = 30_000L

    /** Every later wait of the same task in a row. */
    const val LATER_WAIT_MS = 2 * 60_000L

    /** A provider's own "try again after" is honoured up to this; a step does not wait for a longer one (INT-1). */
    const val MAX_WAIT_MS = 10 * 60_000L

    /**
     * A step whose model call failed in a way that passes waits at most this many times in a row (INT-1); the next such
     * failure counts as an ordinary failed attempt, so the attempt limit ends a step whose provider stays down.
     */
    const val MAX_WAITS_AFTER_FAILURE = 3

    /** A provider whose status is older than this is checked again before work waits for it (D28). */
    const val STALE_MS = 60_000L

    /** How long to wait after [earlier] waits in a row, honouring what a "slow down" answer asked for. */
    fun waitMs(earlier: Int, error: LlmException?): Long {
        val base = if (earlier <= 0) FIRST_WAIT_MS else LATER_WAIT_MS
        val asked = (error as? LlmException.RateLimited)?.retryAfterMs ?: 0
        return maxOf(base, asked.coerceAtMost(MAX_WAIT_MS))
    }

    /** "Ollama on your network", "Groq": a provider as the owner knows it. */
    fun named(provider: Readiness.ProviderSide?, fallback: String): String = when (provider?.locality) {
        null -> fallback
        Locality.ON_DEVICE -> "${provider.name} on this phone"
        Locality.LOCAL_NETWORK -> "${provider.name} on your network"
        Locality.CLOUD -> provider.name
    }

    /** "Waiting: xAI is not answering; trying again at 10:45." */
    fun text(provider: String, error: LlmException?, at: Long, zone: ZoneId): String =
        "$WAITING ${what(provider, error)}; trying again at ${CLOCK.format(Instant.ofEpochMilli(at).atZone(zone))}."

    /** "xAI is not answering", "Gemini asked the app to slow down", "Groq is busy". */
    fun what(provider: String, error: LlmException?): String = when (error) {
        is LlmException.RateLimited -> "$provider asked the app to slow down"
        is LlmException.Overloaded -> "$provider is busy"
        else -> "$provider is not answering"
    }

    /** Opens the status line of work waiting for its provider. */
    const val WAITING = "Waiting:"

    /** What [detail], the status line of work waiting for its provider ([text]), says it waits for; null for any other line. */
    fun waitingFor(detail: String?): String? =
        detail?.takeIf { it.startsWith(WAITING) }?.removePrefix(WAITING)?.trim()?.substringBefore("; trying again")?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    /**
     * The status line of [task], waiting for its provider when its objective stopped (paused, by the owner or by today's
     * limit, or interrupted, INT-3): nothing tries again at the time it named until the objective resumes. Null when
     * [task] is not such work.
     */
    fun stopped(task: TaskRecord): String? =
        if (task.status == TaskStatus.QUEUED && task.retryAt != null) waitingFor(task.statusDetail)?.let { "Was waiting: $it. Tries again when the objective resumes." } else null

    /** [tree]'s work that waited for its provider, its status line rewritten as [stopped] says, at [now]. */
    fun stoppedIn(tree: List<TaskRecord>, rootId: TaskId, now: Long): List<TaskRecord> =
        tree.filter { it.id != rootId }.mapNotNull { task -> stopped(task)?.let { task.copy(statusDetail = it, updatedAt = now) } }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
