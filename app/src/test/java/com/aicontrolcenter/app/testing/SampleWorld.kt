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
import com.aicontrolcenter.testkit.Samples

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
