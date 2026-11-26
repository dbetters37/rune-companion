package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import kotlin.coroutines.coroutineContext

/**
 * The dots of one picture (the Team view, the Dashboard's network card): which messages travel and where they are
 * (rule 17; N1, N2, N9). One frame loop (`withFrameMillis`) runs only while a dot is in the air, and [frameMs], the one
 * value that changes every frame, is read only inside the canvas's draw lambda, so nothing is composed again per frame.
 */
@Stable
class PulseClock internal constructor() {
    internal val scheduler = PulseScheduler()

    /** The frame time the dots are drawn at. Read it only in the draw phase. */
    internal val frameMs = mutableLongStateOf(0L)

    /**
     * Dots in the air or waiting to leave. It changes only when dots leave or when the last one arrives, never per
     * frame; screen tests read it as "dots in flight" ([DotsInFlight]).
     */
    var inFlight by mutableIntStateOf(0)
        internal set

    /** The dots to draw at [at] (the frame time), each with how far it has come. */
    internal fun positions(at: Long): List<Pair<PulseScheduler.Flight, Float>> = scheduler.positions(at)
}

/**
 * Sends a dot along its link for every message in [pulses] that is new to this picture (nothing for what was there when
 * it opened), while neither [calm] (the owner's Calm or Android's animations off, `LocalCalmMotion`) nor a motion
 * duration scale of 0 says to keep still. [resetKey] (what the picture shows: one objective or all) starts again from a
 * fresh history, so switching objectives sends no burst of old messages.
 */
@Composable
fun rememberPulseClock(pulses: List<TeamPulse>, calm: Boolean, resetKey: Any?): PulseClock {
    val clock = remember(resetKey) { PulseClock() }
    LaunchedEffect(clock, pulses, calm) {
        val still = calm || (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f
        if (still) {
            clock.scheduler.skip(pulses)
            clock.inFlight = 0
            return@LaunchedEffect
        }
        var now = withFrameMillis { it }
        clock.scheduler.offer(pulses, now)
        clock.inFlight = clock.scheduler.pending(now)
        clock.frameMs.longValue = now
        while (clock.scheduler.isMoving(now)) {
            now = withFrameMillis { it }
            clock.frameMs.longValue = now
        }
        clock.scheduler.prune(now)
        clock.inFlight = 0
    }
    return clock
}

/** How many dots are in the air (or about to leave) in a picture: for screen tests (rule 17: none under Calm). */
val DotsInFlight = SemanticsPropertyKey<Int>("DotsInFlight")
var SemanticsPropertyReceiver.dotsInFlight by DotsInFlight
