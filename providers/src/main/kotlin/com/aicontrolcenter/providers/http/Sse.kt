package com.aicontrolcenter.providers.http

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One server-sent event: the optional `event:` name and the joined `data:` lines. */
internal data class SseEvent(val event: String?, val data: String)

/**
 * Assembles the lines of a `text/event-stream` body into events, following the parts of the
 * specification that model APIs use: `data:` lines accumulate (joined by a newline), a blank
 * line dispatches, lines starting with a colon are comments (OpenRouter sends them as
 * keep-alives), and unknown fields are ignored. An event still pending when the stream ends is
 * delivered, because several servers omit the final blank line.
 */
internal fun Flow<String>.sseEvents(): Flow<SseEvent> = flow {
    val data = StringBuilder()
    var hasData = false
    var event: String? = null
    collect { line ->
        when {
            line.isEmpty() -> {
                if (hasData) emit(SseEvent(event, data.toString()))
                data.setLength(0)
                hasData = false
                event = null
            }
            line.startsWith(":") -> Unit
            else -> {
                val colon = line.indexOf(':')
                val field = if (colon == -1) line else line.substring(0, colon)
                val value = if (colon == -1) "" else line.substring(colon + 1).removePrefix(" ")
                when (field) {
                    "data" -> {
                        if (hasData) data.append('\n')
                        data.append(value)
                        hasData = true
                    }
                    "event" -> event = value
                }
            }
        }
    }
    if (hasData) emit(SseEvent(event, data.toString()))
}
