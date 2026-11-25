package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeProviderAdapterFactory
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySecretVault
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
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
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
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
    fun `a rate limit that says a daily quota is used up is not sent again within the call`() = runTest {
        val world = TestWorld(retry = RetryPolicy(initialDelayMs = 1_000, maxRetryAfterMs = 20_000))
        val usedUp = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Rate limit exceeded: free-models-per-day.")))
        assertFailsWith<LlmException.RateLimited> { world.gateway.chat(world.serve(usedUp).id, ask) }
        assertEquals(1, usedUp.requests.size)
        assertEquals(0, currentTime, "no time was spent waiting for it")

        val perMinute = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Rate limit exceeded: free-models-per-min.")), ScriptStep.Reply("ok"))
        assertEquals("ok", world.gateway.chat(world.serve(perMinute).id, ask).message.text)
        assertEquals(2, perMinute.requests.size)
    }

    @Test
    fun `an objective's call is not repeated within the gateway for a 429 that names no wait, since its step waits itself`() = runTest {
        // Wave-2a residual verification, NEW-R3-INT1-BOUND: the step waits 30 seconds, then 2 minutes (R4); repeating the
        // request after 1 and 2 seconds inside a per-minute limit only spends requests the provider may count.
        val world = TestWorld(retry = RetryPolicy(initialDelayMs = 1_000, maxRetryAfterMs = 20_000))
        val objective = CallContext(taskId = com.aicontrolcenter.core.TaskId("tsk_1"), rootTaskId = com.aicontrolcenter.core.TaskId("tsk_0"))
        val bare = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Rate limit exceeded: free-models-per-min.")), ScriptStep.Reply("ok"))
        assertFailsWith<LlmException.RateLimited> { world.gateway.chat(world.serve(bare).id, ask, objective) }
        assertEquals(1, bare.requests.size)
        assertEquals(0, currentTime, "no time was spent waiting inside the call")

        // A wait the provider named is still waited for inside the call, and other failures are still repeated.
        val named = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Please try again in 8.3s.", retryAfterMs = 9_000)), ScriptStep.Reply("ok"))
        assertEquals("ok", world.gateway.chat(world.serve(named).id, ask, objective).message.text)
        assertEquals(2, named.requests.size)
        val busy = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.Overloaded("busy")), ScriptStep.Reply("ok"))
        assertEquals("ok", world.gateway.chat(world.serve(busy).id, ask, objective).message.text)

        // A chat, where nothing else waits, still repeats it.
        val chat = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Rate limit exceeded: free-models-per-min.")), ScriptStep.Reply("ok"))
        assertEquals("ok", world.gateway.chat(world.serve(chat).id, ask, CallContext(conversationId = com.aicontrolcenter.core.ConversationId("cnv_1"))).message.text)
        assertEquals(2, chat.requests.size)
    }

    @Test
    fun `a daily quota stays a daily quota when the key is removed from the provider's answer`() = runTest {
        val answer = ScriptedLlmClient.script(ScriptStep.Fail(LlmException.RateLimited("Quota of key $secret used up.", retryAfterMs = 35_000, dailyQuota = true)))
        val model = world.model(world.provider(FakeProviderAdapter(client = answer), baseUrl = "https://quota.example.com/v1", apiKey = secret))
        val error = assertFailsWith<LlmException.RateLimited> { world.gateway.chat(model.id, ask) }
        assertTrue("[key removed]" in error.message.orEmpty(), error.message)
        assertTrue(error.dailyQuota)
        assertEquals(35_000, error.retryAfterMs)
        assertEquals(1, answer.requests.size, "and it is not sent again")
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
        // Phase 6, D29: a dated version of the requested name is the same model and is not noted; a variant is noted;
        // an unrelated model is noted and flagged.
        val snapshot = world.serve(ScriptedLlmClient.script(ScriptStep.Reply("a"), modelName = "scripted-1-2026-01-01"))
        val variant = world.serve(ScriptedLlmClient.script(ScriptStep.Reply("v"), modelName = "scripted-1-fast"))
        val stranger = world.serve(ScriptedLlmClient.script(ScriptStep.Reply("b"), modelName = "another-model"))

        world.gateway.chat(snapshot.id, ask)
        world.gateway.chat(variant.id, ask)
        world.gateway.chat(stranger.id, ask)

        val (dated, fast, other) = modelCalls()
        assertFalse("answered by" in dated.detail)
        assertFalse(dated.data!!.getValue("modelSubstituted").jsonPrimitive.boolean)
        assertEquals("scripted-1-2026-01-01", dated.data!!.getValue("answeredModel").jsonPrimitive.content)
        assertEquals(Severity.INFO, dated.severity)
        assertTrue("answered by scripted-1-fast, not the requested scripted-1" in fast.detail)
        assertEquals(Severity.INFO, fast.severity)
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
    /**
     * A cloud call whose first attempt is rate-limited; while it fails, [change] is made, as the
     * owner might during the pause. Returns the world, the model and what the call ended with.
     */
    private suspend fun retryAfter(streamed: Boolean, change: suspend TestWorld.(ModelProvider, ModelDescriptor) -> Unit): Triple<TestWorld, ScriptedLlmClient, Result<String>> {
        val world = TestWorld()
        lateinit var provider: ModelProvider
        lateinit var model: ModelDescriptor
        val client = ScriptedLlmClient { _, attempt ->
            if (attempt == 0) {
                world.change(provider, model)
                ScriptStep.Fail(LlmException.RateLimited("slow down"))
            } else {
                ScriptStep.Reply("sent again")
            }
        }
        provider = world.provider(FakeProviderAdapter(client = client), baseUrl = "https://api.example.com/v1", apiKey = secret)
        model = world.model(provider)
        val outcome = runCatching {
            if (streamed) {
                (world.gateway.chatStream(model.id, ask).toList().last() as ChatChunk.Completed).response.message.text
            } else {
                world.gateway.chat(model.id, ask).message.text
            }
        }
        return Triple(world, client, outcome)
    }

    @Test
    fun `a retry is checked again in full and not sent when policy, the registry, the address or the key changed during the pause`() = runTest {
        val changes: List<Pair<String, suspend TestWorld.(ModelProvider, ModelDescriptor) -> Unit>> = listOf(
            "local-only mode switched on" to { _, _ -> settings.update { it.copy(localOnlyMode = true) } },
            "model disabled" to { _, m -> providerManager.setModelEnabled(m.id, false) },
            "model removed" to { _, m -> providerManager.removeModel(m.id) },
            "provider disabled" to { p, _ -> providerManager.setProviderEnabled(p.id, false) },
            "provider removed, with its key" to { p, _ -> providerManager.removeProvider(p.id) },
            "key replaced" to { p, _ -> vault.put(p.apiKeyAlias!!, "sk-test-replacement-key") },
            "key deleted" to { p, _ -> vault.delete(p.apiKeyAlias!!) },
            "address changed" to { p, _ -> providers.upsert(providers.get(p.id)!!.copy(baseUrl = "https://elsewhere.example.com/v1")) },
            "headers changed" to { p, _ -> providers.upsert(providers.get(p.id)!!.copy(extraHeaders = mapOf("X-Title" to "Other"))) },
        )
        for (streamed in listOf(false, true)) {
            for ((label, change) in changes) {
                val (world, client, outcome) = retryAfter(streamed, change)
                val case = "$label (streamed: $streamed)"

                val error = outcome.exceptionOrNull()
                assertTrue(error is LlmException.BlockedByPolicy, "$case: $error")
                assertTrue("not repeated" in error.message.orEmpty(), case)
                assertEquals(1, client.requests.size, "$case: the prompt was not sent again")
                val event = world.events(ActivityType.MODEL_CALL).single()
                assertEquals("Model call blocked", event.title, case)
                assertEquals(1, event.data!!.getValue("attempts").jsonPrimitive.int, case)
                assertEquals("api.example.com", event.egress?.destinationHost, "$case: the first attempt did leave the device")
                assertFalse(secret in world.logText(), case)
            }
        }
    }

    @Test
    fun `what does not change where or how a call goes does not stop its retry`() = runTest {
        val (world, client, outcome) = retryAfter(streamed = false) { p, _ -> connections.storeStatus(p.id, ConnectionStatus.OFFLINE, "seen by another call") }
        assertEquals("sent again", outcome.getOrThrow())
        assertEquals(2, client.requests.size)
        assertEquals(2, world.events(ActivityType.MODEL_CALL).single().data!!.getValue("attempts").jsonPrimitive.int)
    }

    @Test
    fun `the size of what left the device counts every attempt that was sent`() = runTest {
        val client = ScriptedLlmClient.script(ScriptStep.Reply("first"), ScriptStep.Fail(LlmException.Overloaded("busy")), ScriptStep.Reply("second"))
        val model = world.model(world.provider(FakeProviderAdapter(client = client), baseUrl = "https://api.example.com/v1"))

        world.gateway.chat(model.id, ask)
        world.gateway.chat(model.id, ask)

        val (once, twice) = modelCalls().map { assertNotNull(it.egress?.approxBytes) }
        assertTrue(once > 0)
        assertEquals(2 * once, twice)
    }

    @Test
    fun `a removed provider's adapter does not come back into the cache through a record read before the removal`() = runTest {
        val provider = world.provider(baseUrl = "https://api.example.com/v1", apiKey = secret)
        world.providerManager.removeProvider(provider.id)

        assertFailsWith<LlmException.BlockedByPolicy> { world.connections.acquire(provider) }

        assertTrue(world.factories.getValue(ProviderKind.OPENAI_COMPATIBLE).connections.isEmpty(), "no adapter was even built")
    }

    // ---- a key goes only where it was saved for ----

    private val keyA = "sk-test-key-for-host-a-000001"
    private val keyB = "sk-test-key-for-host-b-000002"
    private val toB = ProviderDraft("Cloud", ProviderKind.OPENAI_COMPATIBLE, "https://b.example.com/v1", apiKey = keyB)

    @Test
    fun `an edit that lands while a call is being prepared never pairs a key with an address it was not saved for`() = runTest {
        // The edit lands after the gateway read the record and before the lease is built.
        for (streamed in listOf(false, true)) {
            Racing().run {
                val (provider, model) = cloud(keyA)
                providers.afterGet = { manager.updateProvider(provider.id, toB) }
                call(model.id, streamed)
                assertEquals(listOf("https://b.example.com/v1 $keyB chat"), sent, "streamed: $streamed - the new key, to the address it was entered with")
                assertEquals(listOf("b.example.com"), modelCalls().map { it.egress?.destinationHost }, "logged as going where it went")
            }
        }
        // The edit is attempted between the lease's read of the record and its read of the key: it waits.
        Racing().run {
            val (provider, model) = cloud(keyA)
            vault.beforeGet = {
                launch { manager.updateProvider(provider.id, toB) }
                yield()
            }
            call(model.id, streamed = false)
            assertEquals(listOf("https://a.example.com/v1 $keyA chat"), sent, "the old key, to the old address")
            assertEquals(listOf("a.example.com"), modelCalls().map { it.egress?.destinationHost })

            advanceUntilIdle()
            assertEquals("https://b.example.com/v1", providers.get(provider.id)?.baseUrl, "the edit went through after the read")
            call(model.id, streamed = false)
            assertEquals("https://b.example.com/v1 $keyB chat", sent.last())
        }
        // The edit moves the provider off the phone while local-only mode is on: policy applies to what the lease was built from.
        Racing().run {
            settings.update { it.copy(localOnlyMode = true) }
            val (provider, model) = cloud(keyA, baseUrl = "http://127.0.0.1:8080/v1")
            providers.afterGet = { manager.updateProvider(provider.id, toB) }
            val error = assertFailsWith<LlmException.BlockedByPolicy> { call(model.id, streamed = false) }
            assertTrue("Local-only mode is on" in error.message.orEmpty(), error.message)
            assertTrue(sent.isEmpty(), "nothing left the phone: $sent")
            assertEquals("Model call blocked", modelCalls().single().title)
        }
    }

    @Test
    fun `a check or a listing that an edit overtakes goes where the lease's record says, is logged so, and respects local-only mode`() = runTest {
        Racing().run {
            val (provider, _) = cloud(keyA)
            providers.afterGet = { manager.updateProvider(provider.id, toB) }
            manager.checkProvider(provider.id)
            providers.afterGet = { manager.updateProvider(provider.id, toB.copy(baseUrl = "https://c.example.com/v1", apiKey = keyA)) }
            manager.refreshModels(provider.id)

            assertEquals(listOf("https://b.example.com/v1 $keyB check", "https://c.example.com/v1 $keyA list"), sent)
            val contacts = log.all.filter { it.egress != null }.map { it.title to it.egress?.destinationHost }
            assertEquals(listOf("Provider checked" to "b.example.com", "Model list requested" to "c.example.com"), contacts)
        }
        Racing().run {
            settings.update { it.copy(localOnlyMode = true) }
            val (checked, _) = cloud(keyA, baseUrl = "http://127.0.0.1:8080/v1", name = "Checked")
            val (listed, _) = cloud(keyA, baseUrl = "http://127.0.0.1:8081/v1", name = "Listed")
            providers.afterGet = { manager.updateProvider(checked.id, toB.copy(name = "Checked")) }
            val health = manager.checkProvider(checked.id)
            assertTrue("Local-only mode is on" in health.message.orEmpty(), health.message)
            providers.afterGet = { manager.updateProvider(listed.id, toB.copy(name = "Listed", baseUrl = "https://c.example.com/v1")) }
            assertFailsWith<LlmException.BlockedByPolicy> { manager.refreshModels(listed.id) }
            assertEquals("https://c.example.com/v1", providers.get(listed.id)?.baseUrl, "the edit did land before the listing")
            assertTrue(sent.isEmpty(), "nothing left the phone: $sent")
        }
    }

    /** A provider repository that runs [afterGet] once, after its next read and before handing back what it read. */
    private class HookedProviders(private val inner: InMemoryProviderRepository = InMemoryProviderRepository()) : ProviderRepository by inner {
        var afterGet: (suspend () -> Unit)? = null

        override suspend fun get(id: ProviderId): ModelProvider? {
            val read = inner.get(id)
            afterGet?.let {
                afterGet = null
                it()
            }
            return read
        }
    }

    /** A vault that runs [beforeGet] once, before its next read. */
    private class HookedVault(private val inner: InMemorySecretVault = InMemorySecretVault()) : SecretVault by inner {
        var beforeGet: (suspend () -> Unit)? = null

        override suspend fun get(alias: String): String? {
            beforeGet?.let {
                beforeGet = null
                it()
            }
            return inner.get(alias)
        }
    }

    /** Gateway and manager over hooked storage, with adapters that note the address, the key and the request of everything sent. */
    private class Racing {
        val providers = HookedProviders()
        val vault = HookedVault()
        val models = InMemoryModelRepository()
        val settings = InMemorySettingsRepository()
        val log = InMemoryActivityLog()
        val clock = FakeClock(autoAdvanceMs = 1)
        val ids = SequentialIdGenerator()
        val sent = CopyOnWriteArrayList<String>()
        private val factory = FakeProviderAdapterFactory(ProviderKind.OPENAI_COMPATIBLE) { connection ->
            val to = "${connection.provider.baseUrl} ${connection.apiKey}"
            object : ProviderAdapter {
                override suspend fun checkHealth(): ProviderHealth = ProviderHealth(ConnectionStatus.ONLINE).also { sent += "$to check" }
                override suspend fun listModels(): List<RemoteModel> = emptyList<RemoteModel>().also { sent += "$to list" }
                override val client = ScriptedLlmClient("scripted-1") { _, _ ->
                    sent += "$to chat"
                    ScriptStep.Reply("answer")
                }
            }
        }
        val connections = ProviderConnections(providers, vault, listOf(factory), clock)
        private val activity = ActivityRecorder(log, ids, clock)
        val manager = DefaultProviderManager(providers, models, settings, vault, connections, activity, ids, clock)
        val gateway = DefaultModelGateway(providers, models, settings, connections, activity, clock, RetryPolicy.NONE)

        suspend fun cloud(key: String, baseUrl: String = "https://a.example.com/v1", name: String = "Cloud"): Pair<ModelProvider, ModelDescriptor> {
            val provider = manager.addProvider(ProviderDraft(name, ProviderKind.OPENAI_COMPATIBLE, baseUrl, apiKey = key))
            return provider to manager.addModel(provider.id, "scripted-1", null, ModelCapabilities(toolCalling = true))
        }

        suspend fun call(model: ModelId, streamed: Boolean) {
            val ask = ChatRequest(messages = listOf(ChatMessage.user("Hello there")))
            if (streamed) gateway.chatStream(model, ask).toList() else gateway.chat(model, ask)
        }

        fun modelCalls() = log.all.filter { it.type == ActivityType.MODEL_CALL }
    }
}
