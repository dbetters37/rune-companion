package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.LambdaTool
import com.aicontrolcenter.framework.TestWorld
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolAuthorizationTest {
    private val world = TestWorld()
    private val authorizer = DefaultToolAuthorizer(world.settings, world.capabilities)
    private val writeFiles = Capability("files.write")

    private fun tool(
        name: String,
        capability: Capability = Capabilities.BASIC_INFO,
        sendsDataOffDevice: Boolean = false,
        assessed: RiskLevel = RiskLevel.SAFE,
    ) = LambdaTool(name, capability, sendsDataOffDevice = sendsDataOffDevice, assessed = assessed) { ToolOutcome.success("ran") }

    /** The decision for an agent that has [tool] switched on, unless [switchedOn] says otherwise. */
    private suspend fun decide(tool: LambdaTool, switchedOn: Boolean = true): Authorization {
        val context = ToolContext(world.agent(model = null, tools = if (switchedOn) setOf(tool.spec.id) else emptySet()))
        return authorizer.authorize(tool.spec, tool.assess(JsonObject(emptyMap()), context), context)
    }

    private fun Authorization.reason(): String = assertIs<Authorization.Denied>(this).reason

    @Test
    fun `a switched-on tool with a default capability is allowed`() = runTest {
        assertEquals(Authorization.Allowed, decide(tool("lookup")))
        assertEquals(Authorization.Allowed, decide(tool("edit", assessed = RiskLevel.MODIFYING)))
    }

    @Test
    fun `a tool that is not switched on for the agent is denied, naming the tool and the agent`() = runTest {
        val reason = decide(tool("lookup"), switchedOn = false).reason()
        assertTrue("Test lookup" in reason && "not switched on for Scout" in reason, reason)
    }

    @Test
    fun `a capability that is not granted by default, or not known at all, is denied`() = runTest {
        world.capabilities.register(CapabilityInfo(writeFiles, "Write files", "Create and change files.", ToolCategory.FILES, grantedByDefault = false))

        val needsGrant = decide(tool("save", capability = writeFiles)).reason()
        val unknown = decide(tool("teleport", capability = Capability("space.fold"))).reason()

        assertTrue("\"Write files\" permission" in needsGrant && "has not been granted" in needsGrant, needsGrant)
        assertTrue("space.fold" in unknown, unknown)
    }

    @Test
    fun `local-only mode forbids tools that send data off the device, and only those`() = runTest {
        val search = tool("search", sendsDataOffDevice = true)
        val local = tool("lookup")
        assertEquals(Authorization.Allowed, decide(search))

        world.settings.update { it.copy(localOnlyMode = true) }

        assertTrue("Local-only mode" in decide(search).reason())
        assertEquals(Authorization.Allowed, decide(local))
    }

    @Test
    fun `a consequential action is denied because nobody can be asked to approve it yet`() = runTest {
        val reason = decide(tool("wipe", assessed = RiskLevel.CONSEQUENTIAL)).reason()
        assertTrue("owner's approval" in reason && "Run Test wipe" in reason, reason)
    }

    @Test
    fun `the capability registry starts with the built-in capabilities and takes each new one once`() {
        val registry = CapabilityRegistry()
        assertEquals(Capabilities.builtIn, registry.all())
        assertTrue(registry.info(Capabilities.BASIC_INFO)!!.grantedByDefault)
        assertNull(registry.info(writeFiles))

        val info = CapabilityInfo(writeFiles, "Write files", "Create and change files.", ToolCategory.FILES, grantedByDefault = false)
        registry.register(info)

        assertEquals(Capabilities.builtIn + info, registry.all())
        assertFailsWith<IllegalArgumentException> { registry.register(info) }
    }

    @Test
    fun `the tool registry finds tools by id and by function name and refuses duplicates and bad names`() {
        val registry = DefaultToolRegistry()
        val first = tool("lookup")
        registry.register(first)

        assertEquals(listOf(first), registry.all())
        assertSame(first, registry.byId(ToolId("test.lookup")))
        assertSame(first, registry.byFunctionName("test_lookup"))
        assertNull(registry.byId(ToolId("test.missing")))
        assertNull(registry.byFunctionName("test.lookup"), "an id is not a function name")

        assertFailsWith<IllegalArgumentException> { registry.register(tool("lookup")) }
        assertFailsWith<IllegalArgumentException> { registry.register(tool("has space")) }
        assertFailsWith<IllegalArgumentException> { registry.register(tool("x".repeat(70))) }
        assertEquals(listOf(first), registry.all(), "a refused registration leaves nothing behind")
    }
}
