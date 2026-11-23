package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryValidationException
import java.util.Locale

/**
 * The limits every memory obeys, checked before anything is stored. Messages name the field and
 * the limit, never the offending text: it may be the owner's private data.
 */
internal object MemoryRules {
    const val MAX_TITLE = 200
    const val MAX_CONTENT = 20_000
    const val MAX_TAGS = 20
    const val MAX_TAG = 40
    const val MAX_SPACE_NAME = 80
    const val MAX_SPACE_DESCRIPTION = 1_000

    fun title(value: String): String {
        val t = value.trim()
        if (t.isEmpty()) fail("A memory needs a title.")
        if (t.length > MAX_TITLE) fail("A memory title may be at most $MAX_TITLE characters (this one has ${t.length}).")
        return t
    }

    fun content(value: String): String {
        val c = value.trim()
        if (c.isEmpty()) fail("A memory needs content.")
        if (c.length > MAX_CONTENT) fail("Memory content may be at most $MAX_CONTENT characters (this has ${c.length}).")
        return c
    }

    /** Trims and lower-cases tags and drops blank ones and duplicates. */
    fun tags(value: Set<String>): Set<String> {
        val cleaned = value.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
        if (cleaned.size > MAX_TAGS) fail("A memory may have at most $MAX_TAGS tags (this one has ${cleaned.size}).")
        cleaned.firstOrNull { it.length > MAX_TAG }?.let { fail("A tag may be at most $MAX_TAG characters (one has ${it.length}).") }
        return cleaned
    }

    fun unit(name: String, value: Double): Double {
        if (!value.isFinite() || value < 0.0 || value > 1.0) fail("$name must be between 0 and 1.")
        return value
    }

    fun ttl(value: Long?): Long? {
        if (value != null && value <= 0) fail("A short-term memory's lifetime must be positive.")
        return value
    }

    fun spaceName(value: String): String {
        val n = value.trim()
        if (n.isEmpty()) fail("A knowledge space needs a name.")
        if (n.length > MAX_SPACE_NAME) fail("A knowledge space name may be at most $MAX_SPACE_NAME characters.")
        return n
    }

    fun spaceDescription(value: String): String {
        val d = value.trim()
        if (d.length > MAX_SPACE_DESCRIPTION) fail("A knowledge space description may be at most $MAX_SPACE_DESCRIPTION characters.")
        return d
    }

    fun fail(message: String): Nothing = throw MemoryValidationException(message)
}
