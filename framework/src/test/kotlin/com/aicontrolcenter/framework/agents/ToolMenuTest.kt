package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.tools.ClockTool
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ToolMenu] on its own: when a run gets one, what it offers, and what `tools_open` may and may not do. */
class ToolMenuTest {
    /** A tool of [id] whose description takes about [tokens] estimated tokens. */
    private fun spec(id: String, tokens: Int = 100) = ToolSpec(
        ToolId(id), id.replace('.', '_'), "Title of $id", "x".repeat(tokens * 4), buildJsonObject { put("type", "object") },
        ToolCategory.SYSTEM, Capabilities.BASIC_INFO, RiskLevel.SAFE,
    )

    private val registry = DefaultToolRegistry()

    private val big = listOf(
        spec("clock.now", 50), spec("files.read", 600), spec("files.write", 600), spec("memory.save", 300), spec("memory.search", 300),
        spec("screen.read", 700), spec("screen.tap", 700), spec("team.delegate", 200),
    ).sortedBy { it.functionName }

    private fun open(family: String?) = ToolCallRequest("c1", ToolMenu.FUNCTION_NAME, if (family == null) JsonObject(emptyMap()) else family(family))

    @Test
    fun `no menu at or under the threshold, nor when every tool is always on`() {
        val small = listOf(spec("files.read", 600), spec("files.write", 600))
        assertNull(ToolMenu.of(small, emptySet(), registry))
        assertNull(ToolMenu.of(listOf(spec("clock.now", 2_000), spec("memory.search", 2_000)), emptySet(), registry))
        assertNull(ToolMenu.of(big, big.map { it.id }.toSet(), registry), "every tool is an extra tool of the run")
        assertNotNull(ToolMenu.of(big, emptySet(), registry))
        assertNull(ToolMenu.of(big, emptySet(), registry, thresholdTokens = 100_000))
    }

    @Test
    fun `the first offer is the always-on tools and tools_open, whose description lists every other family`() {
        val menu = ToolMenu.of(big, setOf(ToolId("team.delegate")), registry)!!
        assertEquals(listOf("clock_now", "memory_search", "team_delegate", ToolMenu.FUNCTION_NAME), menu.definitions.map { it.name })
        assertEquals(listOf("files", "memory", "screen"), menu.familyNames)
        val description = menu.definitions.last().description
        assertTrue("\n- files: Title of files.read; Title of files.write" in description, description)
        assertTrue("\n- memory: Title of memory.save" in description, "memory.search is always on, memory.save is not: $description")
        assertTrue("\n- screen: Title of screen.read; Title of screen.tap" in description)
        assertFalse("team" in description.substringAfter("Families:"), "the run's extra tools are always on")
        assertTrue(ToolMenu.ALWAYS_ON == setOf(MemoryTools.SEARCH_ID, ClockTool.ID))
    }

    @Test
    fun `opening a family appends its tools once, and tools_open's own definition never changes`() {
        val menu = ToolMenu.of(big, emptySet(), registry)!!
        val first = menu.definitions
        val opened = menu.open(open("screen"))
        assertTrue(opened.ok)
        assertEquals("Opened the screen tools: screen_read, screen_tap. You can use them from now on in this turn.", opened.content)
        assertEquals(first + listOf("screen_read", "screen_tap").map { n -> ToolMenu.definition(big.single { it.functionName == n }) }, menu.definitions)
        val again = menu.open(open("Screen"))
        assertEquals("The screen tools are already open: screen_read, screen_tap.", again.content)
        assertTrue(menu.open(open("files_write")).ok, "a tool's function name opens its family")
        assertEquals(listOf("screen", "files"), menu.openedFamilies)
        assertEquals(first, menu.definitions.take(first.size), "the earlier definitions, tools_open's included, are unchanged")
    }

    @Test
    fun `tools_open refuses anything but one of the agent's families, and adds nothing then`() {
        val menu = ToolMenu.of(big, emptySet(), registry)!!
        val before = menu.definitions
        for (asked in listOf("apps", "", "admin.wipe", "screen; files", "../screen")) {
            val outcome = menu.open(open(asked))
            assertFalse(outcome.ok, asked)
            assertTrue("files, memory, screen" in outcome.content, outcome.content)
        }
        assertFalse(menu.open(open(null)).ok)
        assertFalse(menu.open(ToolCallRequest("c1", ToolMenu.FUNCTION_NAME, JsonObject(mapOf("family" to JsonPrimitive(3))))).ok)
        assertFalse(menu.open(ToolCallRequest("c1", ToolMenu.FUNCTION_NAME, JsonObject(emptyMap()), argumentsError = "not JSON")).ok)
        assertFalse(menu.openFamilyOf("apps_install"), "a tool the agent does not have opens nothing")
        assertEquals(before, menu.definitions)
        assertTrue(menu.openedFamilies.isEmpty())
    }

    @Test
    fun `families used earlier in the conversation open first, in the order they were used`() {
        val menu = ToolMenu.of(big, emptySet(), registry)!!
        menu.openUsedIn(
            listOf(
                ChatMessage.assistant("", listOf(ToolCallRequest("a", "screen_tap", JsonObject(emptyMap())))),
                ChatMessage.assistant("", listOf(ToolCallRequest("b", "apps_install", JsonObject(emptyMap())), open("memory"), open("nope"))),
            ),
        )
        assertEquals(listOf("screen", "memory"), menu.openedFamilies)
    }

    @Test
    fun `no menu when a registered tool already uses tools_open's name`() {
        val clash = object : Tool {
            override val spec = spec("tools.open").copy(functionName = ToolMenu.FUNCTION_NAME)

            override suspend fun execute(arguments: JsonObject, context: ToolContext) = ToolOutcome.success("registered")
        }
        registry.register(clash)
        assertNull(ToolMenu.of(big, emptySet(), registry))
    }

    @Test
    fun `the spec shown for tools_open is safe basic information and matches its name`() {
        assertEquals(ToolMenu.FUNCTION_NAME, ToolMenu.SPEC.functionName)
        assertEquals(RiskLevel.SAFE, ToolMenu.SPEC.risk)
        assertEquals(Capabilities.BASIC_INFO, ToolMenu.SPEC.capability)
        assertFalse(ToolMenu.SPEC.sendsDataOffDevice)
        assertEquals("workspace", ToolMenu.familyOf(ToolId("workspace.write")))
        assertEquals("plain", ToolMenu.familyOf(ToolId("plain")))
    }
}
