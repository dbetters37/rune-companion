package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.path
import com.aicontrolcenter.testkit.AppCall
import com.aicontrolcenter.testkit.contract.PreparedActionChecks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceToolsTest {
    private val f = OperatorFixture()

    @Test
    fun `list, read and search answer with workspace content`() = runTest {
        f.put("notes/plan.md", "# Plan\nbuy milk\n")
        f.put("notes/page.html", "<html><head><title>T</title></head><body><p>Hello <b>world</b></p><script>x()</script></body></html>")
        val list = f.ok("workspace.list", "path" to "notes")
        assertTrue("page.html" in list.content && "plan.md" in list.content, list.content)
        val read = f.ok("workspace.read", "path" to "notes/plan.md")
        assertTrue("buy milk" in read.content, read.content)
        val html = f.ok("workspace.read", "path" to "notes/page.html")
        assertTrue("Hello world" in html.content && "Title: T" in html.content && "x()" !in html.content, html.content)
        val search = f.ok("workspace.search", "query" to "MILK")
        assertTrue("notes/plan.md:2: buy milk" in search.content, search.content)
        assertEquals(CapabilityConstraint.None, f.assess("workspace.list").constraint)
    }

    @Test
    fun `long files are read in parts with the next offset`() = runTest {
        f.put("long.txt", "é".repeat(10))
        val first = f.ok("workspace.read", "path" to "long.txt", "max_bytes" to 5)
        // 5 bytes cut a two-byte character: only whole characters are returned, and the next offset follows them.
        assertTrue("éé\n" in first.content, first.content)
        assertEquals(4L, first.data!!.jsonObject.getValue("next_offset").jsonPrimitive.long)
        val rest = f.ok("workspace.read", "path" to "long.txt", "offset" to 4, "max_bytes" to 100)
        assertTrue("éééééééé" in rest.content, rest.content)
        f.file("bin.dat").writeBytes(byteArrayOf(0, 1, 2))
        assertTrue("not text" in f.fails("workspace.read", "path" to "bin.dat").content)
    }

    @Test
    fun `bad paths are rejected before anything runs`() = runTest {
        assertTrue("\"..\"" in f.rejected("workspace.read", "path" to "../secret"))
        assertTrue("leading" in f.rejected("workspace.write", "path" to "/etc/x", "content" to "x"))
        assertTrue("U+202E" in f.rejected("workspace.write", "path" to "a‮.txt", "content" to "x"))
        assertTrue("top level" in f.rejected("workspace.trash", "paths" to listOf("")))
    }

    @Test
    fun `write creates, refuses to replace without overwrite, and previews exactly`() = runTest {
        val (created, _) = f.run("workspace.write", "path" to "a.md", "content" to "line 1\nline 2")
        assertRisk(RiskLevel.MODIFYING, created)
        assertTrue("Create the workspace file \"a.md\"" in created.summary, created.summary)
        assertTrue("\"line 1⏎\nline 2\"" in created.preview, created.preview)
        assertEquals("line 1\nline 2", f.file("a.md").readText())

        assertTrue("already exists" in f.fails("workspace.write", "path" to "a.md", "content" to "new").content)
        assertEquals("line 1\nline 2", f.file("a.md").readText())
        val (replaced, _) = f.run("workspace.write", "path" to "a.md", "content" to "new", "overwrite" to true)
        assertTrue("Replace the workspace file" in replaced.summary)
        assertEquals("new", f.file("a.md").readText())
    }

    @Test
    fun `write refuses when the file appeared, changed or vanished after approval`() = runTest {
        val tool = f.tool("workspace.write")
        PreparedActionChecks.assertRefusesAfterChange(
            tool, json("path" to "new.md", "content" to "mine"), f.context,
            change = { f.put("new.md", "someone else's") }, nothingDone = { f.file("new.md").readText() == "someone else's" },
        )
        val refusal = PreparedActionChecks.assertRefusesAfterChange(
            tool, json("path" to "new.md", "content" to "mine", "overwrite" to true), f.context,
            change = { f.put("new.md", "changed again!") }, nothingDone = { f.file("new.md").readText() == "changed again!" },
        )
        assertTrue("Nothing was done" in refusal.content && "\"new.md\" changed" in refusal.content, refusal.content)
        PreparedActionChecks.assertRunsWhenUnchanged(tool, json("path" to "new.md", "content" to "mine", "overwrite" to true), f.context)
        assertEquals("mine", f.file("new.md").readText())
    }

    @Test
    fun `append and replace text act only on the file as it was checked`() = runTest {
        f.put("log.md", "one\n")
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.append"), json("path" to "log.md", "content" to "two\n"), f.context,
            change = { f.put("log.md", "rewritten\n") }, nothingDone = { f.file("log.md").readText() == "rewritten\n" },
        )
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("workspace.append"), json("path" to "log.md", "content" to "two\n"), f.context)
        assertEquals("rewritten\ntwo\n", f.file("log.md").readText())

        f.put("t.txt", "cat cat")
        val (assessment, _) = f.run("workspace.replace_text", "path" to "t.txt", "find" to "cat", "replace" to "dog")
        assertTrue("Replace 2 occurrences of \"cat\" with \"dog\"" in assessment.summary, assessment.summary)
        assertEquals("dog dog", f.file("t.txt").readText())
        f.put("t.txt", "cat cat")
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.replace_text"), json("path" to "t.txt", "find" to "cat", "replace" to "dog"), f.context,
            change = { f.put("t.txt", "cat cot") }, nothingDone = { f.file("t.txt").readText() == "cat cot" },
        )
        f.put("t.txt", "cat cat")
        val miscount = f.fails("workspace.replace_text", "path" to "t.txt", "find" to "cat", "replace" to "x", "expected_count" to 3).content
        assertTrue("occurs 2 times" in miscount && "not 3" in miscount, miscount)
        assertEquals("cat cat", f.file("t.txt").readText())
        assertTrue("does not occur" in f.fails("workspace.replace_text", "path" to "t.txt", "find" to "zebra", "replace" to "x").content)
    }

    @Test
    fun `make folder and move check their targets again before acting`() = runTest {
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.make_folder"), json("path" to "dir"), f.context,
            change = { f.put("dir", "a file now") }, nothingDone = { f.file("dir").isFile },
        )
        f.file("dir").delete()
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("workspace.make_folder"), json("path" to "dir"), f.context)
        assertTrue(f.file("dir").isDirectory)

        f.put("draft.md", "d")
        val args = json("from" to "draft.md", "to" to "archive/draft.md")
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.move"), args, f.context,
            change = { f.put("archive/draft.md", "occupied") }, nothingDone = { f.file("draft.md").exists() && f.file("archive/draft.md").readText() == "occupied" },
        )
        f.file("archive/draft.md").delete()
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("workspace.move"), args, f.context)
        assertTrue(f.file("archive/draft.md").exists() && !f.file("draft.md").exists())
        assertTrue("into itself" in f.rejected("workspace.move", "from" to "archive", "to" to "archive/sub/archive"))
    }

    @Test
    fun `trashing up to the bulk limit is modifying, more is consequential, and a changed folder is refused`() = runTest {
        repeat(ConsequentialControls.BULK_FILE_LIMIT) { f.put("small/f$it.txt", "x") }
        repeat(ConsequentialControls.BULK_FILE_LIMIT + 1) { f.put("big/f$it.txt", "x") }
        assertRisk(RiskLevel.MODIFYING, f.assess("workspace.trash", "paths" to listOf("small")))
        val big = f.assess("workspace.trash", "paths" to listOf("big"))
        assertRisk(RiskLevel.CONSEQUENTIAL, big)
        assertTrue("21 files" in big.summary, big.summary)
        // Two folders of 20 and 21 files: 41 files in one call.
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("workspace.trash", "paths" to listOf("small", "big")))

        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.trash"), json("paths" to listOf("small")), f.context,
            change = { f.put("small/extra.txt", "new") }, nothingDone = { f.file("small").list()!!.size == 21 },
        )
        f.file("small/extra.txt").delete()
        val outcome = PreparedActionChecks.assertRunsWhenUnchanged(f.tool("workspace.trash"), json("paths" to listOf("small")), f.context)
        assertFalse(f.file("small").exists())
        assertTrue("\"small\" as " in outcome.content, outcome.content)
        assertTrue("inside another" in f.rejected("workspace.trash", "paths" to listOf("big", "big/f1.txt")))
        assertTrue("nothing at" in f.fails("workspace.trash", "paths" to listOf("big", "missing")).content)
        assertTrue(f.file("big").exists(), "nothing is trashed when one path is missing")
    }

    @Test
    fun `restore lists the trash, puts an item back and refuses an occupied place`() = runTest {
        f.put("a.txt", "a")
        f.ok("workspace.trash", "paths" to listOf("a.txt"))
        val id = (f.workspace.listTrash() as DocResult.Ok).value.single().id
        val listing = f.ok("workspace.restore")
        assertTrue(id in listing.content && "\"a.txt\"" in listing.content, listing.content)
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.restore"), json("id" to id), f.context,
            change = { f.put("a.txt", "new a") }, nothingDone = { f.file("a.txt").readText() == "new a" },
        )
        f.file("a.txt").delete()
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("workspace.restore"), json("id" to id), f.context)
        assertEquals("a", f.file("a.txt").readText())
        assertTrue("no item" in f.fails("workspace.restore", "id" to "1-nope").content)
    }

    @Test
    fun `emptying the trash is consequential, names every item and deletes exactly those`() = runTest {
        f.put("one.txt", "1")
        f.put("two.txt", "22")
        f.ok("workspace.trash", "paths" to listOf("one.txt"))
        val assessment = f.assess("workspace.empty_trash")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertTrue("Delete permanently 1 item" in assessment.summary, assessment.summary)
        assertTrue(assessment.details.single().contains("\"one.txt\""), assessment.preview)

        // An item trashed after the approval is not deleted with the approved ones.
        val outcome = f.tool("workspace.empty_trash").execute(json(), f.context, assessment.also { f.ok("workspace.trash", "paths" to listOf("two.txt")) })
        assertTrue(outcome.ok, outcome.content)
        assertEquals(listOf("two.txt"), (f.workspace.listTrash() as DocResult.Ok).value.map { it.originalPath })

        val id = (f.workspace.listTrash() as DocResult.Ok).value.single().id
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.empty_trash"), json("ids" to listOf(id)), f.context,
            change = { f.workspace.restore(id) }, nothingDone = { f.file("two.txt").exists() },
        )
        assertTrue("empty" in f.fails("workspace.empty_trash").content)
        assertTrue("no item" in f.fails("workspace.empty_trash", "ids" to listOf("1-x")).content)
    }

    @Test
    fun `W1 - with leftovers of an interrupted change in the trash, the tools do not call it empty`() = runTest {
        // Two things in one holder and no record: nothing the trash can list or restore, but not nothing.
        f.put(".trash/1700000000000-two/a.txt", "aaaa")
        f.put(".trash/1700000000000-two/b.txt", "bb")
        val refused = f.fails("workspace.empty_trash")
        assertFalse("trash is empty" in refused.content, refused.content)
        assertTrue("lists no items" in refused.content && "only the owner" in refused.content, refused.content)
        val list = f.ok("workspace.restore")
        assertFalse("is empty" in list.content, list.content)
        assertTrue("lists no items" in list.content && "leftovers" in list.content, list.content)
        assertTrue(f.file(".trash/1700000000000-two/a.txt").exists(), "an agent's call deletes none of it")

        // With a listed item next to them, the list says both, and emptying deletes the listed item only.
        f.put("one.txt", "1")
        f.ok("workspace.trash", "paths" to listOf("one.txt"))
        val both = f.ok("workspace.restore").content
        assertTrue("holds 1 item" in both && "leftovers" in both, both)
        val emptied = f.ok("workspace.empty_trash")
        assertTrue("Deleted 1 item" in emptied.content, emptied.content)
        assertTrue(f.file(".trash/1700000000000-two/b.txt").exists())

        // Once the owner emptied the trash, it is empty and the tools say so.
        assertEquals(DocResult.Ok(0), f.workspace.emptyTrash(null))
        assertTrue("The trash is empty" in f.fails("workspace.empty_trash").content)
        assertEquals("The workspace trash is empty.", f.ok("workspace.restore").content)
    }

    @Test
    fun `export opens the share screen for exactly the approved content`() = runTest {
        f.put("report.csv", "a,b")
        val assessment = f.assess("workspace.export", "path" to "report.csv")
        assertEquals("\"report.csv\" to the app you choose", assessment.dataGoesTo)
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("workspace.export"), json("path" to "report.csv"), f.context,
            change = { f.put("report.csv", "a,c") }, nothingDone = { f.apps.calls.isEmpty() },
        )
        f.apps.installed += InstalledAppInfo("com.example.mail", "Mail")
        val shared = PreparedActionChecks.assertRunsWhenUnchanged(f.tool("workspace.export"), json("path" to "report.csv", "app" to "com.example.mail"), f.context)
        assertTrue("share screen" in shared.content, shared.content)
        val call = f.apps.calls.single() as AppCall.ShareFile
        assertEquals("report.csv", call.file.path.value)
        assertEquals("text/csv", call.mimeType)
        assertTrue("protected" in f.rejected("workspace.export", "path" to "report.csv", "app" to "com.android.settings"))
    }

    @Test
    fun `failing calls say why and touch nothing`() = runTest {
        assertTrue("There is no file" in f.fails("workspace.replace_text", "path" to "none.txt", "find" to "a", "replace" to "b").content)
        assertTrue("There is nothing at" in f.fails("workspace.move", "from" to "none", "to" to "x").content)
        assertNull(f.file("x").takeIf { it.exists() })
        val prepared = f.assess("workspace.write", "path" to "dir/x.txt", "content" to "x").prepared
        assertTrue(prepared is PreparedCall)
        assertEquals(DocResult.NotFound("dir"), f.workspace.stat(path("dir")))
    }
}
