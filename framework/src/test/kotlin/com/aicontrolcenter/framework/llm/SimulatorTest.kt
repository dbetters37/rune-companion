package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorTest {
    private val adapter = SimulatorProviderFactory(chunkDelayMs = 5)
        .create(ProviderConnection(Samples.provider(kind = ProviderKind.SIMULATOR, baseUrl = null, locality = Locality.ON_DEVICE), apiKey = null))
    private val client = adapter.client

    private fun tool(name: String, vararg required: String) = ToolDefinition(
        name, "A tool.",
        buildJsonObject {
            put("type", "object")
            putJsonArray("required") { required.forEach { add(it) } }
        },
    )

    private val clock = tool("clock_now")
    private val team = tool("team_list_agents")

    private fun ask(text: String, vararg tools: ToolDefinition) =
        ChatRequest(model = Simulator.MODEL_NAME, messages = listOf(ChatMessage.system("You are Scout."), ChatMessage.user(text)), tools = tools.toList())

    @Test
    fun `it is healthy, lists its one model and says what it is`() = runTest {
        assertEquals(ConnectionStatus.ONLINE, adapter.checkHealth().status)
        val listed = adapter.listModels().single()
        assertEquals(Simulator.MODEL_NAME, listed.name)
        assertTrue("not a real model" in listed.displayName.orEmpty())
        assertEquals(Simulator.capabilities, listed.capabilities)
    }

    @Test
    fun `it restates the request, shows what a model would receive, and is deterministic`() = runTest {
        val request = ask("Plan a trip to Lisbon. Keep it under three days! What should I pack?")

        val response = client.chat(request)

        val text = response.message.text
        assertTrue(text.startsWith(Simulator.DISCLOSURE))
        assertTrue("Plan a trip to Lisbon." in text)
        assertTrue("It has 3 parts:" in text && "3. What should I pack?" in text)
        assertTrue("Tools offered: none" in text && "Conversation so far: 1 messages" in text)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(Simulator.MODEL_NAME, response.modelName)
        assertTrue(response.usage!!.inputTokens > 0 && response.usage!!.outputTokens > 0)
        assertEquals(response, client.chat(request))
    }

    @Test
    fun `asked for the time it calls the clock tool and then answers from the result`() = runTest {
        val request = ask("What time is it right now?", clock, team)

        val first = client.chat(request)
        assertEquals(FinishReason.TOOL_CALLS, first.finishReason)
        val call = first.message.toolCalls.single()
        assertEquals("clock_now", call.name)
        assertTrue(first.message.text.startsWith(Simulator.DISCLOSURE))

        val followUp = request.copy(messages = request.messages + first.message + ChatMessage.toolResult(call.id, call.name, "It is Tuesday, 14 November 2023, 22:13:20."))
        val second = client.chat(followUp)
        assertEquals(FinishReason.STOP, second.finishReason)
        assertTrue(second.message.toolCalls.isEmpty(), "one round of tools, then an answer")
        assertTrue(second.message.text.startsWith(Simulator.DISCLOSURE))
        assertTrue("It is Tuesday, 14 November 2023, 22:13:20." in second.message.text)
    }

    @Test
    fun `a request about both the date and the team calls both tools`() = runTest {
        val response = client.chat(ask("Tell me today's date and who is on the team.", clock, team))
        assertEquals(listOf("clock_now", "team_list_agents"), response.message.toolCalls.map { it.name })
        assertEquals(2, response.message.toolCalls.map { it.id }.toSet().size)
    }

    @Test
    fun `it does not reach for a tool that is not offered, not needed, forbidden or needs arguments`() = runTest {
        val noCalls = listOf(
            ask("What time is it?"),
            ask("Write a poem about rain.", clock, team),
            ask("Last time we spoke you were helpful.", clock),
            ask("What time is it?", clock).copy(toolChoice = ToolChoice.NONE),
            ask("What time is it?", tool("clock_now", "timezone")),
            ask("What time is it?", tool("update_file")),
        )
        for (request in noCalls) {
            val response = client.chat(request)
            assertTrue(response.message.toolCalls.isEmpty(), "unexpected call for: ${request.messages.last().text}")
            assertTrue(response.message.text.startsWith(Simulator.DISCLOSURE))
        }
    }

    @Test
    fun `a failed tool is reported as such`() = runTest {
        val request = ask("What time is it?", clock)
        val call = client.chat(request).message.toolCalls.single()
        val failed = request.copy(messages = request.messages + ChatMessage.toolResult(call.id, call.name, "The clock is not switched on for Scout.", isError = true))

        val text = client.chat(failed).message.text
        assertTrue("could not be used" in text && "not switched on for Scout" in text)
    }

    @Test
    fun `the streamed pieces add up to the reply and end with the result`() = runTest {
        val request = ask("Describe  the plan.\nThen stop.")
        val chunks = client.chatStream(request).toList()

        val completed = chunks.last() as ChatChunk.Completed
        assertEquals(client.chat(request), completed.response)
        assertEquals(completed.response.message.text, chunks.filterIsInstance<ChatChunk.Text>().joinToString("") { it.delta })
        assertTrue(chunks.count { it is ChatChunk.Text } > 5, "it arrives piece by piece")
    }

    @Test
    fun `asked for JSON it answers with one object that still says it is simulated`() = runTest {
        val response = client.chat(ask("Summarise this.").copy(jsonOutput = true))
        val json = Json.parseToJsonElement(response.message.text).jsonObject
        assertTrue(json.getValue("simulated").jsonPrimitive.boolean)
        assertEquals(Simulator.DISCLOSURE, json.getValue("notice").jsonPrimitive.content)
        assertEquals("Summarise this.", json.getValue("request").jsonPrimitive.content)
    }

    @Test
    fun `an output limit cuts the reply short but never the disclosure`() = runTest {
        val response = client.chat(ask("Explain everything about tea.").copy(maxOutputTokens = 5))
        assertEquals(FinishReason.LENGTH, response.finishReason)
        assertEquals(Simulator.DISCLOSURE, response.message.text)
    }
}
