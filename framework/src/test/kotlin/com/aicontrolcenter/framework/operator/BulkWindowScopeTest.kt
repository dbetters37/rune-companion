package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F6: the bulk rule counts per agent and task, per agent across all its tasks, and per root
 * objective across all its agents. A new task, or a helper agent, does not start again at zero.
 */
class BulkWindowScopeTest {
    private val f = OperatorFixture()
    private val limit = ConsequentialControls.BULK_FILE_LIMIT

    private fun context(task: String?, agent: String = "agt_operator", root: String? = "tsk_root") =
        ToolContext(Samples.agent(id = agent, name = agent), task?.let(::TaskId), root?.let(::TaskId))

    /** Replaces [n] of the owner's files, one call each, and returns each call's risk. */
    private suspend fun replace(n: Int, context: ToolContext, tag: String): List<RiskLevel> = (0 until n).map { i ->
        f.put("$tag-$i.md", "owner text")
        val args = json("path" to "$tag-$i.md", "content" to "", "overwrite" to true)
        val assessment = f.tool("workspace.write").assess(args, context)
        if (assessment.risk == RiskLevel.MODIFYING) assertTrue(f.tool("workspace.write").execute(args, context, assessment).ok)
        assessment.risk
    }

    private suspend fun next(context: ToolContext, tag: String) = f.put("$tag.md", "owner text").let {
        f.tool("workspace.write").assess(json("path" to "$tag.md", "content" to "", "overwrite" to true), context)
    }

    @Test
    fun `a new task of the same agent does not start the count again`() = runTest {
        assertEquals(List(limit) { RiskLevel.MODIFYING }, replace(limit, context("tsk_a"), "a"))
        val sameTask = next(context("tsk_a"), "a-more")
        assertRisk(RiskLevel.CONSEQUENTIAL, sameTask)
        assertTrue(sameTask.details.any { "more than $limit" in it && "in this task" in it }, sameTask.preview)

        val newTask = next(context("tsk_b"), "b")
        assertRisk(RiskLevel.CONSEQUENTIAL, newTask)
        assertTrue(newTask.details.any { "more than $limit" in it && "across its tasks" in it }, newTask.preview)
        // Also in another objective, and outside any task: it is the same agent.
        assertRisk(RiskLevel.CONSEQUENTIAL, next(context("tsk_c", root = "tsk_other_root"), "c"))
        assertRisk(RiskLevel.CONSEQUENTIAL, next(context(null, root = null), "chat"))
    }

    @Test
    fun `a helper agent in the same objective does not start the count again`() = runTest {
        assertEquals(List(limit) { RiskLevel.MODIFYING }, replace(limit, context("tsk_a"), "a"))
        val helper = next(context("tsk_help", agent = "agt_helper"), "h")
        assertRisk(RiskLevel.CONSEQUENTIAL, helper)
        assertTrue(helper.details.any { "more than $limit" in it && "agents working on this objective" in it }, helper.preview)
        // An agent working on something else has its own count.
        assertRisk(RiskLevel.MODIFYING, next(context("tsk_x", agent = "agt_elsewhere", root = "tsk_other_root"), "x"))
        assertRisk(RiskLevel.MODIFYING, next(context(null, agent = "agt_chat", root = null), "y"))
    }

    @Test
    fun `the objective's count adds up over its agents`() = runTest {
        // Three agents, each under the limit on its own.
        for (agent in listOf("agt_1", "agt_2", "agt_3")) {
            val risks = replace(8, context("tsk_$agent", agent = agent), agent)
            val expected = if (agent == "agt_3") List(4) { RiskLevel.MODIFYING } + List(4) { RiskLevel.CONSEQUENTIAL } else List(8) { RiskLevel.MODIFYING }
            assertEquals(expected, risks, agent)
        }
    }

    @Test
    fun `a call checked before the objective's window filled up does not run without approval`() = runTest {
        f.put("early.md", "owner text")
        val args = json("path" to "early.md", "content" to "", "overwrite" to true)
        val early = f.tool("workspace.write").assess(args, context("tsk_help", agent = "agt_helper"))
        assertRisk(RiskLevel.MODIFYING, early)
        replace(limit, context("tsk_a"), "a")
        val outcome = f.tool("workspace.write").execute(args, context("tsk_help", agent = "agt_helper"), early)
        assertTrue(!outcome.ok && "approval" in outcome.content, outcome.content)
        assertEquals("owner text", f.file("early.md").readText())
    }

    @Test
    fun `ten minutes later every count starts again, and a clock set back does not empty them`() = runTest {
        replace(limit, context("tsk_a"), "a")
        f.clock.advance(-3_600_000)
        assertRisk(RiskLevel.CONSEQUENTIAL, next(context("tsk_b"), "back"))
        f.clock.advance(3_600_000 + ConsequentialControls.BULK_WINDOW_MS)
        assertRisk(RiskLevel.MODIFYING, next(context("tsk_b"), "later"))
        assertRisk(RiskLevel.MODIFYING, next(context("tsk_help", agent = "agt_helper"), "later-h"))
    }
}
