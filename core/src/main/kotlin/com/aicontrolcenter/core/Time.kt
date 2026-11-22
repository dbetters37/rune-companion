package com.aicontrolcenter.core

/** Wall-clock time in epoch milliseconds. Injected so tests control time. */
fun interface Clock {
    fun now(): Long
}

object WallClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
