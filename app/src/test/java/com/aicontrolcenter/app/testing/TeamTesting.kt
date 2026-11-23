package com.aicontrolcenter.app.testing

import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.framework.orchestration.OrchestrationProtocol
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Running objectives in tests: the starter team on the built-in simulator (the whole pipeline
 * offline), or on a scripted model that answers each orchestration brief as the test says.
 */

const val DAY_MS = 24L * 60 * 60 * 1000

/** Seeds the starter team and gives every agent the simulator, so an objective runs end to end offline. */
suspend fun TestApp.teamOnSimulator() {
    graph.start().join()
    for (agent in agents.getAll()) graph.agentManager.assignModel(agent.id, Simulator.MODEL_ID)
}

/**
 * Lets the team work. The orchestrator runs in the app scope, which is the test's background
 * scope on virtual time, and `advanceUntilIdle` leaves background work alone.
 */
fun TestScope.letTheTeamWork(ms: Long = DAY_MS) {
    advanceTimeBy(ms)
    runCurrent()
}

/** One request the team model received: who sent it and which brief it answers. */
data class TeamRequest(val agent: String, val marker: String?, val title: String, val request: ChatRequest)

/**
 * One model for the whole team. Each request is routed by the protocol marker of its newest
 * brief and the agent that sends it (read from the system prompt); every handler may suspend,
 * so a test can hold a task while it looks at the screen.
 */
class TeamModel {
    val seen = CopyOnWriteArrayList<TeamRequest>()
    var plan: suspend (TeamRequest) -> ScriptStep = { ScriptStep.Reply(planJson(listOf(task("t1", "Research offline note apps")))) }
    var work: suspend (TeamRequest) -> ScriptStep = { ScriptStep.Reply("## ${it.title}\nDone by ${it.agent}.") }
    var review: suspend (TeamRequest) -> ScriptStep = { ScriptStep.Reply(verdict("approved", "Meets the criteria.")) }
    var arbitrate: suspend (TeamRequest) -> ScriptStep = { ScriptStep.Reply("""{"decision": "accept", "reason": "Good enough."}""") }
    var synthesis: suspend (TeamRequest) -> ScriptStep = { ScriptStep.Reply("# Final\nAll done.") }

    val client = ScriptedLlmClient { request, _ ->
        val brief = request.messages.lastOrNull { it.role == ChatRole.USER }?.text.orEmpty()
        val system = request.messages.firstOrNull { it.role == ChatRole.SYSTEM }?.text.orEmpty()
        val agent = AGENT_NAME.find(system)?.groupValues?.get(1) ?: "?"
        val seen = TeamRequest(agent, OrchestrationProtocol.markerIn(brief), TITLE.find(brief)?.groupValues?.get(1)?.trim().orEmpty(), request)
        this.seen += seen
        when (seen.marker) {
            OrchestrationProtocol.PLAN -> plan(seen)
            OrchestrationProtocol.TASK -> work(seen)
            OrchestrationProtocol.REVIEW -> review(seen)
            OrchestrationProtocol.ARBITRATE -> arbitrate(seen)
            OrchestrationProtocol.SYNTHESIS -> synthesis(seen)
            else -> ScriptStep.Reply("Answer from $agent.")
        }
    }

    companion object {
        private val AGENT_NAME = Regex("^You are (.+?), an? ")
        private val TITLE = Regex("Title: (.+)")

        fun task(key: String, title: String, dependsOn: List<String> = emptyList(), needsReview: Boolean = false, skills: List<String> = listOf("research")) =
            buildJsonObject {
                put("key", key)
                put("title", title)
                put("instructions", "Do: $title")
                put("completion_criteria", buildJsonArray { add("Covers $title") })
                put("skills", buildJsonArray { skills.forEach { add(it) } })
                put("depends_on", buildJsonArray { dependsOn.forEach { add(it) } })
                put("needs_review", needsReview)
            }

        fun planJson(tasks: List<kotlinx.serialization.json.JsonObject>, interpretation: String = "Find the best offline note app.") = buildJsonObject {
            put("interpretation", interpretation)
            put("tasks", buildJsonArray { tasks.forEach { add(it) } })
            put("final_instructions", "Write it up.")
        }.toString()

        fun verdict(decision: String, summary: String, vararg issues: String) = buildJsonObject {
            put("decision", decision)
            put("summary", summary)
            put("issues", buildJsonArray { issues.forEach { add(it) } })
        }.toString()
    }
}

/** Seeds the starter team and gives every agent [model], served on this phone. */
suspend fun TestApp.teamOn(model: TeamModel) {
    graph.start().join()
    val served = serveModel(model.client, capabilities = ModelCapabilities(toolCalling = true, jsonOutput = true))
    for (agent in agents.getAll()) graph.agentManager.assignModel(agent.id, served.id)
}

/**
 * The team in the middle of an objective, live: the Researcher and the Analyst each on a task
 * that does not finish, the Analyst having asked the Researcher a question first, and the
 * writing waiting for both. For pictures of the dashboard while agents work.
 */
suspend fun TestApp.teamAtWork(): com.aicontrolcenter.core.tasks.TaskRecord {
    val model = TeamModel()
    val never = kotlinx.coroutines.CompletableDeferred<Unit>()
    model.plan = {
        ScriptStep.Reply(
            TeamModel.planJson(
                listOf(
                    TeamModel.task("t1", "Find offline-first note apps", skills = listOf("research")),
                    TeamModel.task("t2", "Compare the three best apps", skills = listOf("analysis", "comparison")),
                    TeamModel.task("t3", "Write the recommendation", dependsOn = listOf("t1", "t2"), skills = listOf("writing")),
                ),
            ),
        )
    }
    model.work = { request ->
        val answered = request.request.messages.lastOrNull()?.role == ChatRole.TOOL
        if (request.agent == "Analyst" && !answered) {
            ScriptStep.Calls(
                listOf(
                    ScriptedLlmClient.call(
                        "ask_1", "team_ask_agent",
                        buildJsonObject {
                            put("agent", "Researcher")
                            put("question", "Which of the apps you found sync without an account?")
                        },
                    ),
                ),
            )
        } else {
            never.await()
            ScriptStep.Reply("Done.")
        }
    }
    teamOn(model)
    return graph.orchestrator.startObjective("Research offline-first note apps, compare the best three and write a recommendation")
}
