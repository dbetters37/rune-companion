package com.aicontrolcenter.providers.http

import com.aicontrolcenter.providers.json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a model's tool arguments become: an object, or empty arguments with a reason for the model. */
class ToolArgumentsTest {
    private fun reasonFor(raw: String): String = assertNotNull(ToolArguments.fromText(raw).error, raw)

    @Test
    fun `objects are read, and nothing at all means no arguments`() {
        val nested = """{"n": -0.5, "big": 1E5, "flag": true, "none": null, "list": [1, "two", {"deep": false}]}"""
        assertEquals(json(nested).jsonObject, ToolArguments.fromText(nested).arguments)
        assertNull(ToolArguments.fromText(nested).error)
        for (empty in listOf("", "  ", "null")) {
            assertTrue(ToolArguments.fromText(empty).arguments.isEmpty())
            assertNull(ToolArguments.fromText(empty).error)
        }
        assertEquals(json("""{"city":"Oslo"}""").jsonObject, ToolArguments.fromText(""""{\"city\":\"Oslo\"}"""").arguments, "encoded twice")
        assertNull(ToolArguments.fromElement(null).error)
    }

    @Test
    fun `text that is not a json object gets a reason the model can act on`() {
        assertTrue("not valid JSON" in reasonFor("""{"city": "Oslo",}"""))
        assertTrue("not valid JSON" in reasonFor("""{'city': 'Oslo'}"""))
        assertTrue("one JSON object" in reasonFor("""["Oslo"]"""))
        assertTrue("one JSON object" in reasonFor("42"))
        assertTrue("one JSON object" in reasonFor("\"Oslo\""))
        assertTrue("one JSON object" in assertNotNull(ToolArguments.fromElement(JsonPrimitive(3)).error))
    }

    @Test
    fun `an unquoted value that the lenient parser accepts is still not json`() {
        val reason = reasonFor("""{"city": Oslo}""")
        assertTrue("Oslo is not in quotes" in reason, reason)
        assertTrue("is not in quotes" in reasonFor("""{"a": [1, {"b": NaN}]}"""))
        assertTrue(ToolArguments.fromText("""{"city": Oslo}""").arguments.isEmpty(), "nothing half-read is passed on")
    }

    @Test
    fun `short arguments are quoted back and long ones only measured`() {
        val short = """{"city": "Oslo"""
        assertTrue(short in reasonFor(short))
        val long = """{"text": """" + "y".repeat(ToolArguments.MAX_QUOTED_CHARS + 50)
        val reason = reasonFor(long)
        assertFalse("yyyyyyyy" in reason, reason)
        assertTrue("${long.length} characters" in reason, reason)
    }

    @Test
    fun `deeply nested input does not exhaust the stack`() {
        val deep = "{\"a\":" + "[".repeat(5_000) + "]".repeat(5_000) + "}"
        // Either outcome is fine, as long as it is an answer and not a crash.
        ToolArguments.fromText(deep)
    }
}
