package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.MediaId
import com.aicontrolcenter.core.operator.MediaItem
import com.aicontrolcenter.core.operator.MediaKind
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Replacing the owner's files: a shared folder has no trash, so replacing a file there is asked
 * every time; in the workspace the replaced file is kept in the trash; and the bulk rule counts
 * across calls. Gateway copies go through the workspace's limits.
 */
class FileSafetyTest {
    private val f = OperatorFixture()

    @Test
    fun `replacing a file in a shared folder is consequential, even with an always grant`() = runTest {
        val folder = f.shareFolder("Reports")
        f.sharedFolders.putFile(folder.treeUri, "Thesis.md", "Four years of work. ".repeat(500))
        val args = json("folder" to "Reports", "path" to "Thesis.md", "content" to "", "overwrite" to true)
        val tool = f.tool("folders.write")
        val assessment = tool.assess(args, f.context)
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertTrue(FolderTools.NO_TRASH in assessment.details, assessment.preview)
        assertTrue("for good" in assessment.summary, assessment.summary)

        // The broker asks the owner although an ALWAYS folders.write grant exists.
        val b = BrokerFixture(this).setUp()
        val agent = b.agent.copy(toolIds = b.agent.toolIds + tool.spec.id)
        b.agents.upsert(agent)
        b.grants.insert(PermissionGrant(GrantId("grt_always"), agent.id, OperatorCapabilities.FOLDERS_WRITE, CapabilityConstraint.Folder(folder.id), GrantScope.ALWAYS, grantedAt = 1))
        val decision = b.start(tool.spec, assessment, b.context(who = agent))
        assertEquals(OwnerRequestKind.APPROVAL, b.pending().kind)
        b.owner.approve(b.pending().id)
        assertIs<Authorization.AllowedByOwner>(decision.await())

        // Creating a new file there is not.
        assertRisk(RiskLevel.MODIFYING, f.assess("folders.write", "folder" to "Reports", "path" to "new.md", "content" to "x"))
    }

    @Test
    fun `copying over a file in a shared folder is consequential`() = runTest {
        val folder = f.shareFolder("Reports")
        f.put("blank.txt", "x")
        f.sharedFolders.putFile(folder.treeUri, "Budget.csv", "a,b\n1,2\n")
        val over = f.assess("folders.copy_from_workspace", "from" to "blank.txt", "folder" to "Reports", "to" to "Budget.csv", "overwrite" to true)
        assertRisk(RiskLevel.CONSEQUENTIAL, over)
        assertTrue(FolderTools.NO_TRASH in over.details, over.preview)
        assertRisk(RiskLevel.MODIFYING, f.assess("folders.copy_from_workspace", "from" to "blank.txt", "folder" to "Reports", "to" to "New.csv"))
    }

    @Test
    fun `replacing a workspace file keeps the old one in the trash`() = runTest {
        f.put("Imported/Contract.pdf.txt", "signed contract text ".repeat(100))
        val (a, outcome) = f.run("workspace.write", "path" to "Imported/Contract.pdf.txt", "content" to "", "overwrite" to true)
        assertRisk(RiskLevel.MODIFYING, a)
        assertTrue(outcome.ok, outcome.content)
        assertTrue(WorkspaceWrite.KEPT_IN_TRASH in a.details && "trash" in a.summary, a.preview)
        assertEquals(0L, f.file("Imported/Contract.pdf.txt").length())
        val kept = (f.workspace.listTrash() as DocResult.Ok).value.single()
        assertEquals("Imported/Contract.pdf.txt", kept.originalPath)
        assertEquals("signed contract text ".repeat(100), f.file(".trash/${kept.id}/Contract.pdf.txt").readText())

        f.put("a.md", "A")
        f.put("b.md", "B")
        val (move, moved) = f.run("workspace.move", "from" to "a.md", "to" to "b.md", "overwrite" to true)
        assertTrue(moved.ok && WorkspaceWrite.KEPT_IN_TRASH in move.details, move.preview)
        assertTrue((f.workspace.listTrash() as DocResult.Ok).value.any { it.originalPath == "b.md" })
    }

    @Test
    fun `the bulk rule counts files changed across calls over ten minutes`() = runTest {
        val limit = ConsequentialControls.BULK_FILE_LIMIT
        repeat(limit + 5) { f.put("f$it.md", "owner text $it") }
        val risks = (0 until limit + 5).map { i ->
            val (a, outcome) = f.run("workspace.write", "path" to "f$i.md", "content" to "", "overwrite" to true)
            if (a.risk == RiskLevel.MODIFYING) assertTrue(outcome.ok, outcome.content)
            a.risk
        }
        assertEquals(List(limit) { RiskLevel.MODIFYING } + List(5) { RiskLevel.CONSEQUENTIAL }, risks)
        val asked = f.assess("workspace.write", "path" to "f0.md", "content" to "x", "overwrite" to true)
        assertTrue(asked.details.any { "more than $limit" in it }, asked.preview)
        // Edits and trashing count the same way; creating new files never does.
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("workspace.trash", "paths" to listOf("f1.md")))
        assertRisk(RiskLevel.MODIFYING, f.assess("workspace.write", "path" to "brand-new.md", "content" to "x"))
        // Another task of the same agent does not start again at zero (BulkWindowScopeTest has the other counts).
        val otherTask = f.context.copy(taskId = TaskId("tsk_other"))
        assertRisk(RiskLevel.CONSEQUENTIAL, f.tool("workspace.write").assess(json("path" to "f0.md", "content" to "x", "overwrite" to true), otherTask))
        // Ten minutes later the window has moved on.
        f.clock.advance(ConsequentialControls.BULK_WINDOW_MS)
        assertRisk(RiskLevel.MODIFYING, f.assess("workspace.write", "path" to "f0.md", "content" to "x", "overwrite" to true))
    }

    @Test
    fun `a call checked before the window filled up does not run without approval`() = runTest {
        val limit = ConsequentialControls.BULK_FILE_LIMIT
        repeat(limit + 1) { f.put("f$it.md", "owner text $it") }
        val tool = f.tool("workspace.write")
        val args = json("path" to "f0.md", "content" to "", "overwrite" to true)
        val early = tool.assess(args, f.context)
        assertRisk(RiskLevel.MODIFYING, early)
        for (i in 1..limit) assertTrue(f.ok("workspace.write", "path" to "f$i.md", "content" to "", "overwrite" to true).ok)
        val outcome = tool.execute(args, f.context, early)
        assertFalse(outcome.ok)
        assertTrue("approval" in outcome.content, outcome.content)
        assertEquals("owner text 0", f.file("f0.md").readText())
    }

    @Test
    fun `a media copy goes through the workspace limits and tells the screens`() = runTest {
        val small = OperatorFixture(workspaceLimits = WorkspaceLimits(maxFileBytes = 1_000, maxTotalBytes = 1_000))
        small.media.items += MediaItem(MediaId("m1"), MediaKind.IMAGE, "photo.jpg", "image/jpeg", sizeBytes = 5_000)
        small.media.contents[MediaId("m1")] = ByteArray(5_000)
        val before = small.workspace.changes.first()
        val failed = small.fails("media.copy_to_workspace", "id" to "m1", "to" to "photo.jpg")
        assertTrue("1000 bytes" in failed.content || "limit" in failed.content, failed.content)
        assertFalse(small.file("photo.jpg").exists(), "nothing over the limit is left in the workspace")

        small.media.items += MediaItem(MediaId("m2"), MediaKind.IMAGE, "small.jpg", "image/jpeg", sizeBytes = 300)
        small.media.contents[MediaId("m2")] = ByteArray(300)
        assertTrue(small.ok("media.copy_to_workspace", "id" to "m2", "to" to "small.jpg").ok)
        assertEquals(300L, small.file("small.jpg").length())
        assertTrue(small.workspace.changes.first() > before, "the owner's file manager is told")
    }

    @Test
    fun `a folder copy into a full workspace is refused and removed`() = runTest {
        val small = OperatorFixture(workspaceLimits = WorkspaceLimits(maxFileBytes = 1_000, maxTotalBytes = 1_000))
        val folder = small.shareFolder("Reports")
        small.put("taken.bin", "x".repeat(900))
        small.sharedFolders.putFile(folder.treeUri, "data.csv", "y".repeat(500))
        val failed = small.fails("folders.copy_to_workspace", "folder" to "Reports", "path" to "data.csv")
        assertTrue("full" in failed.content, failed.content)
        assertFalse(small.file("data.csv").exists())
    }
}
