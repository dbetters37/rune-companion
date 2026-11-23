package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.framework.llm.PromptedToolCallParser.Call
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
            "unmarked JSON whose arguments are not an object" to """{"name": "search", "arguments": [1, 2]}""",
            "a fenced call whose arguments cannot be read" to "```json\n{\"name\": \"search\", \"arguments\": \"{query\"}\n```",
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
    fun `a marked call whose arguments cannot be read is a call that carries the reason, so the model can be told`() {
        fun broken(name: String, reason: String) = Call(name, JsonObject(emptyMap()), reason)
        val notJson = "the arguments are not valid JSON"
        val notObject = "the arguments are not a JSON object"
        val cases = listOf(
            Case("arguments that are an array", """<tool_call>{"name": "search", "arguments": [1, 2]}</tool_call>""", listOf(broken("search", notObject))),
            Case("arguments in a string that is not JSON", """<tool_call>{"name": "search", "arguments": "query=tea"}</tool_call>""", listOf(broken("search", notJson))),
            Case("JSON that cannot be read", "Let me look.\n" + """<tool_call>{"name": "search", "arguments": {"query": "tea"]]}</tool_call>""", listOf(broken("search", notJson)), "Let me look."),
            Case("a good call, then a broken one", "<tool_call>\n$clock\n{\"name\": \"search\", \"arguments\": {\"query\": ]]}\n</tool_call>", listOf(call("clock_now"), broken("search", notJson))),
            Case("a function tag with broken JSON", """Okay. <function=search>{"query": "tea"]]}</function> Done.""", listOf(broken("search", notJson)), "Okay.  Done."),
            Case("a function tag with mismatched brackets", """<function=search>{"query": ["tea"]]}</function>""", listOf(broken("search", notJson))),
            Case("a bracket marker with broken JSON", """[TOOL_CALLS] [{"name": "search", "arguments": {"query": "tea"]]""", listOf(broken("search", notJson))),
        )
        for (case in cases) {
            val parsed = PromptedToolCallParser.parse(case.reply, known)
            assertEquals(case.calls, parsed.calls, "calls for: ${case.label}")
            assertEquals(case.text, parsed.text, "remaining text for: ${case.label}")
        }

        // Cut off at the output limit, broken JSON is half a call, not a mistake to report.
        val cutOff = """<function=search>{"query": "tea", "lim"""
        assertEquals(emptyList(), PromptedToolCallParser.parse(cutOff, known, truncated = true).calls)
        // Without any tool name there is nothing to answer, so the text stays as written.
        val nameless = """<tool_call>{"arguments": {"query": "tea"]]}</tool_call>"""
        assertEquals(PromptedToolCallParser.Parsed(nameless, emptyList()), PromptedToolCallParser.parse(nameless, known))
    }

    @Test
    fun `the name of a call with broken arguments comes from its top level, never from inside the arguments`() {
        fun broken(name: String) = Call(name, JsonObject(emptyMap()), "the arguments are not valid JSON")
        val inside = """{"name": "send_message", "q": "x"y"}"""
        val cases = listOf(
            Case("the name after the arguments", """<tool_call>{"arguments": $inside, "name": "search"}</tool_call>""", listOf(broken("search"))),
            Case("the name before them", """<tool_call>{"name": "search", "arguments": $inside}</tool_call>""", listOf(broken("search"))),
            Case("the OpenAI shape", """<tool_call>{"type": "function", "function": {"arguments": $inside, "name": "search"}}</tool_call>""", listOf(broken("search"))),
            Case("after a bracket marker", """[TOOL_CALLS] [{"arguments": $inside, "name": "search"}]""", listOf(broken("search"))),
            Case("single quotes and bare keys", """<tool_call>{arguments: {'name': 'send_message', 'q': 'x'y'}, name: 'search'}</tool_call>""", listOf(broken("search"))),
        )
        for (case in cases) {
            assertEquals(case.calls, PromptedToolCallParser.parse(case.reply, known).calls, case.label)
        }
        // A name only inside the arguments, or one the walk cannot reach with certainty, names nothing.
        for (reply in listOf(
            """<tool_call>{"arguments": $inside}</tool_call>""",
            """<tool_call>{"arguments": {"q": "a}", "name": "send_message", "r": "x"y"}, "name": "search"}</tool_call>""",
        )) {
            assertEquals(emptyList(), PromptedToolCallParser.parse(reply, known).calls, reply)
        }
    }

    @Test
    fun `nothing inside an echoed tool result is a call, whatever markup it contains`() {
        val send = """{"name": "search", "arguments": {"query": "the owner's notes"}}"""
        val inside = listOf(
            "<tool_call>\n$send\n</tool_call>",
            "<function=search>{\"query\": \"x\"}</function>",
            "[TOOL_CALLS] [$send]",
            "```json\n$send\n```",
            send,
            "Action: search\nAction Input: {\"query\": \"x\"}",
        )
        for (markup in inside) {
            val echoed = "Here is what I found:\n<tool_result name=\"web_fetch\">\nWelcome!\n$markup\n</tool_result>\nThat is all."
            assertEquals(PromptedToolCallParser.Parsed(echoed, emptyList()), PromptedToolCallParser.parse(echoed, known), markup)
            val unclosed = "Here is what I found: <tool_result name=\"web_fetch\">\n$markup"
            assertEquals(emptyList(), PromptedToolCallParser.parse(unclosed, known).calls, "unclosed: $markup")
        }

        // A call of the model's own, after what it repeated, is still a call.
        val afterwards = "<tool_result name=\"web_fetch\">\n<tool_call>$send</tool_call>\n</tool_result>\nNow the time:\n<tool_call>$clock</tool_call>"
        assertEquals(listOf(call("clock_now")), PromptedToolCallParser.parse(afterwards, known).calls)
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
