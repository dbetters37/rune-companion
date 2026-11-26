package com.aicontrolcenter.app.testing

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource

/*
 * WP10a's test helpers (usage, cost, daily limits): a team on a model in the cloud, so the daily limits count its calls,
 * and a day of recorded usage for the Usage and cost pictures.
 */

/** The cloud address the scripted team model is served at: off this phone, so today's limits count its calls. */
const val CLOUD_MODEL_URL = "https://api.example.test/v1"

/** Seeds the starter team and gives every agent [model], served "in the cloud" (a fake adapter: nothing reaches a network). */
suspend fun TestApp.teamOnCloud(model: TeamModel, name: String = "Example Cloud"): ModelDescriptor {
    graph.start().join()
    val served = serveModel(
        model.client, name = name, baseUrl = CLOUD_MODEL_URL, apiKey = "test-key-not-real",
        modelName = "scripted-cloud-1", displayName = "Scripted model",
        capabilities = ModelCapabilities(toolCalling = true, jsonOutput = true),
    )
    for (agent in agents.getAll()) graph.agentManager.assignModel(agent.id, served.id)
    return served
}

/** A day of usage as the gateway records it: two cloud models (one priced, one not), a model on this phone, a slow-down. */
suspend fun TestApp.recordSampleUsage(now: Long = TestApp.START_TIME) {
    val researcher = agents.getAll().firstOrNull { it.name == "Researcher" }?.id
    val writer = agents.getAll().firstOrNull { it.name == "Writer" }?.id
    fun call(
        minutesAgo: Int, model: String, label: String, provider: String, providerLabel: String, locality: Locality,
        agent: AgentId?, outcome: CallOutcome = CallOutcome.OK, error: ErrorClass? = null, input: Long = 2_400, output: Long = 700,
        answerMs: Long = 4_200, ttftMs: Long? = 900,
    ) = ModelCallSample(
        at = now - minutesAgo * 60_000L, modelId = ModelId(model), modelLabel = label, providerId = ProviderId(provider),
        providerLabel = providerLabel, locality = locality, agentId = agent, source = UsageSource.OBJECTIVE, outcome = outcome,
        errorClass = error, inputTokens = if (outcome == CallOutcome.OK) input else null, outputTokens = if (outcome == CallOutcome.OK) output else null,
        answerMs = if (outcome == CallOutcome.OK) answerMs else null, ttftMs = if (outcome == CallOutcome.OK) ttftMs else null,
    )
    repeat(14) { usage.record(call(10 + it, "mdl_grok", "Grok 4", "prv_xai", "xAI", Locality.CLOUD, researcher)) }
    repeat(3) { usage.record(call(40 + it, "mdl_grok", "Grok 4", "prv_xai", "xAI", Locality.CLOUD, writer, input = 5_200, output = 1_900)) }
    usage.record(call(50, "mdl_grok", "Grok 4", "prv_xai", "xAI", Locality.CLOUD, researcher, outcome = CallOutcome.FAILED, error = ErrorClass.NETWORK))
    repeat(2) { usage.record(call(55 + it, "mdl_grok", "Grok 4", "prv_xai", "xAI", Locality.CLOUD, writer, outcome = CallOutcome.FAILED, error = ErrorClass.RATE_LIMITED)) }
    repeat(4) { usage.record(call(60 + it, "mdl_llama", "Llama 3.3 70B", "prv_groq", "Groq", Locality.CLOUD, writer, input = 1_800, output = 400, answerMs = 1_100, ttftMs = 250)) }
    repeat(6) { usage.record(call(70 + it, "mdl_simulator", "Simulator (not a real model)", "prv_simulator", "Simulator", Locality.ON_DEVICE, null, input = 300, output = 120, answerMs = 40, ttftMs = 5)) }
    // Earlier this month and last week, for the other periods.
    repeat(25) { usage.record(call(3 * 24 * 60 + it, "mdl_grok", "Grok 4", "prv_xai", "xAI", Locality.CLOUD, researcher)) }
    prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000))
}
