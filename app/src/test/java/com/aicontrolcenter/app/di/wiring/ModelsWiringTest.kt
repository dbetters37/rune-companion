package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.framework.llm.DefaultProviderRechecks
import com.aicontrolcenter.framework.llm.VaultKeyCheck
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * WP4's wiring (ModelsWiring.kt): the app's gateway records usage and keeps today's limits from the first start (the
 * owner's Q2), the provider re-checks run, the owner's key check reads the vault, and the registry snapshot is shared.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsWiringTest {
    private val ask = ChatRequest(messages = listOf(ChatMessage.user("Hello")))

    /** A cloud provider and model served by [client]. */
    private suspend fun TestApp.cloudModel(client: ScriptedLlmClient = ScriptedLlmClient.replies("ok")): com.aicontrolcenter.core.registry.ModelDescriptor {
        val url = "https://api.example.com/v1"
        adaptersByUrl[url] = FakeProviderAdapter(client = client)
        providers.upsert(Samples.provider(id = "prv_cloud", name = "Cloud", baseUrl = url, locality = Locality.CLOUD))
        val model = Samples.model(id = "mdl_cloud", providerId = "prv_cloud")
        models.upsert(model)
        return model
    }

    @Test
    fun `after installing, the owner's daily limits are on and the gateway keeps them`() = runTest {
        val app = TestApp(backgroundScope)
        // Morning in the zone the app's limits use, so the test's moving clock stays inside one day.
        app.clock.current = LocalDate.of(2026, 11, 14).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(DailyLimits.DEFAULT, app.graph.settingsView.get().dailyLimits)
        val client = ScriptedLlmClient.replies("ok")
        val model = app.cloudModel(client)
        val limiter = app.graph.modelsAssembly.dailyLimiter

        // 199 calls already counted today, then the 200th through the app's gateway: it goes; the 201st does not.
        repeat(199) { limiter.settle(limiter.reserve(model.id, Locality.CLOUD), model.id, null, app.clock.now()) }
        app.graph.modelGateway.chat(model.id, ask)
        val refused = assertFailsWith<LlmException.DailyLimitReached> { app.graph.modelGateway.chat(model.id, ask) }

        assertTrue(refused.message!!.startsWith("Today's limit of 200 model answers is reached."))
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `every call through the app's gateway is recorded in the usage the screens read`() = runTest {
        val app = TestApp(backgroundScope)
        val model = app.cloudModel()

        app.graph.modelGateway.chat(model.id, ask)

        val usage = app.graph.usageReports.byModel(UsageRange.ALL).single()
        assertEquals(model.id, usage.modelId)
        assertEquals(1, usage.totals.ok)
        assertEquals(Locality.CLOUD, usage.locality)
    }

    @Test
    fun `the provider re-checks run in the app on their own time, and the wake alarm runs what is due by the wall clock`() = runTest {
        val app = TestApp(backgroundScope)
        val rechecks = app.graph.providerRechecks
        assertIs<DefaultProviderRechecks>(rechecks)
        assertSame(app.graph.modelsAssembly.providerRechecks, rechecks)
        val checks = AtomicInteger()
        val down = object : ProviderAdapter {
            override val client = ScriptedLlmClient.replies("unused")

            override suspend fun checkHealth(): ProviderHealth {
                checks.incrementAndGet()
                return ProviderHealth(ConnectionStatus.OFFLINE, "Nothing answered.")
            }

            override suspend fun listModels(): List<RemoteModel> = emptyList()
        }
        app.cloudModel()
        app.adaptersByUrl["https://api.example.com/v1"] = down
        app.providers.upsert(app.providers.get(Samples.provider(id = "prv_cloud").id)!!.copy(status = ConnectionStatus.OFFLINE))
        runCurrent()

        // The test app's clock moves 37 seconds at every reading; the schedule runs on its own time, so nothing is
        // checked before a minute has really passed, and the wake alarm's time is published.
        val due = assertNotNull(rechecks.timedWaits.nextDueAt.value)
        assertEquals(0, checks.get())
        assertTrue(due > TestApp.START_TIME)

        // The phone slept for two minutes, then the wake alarm rings: by the wall clock the first check is due, so it runs.
        app.clock.advance(2 * 60_000L)
        rechecks.timedWaits.wake()
        runCurrent()

        assertEquals(1, checks.get())
    }

    @Test
    fun `the owner's key check compares answers with the keys in the app's vault`() = runTest {
        val app = TestApp(backgroundScope)
        val check = app.graph.modelsAssembly.ownerKeyCheck
        assertIs<VaultKeyCheck>(check)
        app.vault.put("provider/prv_cloud/1", "xai-0123456789abcdefABCDEF")

        assertTrue(check.matchesStoredKey("my key is xai-0123456789abcdefABCDEF"))
        assertTrue(!check.matchesStoredKey("my key is xai-9999999999999999999999"))
    }

    @Test
    fun `every screen observing the registry shares one snapshot`() = runTest {
        val app = TestApp(backgroundScope)

        assertSame(app.graph.registryDirectory.observe(), app.graph.registryDirectory.observe())
    }
}
