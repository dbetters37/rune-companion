package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.tools.BuiltInTools
import com.aicontrolcenter.testkit.InMemoryMessageRepository
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** One request the scripted model received, with the agent that sent it and the brief's marker. */
data class Seen(val agent: String, val marker: String?, val brief: String, val request: ChatRequest)

/**
 * A scripted model for the whole team: each request is routed by the protocol marker of its
 * newest brief and by the agent that sends it (read from the system prompt). Every handler may
 * suspend, so tests can hold a task, or let virtual time run out.
 */
class TeamScript {
    val seen = CopyOnWriteArrayList<Seen>()
    var plan: suspend (Seen, attempt: Int) -> ScriptStep = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t1", "Research the subject", skills = listOf("research"))))) }
    var work: suspend (Seen) -> ScriptStep = { s -> ScriptStep.Reply("Result of ${s.agent}: ${titleOf(s.brief)}") }
    var review: suspend (Seen) -> ScriptStep = { ScriptStep.Reply(verdict("approved", "Meets the criteria.")) }
    var arbitrate: suspend (Seen) -> ScriptStep = { ScriptStep.Reply("""{"decision": "accept", "reason": "Good enough."}""") }
    var synthesis: suspend (Seen) -> ScriptStep = { ScriptStep.Reply("# Final\nAll done.") }
    var ask: suspend (Seen) -> ScriptStep = { s -> ScriptStep.Reply("Answer from ${s.agent}.") }
    private var planAttempts = 0

    val client = ScriptedLlmClient { request, _ ->
        val brief = request.messages.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty()
        val system = request.messages.firstOrNull { it.role == ChatRole.SYSTEM }?.text.orEmpty()
        val agent = Regex("^You are (.+?), an? ").find(system)?.groupValues?.get(1) ?: "?"
        val s = Seen(agent, OrchestrationProtocol.markerIn(brief), brief, request)
        seen += s
        when (s.marker) {
            OrchestrationProtocol.PLAN -> plan(s, planAttempts++)
            OrchestrationProtocol.TASK -> work(s)
            OrchestrationProtocol.REVIEW -> review(s)
            OrchestrationProtocol.ARBITRATE -> arbitrate(s)
            OrchestrationProtocol.SYNTHESIS -> synthesis(s)
            OrchestrationProtocol.ASK -> ask(s)
            else -> ScriptStep.Reply("unexpected")
        }
    }

    fun seenBy(marker: String) = seen.filter { it.marker == marker }

    /** The request came back with tool results: the agent's earlier call has been answered. */
    fun afterTools(s: Seen) = s.request.messages.lastOrNull()?.role == ChatRole.TOOL

    fun lastToolResult(s: Seen): String = s.request.messages.lastOrNull { it.role == ChatRole.TOOL }?.toolResult?.content.orEmpty()

    companion object {
        fun task(
            key: String,
            title: String,
            skills: List<String> = emptyList(),
            dependsOn: List<String> = emptyList(),
            needsReview: Boolean = false,
            assignee: String? = null,
            instructions: String = "Do: $title",
        ): JsonObject = buildJsonObject {
            put("key", key)
            put("title", title)
            put("instructions", instructions)
            put("completion_criteria", buildJsonArray { add("Covers $title") })
            put("skills", buildJsonArray { skills.forEach { add(it) } })
            assignee?.let { put("assignee", it) }
            put("depends_on", buildJsonArray { dependsOn.forEach { add(it) } })
            put("needs_review", needsReview)
        }

        fun planJson(tasks: List<JsonObject>, interpretation: String = "Do it well.") = buildJsonObject {
            put("interpretation", interpretation)
            put("tasks", buildJsonArray { tasks.forEach { add(it) } })
            put("final_instructions", "Write it up.")
        }.toString()

        fun verdict(decision: String, summary: String, vararg issues: String) = buildJsonObject {
            put("decision", decision)
            put("summary", summary)
            put("issues", buildJsonArray { issues.forEach { add(it) } })
        }.toString()

        /** The task title of a work or review brief. */
        fun titleOf(brief: String): String =
            brief.lineSequence().map { it.removePrefix(DataBlock.QUOTE) }.firstOrNull { it.startsWith(BriefWriter.FACT_TITLE) }?.removePrefix(BriefWriter.FACT_TITLE)?.trim().orEmpty()
    }
}

/**
 * The agent side of the providers' re-checks (D28) as a test sees it: every call is recorded, and [answer] decides what
 * a re-check does (by default nothing: the provider stays as it is stored). Never a provider manager.
 */
class FakeRechecks : ProviderRechecks {
    val calls = CopyOnWriteArrayList<Pair<ProviderId, Long>>()
    var answer: suspend (ProviderId) -> ConnectionStatus? = { null }

    override suspend fun recheckIfStale(providerId: ProviderId, olderThanMs: Long): ConnectionStatus? {
        calls += providerId to olderThanMs
        return answer(providerId)
    }

    override val timedWaits: TimedWaits get() = TimedWaits.None
}

/**
 * A team ready for objectives, over in-memory stores, with the orchestrator running in the
 * test's background scope (virtual time). The Director and three specialists share one
 * scripted model, unless a test gives an agent another one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TeamFixture(val test: TestScope, settings: FrameworkSettings.() -> FrameworkSettings = { this }) {
    val world = TestWorld(scope = test.backgroundScope).also { it.time.source = test.testTimeSource }
    val tasks = world.tasks
    val messages = InMemoryMessageRepository()
    val script = TeamScript()
    lateinit var model: ModelDescriptor
    lateinit var director: AgentProfile
    lateinit var researcher: AgentProfile
    lateinit var analyst: AgentProfile
    lateinit var writer: AgentProfile
    lateinit var reviewer: AgentProfile
    private val settingsChange = settings

    /** The providers' re-checks the orchestrator asks (D28). */
    val rechecks = FakeRechecks()

    /** True while a backup is restored, as the maintenance gate says. */
    val restoring = kotlinx.coroutines.flow.MutableStateFlow(false)

    val orchestrator = newOrchestrator()

    /** Another orchestrator over the same stores, as after a restart of the app; [scope] stands for the app's scope. */
    fun newOrchestrator(scope: CoroutineScope = test.backgroundScope) = DefaultOrchestrator(
        tasks, messages, world.conversations, world.agents, world.directory, world.settings, world.runner, world.toolRegistry,
        world.monitor, world.activity, world.ids, world.clock, scope, world.memory, timeSource = world.time, operatorHooks = world.operatorHooks,
        rechecks = rechecks, activityLog = world.log, zone = { ZoneOffset.UTC }, restoring = restoring,
    )

    init {
        TeamTools.registerAll(world.toolRegistry, world.capabilities, orchestrator)
    }

    suspend fun setUp(): TeamFixture {
        model = world.serve(script.client, ModelCapabilities(toolCalling = true, jsonOutput = true))
        director = agent("Director", AgentKind.DIRECTOR, setOf("planning", "coordination", "synthesis"), delegation = DelegationPolicy(canDelegate = true, maxDepth = 3))
        researcher = agent("Researcher", AgentKind.SPECIALIST, setOf("research", "fact-finding"), "Investigates subjects and reports findings.")
        analyst = agent("Analyst", AgentKind.SPECIALIST, setOf("analysis", "comparison", "planning"), "Compares options and recommends.")
        writer = agent("Writer", AgentKind.SPECIALIST, setOf("writing", "documentation"), "Writes clear documents.")
        reviewer = agent("Reviewer", AgentKind.REVIEWER, setOf("review", "fact-checking"), "Checks results against criteria.")
        world.settings.update { it.copy(directorAgentId = director.id).settingsChange() }
        return this
    }

    suspend fun agent(
        name: String,
        kind: AgentKind,
        skills: Set<String>,
        description: String = "",
        delegation: DelegationPolicy = DelegationPolicy(),
        model: ModelDescriptor? = this.model,
    ): AgentProfile {
        val at = world.clock.now()
        val profile = AgentProfile(
            id = AgentId(world.ids.newId("agt")), name = name, kind = kind, description = description,
            systemInstructions = "Secret instructions of $name.", skills = skills, modelId = model?.id,
            toolIds = BuiltInTools.defaultToolIds, delegation = delegation, createdAt = at, updatedAt = at,
        )
        world.agents.upsert(profile)
        return profile
    }

    suspend fun start(text: String = "Research note apps", options: ObjectiveOptions = ObjectiveOptions()): TaskRecord =
        orchestrator.startObjective(text, options)

    /**
     * Lets everything that can happen within [ms] of virtual time happen (a day by default:
     * every time limit has fired by then). The orchestrator runs in the background scope, whose
     * work advanceUntilIdle does not wait for; advancing time runs it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun settle(ms: Long = DAY_MS) {
        test.advanceTimeBy(ms)
        test.runCurrent()
    }

    /** Lets [ms] pass by the app's clock and in virtual time together, as on a phone (timed waits read the clock). */
    fun pass(ms: Long) {
        world.clock.advance(ms)
        settle(ms)
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val MINUTE_MS = 60_000L
    }

    suspend fun objective(id: TaskId): TaskRecord = tasks.get(id)!!

    suspend fun tree(id: TaskId): List<TaskRecord> = tasks.getTree(id)

    suspend fun work(id: TaskId): List<TaskRecord> = tree(id).filter { it.kind == TaskKind.WORK }.sortedBy { it.order }

    suspend fun reviews(id: TaskId): List<TaskRecord> = tree(id).filter { it.kind == TaskKind.REVIEW }

    suspend fun byTitle(id: TaskId, title: String): TaskRecord = tree(id).first { it.title == title }

    suspend fun messages(id: TaskId): List<AgentMessage> = messages.list(id)

    suspend fun messagesOf(id: TaskId, type: MessageType) = messages(id).filter { it.type == type }

    suspend fun limitMessages(id: TaskId) = messagesOf(id, MessageType.STATUS).filter { it.body.startsWith("Limit reached") }
}
