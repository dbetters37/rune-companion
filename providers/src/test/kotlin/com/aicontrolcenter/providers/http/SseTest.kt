package com.aicontrolcenter.providers.http

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class SseTest {
    private fun events(vararg lines: String): List<SseEvent> = runBlocking { flowOf(*lines).sseEvents().toList() }

    @Test
    fun `a blank line dispatches the data collected so far`() {
        assertEquals(
            listOf(SseEvent(null, "one"), SseEvent(null, "two")),
            events("data: one", "", "data: two", ""),
        )
    }

    @Test
    fun `several data lines form one event joined by newlines`() {
        assertEquals(listOf(SseEvent(null, "{\n\"a\":1\n}")), events("data: {", "data: \"a\":1", "data: }", ""))
    }

    @Test
    fun `only one leading space of the value is dropped`() {
        assertEquals(listOf(SseEvent(null, " padded"), SseEvent(null, "tight")), events("data:  padded", "", "data:tight", ""))
    }

    @Test
    fun `comments and unknown fields are ignored and event names are kept`() {
        assertEquals(
            listOf(SseEvent("delta", "x")),
            events(": OPENROUTER PROCESSING", "", "id: 7", "retry: 100", "event: delta", "data: x", ""),
        )
    }

    @Test
    fun `an event name does not leak into the next event`() {
        assertEquals(listOf(SseEvent("first", "a"), SseEvent(null, "b")), events("event: first", "data: a", "", "data: b", ""))
    }

    @Test
    fun `an event still pending at the end of the stream is delivered`() {
        assertEquals(listOf(SseEvent(null, "last")), events("data: last"))
    }

    @Test
    fun `blank lines without data produce nothing`() {
        assertEquals(emptyList(), events("", "", ": keep-alive", ""))
    }
}
