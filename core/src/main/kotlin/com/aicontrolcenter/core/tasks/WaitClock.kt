package com.aicontrolcenter.core.tasks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The running time of one time limit set with [withPausableTimeout], minus the time spent
 * waiting for the owner ([pausedWhile]). A coroutine-context element: work inside a limit
 * inherits it, and a pause inside nested limits (a tool call inside an agent run inside an
 * objective) pauses every one of them.
 */
class WaitClock internal constructor(
    private val parent: WaitClock?,
    private val timeSource: TimeSource,
) : AbstractCoroutineContextElement(Key) {
    private val lock = Any()
    private var depth = 0
    private var pauseStart: TimeMark? = null
    private var pausedTotalMs = 0L
    private var pauseCount = 0L
    internal val pausedNow = MutableStateFlow(false)

    /** Time spent paused so far, including a pause in progress (ms). */
    fun pausedMs(): Long = synchronized(lock) { pausedTotalMs + (pauseStart?.elapsedNow()?.inWholeMilliseconds ?: 0) }

    /** True while some work under this limit waits for the owner. */
    val isPaused: Boolean get() = pausedNow.value

    internal fun pausesStarted(): Long = synchronized(lock) { pauseCount }

    internal fun begin() {
        synchronized(lock) {
            if (depth++ == 0) {
                pauseStart = timeSource.markNow()
                pauseCount++
                pausedNow.value = true
            }
        }
        parent?.begin()
    }

    internal fun end() {
        synchronized(lock) {
            if (--depth == 0) {
                pausedTotalMs += pauseStart?.elapsedNow()?.inWholeMilliseconds ?: 0
                pauseStart = null
                pausedNow.value = false
            }
        }
        parent?.end()
    }

    /** Returns when [budgetMs] of unpaused time has passed since [start]. */
    internal suspend fun runDown(budgetMs: Long, start: TimeMark) {
        while (true) {
            pausedNow.first { !it }
            val left = budgetMs - (start.elapsedNow().inWholeMilliseconds - pausedMs()).coerceAtLeast(0)
            if (left <= 0) return
            val pausesBefore = pausesStarted()
            val paused = withTimeoutOrNull(left) { pausedNow.first { it } } != null
            // A full `left` ran with no pause at all: the budget is used up, whatever the time source says.
            if (!paused && pausesStarted() == pausesBefore) return
        }
    }

    /** Key and entry points. */
    companion object Key : CoroutineContext.Key<WaitClock> {
        /**
         * Runs [block] as time the enclosing pausable limits do not count: the permission broker
         * wraps its wait for the owner in this. Outside any such limit it just runs [block].
         */
        suspend fun <T> pausedWhile(block: suspend () -> T): T {
            val clock = currentCoroutineContext()[Key] ?: return block()
            clock.begin()
            try {
                return block()
            } finally {
                clock.end()
            }
        }

        /** The innermost limit's clock, or null outside any. */
        suspend fun current(): WaitClock? = currentCoroutineContext()[Key]
    }
}

/** Cancels the work of a [withPausableTimeout] that ran out. */
private class PausableTimeout : CancellationException("Time limit reached")

/**
 * Like `withTimeoutOrNull(timeoutMs)`, except that time inside [WaitClock.pausedWhile] does not
 * count: [block] is cancelled and null returned only once [timeoutMs] of unpaused time has
 * passed. Use it for task, objective, run and planning limits instead of a fixed timeout.
 * [timeSource] measures pauses; tests pass the test scheduler's (`testTimeSource`). With a
 * time source that does not match the dispatcher's delays the limit can only grow, never shrink.
 */
suspend fun <T> withPausableTimeout(
    timeoutMs: Long,
    timeSource: TimeSource = TimeSource.Monotonic,
    block: suspend CoroutineScope.() -> T,
): T? {
    if (timeoutMs <= 0) return null
    val clock = WaitClock(currentCoroutineContext()[WaitClock], timeSource)
    val start = timeSource.markNow()
    return coroutineScope {
        val work = async(clock) { block() }
        val timer = launch {
            clock.runDown(timeoutMs, start)
            work.cancel(PausableTimeout())
        }
        try {
            work.await()
        } catch (e: PausableTimeout) {
            null
        } finally {
            timer.cancel()
        }
    }
}
