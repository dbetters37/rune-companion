package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * F1: workspace.replace_text keeps the version it replaces in the trash, as workspace.write
 * does, and says so. A version the same task wrote itself in the last ten minutes is not kept a
 * second time, so many small edits keep what was there before the task's first change once.
 */
class WorkspaceVersionsTest {
    private val f = OperatorFixture()

    private suspend fun trash(): List<TrashItem> = (f.workspace.listTrash() as DocResult.Ok).value

    private fun kept(item: TrashItem): String = f.file(".trash/${item.id}/${item.originalPath.substringAfterLast('/')}").readText()

    private fun task(id: String, agent: String = "agt_operator") = ToolContext(Samples.agent(id = agent, name = agent), TaskId(id), TaskId("tsk_root"))

    private suspend fun edit(context: ToolContext, path: String, find: String, replace: String) =
        json("path" to path, "find" to find, "replace" to replace).let { args ->
            val tool = f.tool("workspace.replace_text")
            val assessment = tool.assess(args, context)
            assessment to tool.execute(args, context, assessment)
        }

    @Test
    fun `emptying a file with replace_text keeps what was in it, and the approval and the answer say so`() = runTest {
        // The verifier's reproduction: a file of the owner's, its whole text replaced by nothing.
        val text = "Signed contract, clause ${"x".repeat(40)}. ".repeat(150)
        f.put("Imported/Contract.txt", text)
        val (assessment, outcome) = f.run("workspace.replace_text", "path" to "Imported/Contract.txt", "find" to text.take(10_000), "replace" to "")
        assertRisk(RiskLevel.MODIFYING, assessment)
        assertTrue(outcome.ok, outcome.content)
        assertEquals(0L, f.file("Imported/Contract.txt").length())
        assertTrue("the version before the edit is kept in the workspace trash" in assessment.summary, assessment.summary)
        assertTrue(WorkspaceReplaceText.KEPT_IN_TRASH in assessment.details, assessment.preview)
        assertTrue("is in the workspace trash" in outcome.content, outcome.content)

        val item = trash().single()
        assertEquals("Imported/Contract.txt", item.originalPath)
        assertEquals(text, kept(item))
        // And it comes back the usual way.
        f.ok("workspace.trash", "paths" to listOf("Imported/Contract.txt"))
        f.ok("workspace.restore", "id" to item.id)
        assertEquals(text, f.file("Imported/Contract.txt").readText())
    }

    @Test
    fun `twenty small edits by one task keep the owner's original once, not twenty copies`() = runTest {
        val big = "The quick brown fox jumps over the lazy dog 0123456789.\n".repeat(4000)
        f.put("Imported/Ledger.csv", big)
        var expected = big
        val letters = "abcdefghijklmnopqrst".map { it.toString() }
        letters.forEachIndexed { index, letter ->
            val (assessment, outcome) = f.run("workspace.replace_text", "path" to "Imported/Ledger.csv", "find" to letter, "replace" to "")
            assertTrue(outcome.ok, outcome.content)
            expected = expected.replace(letter, "")
            if (index == 0) {
                assertTrue(WorkspaceReplaceText.KEPT_IN_TRASH in assessment.details && "is in the workspace trash" in outcome.content, assessment.preview)
            } else {
                // Its own version of a moment ago: the approval and the answer say that it is not kept again.
                assertTrue(assessment.details.any { "not kept again" in it } && "trash" !in assessment.summary, assessment.preview)
                assertTrue("not kept again" in outcome.content, outcome.content)
            }
        }
        assertEquals(expected, f.file("Imported/Ledger.csv").readText())
        val item = trash().single()
        assertEquals(big, kept(item), "the version from before the first change is the one in the trash")
        assertTrue(f.workspace.usedBytes() < 2L * big.length, "one copy is kept, not twenty: ${f.workspace.usedBytes()} bytes used")
    }

    @Test
    fun `a version somebody else wrote, or the task wrote long ago, is kept`() = runTest {
        val a = task("tsk_a")
        f.put("notes.md", "the owner's text")
        assertTrue(edit(a, "notes.md", "owner's", "agent's").second.ok)
        assertEquals(listOf("the owner's text"), trash().map(::kept))

        // The owner puts a new version in place; the task's next edit must keep that one too.
        f.put("notes.md", "the owner's second text")
        val (afterOwner, _) = edit(a, "notes.md", "second", "2nd")
        assertTrue(WorkspaceReplaceText.KEPT_IN_TRASH in afterOwner.details, afterOwner.preview)
        assertEquals(setOf("the owner's text", "the owner's second text"), trash().map(::kept).toSet())

        // Another task, and another agent, edit what task A wrote: A's version is not theirs, so it is kept.
        assertTrue(edit(task("tsk_b"), "notes.md", "2nd", "third").second.ok)
        assertTrue("the owner's 2nd text" in trash().map(::kept))
        assertTrue(edit(task("tsk_a", agent = "agt_helper"), "notes.md", "third", "fourth").second.ok)
        assertTrue("the owner's third text" in trash().map(::kept))

        // The helper's own version, ten minutes later: kept again.
        f.clock.advance(ConsequentialControls.BULK_WINDOW_MS)
        assertTrue(edit(task("tsk_a", agent = "agt_helper"), "notes.md", "fourth", "fifth").second.ok)
        assertTrue("the owner's fourth text" in trash().map(::kept))
        assertEquals(5, trash().size)

        // A version changed outside the tools (a shell command, say) since the task wrote it is not the task's own any more.
        f.file("notes.md").appendText(" and a line a command added")
        assertTrue(edit(task("tsk_a", agent = "agt_helper"), "notes.md", "fifth", "sixth").second.ok)
        assertTrue("the owner's fifth text and a line a command added" in trash().map(::kept))
    }

    @Test
    fun `a file the task wrote itself is not kept by its own edits, and an approval that took ten minutes keeps it after all`() = runTest {
        val a = task("tsk_a")
        val write = f.tool("workspace.write")
        val args = json("path" to "draft.md", "content" to "first draft")
        assertTrue(write.execute(args, a, write.assess(args, a)).ok)
        assertTrue(edit(a, "draft.md", "first", "second").second.ok)
        assertTrue(edit(a, "draft.md", "second", "third").second.ok)
        assertTrue(trash().isEmpty(), "the task's own drafts of the last minutes are not kept: ${trash()}")

        // Checked while the version was fresh, run after the owner took more than ten minutes to answer.
        val tool = f.tool("workspace.replace_text")
        val later = json("path" to "draft.md", "find" to "third", "replace" to "fourth")
        val assessment = tool.assess(later, a)
        assertTrue(assessment.details.any { "not kept again" in it }, assessment.preview)
        f.clock.advance(ConsequentialControls.BULK_WINDOW_MS)
        val outcome = tool.execute(later, a, assessment)
        assertTrue(outcome.ok && "is in the workspace trash" in outcome.content, outcome.content)
        assertEquals(listOf("third draft"), trash().map(::kept))

        // A replacing write always keeps what it replaces, the task's own version too.
        val over = json("path" to "draft.md", "content" to "rewritten", "overwrite" to true)
        assertTrue(write.execute(over, a, write.assess(over, a)).ok)
        assertTrue("fourth draft" in trash().map(::kept))
    }

    @Test
    fun `the workspace keeps the replaced version unless it is exactly the one the caller names as its own`() = runTest {
        val p = RelativePath.of("t.md")
        fun sha(text: String) = WorkspaceStates.sha256(text.toByteArray())
        assertIs<DocResult.Ok<*>>(f.workspace.write(p, "one".toByteArray(), overwrite = false))
        assertEquals(DocResult.Ok(1), f.workspace.replaceText(p, "one", "two", null))
        assertEquals(listOf("one"), trash().map(::kept))
        assertEquals(DocResult.Ok(1), f.workspace.replaceText(p, "two", "three", null, ownVersionSha256 = sha("two")))
        assertEquals(listOf("one"), trash().map(::kept), "the caller's own version is not kept again")
        assertEquals(DocResult.Ok(1), f.workspace.replaceText(p, "three", "four", null, ownVersionSha256 = sha("two")))
        assertEquals(setOf("one", "three"), trash().map(::kept).toSet(), "a version with other content is kept, whatever the caller says")
        assertEquals("four", f.file("t.md").readText())
        // Nothing found, nothing changed, nothing kept.
        assertEquals(DocResult.Ok(0), f.workspace.replaceText(p, "absent", "x", null))
        assertEquals(2, trash().size)
        assertFalse(f.file(".trash/.staging").list().orEmpty().isNotEmpty())
    }

    @Test
    fun `a kept version counts against the workspace's size, so an edit needs room for both`() = runTest {
        val small = OperatorFixture(workspaceLimits = WorkspaceLimits(maxFileBytes = 1_000, maxTotalBytes = 1_500))
        small.put("a.txt", "x".repeat(900))
        val full = small.fails("workspace.replace_text", "path" to "a.txt", "find" to "x", "replace" to "y")
        assertTrue("full" in full.content, full.content)
        assertEquals("x".repeat(900), small.file("a.txt").readText())
        assertTrue((small.workspace.listTrash() as DocResult.Ok).value.isEmpty())
    }
}
