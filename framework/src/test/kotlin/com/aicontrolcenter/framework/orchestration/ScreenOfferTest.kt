package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP15's hand-off, item 5 (optional, taken): an objective whose options allow no screens (a routine without the tick,
 * F12) does not offer the screen tools to its agents at all, which saves their definitions on every call; the broker
 * refuses them anyway. An objective the owner starts offers them as before.
 */
class ScreenOfferTest {

    private class ScreenRead : Tool {
        override val spec = ToolSpec(
            id = ToolId("screen.read"), functionName = "screen_read", title = "Read the screen", description = "Reads the app in front.",
            parameters = buildJsonObject { put("type", "object") }, category = ToolCategory.ACCESSIBILITY, capability = Capabilities.BASIC_INFO, risk = RiskLevel.SAFE,
        )

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome = ToolOutcome.success("Nothing on screen.")
    }

    @Test
    fun `the screen tools are offered only in an objective whose options allow screens`() = runTest {
        val team = TeamFixture(this).setUp()
        team.world.register(ScreenRead())
        team.world.agents.upsert(team.researcher.copy(toolIds = team.researcher.toolIds + ToolId("screen.read")))

        val routine = team.start("Research note apps", ObjectiveOptions(routineId = RoutineId("rtn_1")))
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(routine.id).status)
        assertTrue(!offersScreens(team), "not offered, not even in a menu of tool families")

        team.script.seen.clear()
        val owners = team.start("Research note apps", ObjectiveOptions())
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(owners.id).status)
        assertTrue(offersScreens(team), "offered, at once or in a menu of tool families")
    }

    /** The Researcher's request offers the screen tool itself, or a family menu (WP15's `tools_open`) that names the screen family. */
    private fun offersScreens(team: TeamFixture): Boolean {
        val tools = team.script.seenBy(OrchestrationProtocol.TASK).single { it.agent == "Researcher" }.request.tools
        val menu = tools.firstOrNull { it.name == "tools_open" }
        return tools.any { it.name == "screen_read" } || (menu != null && "screen" in menu.description + menu.parameters)
    }
}
