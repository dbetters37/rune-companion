package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.contract.PreparedActionChecks
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderToolsTest {
    private val f = OperatorFixture()
    private val tree = "content://tree/reports"

    @Test
    fun `a folder is named by name or id, and every call is constrained to it`() = runTest {
        val folder = f.shareFolder("Reports")
        f.sharedFolders.putFile(tree, "2025/q1.csv", "a,b")
        val byName = f.assess("folders.list", "folder" to "reports")
        val byId = f.assess("folders.list", "folder" to folder.id.value, "path" to "2025")
        assertEquals(CapabilityConstraint.Folder(folder.id), byName.constraint)
        assertEquals(CapabilityConstraint.Folder(folder.id), byId.constraint)
        val listing = f.ok("folders.list", "folder" to "Reports", "path" to "2025")
        assertTrue("q1.csv" in listing.content, listing.content)
        val read = f.ok("folders.read", "folder" to "Reports", "path" to "2025/q1.csv")
        assertTrue("a,b" in read.content, read.content)
    }

    @Test
    fun `an unknown or missing folder is answered with the folders that exist`() = runTest {
        f.shareFolder("Reports")
        f.shareFolder("Photos", readOnly = true)
        val unknown = f.rejected("folders.list", "folder" to "Taxes")
        assertTrue("no shared folder called \"Taxes\"" in unknown && "\"Reports\" (id fld_reports)" in unknown && "read-only" in unknown, unknown)
        assertTrue("Name a shared folder" in f.rejected("folders.list"))
    }

    @Test
    fun `nothing is changed in a read-only folder`() = runTest {
        f.shareFolder("Reports", readOnly = true)
        val outcome = f.fails("folders.write", "folder" to "Reports", "path" to "a.txt", "content" to "x")
        assertTrue("read-only" in outcome.content, outcome.content)
        assertTrue("read-only" in f.fails("folders.delete", "folder" to "Reports", "path" to "a.txt").content)
        assertFalse(f.sharedFolders.exists(tree, "a.txt"))
    }

    @Test
    fun `write refuses after the folder was removed, made read-only or the file changed`() = runTest {
        val folder = f.shareFolder("Reports")
        val args = json("folder" to "Reports", "path" to "notes.md", "content" to "hello")
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("folders.write"), args, f.context,
            change = { f.folderRepository.upsert(folder.copy(readOnly = true)) }, nothingDone = { !f.sharedFolders.exists(tree, "notes.md") },
        ).also { assertTrue("made read-only" in it.content, it.content) }
        f.folderRepository.upsert(folder)
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("folders.write"), args, f.context,
            change = { f.folderRepository.delete(folder.id) }, nothingDone = { !f.sharedFolders.exists(tree, "notes.md") },
        ).also { assertTrue("was removed" in it.content, it.content) }
        f.folderRepository.upsert(folder)
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("folders.write"), args, f.context,
            change = { f.sharedFolders.putFile(tree, "notes.md", "theirs") }, nothingDone = { f.sharedFolders.fileText(tree, "notes.md") == "theirs" },
        )
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("folders.write"), json("folder" to "Reports", "path" to "notes.md", "content" to "hello", "overwrite" to true), f.context)
        assertEquals("hello", f.sharedFolders.fileText(tree, "notes.md"))
    }

    @Test
    fun `delete is consequential, counts what goes and refuses when the folder grew`() = runTest {
        f.shareFolder("Reports")
        repeat(3) { f.sharedFolders.putFile(tree, "old/f$it.txt", "xx") }
        f.sharedFolders.putFile(tree, "old/sub/g.txt", "y")
        val assessment = f.assess("folders.delete", "folder" to "Reports", "path" to "old")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertTrue("4 files and 1 folder" in assessment.summary, assessment.summary)
        assertTrue(assessment.details.any { "cannot be undone" in it })
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("folders.delete"), json("folder" to "Reports", "path" to "old"), f.context,
            change = { f.sharedFolders.putFile(tree, "old/new.txt", "z") }, nothingDone = { f.sharedFolders.exists(tree, "old/f0.txt") },
        ).also { assertTrue("now holds 5 files" in it.content, it.content) }
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("folders.delete"), json("folder" to "Reports", "path" to "old"), f.context)
        assertFalse(f.sharedFolders.exists(tree, "old"))
        assertTrue("top level" in f.rejected("folders.delete", "folder" to "Reports", "path" to ""))
    }

    @Test
    fun `copies between a folder and the workspace never replace a workspace file`() = runTest {
        f.shareFolder("Reports")
        f.sharedFolders.putFile(tree, "q1.csv", "1,2")
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("folders.copy_to_workspace"), json("folder" to "Reports", "path" to "q1.csv", "to" to "in/q1.csv"), f.context,
            change = { f.put("in/q1.csv", "mine") }, nothingDone = { f.file("in/q1.csv").readText() == "mine" },
        )
        assertTrue("never replace" in f.fails("folders.copy_to_workspace", "folder" to "Reports", "path" to "q1.csv", "to" to "in/q1.csv").content)
        f.ok("folders.copy_to_workspace", "folder" to "Reports", "path" to "q1.csv")
        assertEquals("1,2", f.file("q1.csv").readText())

        f.put("out.txt", "from the workspace")
        val args = json("from" to "out.txt", "folder" to "Reports", "to" to "exports/out.txt")
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("folders.copy_from_workspace"), args, f.context,
            change = { f.put("out.txt", "changed content") }, nothingDone = { !f.sharedFolders.exists(tree, "exports/out.txt") },
        )
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("folders.copy_from_workspace"), args, f.context)
        assertEquals("changed content", f.sharedFolders.fileText(tree, "exports/out.txt"))
        assertTrue("already exists" in f.fails("folders.copy_from_workspace", "from" to "out.txt", "folder" to "Reports", "to" to "exports/out.txt").content)
    }

    @Test
    fun `make folder and rename act on what was checked`() = runTest {
        f.shareFolder("Reports")
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("folders.make_folder"), json("folder" to "Reports", "path" to "a/b"), f.context)
        assertTrue(f.sharedFolders.exists(tree, "a/b"))
        f.sharedFolders.putFile(tree, "a/report.txt", "r")
        val args = json("folder" to "Reports", "path" to "a/report.txt", "new_name" to "final.txt")
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("folders.rename"), args, f.context,
            change = { f.sharedFolders.putFile(tree, "a/final.txt", "taken") }, nothingDone = { f.sharedFolders.exists(tree, "a/report.txt") },
        )
        assertTrue("single valid name" in f.rejected("folders.rename", "folder" to "Reports", "path" to "a/report.txt", "new_name" to "../x"))
    }

    @Test
    fun `a folder whose access was withdrawn fails when run, without telling before authorisation`() = runTest {
        f.shareFolder("Reports")
        f.sharedFolders.granted.clear()
        // The assessment is still made (the broker decides first); the call fails only when run.
        val assessment = f.assess("folders.write", "folder" to "Reports", "path" to "a.txt", "content" to "x")
        assertEquals(CapabilityConstraint.Folder(f.folderRepository.getAll().single().id), assessment.constraint)
        val outcome = f.tool("folders.write").execute(json("folder" to "Reports", "path" to "a.txt", "content" to "x"), f.context, assessment)
        assertFalse(outcome.ok)
        assertTrue("withdrawn" in outcome.content, outcome.content)
        assertTrue(f.sharedFolders.calls.none { it.startsWith("write") })
        assertEquals(DocResult.NotAllowed("Access to Reports was withdrawn."), f.sharedFolders.read(f.folderRepository.getAll().single().id, OperatorFixture.path("a.txt"), 10))
    }
}
