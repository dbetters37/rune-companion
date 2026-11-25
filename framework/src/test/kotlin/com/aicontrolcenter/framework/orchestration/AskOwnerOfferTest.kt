package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 (WP1c): a work task offers the question to the owner only where that tool is
 * registered and while the owner allows agents' questions. Nothing registers it in this wave, so
 * no request changes.
 */
class AskOwnerOfferTest {
    /** Stands in for the question tool, which a later package builds; only its id and name matter here. */
    private class AskOwnerStandIn : Tool {
        override val spec = ToolSpec(
            id = TeamTools.ASK_OWNER_ID, functionName = "team_ask_owner", title = "Ask the owner", description = "Stand-in.",
            parameters = buildJsonObject { put("type", "object") }, category = ToolCategory.COMMUNICATION,
            capability = Capabilities.BASIC_INFO, risk = RiskLevel.SAFE,
        )

        override suspend fun assess(arguments: JsonObject, context: ToolContext) = ActionAssessment(RiskLevel.SAFE, "Ask the owner")

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome = ToolOutcome.failure("not built")
    }

    /** The tools the Researcher's first work request offered, after one objective with one task. */
    private suspend fun TestScope.offered(register: Boolean, settings: FrameworkSettings.() -> FrameworkSettings = { this }): List<String> {
        val team = TeamFixture(this, settings).setUp()
        if (register) team.world.toolRegistry.register(AskOwnerStandIn())
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Find apps", listOf("research"))))) }
        val objective = team.start()
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
        return team.script.seenBy(OrchestrationProtocol.TASK).first().request.tools.map { it.name }
    }

    @Test
    fun `the question to the owner is offered where it is registered and the owner allows questions`() = runTest {
        assertTrue(FrameworkSettings().agentQuestionsEnabled, "questions are allowed by default (Q3)")
        assertTrue("team_ask_owner" in offered(register = true))
    }

    @Test
    fun `it is not offered while the owner does not allow questions`() = runTest {
        assertTrue("team_ask_owner" !in offered(register = true) { copy(agentQuestionsEnabled = false) })
    }

    @Test
    fun `it is not offered where it is not registered, so nothing changes in this wave`() = runTest {
        val tools = offered(register = false)
        assertTrue("team_ask_owner" !in tools)
        assertTrue(tools.none { it.startsWith("team_ask_owner") })
    }
}
