package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ProviderKind

/**
 * The built-in stand-in for a model: deterministic, offline and free, so the whole pipeline
 * (agent, gateway, tools, chat, activity log) can be tried before any provider is connected.
 * It is not intelligence and never passes as it: every reply says that it is simulated.
 */
object Simulator {
    /** Fixed ids, so the built-in provider and model are recognised on every start. */
    val PROVIDER_ID = ProviderId("prv_simulator")
    val MODEL_ID = ModelId("mdl_simulator")

    const val PROVIDER_NAME = "Simulator"
    const val MODEL_NAME = "simulator-1"
    const val MODEL_DISPLAY_NAME = "Simulator (not a real model)"

    /** Opens every simulated reply. */
    const val DISCLOSURE = "Simulated reply. No real AI model wrote this."

    /** Tool calling is declared so that the native tool path is exercised; the tiers stay unknown because there is no ability to rate. */
    val capabilities = ModelCapabilities(chat = true, toolCalling = true, vision = false, streaming = true, jsonOutput = true)
}

/** Registers the simulator as a provider kind like any other. */
class SimulatorProviderFactory(
    /** Pause between streamed pieces, so a simulated reply arrives the way a real one does. */
    private val chunkDelayMs: Long = 20,
) : ProviderAdapterFactory {
    override val kind: ProviderKind = ProviderKind.SIMULATOR

    override fun create(connection: ProviderConnection): ProviderAdapter = SimulatorAdapter(chunkDelayMs)
}

private class SimulatorAdapter(chunkDelayMs: Long) : ProviderAdapter {
    override val client: LlmClient = SimulatorLlmClient(chunkDelayMs)

    override suspend fun checkHealth(): ProviderHealth = ProviderHealth(ConnectionStatus.ONLINE, latencyMs = 0)

    override suspend fun listModels(): List<RemoteModel> =
        listOf(RemoteModel(Simulator.MODEL_NAME, Simulator.MODEL_DISPLAY_NAME, Simulator.capabilities, ownedBy = "AI Control Center"))
}
