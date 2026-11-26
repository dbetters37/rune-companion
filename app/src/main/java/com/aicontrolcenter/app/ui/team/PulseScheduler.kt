package com.aicontrolcenter.app.ui.team

/**
 * When the dots of the Team view (and the Dashboard's network card) travel (rule 17; N2): a dot travels **once**, when its
 * message is new to the screen, from the sender to the recipient in [travelMs]; at most [maxInFlight] at a time, later
 * ones wait their turn for at most [maxDelayMs] and are then dropped (only counted). What was there when the screen
 * opened is history and never travels: the first [offer] only records it.
 *
 * Times are the screen's own frame clock in milliseconds (any monotonic timeline), never the app's clock: a dot's moment
 * is when the screen learns of the message. Pure and single-threaded (the composition's), so it is tested on the JVM.
 */
class PulseScheduler(
    val maxInFlight: Int = MAX_IN_FLIGHT,
    val travelMs: Long = TRAVEL_MS,
    val maxDelayMs: Long = MAX_DELAY_MS,
) {
    /** One dot: [pulse], leaving its sender at [startAt]. */
    data class Flight(val pulse: TeamPulse, val startAt: Long)

    private val seen = LinkedHashSet<String>()
    private val flights = ArrayList<Flight>()
    private var started = false

    /** Dots that could not leave within [maxDelayMs] and were let go. */
    var dropped: Int = 0
        private set

    /**
     * Takes the screen's newest messages ([pulses], oldest first) at [at] and sends a dot for each one not seen before.
     * Returns how many new dots were sent (0 on the first call, which only records what is already there).
     */
    fun offer(pulses: List<TeamPulse>, at: Long): Int {
        prune(at)
        val fresh = pulses.filter { it.key !in seen }
        fresh.forEach { remember(it.key) }
        if (!started) {
            started = true
            return 0
        }
        var sent = 0
        for (pulse in fresh) {
            val start = slotFor(at)
            if (start == null) {
                dropped++
            } else {
                flights += Flight(pulse, start)
                sent++
            }
        }
        return sent
    }

    /**
     * Marks [pulses] as seen without sending anything: Calm (the owner's setting or Android's animations off), so a
     * message that arrives under Calm never travels later when Calm is switched off.
     */
    fun skip(pulses: List<TeamPulse>) {
        started = true
        pulses.forEach { remember(it.key) }
        // A dot under way when Calm came on is not drawn frozen half-way: it is gone.
        flights.clear()
    }

    /** The dots in the air at [at], each with how far it has come (0 at the sender, 1 at the recipient). */
    fun positions(at: Long): List<Pair<Flight, Float>> = flights.mapNotNull { flight ->
        val t = (at - flight.startAt).toFloat() / travelMs
        if (t < 0f || t > 1f) null else flight to t
    }

    /** Whether any dot is in the air or waiting to leave: the frame loop runs only while this is true. */
    fun isMoving(at: Long): Boolean = flights.any { at <= it.startAt + travelMs }

    /** Dots in the air or waiting to leave at [at]. */
    fun pending(at: Long): Int = flights.count { at <= it.startAt + travelMs }

    /** Forgets dots that have arrived. */
    fun prune(at: Long) {
        flights.removeAll { at > it.startAt + travelMs }
    }

    /**
     * The earliest moment from [at] at which fewer than [maxInFlight] dots are in the air, or null past [maxDelayMs]. Dots
     * leave in the order their messages came (never before one already waiting), so counting at the moment of leaving is
     * enough: no dot already planned leaves after it.
     */
    private fun slotFor(at: Long): Long? {
        val earliest = maxOf(at, flights.maxOfOrNull { it.startAt } ?: at)
        val candidates = (listOf(earliest) + flights.map { it.startAt + travelMs + 1 }.filter { it > earliest }).sorted()
        for (moment in candidates) {
            if (moment - at > maxDelayMs) return null
            val inAir = flights.count { it.startAt <= moment && moment <= it.startAt + travelMs }
            if (inAir < maxInFlight) return moment
        }
        return null
    }

    private fun remember(key: String) {
        seen += key
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
    }

    companion object {
        const val MAX_IN_FLIGHT = 8
        const val TRAVEL_MS = 1_200L
        const val MAX_DELAY_MS = 2_000L

        /** Message ids remembered; far more than the screen ever offers at once. */
        private const val MAX_SEEN = 2_000
    }
}
