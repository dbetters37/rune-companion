package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuiltInToolsTest {
    private val world = TestWorld()
    private val noArguments = JsonObject(emptyMap())

    /** 2023-11-14T22:13:20Z. */
    private val instant = 1_700_000_000_000L
    private val clockTool = ClockTool(FakeClock(instant)) { ZoneId.of("Europe/Paris") }

    @Test
    fun `the built-in tools are registered, read-only and need only basic information`() {
        val specs = world.toolRegistry.all().map { it.spec }.filter { it.category != ToolCategory.MEMORY }

        assertEquals(listOf(ToolId("clock.now"), ToolId("team.list_agents")), specs.map { it.id })
        assertEquals(BuiltInTools.defaultToolIds, specs.map { it.id }.toSet())
        assertEquals(listOf("clock_now", "team_list_agents"), specs.map { it.functionName })
        assertTrue(specs.all { it.risk == RiskLevel.SAFE && it.capability == Capabilities.BASIC_INFO && !it.sendsDataOffDevice })
        assertTrue(specs.all { it.parameters.getValue("type").jsonPrimitive.content == "object" && it.description.length > 40 })
    }

    @Test
    fun `the clock gives date, time, weekday and zone of the phone`() = runTest {
        val outcome = clockTool.execute(noArguments, ToolContext(world.agent(model = null)))

        assertTrue(outcome.ok)
        assertTrue("Tuesday, 14 November 2023, 23:13:20" in outcome.content, outcome.content)
        assertTrue("Europe/Paris" in outcome.content && "UTC+01:00" in outcome.content, outcome.content)
        val data = outcome.data!!.jsonObject
        assertEquals("2023-11-14T23:13:20+01:00", data.getValue("iso").jsonPrimitive.content)
        assertEquals(instant, data.getValue("epochMillis").jsonPrimitive.long)
        assertEquals("Europe/Paris", data.getValue("timeZone").jsonPrimitive.content)
        assertEquals("Tuesday", data.getValue("weekday").jsonPrimitive.content)
    }

    @Test
    fun `the clock answers for another time zone and explains an unknown one`() = runTest {
        val context = ToolContext(world.agent(model = null))

        val tokyo = clockTool.execute(JsonObject(mapOf("timezone" to JsonPrimitive("Asia/Tokyo"))), context)
        val nowhere = clockTool.execute(JsonObject(mapOf("timezone" to JsonPrimitive("Mars/Olympus"))), context)

        assertTrue("Wednesday, 15 November 2023, 07:13:20 in Asia/Tokyo" in tokyo.content, tokyo.content)
        assertFalse(nowhere.ok)
        assertTrue("Mars/Olympus" in nowhere.content && "IANA" in nowhere.content, nowhere.content)
    }

    @Test
    fun `the team list names every agent with role, purpose, skills and whether it can run`() = runTest {
        val model = world.serve(ScriptedLlmClient.replies("ok"))
        val scout = world.agent(model, tools = setOf(TeamListTool.ID))
        world.agent(model = null, name = "Drafter")
        world.agents.upsert(world.agent(model, name = "Sleeper").copy(enabled = false))

        val execution = world.executor.execute(ToolCallRequest("call_1", "team_list_agents", noArguments), ToolContext(scout))

        assertEquals(ToolCallEnd.OK, execution.end)
        val lines = execution.outcome.content.lines()
        assertEquals("The team has 3 agents:", lines[0])
        assertEquals("- Scout (Specialist, this is you): Finds things out. Skills: research. Can run now: yes.", lines[1])
        assertEquals("- Drafter (Specialist): Finds things out. Skills: research. Can run now: no. No model is assigned to this agent.", lines[2])
        assertEquals("- Sleeper (Specialist): Finds things out. Skills: research. Can run now: no, it is switched off.", lines[3])
        assertFalse("Answer briefly." in execution.outcome.content, "other agents' instructions are not revealed")

        val team = execution.outcome.data!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Scout", "Drafter", "Sleeper"), team.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(listOf(true, false, false), team.map { it.getValue("canRun").jsonPrimitive.boolean })
        assertEquals("Specialist", team[0].getValue("role").jsonPrimitive.content)
        assertEquals("Finds things out.", team[0].getValue("description").jsonPrimitive.content)
        assertEquals(listOf("research"), team[0].getValue("skills").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `an empty team is reported as such`() = runTest {
        val outsider = world.agent(model = null).also { world.agents.delete(it.id) }
        val outcome = TeamListTool(world.directory).execute(noArguments, ToolContext(outsider))
        assertEquals("There are no agents on the team.", outcome.content)
    }
}
