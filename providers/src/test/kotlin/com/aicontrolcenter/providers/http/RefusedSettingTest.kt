package com.aicontrolcenter.providers.http

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefusedSettingTest {
    private val redactor = Redactor(emptyList())
    private val sent = mapOf(
        "max_tokens" to listOf("max_tokens"),
        "temperature" to listOf("temperature"),
        "stream_options" to listOf("stream_options", "include_usage"),
        "reasoning_effort" to listOf("reasoning_effort"),
    )

    private fun find(status: Int, body: String, settings: Map<String, List<String>> = sent) =
        RefusedSetting.find(HttpFailure(status, ErrorDetail.parse(body, redactor)), settings)

    @Test
    fun `an unsupported parameter named in param is a permanent refusal`() {
        val body = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead.","type":"invalid_request_error","param":"max_tokens","code":"unsupported_parameter"}}"""
        assertEquals(Refusal("max_tokens", permanent = true), find(400, body))
    }

    @Test
    fun `an objection to a value is a refusal for this request only`() {
        val body = """{"error":{"message":"max_tokens is too large: 100000. This model supports at most 16384 completion tokens.","type":"invalid_request_error","param":"max_tokens","code":"invalid_value"}}"""
        assertEquals(Refusal("max_tokens", permanent = false), find(400, body))
    }

    @Test
    fun `a parameter named only in the message is found as a whole word`() {
        assertEquals(Refusal("temperature", permanent = true), find(400, """{"error":{"message":"temperature is not supported for this model"}}"""))
        // "reasoning" inside another identifier is not the setting.
        assertNull(find(400, """{"error":{"message":"reasoning_effort_v2 is invalid"}}"""))
    }

    @Test
    fun `an alias identifies its setting`() {
        assertEquals("stream_options", find(400, """{"error":{"message":"Unrecognized request argument supplied: include_usage"}}""")?.setting)
    }

    @Test
    fun `a nested param path blames the setting it starts with`() {
        assertEquals("stream_options", find(400, """{"error":{"message":"bad","param":"stream_options.include_usage"}}""")?.setting)
    }

    @Test
    fun `a validation list blames the field in loc`() {
        val body = """{"object":"error","message":{"detail":[{"type":"extra_forbidden","loc":["body","stream_options"],"msg":"Extra inputs are not permitted","input":{"include_usage":true,"note":"temperature"}}]},"type":"invalid_request_error"}"""
        assertEquals(Refusal("stream_options", permanent = true), find(422, body))
    }

    @Test
    fun `the explicit param wins over other settings quoted in the message`() {
        val body = """{"error":{"message":"temperature and max_tokens were given but reasoning_effort is not supported","param":"reasoning_effort"}}"""
        assertEquals("reasoning_effort", find(400, body)?.setting)
    }

    @Test
    fun `only settings that were sent can be refused`() {
        val body = """{"error":{"message":"Unsupported parameter: 'max_tokens'","param":"max_tokens"}}"""
        assertNull(find(400, body, mapOf("temperature" to listOf("temperature"))))
    }

    @Test
    fun `other statuses and other errors are not refusals`() {
        assertNull(find(500, """{"error":{"message":"temperature is not supported"}}"""))
        assertNull(find(429, """{"error":{"message":"max_tokens quota exceeded"}}"""))
        assertNull(find(400, """{"error":{"message":"messages must not be empty"}}"""))
    }

    @Test
    fun `memory accumulates per scope`() {
        val memory = ParameterMemory()
        memory.remember("a|model", "max_tokens")
        memory.remember("a|model", "temperature")
        assertEquals(setOf("max_tokens", "temperature"), memory.refused("a|model"))
        assertEquals(emptySet(), memory.refused("b|model"))
    }
}
