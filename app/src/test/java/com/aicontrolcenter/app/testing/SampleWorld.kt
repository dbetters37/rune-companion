package com.aicontrolcenter.app.testing

import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Realistic registry states for screenshots and journeys, built only through the framework
 * services and the in-memory ports, so what is pictured is what the app would really show.
 */

private const val HOUR = 60L * 60 * 1000

/**
 * The app as its owner has it in Phase 1 after a few minutes of use: the starter team (none
 * with a model, one switched off), a scanned device with one app that has since been
 * uninstalled, one unverified guess and one app added by hand, and the activity all that left.
 */
suspend fun TestApp.populatePhaseOne() {
    // Two entries from the day before, so the log shows more than one day.
    activityLog.record(
        ActivityEvent(EventId("evt_seed_1"), TestApp.START_TIME - 20 * HOUR, ActivityType.SYSTEM, title = "App installed", detail = "Version 0.1.0"),
    )
    activityLog.record(
        ActivityEvent(
            EventId("evt_seed_2"), TestApp.START_TIME - 19 * HOUR, ActivityType.ERROR, Severity.ERROR,
            title = "Could not refresh AI app statuses", detail = "The package manager did not answer in time.",
        ),
    )
    graph.start().join()
    graph.agentManager.setEnabled(agentNamed("Operator").profile.id, false)

    installSampleApps()
    graph.aiAppRegistry.discover()
    graph.aiAppRegistry.registerManually("org.example.terminal")
    val chatbot = appNamed("Example Chatbot")
    graph.aiAppRegistry.updateNotes(chatbot.id, "Signed in with the work account.")
    graph.aiAppLauncher.handOffText(chatbot, "Summarise the attached meeting notes in five bullet points.")

    // Sample Assistant is uninstalled behind the app's back.
    scanner.aiApps = listOf(TestApp.chatbot, TestApp.lookalike)
    graph.aiAppRegistry.refreshStatuses()
}

/**
 * The same owner one phase later, with providers and models in the registry: every agent
 * status the app can show appears at least once.
 */
suspend fun TestApp.populateWithModels() {
    populatePhaseOne()
    providers.upsert(Samples.provider())
    providers.upsert(
        Samples.provider(
            id = "prv_cloud", name = "Hosted API", kind = ProviderKind.ANTHROPIC, baseUrl = null,
            locality = Locality.CLOUD, createdAt = 2_000,
        ),
    )
    // The sample providers point at key aliases; give the cloud one a key, as adding it would have.
    vault.put("provider/prv_cloud", "sk-sample-0000000000000000")
    providers.upsert(
        Samples.provider(
            id = "prv_phone", name = "Phone runtime", kind = ProviderKind.OLLAMA, baseUrl = "http://127.0.0.1:11434",
            locality = Locality.ON_DEVICE, status = ConnectionStatus.OFFLINE, createdAt = 3_000,
        ),
    )
    models.upsert(Samples.model())
    models.upsert(Samples.model(id = "mdl_cloud", providerId = "prv_cloud", modelName = "large-reasoner-2", createdAt = 2_000).copy(displayName = "Large Reasoner 2", localPath = null))
    models.upsert(Samples.model(id = "mdl_phone", providerId = "prv_phone", modelName = "qwen3:1.7b", createdAt = 3_000).copy(displayName = "Qwen 3 1.7B"))

    val manager = graph.agentManager
    manager.assignModel(director().profile.id, ModelId("mdl_cloud"))
    val researcher = agentNamed("Researcher").profile
    manager.assignModel(researcher.id, ModelId("mdl_sample"))
    manager.assignModel(agentNamed("Analyst").profile.id, ModelId("mdl_phone"))
    agents.updateStats(researcher.id) {
        AgentStats(
            tasksAssigned = 14, tasksCompleted = 11, tasksFailed = 1, tasksTimedOut = 1, tasksCancelled = 1,
            reviewsPassed = 8, revisionsRequested = 3, reviewsRejected = 1, modelCalls = 96, modelErrors = 2,
            toolCalls = 41, toolErrors = 1, inputTokens = 182_400, outputTokens = 37_950,
            totalTaskDurationMs = 13 * 4 * 60_000L, lastActiveAt = TestApp.START_TIME,
        )
    }
}

/** Where each Phase 2 sample provider lives. */
object SampleUrls {
    const val PHONE = "http://127.0.0.1:11434"
    const val DESK = "http://192.168.1.40:1234/v1"
    const val OPENROUTER = "https://openrouter.ai/api/v1"
}

/**
 * The owner one phase later: providers of every locality and state, connected through the real
 * provider manager (an Ollama server on the phone and a cloud service that answer, a desk PC that
 * is switched off, a cloud service still without a key), their models, and most of the team
 * given one. [phoneClient] answers for the models on the phone.
 */
suspend fun TestApp.populateWithProviders(phoneClient: LlmClient = ScriptedLlmClient.replies("Done.")) {
    populatePhaseOne()
    val manager = graph.providerManager
    adaptersByUrl[SampleUrls.PHONE] = FakeProviderAdapter(
        models = listOf(
            RemoteModel("llama3.2:3b", "Llama 3.2 3B", ModelCapabilities(toolCalling = true, contextWindow = 131_072, reasoning = CapabilityTier.BASIC, coding = CapabilityTier.BASIC)),
            RemoteModel("qwen3:1.7b", "Qwen3 1.7B", ModelCapabilities(toolCalling = true, contextWindow = 40_960, reasoning = CapabilityTier.BASIC)),
        ),
        client = phoneClient,
        health = ProviderHealth(ConnectionStatus.ONLINE, latencyMs = 8),
    )
    val phone = manager.addProvider(ProviderDraft("Ollama on this phone", ProviderKind.OLLAMA, SampleUrls.PHONE))
    manager.checkProvider(phone.id)
    manager.refreshModels(phone.id)

    adaptersByUrl[""] = FakeProviderAdapter(
        models = listOf(
            RemoteModel(
                "claude-sonnet-4-5", "Claude Sonnet 4.5",
                ModelCapabilities(toolCalling = true, vision = true, contextWindow = 200_000, maxOutputTokens = 64_000, reasoning = CapabilityTier.STRONG, coding = CapabilityTier.STRONG),
            ),
        ),
        health = ProviderHealth(ConnectionStatus.ONLINE, latencyMs = 240),
    )
    val cloud = manager.addProvider(ProviderDraft("Anthropic", ProviderKind.ANTHROPIC, null, apiKey = "sk-ant-sample-0000000000000000"))
    manager.checkProvider(cloud.id)
    manager.refreshModels(cloud.id)

    adaptersByUrl[SampleUrls.DESK] = FakeProviderAdapter(
        health = ProviderHealth(ConnectionStatus.OFFLINE, "Connection refused: nothing is listening at 192.168.1.40:1234."),
    )
    val desk = manager.addProvider(ProviderDraft("Desk PC (LM Studio)", ProviderKind.OPENAI_COMPATIBLE, SampleUrls.DESK))
    manager.addModel(desk.id, "qwen2.5-coder-14b-instruct", "Qwen2.5 Coder 14B")
    manager.checkProvider(desk.id)

    manager.addProvider(ProviderDraft("OpenRouter", ProviderKind.OPENAI_COMPATIBLE, SampleUrls.OPENROUTER))

    val models = models.getAll()
    fun model(name: String) = models.first { it.modelName == name }.id
    val agents = graph.agentManager
    agents.assignModel(director().profile.id, model("claude-sonnet-4-5"))
    agents.assignModel(agentNamed("Researcher").profile.id, model("llama3.2:3b"))
    agents.assignModel(agentNamed("Analyst").profile.id, model("qwen2.5-coder-14b-instruct"))
    agents.assignModel(agentNamed("Writer").profile.id, Simulator.MODEL_ID)
}

/** What the Researcher's model on the phone says in the sample conversation: it looks up the time and the team, then answers. */
fun sampleResearcherClient(): ScriptedLlmClient = ScriptedLlmClient.script(
    ScriptStep.Calls(
        listOf(
            ScriptedLlmClient.call("call_1", "clock_now", buildJsonObject { put("timezone", JsonPrimitive("Europe/Berlin")) }),
            ScriptedLlmClient.call("call_2", "team_list_agents"),
        ),
        text = "Let me check the time and who is on the team.",
        usage = TokenUsage(1_204, 38),
    ),
    ScriptStep.Reply(
        "It is 13:44 in Berlin. Five agents are on the team: the Director, Analyst, Writer, Reviewer and me. " +
            "The Analyst cannot work right now because its server is offline.",
        usage = TokenUsage(1_530, 61),
    ),
    modelName = "llama3.2:3b",
)
