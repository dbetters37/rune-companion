package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.CapabilityRequester
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionRequestToolTest {
    private fun args(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    /** Records what it is asked and answers [answer]. */
    private class Recorder(var answer: AskResult = AskResult.AlreadyGranted) : CapabilityRequester {
        val asked = ArrayList<Triple<Capability, CapabilityConstraint, String>>()

        override suspend fun ask(context: ToolContext, capability: Capability, constraint: CapabilityConstraint, reason: String): AskResult {
            asked += Triple(capability, constraint, reason)
            return answer
        }
    }

    @Test
    fun `its spec is the catalogue's entry`() {
        val spec = PermissionRequestTool(Recorder()).spec
        val info = OperatorTools.info(ToolId("permissions.request"))!!
        assertEquals(info.id, spec.id)
        assertEquals(info.capability, spec.capability)
        assertEquals(info.risk, spec.risk)
        assertEquals(info.category, spec.category)
        assertEquals(info.sendsDataOffDevice, spec.sendsDataOffDevice)
        assertTrue(OperatorCapabilities.all.all { it.capability.id in spec.description }, "the model is told which permissions exist")
    }

    @Test
    fun `it hands the capability, the target and the reason to the requester and says what happened`() = runTest {
        val requester = Recorder()
        val tool = PermissionRequestTool(requester)
        val context = ToolContext(com.aicontrolcenter.testkit.Samples.agent())

        requester.answer = AskResult.Raised(com.aicontrolcenter.core.RequestId("req_1"))
        val raised = tool.execute(args("capability" to "folders.read", "reason" to "To total Q1.", "folder_id" to "fld_reports"), context)
        assertTrue(raised.ok && "Asked the owner" in raised.content, raised.content)
        requester.answer = AskResult.AlreadyPending(com.aicontrolcenter.core.RequestId("req_1"))
        assertTrue("already asked" in tool.execute(args("capability" to "screen.read", "reason" to "x", "app" to "com.example.notes"), context).content)
        requester.answer = AskResult.AlreadyGranted
        assertTrue("already have" in tool.execute(args("capability" to "workspace.read", "reason" to "x"), context).content)
        requester.answer = AskResult.Refused("Phone operation is switched off in Settings.")
        val refused = tool.execute(args("capability" to "web.read", "reason" to "x"), context)
        assertFalse(refused.ok)
        assertEquals("Not asked: Phone operation is switched off in Settings.", refused.content)
        assertEquals(
            listOf(
                Triple(OperatorCapabilities.FOLDERS_READ, CapabilityConstraint.Folder(com.aicontrolcenter.core.FolderId("fld_reports")), "To total Q1."),
                Triple(OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App("com.example.notes"), "x"),
                Triple(OperatorCapabilities.WORKSPACE_READ, CapabilityConstraint.None, "x"),
                Triple(OperatorCapabilities.WEB_READ, CapabilityConstraint.None, "x"),
            ),
            requester.asked,
        )

        // Targets that cannot be read are refused before anything is asked.
        val before = requester.asked.size
        assertFalse(tool.execute(args("capability" to "screen.read", "reason" to "x", "app" to "not a package"), context).ok)
        assertFalse(tool.execute(args("capability" to "folders.read", "reason" to "x", "folder_id" to "fld_1", "app" to "com.example.notes"), context).ok)
        assertFalse(tool.execute(args("capability" to " ", "reason" to "x"), context).ok)
        assertEquals(before, requester.asked.size)
    }

    @Test
    fun `through the executor and the broker it raises the agent's ask at once, quoting its reason, and grants nothing`() = runTest {
        val f = BrokerFixture(this).setUp()
        val requester = DefaultCapabilityRequester(f.facts, f.requests, f.grants, f.activity, f.ids, f.clock)
        val tool = PermissionRequestTool(requester)
        val agent = f.agent.copy(toolIds = f.agent.toolIds + tool.spec.id).also { f.agents.upsert(it) }
        val executor = ToolExecutor(DefaultToolRegistry().also { it.register(tool) }, f.broker, f.activity, f.clock)
        val call = ToolCallRequest("c1", tool.spec.functionName, args("capability" to "folders.write", "reason" to "I need to save \"the totals\".", "folder_id" to f.folder.id.value))

        val execution = executor.execute(call, f.context(who = agent))

        assertEquals(ToolCallEnd.OK, execution.end, execution.outcome.content)
        val ask = f.pending()
        assertEquals(OwnerRequestKind.PERMISSION, ask.kind)
        assertNull(ask.toolId, "an agent's own ask")
        assertEquals("I need to save \"the totals\".", ask.reason)
        assertEquals("Reports", ask.targetLabel)
        assertTrue(f.grants.observeAll().first().isEmpty())
        assertEquals(listOf("Permission asked for by the agent: Change a shared folder"), f.events(ActivityType.PERMISSION).map { it.title })
        // Asking again changes nothing.
        assertTrue("already asked" in executor.execute(call, f.context(who = agent)).outcome.content)
        assertEquals(1, f.allRequests().size)
    }
}
