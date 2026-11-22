package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.framework.StreamingClient
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelGatewayTest {
    private val world = TestWorld()
    private val ask = ChatRequest(messages = listOf(ChatMessage.user("Hello there")))
    private val secret = "sk-test-0123456789abcdef"

    private fun modelCalls() = world.events(ActivityType.MODEL_CALL)

    private fun reply(text: String) = ChatResponse(ChatMessage.assistant(text), FinishReason.STOP, TokenUsage(10, 5), "scripted-1")

    @Test
    fun `a call goes out under the wire name and is logged with model, usage and duration`() = runTest {
        val client = ScriptedLlmClient.replies("Hi")
        val model = world.serve(client)

        val response = world.gateway.chat(model.id, ask, CallContext(agentId = AgentId("agt_x")))

        assertEquals("Hi", response.message.text)
        assertEquals("scripted-1", client.requests.single().model)
        val event = modelCalls().single()
        assertEquals("Model call", event.title)
        assertEquals(Severity.INFO, event.severity)
        assertEquals(AgentId("agt_x"), event.agentId)
        val data = assertNotNull(event.data)
        assertEquals("scripted-1", data.getValue("answeredModel").jsonPrimitive.content)
        assertEquals(10, data.getValue("inputTokens").jsonPrimitive.int)
        assertEquals(5, data.getValue("outputTokens").jsonPrimitive.int)
        assertEquals(1, data.getValue("attempts").jsonPrimitive.int)
        assertTrue("durationMs" in data)
        assertNull(event.egress, "a server on this phone is not egress")
        assertEquals(ConnectionStatus.ONLINE, world.providers.get(model.providerId)!!.status)
    }

    @Test
    fun `calls to providers off the device record where the data went, and the key stays out of the log`() = runTest {
        val cloud = world.provider(baseUrl = "https://api.example.com/v1", apiKey = secret)
        val lan = world.provider(baseUrl = "http://192.168.1.20:8080/v1", name = "Home server")

        world.gateway.chat(world.model(cloud).id, ask, CallContext(purpose = "test prompt"))
        world.gateway.chat(world.model(lan).id, ask)

        val (toCloud, toLan) = modelCalls().map { assertNotNull(it.egress) }
        assertEquals("api.example.com", toCloud.destinationHost)
        assertEquals(Locality.CLOUD, toCloud.locality)
        assertEquals(cloud.id, toCloud.providerId)
        assertEquals("test prompt", toCloud.purpose)
        assertTrue(toCloud.approxBytes!! >= "Hello there".length)
        assertEquals("192.168.1.20", toLan.destinationHost)
        assertEquals(Locality.LOCAL_NETWORK, toLan.locality)

        val connections = world.factories.getValue(ProviderKind.OPENAI_COMPATIBLE).connections
        assertEquals(listOf(secret, null), connections.map { it.apiKey }, "the key is read from the vault for the provider that has one")
        assertFalse(secret in world.logText())
    }

    @Test
    fun `policy refuses missing and disabled models and providers without calling anything`() = runTest {
        val client = ScriptedLlmClient.replies("never")
        val adapter = FakeProviderAdapter(client = client)
        val disabledModel = world.model(world.provider(adapter), enabled = false)
        val onDisabledProvider = world.model(world.provider(adapter, enabled = false, name = "Off"))
        val orphan = world.model(world.provider(adapter, name = "Gone")).also { world.providers.delete(it.providerId) }

        val refused = listOf(ModelId("mdl_missing"), disabledModel.id, onDisabledProvider.id, orphan.id).map { id ->
            assertFailsWith<LlmException.BlockedByPolicy> { world.gateway.chat(id, ask) }.message.orEmpty()
        }

        assertTrue("no longer registered" in refused[0])
        assertTrue("disabled" in refused[1])
        assertTrue("Off is disabled" in refused[2])
        assertTrue("provider" in refused[3] && "no longer registered" in refused[3])
        assertTrue(client.requests.isEmpty())
        assertEquals(List(4) { "Model call blocked" }, modelCalls().map { it.title })
        assertTrue(modelCalls().all { it.egress == null && it.severity == Severity.WARNING })
    }

    @Test
    fun `local-only mode blocks every provider whose address leaves the device, whatever its record claims`() = runTest {
        val onPhone = world.model(world.provider())
        val lan = world.model(world.provider(baseUrl = "http://192.168.1.20:8080/v1"))
        val mislabelled = world.provider(baseUrl = "https://api.example.com/v1")
        world.providers.upsert(mislabelled.copy(locality = Locality.ON_DEVICE))
        val cloud = world.model(mislabelled)
        world.settings.update { it.copy(localOnlyMode = true) }

        assertEquals("ok", world.gateway.chat(onPhone.id, ask).message.text)
        for (model in listOf(lan, cloud)) {
            val error = assertFailsWith<LlmException.BlockedByPolicy> { world.gateway.chatStream(model.id, ask).toList() }
            assertTrue("Local-only mode" in error.message.orEmpty())
        }
        assertTrue(modelCalls().none { it.egress != null })
    }

    @Test
    fun `retryable failures are retried with growing pauses and logged as one call`() = runTest {
        val world = TestWorld(retry = RetryPolicy(maxRetries = 2, initialDelayMs = 1_000, maxDelayMs = 8_000))
        val client = ScriptedLlmClient.script(
            ScriptStep.Fail(LlmException.Overloaded("busy")), ScriptStep.Fail(LlmException.Server("bad gateway", 502)), ScriptStep.Reply("done"),
        )
        val model = world.serve(client)

        assertEquals("done", world.gateway.chat(model.id, ask).message.text)

        assertEquals(3, client.requests.size)
        assertEquals(3_000, currentTime, "1 s before the second attempt, 2 s before the third")
        val event = world.events(ActivityType.MODEL_CALL).single()
        assertEquals(3, event.data!!.getValue("attempts").jsonPrimitive.int)
    }

    @Test
    fun `retries are bounded and the last failure is reported`() = runTest {
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Network("unreachable")))
        val model = world.serve(client)

        assertFailsWith<LlmException.Network> { world.gateway.chat(model.id, ask) }

        assertEquals(3, client.requests.size, "the first attempt and two retries")
        val event = modelCalls().single()
        assertEquals("Model call failed", event.title)
        assertEquals(Severity.ERROR, event.severity)
        assertTrue("unreachable" in event.detail)
    }

    @Test
    fun `failures that cannot pass are not retried`() = runTest {
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.InvalidRequest("bad request", 400)))
        val model = world.serve(client)
        assertFailsWith<LlmException.InvalidRequest> { world.gateway.chat(model.id, ask) }
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `a rate limit's retry-after is waited for, unless it is too long to be worth it`() = runTest {
        val world = TestWorld(retry = RetryPolicy(initialDelayMs = 1_000, maxRetryAfterMs = 20_000))
        val patient = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("slow down", retryAfterMs = 5_000)), ScriptStep.Reply("ok"))
        assertEquals("ok", world.gateway.chat(world.serve(patient).id, ask).message.text)
        assertEquals(5_000, currentTime)

        val hopeless = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("come back tomorrow", retryAfterMs = 60_000)))
        assertFailsWith<LlmException.RateLimited> { world.gateway.chat(world.serve(hopeless).id, ask) }
        assertEquals(1, hopeless.requests.size)
        assertEquals(5_000, currentTime, "no time was spent waiting for it")
    }

    @Test
    fun `a stream is retried while nothing was passed on, never after output started`() = runTest {
        val recovers = StreamingClient { _, attempt ->
            flow {
                if (attempt == 0) throw LlmException.Network("connection reset")
                emit(ChatChunk.Text("ok"))
                emit(ChatChunk.Completed(reply("ok")))
            }
        }
        val chunks = world.gateway.chatStream(world.serve(recovers).id, ask).toList()
        assertEquals(listOf(ChatChunk.Text("ok"), ChatChunk.Completed(reply("ok"))), chunks)
        assertEquals(2, recovers.requests.size)

        val breaksMidway = StreamingClient { _, _ ->
            flow {
                emit(ChatChunk.Text("Hel"))
                throw LlmException.Network("connection reset")
            }
        }
        val seen = ArrayList<ChatChunk>()
        assertFailsWith<LlmException.Network> { world.gateway.chatStream(world.serve(breaksMidway).id, ask).collect { seen += it } }
        assertEquals(listOf<ChatChunk>(ChatChunk.Text("Hel")), seen)
        assertEquals(1, breaksMidway.requests.size, "repeating the call would show the text twice")
    }

    @Test
    fun `a stream that ends without a result is a malformed response`() = runTest {
        val client = StreamingClient { _, _ -> flow { emit(ChatChunk.Text("half")) } }
        assertFailsWith<LlmException.MalformedResponse> { world.gateway.chatStream(world.serve(client).id, ask).toList() }
    }

    @Test
    fun `tools go through the prompted protocol only for models without native tool calling`() = runTest {
        val clockTool = ToolDefinition("clock_now", "Tells the time.", buildJsonObject { put("type", "object") })
        val withTools = ask.copy(tools = listOf(clockTool))

        val plain = ScriptedLlmClient.replies("""Checking. <tool_call>{"name": "clock_now", "arguments": {}}</tool_call>""")
        val response = world.gateway.chat(world.serve(plain, ModelCapabilities(toolCalling = false)).id, withTools)

        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
        assertEquals("clock_now", response.message.toolCalls.single().name)
        assertEquals("Checking.", response.message.text)
        val sent = plain.requests.single()
        assertTrue(sent.tools.isEmpty(), "the provider must not be offered tools it cannot take")
        assertTrue(sent.messages.any { it.role == ChatRole.SYSTEM && "<tool_call>" in it.text && "clock_now" in it.text })
        assertTrue(modelCalls().last().data!!.getValue("promptedTools").jsonPrimitive.boolean)

        val native = ScriptedLlmClient.replies("ok")
        world.gateway.chat(world.serve(native, ModelCapabilities(toolCalling = true)).id, withTools)
        assertEquals(listOf(clockTool), native.requests.single().tools)
        assertEquals(ask.messages, native.requests.single().messages)
        assertFalse(modelCalls().last().data!!.getValue("promptedTools").jsonPrimitive.boolean)
    }

    @Test
    fun `a different model answering than the one requested is noted, and flagged when unrelated`() = runTest {
        val snapshot = world.serve(ScriptedLlmClient.script(ScriptStep.Reply("a"), modelName = "scripted-1-2026-01-01"))
        val stranger = world.serve(ScriptedLlmClient.script(ScriptStep.Reply("b"), modelName = "another-model"))

        world.gateway.chat(snapshot.id, ask)
        world.gateway.chat(stranger.id, ask)

        val (dated, other) = modelCalls()
        assertTrue("answered by scripted-1-2026-01-01, not the requested scripted-1" in dated.detail)
        assertEquals(Severity.INFO, dated.severity)
        assertTrue("answered by another-model, not the requested scripted-1" in other.detail)
        assertEquals(Severity.WARNING, other.severity)
        assertTrue(other.data!!.getValue("modelSubstituted").jsonPrimitive.boolean)
    }

    @Test
    fun `an authentication failure marks the provider, and the next success clears it`() = runTest {
        val client = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Authentication("The key was rejected.")), ScriptStep.Reply("ok"))
        val model = world.serve(client)

        assertFailsWith<LlmException.Authentication> { world.gateway.chat(model.id, ask) }
        val rejected = world.providers.get(model.providerId)!!
        assertEquals(ConnectionStatus.AUTH_ERROR, rejected.status)
        assertEquals("The key was rejected.", rejected.statusMessage)
        assertNotNull(rejected.lastCheckedAt)

        world.gateway.chat(model.id, ask)
        val accepted = world.providers.get(model.providerId)!!
        assertEquals(ConnectionStatus.ONLINE, accepted.status)
        assertNull(accepted.statusMessage)
    }

    @Test
    fun `whatever an adapter throws arrives as an LlmException without the key in it`() = runTest {
        val crashing = ScriptedLlmClient { _, _ -> throw IllegalStateException("exploded while sending $secret") }
        val echoing = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.InvalidRequest("header Authorization: Bearer $secret was refused", 400)))
        val a = world.model(world.provider(FakeProviderAdapter(client = crashing), baseUrl = "https://a.example.com/v1", apiKey = secret))
        val b = world.model(world.provider(FakeProviderAdapter(client = echoing), baseUrl = "https://b.example.com/v1", apiKey = secret))

        val unexpected = assertFailsWith<LlmException.MalformedResponse> { world.gateway.chat(a.id, ask) }
        val echoed = assertFailsWith<LlmException.InvalidRequest> { world.gateway.chat(b.id, ask) }

        assertTrue("IllegalStateException" in unexpected.message.orEmpty())
        assertEquals(400, echoed.status)
        assertTrue("[key removed]" in echoed.message.orEmpty())
        for (error in listOf(unexpected, echoed)) {
            assertTrue(generateSequence<Throwable>(error) { it.cause }.none { secret in it.message.orEmpty() || secret in it.toString() })
        }
        assertFalse(secret in world.logText())
    }

    @Test
    fun `an adapter is kept between calls and replaced, and closed, when the key or the record changes`() = runTest {
        val adapters = ArrayList<FakeProviderAdapter>()
        world.fallbackAdapter = { FakeProviderAdapter().also { adapters += it } }
        val provider = world.provider(baseUrl = "https://api.example.com/v1", apiKey = secret)
        world.adaptersByUrl.clear()
        val model = world.model(provider)
        val factory = world.factories.getValue(ProviderKind.OPENAI_COMPATIBLE)

        world.gateway.chat(model.id, ask)
        world.gateway.chat(model.id, ask)
        assertEquals(1, factory.connections.size, "one adapter serves both calls")

        world.vault.put(provider.apiKeyAlias!!, "sk-test-replacement-key")
        world.gateway.chat(model.id, ask)
        assertEquals(listOf(secret, "sk-test-replacement-key"), factory.connections.map { it.apiKey })
        assertTrue(adapters[0].closed)

        world.providers.upsert(world.providers.get(provider.id)!!.copy(extraHeaders = mapOf("X-Title" to "Test")))
        world.gateway.chat(model.id, ask)
        assertEquals(3, factory.connections.size)
        assertTrue(adapters[1].closed)
        assertFalse(adapters[2].closed)
        assertSame(adapters[2], world.connections.acquire(world.providers.get(provider.id)!!).adapter)
    }

    @Test
    fun `a cancelled call is still logged, with egress, because the prompt may have been delivered`() = runTest {
        val hanging = ScriptedLlmClient { _, _ -> awaitCancellation() }
        val model = world.model(world.provider(FakeProviderAdapter(client = hanging), baseUrl = "https://api.example.com/v1"))

        val call = launch { world.gateway.chat(model.id, ask) }
        runCurrent()
        call.cancelAndJoin()

        val event = modelCalls().single()
        assertEquals("Model call cancelled", event.title)
        assertEquals("api.example.com", event.egress?.destinationHost)
    }
}
