package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.framework.llm.PromptedToolCallParser.Call
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals

class PromptedToolCallParserTest {
    private val known = setOf("clock_now", "search")

    private fun call(name: String, arguments: String = "{}") = Call(name, Json.parseToJsonElement(arguments).jsonObject)

    private class Case(val label: String, val reply: String, val calls: List<Call>, val text: String = "")

    private val clock = """{"name": "clock_now", "arguments": {}}"""
    private val tea = """{"name": "search", "arguments": {"query": "tea"}}"""
    private val searchTea = call("search", """{"query": "tea"}""")

    @Test
    fun `reads calls from the shapes models really produce`() {
        val cases = listOf(
            Case("the exact format", "<tool_call>\n$clock\n</tool_call>", listOf(call("clock_now"))),
            Case("prose before and after", "Let me check.\n<tool_call>$tea</tool_call>\nOne moment.", listOf(searchTea), "Let me check.\n\nOne moment."),
            Case("no closing tag", "Sure.\n<tool_call>$tea", listOf(searchTea), "Sure."),
            Case("upper-case tags", "<TOOL_CALL>$clock</TOOL_CALL>", listOf(call("clock_now"))),
            Case("a code fence inside the tags", "<tool_call>\n```json\n$tea\n```\n</tool_call>", listOf(searchTea)),
            Case("a json code fence instead of tags", "I will search.\n```json\n$tea\n```", listOf(searchTea), "I will search."),
            Case("a fence without a language", "```\n$clock\n```", listOf(call("clock_now"))),
            Case("nothing but the JSON", clock, listOf(call("clock_now"))),
            Case("bare JSON after a sentence", "I'll look that up.\n$tea", listOf(searchTea), "I'll look that up."),
            Case("several tagged calls", "<tool_call>$clock</tool_call>\n<tool_call>$tea</tool_call>", listOf(call("clock_now"), searchTea)),
            Case("an array of calls in one block", "<tool_call>[$clock, $tea]</tool_call>", listOf(call("clock_now"), searchTea)),
            Case("one call per line in one block", "<tool_call>\n$clock\n$tea\n</tool_call>", listOf(call("clock_now"), searchTea)),
            Case("single quotes, bare keys, trailing commas", "<tool_call>{name: 'search', arguments: {'query': 'tea',},}</tool_call>", listOf(searchTea)),
            Case("typographic quotes", "<tool_call>{“name”: “clock_now”, “arguments”: {}}</tool_call>", listOf(call("clock_now"))),
            Case("braces never closed", "<tool_call>{\"name\": \"search\", \"arguments\": {\"query\": \"tea\"</tool_call>", listOf(searchTea)),
            Case(
                "python literals", """<tool_call>{"name": "search", "arguments": {"query": "tea", "exact": True, "limit": None}}</tool_call>""",
                listOf(call("search", """{"query": "tea", "exact": true, "limit": null}""")),
            ),
            Case("arguments as a JSON string", """<tool_call>{"name": "search", "arguments": "{\"query\": \"tea\"}"}</tool_call>""", listOf(searchTea)),
            Case("no arguments at all", """<tool_call>{"name": "clock_now"}</tool_call>""", listOf(call("clock_now"))),
            Case("other key names", """{"tool": "search", "args": {"query": "tea"}}""", listOf(searchTea)),
            Case("function and parameters", """{"function": "search", "parameters": {"query": "tea"}}""", listOf(searchTea)),
            Case(
                "the OpenAI wire shape", """<tool_call>{"type": "function", "function": {"name": "search", "arguments": "{\"query\": \"tea\"}"}}</tool_call>""",
                listOf(searchTea),
            ),
            Case("a tool_calls wrapper", """{"tool_calls": [$clock]}""", listOf(call("clock_now"))),
            Case("function tags", """Okay. <function=search>{"query": "tea"}</function>""", listOf(searchTea), "Okay."),
            Case("a bracket marker", """[TOOL_CALLS] [$tea]""", listOf(searchTea)),
            Case("thought, action, action input", "Thought: I need the time.\nAction: clock_now\nAction Input: {}", listOf(call("clock_now")), "Thought: I need the time."),
            Case("numbers and nesting", """<tool_call>{"name": "search", "arguments": {"query": "tea", "limit": 5, "filters": {"lang": ["en", "fr"]}}}</tool_call>""",
                listOf(call("search", """{"query": "tea", "limit": 5, "filters": {"lang": ["en", "fr"]}}"""))),
            Case("an unknown tool inside tags is still a call", """<tool_call>{"name": "launch_rockets", "arguments": {}}</tool_call>""", listOf(call("launch_rockets"))),
        )
        for (case in cases) {
            val parsed = PromptedToolCallParser.parse(case.reply, known)
            assertEquals(case.calls, parsed.calls, "calls for: ${case.label}")
            assertEquals(case.text, parsed.text, "remaining text for: ${case.label}")
        }
    }

    @Test
    fun `leaves alone whatever is not clearly a call`() {
        val replies = mapOf(
            "ordinary prose" to "The capital of France is Paris.",
            "JSON that is data" to "Here is the reading:\n```json\n{\"temperature\": 21}\n```",
            "bare JSON naming no offered tool" to """{"name": "Alice", "arguments": {"age": 30}}""",
            "a fenced call to a tool that was not offered" to "```json\n{\"name\": \"launch_rockets\", \"arguments\": {}}\n```",
            "tags around prose" to "<tool_call>please run the clock</tool_call>",
            "the tag mentioned in passing" to "Write a <tool_call> block when you need a tool.",
            "arguments that are not an object" to """<tool_call>{"name": "search", "arguments": [1, 2]}</tool_call>""",
            "a name that is no tool name" to """<tool_call>{"name": "what is the time?", "arguments": {}}</tool_call>""",
            "a brace that opens nothing" to "{not json at all",
            "an action for an unknown tool" to "Action: sing\nAction Input: {}",
            "code in another language" to "```kotlin\nval name = \"clock_now\"\n```",
        )
        for ((label, reply) in replies) {
            val parsed = PromptedToolCallParser.parse(reply, known)
            assertEquals(emptyList(), parsed.calls, "calls for: $label")
            assertEquals(reply, parsed.text, "text must be untouched for: $label")
        }
    }

    @Test
    fun `a call cut off at the output limit is not completed by guesswork`() {
        val cutOff = """Searching. <tool_call>{"name": "search", "arguments": {"query": "the history of te"""

        val truncated = PromptedToolCallParser.parse(cutOff, known, truncated = true)
        assertEquals(emptyList(), truncated.calls)
        assertEquals(cutOff, truncated.text)

        // The same text from a model that simply forgot to close its brackets is a complete thought.
        val sloppy = PromptedToolCallParser.parse(cutOff, known, truncated = false)
        assertEquals(listOf(call("search", """{"query": "the history of te"}""")), sloppy.calls)

        // A call that was finished before the cut is still good.
        val finishedFirst = PromptedToolCallParser.parse("<tool_call>$clock</tool_call> And then I", known, truncated = true)
        assertEquals(listOf(call("clock_now")), finishedFirst.calls)
    }

    @Test
    fun `strict JSON reads the same as with a strict parser`() {
        val json = """{"a": [1, -2, 3.5, true, false, null], "b": {"c": "line\nbreak \"quoted\" é \\ /"}, "d": ""}"""
        val read = LenientJson.readAt(json, 0, allowUnclosed = false)
        assertEquals(Json.parseToJsonElement(json), read?.element)
        assertEquals(json.length, read?.end)
    }
}
