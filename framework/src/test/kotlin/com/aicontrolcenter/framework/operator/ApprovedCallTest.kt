package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.PreparedAction
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** What runs is what was approved: the executor with the broker, for a tool that prepares its action. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovedCallTest {
    /** Deletes the files of Reports; its assessment prepares the count it saw, and the action checks it again. */
    private class DeleteFiles(fixture: BrokerFixture) : Tool {
        var files = 3
        val assessed = ArrayList<ActionAssessment>()
        val ran = ArrayList<ActionAssessment>()
        private val constraint = fixture.inFolder

        override val spec = fixture.deleteInFolder

        override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
            val seen = files
            val prepared = object : PreparedAction {
                override suspend fun changedSince(): String? = if (files != seen) "the folder now holds $files files" else null
            }
            return ActionAssessment(RiskLevel.CONSEQUENTIAL, "Delete $seen files in Reports", listOf("$seen files"), constraint, prepared = prepared).also { assessed += it }
        }

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome = error("the framework runs the assessment it authorised")

        override suspend fun execute(arguments: JsonObject, context: ToolContext, assessment: ActionAssessment): ToolOutcome {
            assessment.prepared?.changedSince()?.let { return ToolOutcome.changedSinceApproved(it) }
            ran += assessment
            return ToolOutcome.success("Deleted.")
        }
    }

    @Test
    fun `the tool runs with the very assessment the owner approved, and the log names the request`() = runTest {
        val f = BrokerFixture(this).setUp()
        f.grants.insert(PermissionGrant(GrantId("grt_1"), f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder, GrantScope.ALWAYS, grantedAt = 1))
        val tool = DeleteFiles(f)
        val executor = ToolExecutor(DefaultToolRegistry().also { it.register(tool) }, f.broker, f.activity, f.clock)

        val call = async { executor.execute(ToolCallRequest("c1", tool.spec.functionName, JsonObject(emptyMap())), f.context()) }
        runCurrent()
        val approval = f.pending()
        assertEquals("Delete 3 files in Reports", approval.summary)
        f.owner.approve(approval.id)
        val done = call.await()

        assertEquals(ToolCallEnd.OK, done.end)
        assertSame(tool.assessed.single(), tool.ran.single(), "the assessment that was authorised, not a fresh one")
        val logged = f.events(ActivityType.TOOL_CALL).single().data!!
        assertEquals(listOf(approval.id.value), logged.getValue("requestIds").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a target that changed while the owner was asked is refused, and nothing is done`() = runTest {
        val f = BrokerFixture(this).setUp()
        f.grants.insert(PermissionGrant(GrantId("grt_1"), f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder, GrantScope.ALWAYS, grantedAt = 1))
        val tool = DeleteFiles(f)
        val executor = ToolExecutor(DefaultToolRegistry().also { it.register(tool) }, f.broker, f.activity, f.clock)

        val call = async { executor.execute(ToolCallRequest("c1", tool.spec.functionName, JsonObject(emptyMap())), f.context()) }
        runCurrent()
        tool.files = 300
        f.owner.approve(f.pending().id)
        val refused = call.await()

        assertEquals(ToolCallEnd.DENIED, refused.end)
        assertEquals(ToolOutcome.changedSinceApproved("the folder now holds 300 files"), refused.outcome)
        assertEquals(emptyList(), tool.ran)
        val logged = f.events(ActivityType.TOOL_CALL).single()
        assertEquals("Tool call denied: ${tool.spec.title}", logged.title)
        assertEquals(1, logged.data!!.getValue("requestIds").jsonArray.size, "the approval it was refused after is named")
        assertNull(f.grants.findByRequest(f.allRequests().single().id), "an approval stores no grant")
    }
}
