package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F2: replacing a workspace file (write, edit, move over, import over) keeps the old version in
 * the trash without the file ever leaving its place. A reader sees the old file or the new one,
 * never none; a kill at any step leaves the file where it is, and nothing in the trash that
 * emptying it would not clean. Both ways of keeping are covered: the hard link, and the copy a
 * phone falls back to when Android refuses links in app storage.
 *
 * W1 (round 3): the same for the explicit trash and for what an interrupted change leaves in the
 * trash. The trash record is written before the content goes into its holder, so a kill leaves an
 * item in its place or in the trash with its record, never unlisted; at the first change after a
 * start a record without content is removed, and content without a record is listed again.
 */
class WorkspaceAtomicReplaceTest {
    private val clock = FakeClock(autoAdvanceMs = 1)
    private val live = RelativePath.of("doc/live.txt")

    private fun workspace(root: File, links: Boolean) = LocalWorkspace(FakeWorkspaceRoot(root), clock).also { it.linksWork = links }

    private fun newRoot(): File = Files.createTempDirectory("aicc-atomic").toFile()

    private fun <T> ok(r: DocResult<T>): T = assertIs<DocResult.Ok<T>>(r, r.toString()).value

    /** A whole version: the same line a fixed number of times, so half a file or a mix of two versions is recognisable. */
    private fun version(n: Int) = "version $n\n".repeat(400)

    private fun isWhole(text: String): Boolean {
        val lines = text.split("\n")
        return lines.size == 401 && lines.last().isEmpty() && lines.dropLast(1).toSet().size == 1 && lines[0].startsWith("version ")
    }

    /** Replaces the live file in one of the four ways, from version [n] to version n + 1. */
    private suspend fun replace(ws: LocalWorkspace, n: Int, how: Int) {
        when (how) {
            0 -> ok(ws.write(live, version(n + 1).toByteArray(), overwrite = true))
            1 -> assertEquals(400, ok(ws.replaceText(live, "version $n", "version ${n + 1}", expectedCount = 400)))
            2 -> {
                ok(ws.write(RelativePath.of("incoming.txt"), version(n + 1).toByteArray(), overwrite = false))
                ok(ws.move(RelativePath.of("incoming.txt"), live, overwrite = true))
            }
            else -> ok(ws.import(live, ByteArrayInputStream(version(n + 1).toByteArray()), 1_000_000, overwrite = true))
        }
    }

    private val ways = listOf("write", "replaceText", "move", "import")

    @Test
    fun `a reader always finds the old or the new file while it is replaced`() {
        for (links in listOf(true, false)) {
            val root = newRoot()
            val ws = workspace(root, links)
            runBlocking { ok(ws.write(live, version(0).toByteArray(), overwrite = false)) }
            val file = File(root, live.value).toPath()
            val stop = AtomicBoolean(false)
            val reads = AtomicLong()
            val attempts = AtomicLong()
            val problems = ConcurrentLinkedQueue<String>()
            val reader = thread(name = "reader") {
                while (!stop.get()) {
                    try {
                        val text = String(Files.readAllBytes(file))
                        if (!isWhole(text)) problems += "not one whole version: ${text.length} characters"
                        reads.incrementAndGet()
                    } catch (e: NoSuchFileException) {
                        problems += "the file was missing"
                    } catch (e: IOException) {
                        problems += e.toString()
                    }
                    attempts.incrementAndGet()
                }
            }
            // The reader runs freely all along. On top of that, before every step of a replacement the writer waits until
            // the reader has read the file from start to end at least once, so every state in between is seen on any machine.
            ws.beforeStep = {
                val enough = attempts.get() + 2
                val giveUp = System.nanoTime() + 5_000_000_000L
                while (attempts.get() < enough && System.nanoTime() < giveUp) Thread.yield()
            }
            try {
                runBlocking { repeat(REPLACEMENTS) { n -> replace(ws, n, n % 4) } }
            } finally {
                ws.beforeStep = null
                stop.set(true)
                reader.join(10_000)
            }
            assertTrue(problems.isEmpty(), "links=$links: ${problems.size} bad reads of ${attempts.get()}, first: ${problems.peek()}")
            assertTrue(reads.get() > REPLACEMENTS * 5L, "the reader saw every step (${reads.get()} reads)")
            assertEquals(version(REPLACEMENTS), String(Files.readAllBytes(file)))
            runBlocking { assertEquals(REPLACEMENTS, ok(ws.listTrash()).size, "every replaced version was kept") }
        }
    }

    @Test
    fun `killed at any step, the file is in place, the old version is kept once the new one is there, and nothing is left that emptying the trash would not clean`() = runBlocking {
        for (links in listOf(true, false)) {
            for ((how, way) in ways.withIndex()) {
                val root = newRoot()
                val ws = workspace(root, links)
                ok(ws.write(live, version(0).toByteArray(), overwrite = false))
                // The workspace as a kill right before each step would leave it.
                val killed = ArrayList<Pair<String, File>>()
                ws.beforeStep = { step -> killed += step to snapshot(root) }
                replace(ws, 0, how)
                ws.beforeStep = null
                killed += "nothing: it finished" to snapshot(root)
                assertTrue(killed.size >= 5, "$way: the steps were seen: ${killed.map { it.first }}")
                if (!links) {
                    // A copy arrives in its holder whole: it is made next to the staged files and moved in as one step.
                    val beforeMove = killed.firstOrNull { it.first == "move the copy into the holder" }?.second
                    assertTrue(beforeMove != null, "$way: the copy is moved into the holder in a step of its own: ${killed.map { it.first }}")
                    val holders = File(beforeMove, ".trash").listFiles().orEmpty().filter { it.isDirectory && it.name != ".staging" }
                    assertTrue(holders.size == 1 && holders.single().list().orEmpty().isEmpty(), "$way: the holder is still empty while the copy is made")
                    assertEquals(version(0), File(beforeMove, ".trash/.staging").listFiles().orEmpty().single { it.name.startsWith("k-") }.readText(), way)
                }

                for ((step, dir) in killed) {
                    val where = "$way, links=$links, killed before \"$step\""
                    val file = File(dir, live.value)
                    assertTrue(file.isFile, "$where: the file is gone")
                    assertNoContentWithoutRecord(dir, where)
                    val content = file.readText()
                    assertTrue(content == version(0) || content == version(1), "$where: the file holds neither version")

                    val reopened = workspace(dir, links)
                    val listed = ok(reopened.listTrash())
                    for (item in listed) assertTrue(File(dir, ".trash/${item.id}/${item.originalPath.substringAfterLast('/')}").exists(), "$where: a listed item has no content")
                    if (content == version(1)) {
                        val old = listed.filter { it.originalPath == live.value }
                        assertEquals(1, old.size, "$where: the replaced version is in the trash")
                        assertEquals(version(0), File(dir, ".trash/${old.single().id}/live.txt").readText(), where)
                    }

                    // The first change after the restart clears what was staged, and a kept "version" that is still the live file itself.
                    ok(reopened.makeFolder(RelativePath.of("after-restart")))
                    assertTrue(File(dir, ".trash/.staging").list().orEmpty().isEmpty(), "$where: staged files are left: ${File(dir, ".trash/.staging").list()?.toList()}")
                    for (item in ok(reopened.listTrash()).filter { it.originalPath == live.value }) {
                        assertFalse(Files.isSameFile(file.toPath(), File(dir, ".trash/${item.id}/live.txt").toPath()), "$where: the trash lists the live file itself as a kept version")
                    }
                    // A later change in place must not reach into the trash.
                    val before = ok(reopened.listTrash()).associate { it.id to File(dir, ".trash/${it.id}/${it.originalPath.substringAfterLast('/')}").readText() }
                    ok(reopened.append(live, "appended\n".toByteArray()))
                    for ((id, text) in before) assertEquals(text, File(dir, ".trash/$id").listFiles()!!.single().readText(), "$where: appending to the file changed a kept version")

                    ok(reopened.emptyTrash(null))
                    assertEquals(emptySet(), File(dir, ".trash").list().orEmpty().toSet() - ".staging", "$where: emptying the trash left something")
                    assertTrue(File(dir, ".trash/.staging").list().orEmpty().isEmpty(), where)
                    assertEquals(content + "appended\n", file.readText(), where)
                }
            }
        }
    }

    @Test
    fun `when a step fails, the old file stays, nothing is kept for it and nothing is left staged`() = runBlocking {
        for (links in listOf(true, false)) {
            for ((how, way) in ways.withIndex()) {
                val steps = ArrayList<String>()
                run {
                    val probe = workspace(newRoot(), links)
                    ok(probe.write(live, version(0).toByteArray(), overwrite = false))
                    probe.beforeStep = { steps += it }
                    replace(probe, 0, how)
                }
                // Fail each step of the replacement in turn (for a move, the write of the incoming file is not part of it).
                for (failing in steps.indices) {
                    val root = newRoot()
                    val ws = workspace(root, links)
                    ok(ws.write(live, version(0).toByteArray(), overwrite = false))
                    if (how == 2) ok(ws.write(RelativePath.of("incoming.txt"), version(1).toByteArray(), overwrite = false))
                    // The incoming file was written before (two steps: staged, renamed into place); the move's own steps follow.
                    val skipped = if (how == 2) 2 else 0
                    if (failing < skipped) continue
                    var seen = skipped
                    ws.beforeStep = { if (seen++ == failing) throw IOException("the disk says no at \"$it\"") }
                    val result = when (how) {
                        0 -> ws.write(live, version(1).toByteArray(), overwrite = true)
                        1 -> ws.replaceText(live, "version 0", "version 1", expectedCount = 400)
                        2 -> ws.move(RelativePath.of("incoming.txt"), live, overwrite = true)
                        else -> ws.import(live, ByteArrayInputStream(version(1).toByteArray()), 1_000_000, overwrite = true)
                    }
                    ws.beforeStep = null
                    val where = "$way, links=$links, failing at \"${steps[failing]}\""
                    assertIs<DocResult.Failed>(result, where)
                    assertEquals(version(0), File(root, live.value).readText(), where)
                    if (how == 2) assertEquals(version(1), File(root, "incoming.txt").readText(), where)
                    assertTrue(ok(ws.listTrash()).isEmpty(), "$where: something was kept for a file that was not replaced")
                    assertEquals(emptySet(), File(root, ".trash").list().orEmpty().toSet() - ".staging", where)
                    assertTrue(File(root, ".trash/.staging").list().orEmpty().isEmpty(), where)
                    // And the same change then goes through.
                    replace(ws, 0, how.takeIf { it != 2 } ?: 0)
                    assertEquals(version(1), File(root, live.value).readText(), where)
                }
            }
        }
    }

    @Test
    fun `a file with a second name is kept as a copy, so its other name cannot change the kept version`() = runBlocking {
        val root = newRoot()
        val ws = workspace(root, links = true)
        ok(ws.write(live, version(0).toByteArray(), overwrite = false))
        // A hard link, as a shell command could make one.
        Files.createLink(File(root, "doc/other-name.txt").toPath(), File(root, live.value).toPath())
        ok(ws.write(live, version(1).toByteArray(), overwrite = true))
        ok(ws.append(RelativePath.of("doc/other-name.txt"), "changed through the other name\n".toByteArray()))
        val item = ok(ws.listTrash()).single()
        assertEquals(version(0), File(root, ".trash/${item.id}/live.txt").readText())
        assertEquals(version(1), File(root, live.value).readText())
    }

    @Test
    fun `what an interrupted write staged is cleared at the first change after a start, and by emptying the trash`() = runBlocking {
        val root = newRoot()
        File(root, ".trash/.staging").mkdirs()
        File(root, ".trash/.staging/w-leftover").writeText("half a file")
        val ws = workspace(root, links = true)
        // Reading clears nothing; the first change does.
        ok(ws.list(RelativePath.ROOT))
        assertTrue(File(root, ".trash/.staging/w-leftover").exists())
        ok(ws.makeFolder(RelativePath.of("x")))
        assertFalse(File(root, ".trash/.staging/w-leftover").exists())

        File(root, ".trash/.staging/w-later").writeText("another")
        ok(ws.makeFolder(RelativePath.of("y")))
        assertTrue(File(root, ".trash/.staging/w-later").exists(), "only once per start")
        assertEquals(0, ok(ws.emptyTrash(null)))
        assertFalse(File(root, ".trash/.staging/w-later").exists())
        assertTrue(File(root, ".trash/.staging").isDirectory)
    }

    // --- W1: the explicit trash, and what an interrupted change leaves in the trash ---

    /** What is directly under the trash folder, apart from the staging folder. */
    private fun trashEntries(dir: File): Set<String> = File(dir, ".trash").list().orEmpty().toSet() - ".staging"

    /**
     * The order the trash keeps: the record is in place before anything goes into its holder. Looked at in [dir] as a kill
     * left it, before the workspace tidied anything: no holder has content without its record.
     */
    private fun assertNoContentWithoutRecord(dir: File, where: String) {
        val holders = File(dir, ".trash").listFiles().orEmpty().filter { it.isDirectory && it.name != ".staging" && it.list().orEmpty().isNotEmpty() }
        for (holder in holders) assertTrue(File(dir, ".trash/${holder.name}.json").isFile, "$where: the holder ${holder.name} has content (${holder.list()?.toList()}) but no record")
    }

    @Test
    fun `W1 - killed at any step of an explicit trash, the item is in its place or in the trash with its record, never neither`() = runBlocking<Unit> {
        for (folder in listOf(false, true)) {
            val root = newRoot()
            val ws = workspace(root, links = true)
            val path = if (folder) RelativePath.of("doc/old") else live
            if (folder) {
                ok(ws.write(RelativePath.of("doc/old/a.txt"), version(0).toByteArray(), overwrite = false))
                ok(ws.write(RelativePath.of("doc/old/sub/b.txt"), version(1).toByteArray(), overwrite = false))
            } else {
                ok(ws.write(live, version(0).toByteArray(), overwrite = false))
            }
            val killed = ArrayList<Pair<String, File>>()
            ws.beforeStep = { step -> killed += step to snapshot(root) }
            ok(ws.trash(path))
            ws.beforeStep = null
            killed += "nothing: it finished" to snapshot(root)

            for ((step, dir) in killed) {
                val where = "trash of a ${if (folder) "folder" else "file"}, killed before \"$step\""
                assertNoContentWithoutRecord(dir, where)
                val reopened = workspace(dir, links = true)
                fun inPlace() = if (folder) File(dir, "doc/old/a.txt").isFile && File(dir, "doc/old/sub/b.txt").isFile else File(dir, live.value).isFile
                suspend fun listed() = ok(reopened.listTrash()).filter { it.originalPath == path.value }

                // As the owner's trash screen finds it right after the start, before anything changed.
                val atStart = listed()
                assertTrue(inPlace() != atStart.isNotEmpty(), "$where: in place=${inPlace()}, listed in the trash=${atStart.size}")
                for (item in atStart) assertTrue(File(dir, ".trash/${item.id}/${path.name}").exists(), "$where: a listed item has no content")

                // After the first change after the start: still one or the other, and nothing half is left.
                ok(reopened.makeFolder(RelativePath.of("after-restart")))
                val items = listed()
                assertTrue(inPlace() != items.isNotEmpty(), "$where, after the first change: in place=${inPlace()}, listed in the trash=${items.size}")
                assertTrue(items.size <= 1, where)
                assertEquals(
                    ok(reopened.listTrash()).flatMap { listOf(it.id, "${it.id}.json") }.toSet(), trashEntries(dir),
                    "$where: a record without its content, or content without its record, is left",
                )
                assertTrue(File(dir, ".trash/.staging").list().orEmpty().isEmpty(), where)

                // What is in the trash comes back whole.
                items.singleOrNull()?.let { ok(reopened.restore(it.id)) }
                assertTrue(inPlace(), "$where: the item is not back in its place")
                if (folder) {
                    assertEquals(version(0), File(dir, "doc/old/a.txt").readText(), where)
                    assertEquals(version(1), File(dir, "doc/old/sub/b.txt").readText(), where)
                } else {
                    assertEquals(version(0), File(dir, live.value).readText(), where)
                }
                assertEquals(0, ok(reopened.emptyTrash(null)), where)
                assertEquals(emptySet(), trashEntries(dir), where)
            }
            // The record is in place before the item leaves its own.
            val steps = killed.map { it.first }
            assertTrue(steps.indexOf("rename the trash record into place") in 0 until steps.indexOf("move it into the trash holder"), "the steps: $steps")
        }
    }

    @Test
    fun `W1 - when a step of an explicit trash fails, the item stays in its place and nothing is left in the trash`() = runBlocking<Unit> {
        val steps = ArrayList<String>()
        run {
            val probe = workspace(newRoot(), links = true)
            ok(probe.write(live, version(0).toByteArray(), overwrite = false))
            probe.beforeStep = { steps += it }
            ok(probe.trash(live))
        }
        for (failing in steps.indices) {
            val root = newRoot()
            val ws = workspace(root, links = true)
            ok(ws.write(live, version(0).toByteArray(), overwrite = false))
            var seen = 0
            ws.beforeStep = { if (seen++ == failing) throw IOException("the disk says no at \"$it\"") }
            val result = ws.trash(live)
            ws.beforeStep = null
            val where = "failing at \"${steps[failing]}\""
            assertIs<DocResult.Failed>(result, where)
            assertEquals(version(0), File(root, live.value).readText(), where)
            assertTrue(ok(ws.listTrash()).isEmpty(), where)
            assertEquals(emptySet(), trashEntries(root), where)
            assertTrue(File(root, ".trash/.staging").list().orEmpty().isEmpty(), where)
            // And the same trash then goes through.
            val item = ok(ws.trash(live))
            assertEquals(version(0), File(root, ".trash/${item.id}/live.txt").readText(), where)
        }
    }

    @Test
    fun `W1 - at the first change after a start a record without content is removed, and content without a record is listed again, never deleted`() = runBlocking<Unit> {
        val id = "1700000000000-abc12345"

        // A holder with a file and no record: what a trash that an earlier version of the app was killed in left behind.
        run {
            val root = newRoot()
            File(root, ".trash/$id").mkdirs()
            File(root, ".trash/$id/report.txt").writeText("the owner's report")
            val ws = workspace(root, links = true)
            // (What the first look at the trash does when it comes before the first change: WorkspaceRound4Test.)
            ok(ws.makeFolder(RelativePath.of("after-restart")))
            val item = ok(ws.listTrash()).singleOrNull()
            assertEquals(id, item?.id, "the holder is listed again")
            assertEquals("the owner's report", File(root, ".trash/$id/report.txt").readText(), "nothing was deleted")
            assertEquals("report.txt" to false, item!!.originalPath to item.isFolder)
            assertEquals(18L, item.sizeBytes)
            assertEquals(1_700_000_000_000L, item.trashedAt)
            assertTrue(item.recovered, "its place before the trash is not known, and the record says so")
            assertNull(ws.trashLeftovers())
            ok(ws.restore(id))
            assertEquals("the owner's report", File(root, "report.txt").readText(), "restored to the top of the workspace, under its name")
            assertEquals(emptySet(), trashEntries(root))
        }

        // The same with a folder, and with a record that cannot be read any more.
        run {
            val root = newRoot()
            File(root, ".trash/$id/old/sub").mkdirs()
            File(root, ".trash/$id/old/a.txt").writeText("aaa")
            File(root, ".trash/$id/old/sub/b.txt").writeText("bb")
            File(root, ".trash/$id.json").writeText("{ not a record")
            val ws = workspace(root, links = true)
            ok(ws.makeFolder(RelativePath.of("after-restart")))
            val item = ok(ws.listTrash()).singleOrNull()
            assertEquals(Triple(id, "old", true), item?.let { Triple(it.id, it.originalPath, it.isFolder) })
            assertEquals(5L, item!!.sizeBytes)
            ok(ws.restore(id))
            assertEquals("aaa" to "bb", File(root, "old/a.txt").readText() to File(root, "old/sub/b.txt").readText())
        }

        // Something is at the top of the workspace under that name already: nothing is replaced, the item waits in the trash.
        run {
            val root = newRoot()
            File(root, ".trash/$id").mkdirs()
            File(root, ".trash/$id/report.txt").writeText("the trashed report")
            File(root, "report.txt").writeText("another report")
            val ws = workspace(root, links = true)
            ok(ws.makeFolder(RelativePath.of("after-restart")))
            assertIs<DocResult.AlreadyExists>(ws.restore(id))
            assertEquals("another report", File(root, "report.txt").readText())
            assertEquals("the trashed report", File(root, ".trash/$id/report.txt").readText())
        }

        // A record whose content never arrived (no holder, or an empty one): not listed, and removed.
        for (emptyHolder in listOf(false, true)) {
            val root = newRoot()
            File(root, ".trash").mkdirs()
            if (emptyHolder) File(root, ".trash/$id").mkdirs()
            File(root, ".trash/$id.json").writeText("""{"id":"$id","originalPath":"doc/live.txt","isFolder":false,"sizeBytes":4,"trashedAt":1}""")
            File(root, "doc").mkdirs()
            File(root, "doc/live.txt").writeText("live")
            val ws = workspace(root, links = true)
            assertTrue(ok(ws.listTrash()).isEmpty(), "a record without content is not a trash item (empty holder=$emptyHolder)")
            assertIs<DocResult.NotFound>(ws.restore(id))
            ok(ws.makeFolder(RelativePath.of("after-restart")))
            assertEquals(emptySet(), trashEntries(root), "empty holder=$emptyHolder")
            assertEquals("live", File(root, "doc/live.txt").readText())
        }

        // An empty holder alone goes too.
        run {
            val root = newRoot()
            File(root, ".trash/$id").mkdirs()
            val ws = workspace(root, links = true)
            ok(ws.makeFolder(RelativePath.of("after-restart")))
            assertEquals(emptySet(), trashEntries(root))
        }
    }

    @Test
    fun `W1 - a holder without a record that is only a second name of the live file is removed, and the file is no longer counted twice`() = runBlocking<Unit> {
        // The verifier's state: a replace killed (in the round-2 order) after the old version got its second name and before its record.
        val limits = WorkspaceLimits(maxFileBytes = 50_000, maxTotalBytes = 100_000, maxTextEditBytes = 50_000, maxSearchFileBytes = 50_000)
        val root = newRoot()
        File(root, "report.txt").writeBytes(ByteArray(40_000) { 'a'.code.toByte() })
        File(root, ".trash/1700000000000-abc12345").mkdirs()
        Files.createLink(File(root, ".trash/1700000000000-abc12345/report.txt").toPath(), File(root, "report.txt").toPath())
        val ws = LocalWorkspace(FakeWorkspaceRoot(root), clock, limits)
        assertEquals(80_000L, ws.usedBytes(), "counted twice until the first change")
        ok(ws.makeFolder(RelativePath.of("after-restart")))
        assertEquals(40_000L, ws.usedBytes())
        assertEquals(emptySet(), trashEntries(root))
        assertTrue(ok(ws.listTrash()).isEmpty())
        assertEquals(40_000L, File(root, "report.txt").length(), "the file itself is untouched")
        ok(ws.write(RelativePath.of("second.bin"), ByteArray(40_000), overwrite = false))

        // A second name that is not in the workspace is no reason to drop the trash's name: that file is listed again.
        val elsewhere = newRoot()
        val outside = Files.createTempDirectory("aicc-outside").toFile()
        File(elsewhere, ".trash/1700000000000-abc12345").mkdirs()
        File(elsewhere, ".trash/1700000000000-abc12345/report.txt").writeText("only in the trash, as far as the workspace goes")
        Files.createLink(File(outside, "report.txt").toPath(), File(elsewhere, ".trash/1700000000000-abc12345/report.txt").toPath())
        val other = LocalWorkspace(FakeWorkspaceRoot(elsewhere), clock, limits)
        ok(other.makeFolder(RelativePath.of("after-restart")))
        assertEquals(listOf("report.txt"), ok(other.listTrash()).map { it.originalPath })
        ok(other.restore("1700000000000-abc12345"))
        assertEquals("only in the trash, as far as the workspace goes", File(elsewhere, "report.txt").readText())

        // Where links are refused (a phone) the holder is a copy of its own: that is content, so it is listed, not deleted.
        val copied = newRoot()
        File(copied, "report.txt").writeBytes(ByteArray(40_000) { 'a'.code.toByte() })
        File(copied, ".trash/1700000000000-abc12345").mkdirs()
        File(copied, ".trash/1700000000000-abc12345/report.txt").writeBytes(ByteArray(40_000) { 'a'.code.toByte() })
        val phone = LocalWorkspace(FakeWorkspaceRoot(copied), clock, limits).also { it.linksWork = false }
        ok(phone.makeFolder(RelativePath.of("after-restart")))
        val item = ok(phone.listTrash()).single()
        assertEquals("report.txt" to 40_000L, item.originalPath to item.sizeBytes)
        assertIs<DocResult.Failed>(phone.write(RelativePath.of("second.bin"), ByteArray(40_000), overwrite = false))
        assertEquals(1, ok(phone.emptyTrash(null)), "and the owner can clear it from the trash screen")
        ok(phone.write(RelativePath.of("second.bin"), ByteArray(40_000), overwrite = false))
    }

    @Test
    fun `W1 - what cannot be listed again stays until the trash is emptied, which clears everything under the trash folder`() = runBlocking<Unit> {
        val root = newRoot()
        val ws = workspace(root, links = true)
        ok(ws.write(RelativePath.of("kept.txt"), "kept".toByteArray(), overwrite = false))
        ok(ws.write(RelativePath.of("gone.txt"), "gone".toByteArray(), overwrite = false))
        val listed = ok(ws.trash(RelativePath.of("gone.txt")))
        assertNull(ws.trashLeftovers(), "a listed item is no leftover")

        // Two things in one holder (the app never makes that), a stray file, and a staged file of a write that was killed.
        File(root, ".trash/1700000000000-two").mkdirs()
        File(root, ".trash/1700000000000-two/a.txt").writeText("aaaa")
        File(root, ".trash/1700000000000-two/b.txt").writeText("bb")
        File(root, ".trash/stray.bin").writeText("stray")
        File(root, ".trash/.staging/w-killed").writeText("staged!")
        val reopened = workspace(root, links = true)
        // A look tidies nothing (4b, F3): before the tidy, the staged file of the killed write is among the leftovers.
        assertEquals(4L + 2 + 5 + 7, reopened.trashLeftovers())
        assertTrue(File(root, ".trash/.staging/w-killed").exists())
        // The app tidies when it starts (and the owner's trash screen before its first look), so it is not among them then.
        reopened.tidyTrash()
        assertEquals(4L + 2 + 5, reopened.trashLeftovers())
        assertFalse(File(root, ".trash/.staging/w-killed").exists())
        ok(reopened.makeFolder(RelativePath.of("after-restart")))
        assertEquals(listOf(listed.id), ok(reopened.listTrash()).map { it.id }, "only what can be restored is listed")
        assertTrue(File(root, ".trash/1700000000000-two/a.txt").exists() && File(root, ".trash/stray.bin").exists(), "nothing is deleted silently")
        assertEquals(4L + 2 + 5, reopened.trashLeftovers(), "the rest waits for the owner")

        // Naming items deletes only those; emptying the whole trash clears all of it.
        assertEquals(0, ok(reopened.emptyTrash(setOf("1700000000000-nothing"))))
        assertTrue(File(root, ".trash/stray.bin").exists())
        assertEquals(1, ok(reopened.emptyTrash(null)), "one listed item")
        assertEquals(emptySet(), trashEntries(root))
        assertNull(reopened.trashLeftovers())
        assertEquals("kept", File(root, "kept.txt").readText())

        // A ".staging" that is not a folder (only a shell command could make one) blocks every write; emptying the trash clears it too.
        File(root, ".trash/.staging").deleteRecursively()
        File(root, ".trash/.staging").writeText("not a folder")
        assertIs<DocResult.Failed>(reopened.write(RelativePath.of("blocked.txt"), "x".toByteArray(), overwrite = false))
        assertEquals(12L, reopened.trashLeftovers())
        assertEquals(0, ok(reopened.emptyTrash(null)))
        ok(reopened.write(RelativePath.of("works.txt"), "x".toByteArray(), overwrite = false))
    }

    @Test
    fun `W1 - what the tidy after a start changed is announced as a change, also when the change it ran before fails`() = runBlocking<Unit> {
        val root = newRoot()
        File(root, ".trash/1700000000000-abc12345").mkdirs()
        File(root, ".trash/1700000000000-abc12345/report.txt").writeText("x")
        val ws = workspace(root, links = true)
        val before = ws.changes.first()
        // The first change after the start fails (nothing is at that path), yet the trash now lists the holder again.
        assertIs<DocResult.NotFound>(ws.trash(RelativePath.of("missing.txt")))
        assertEquals(1, ok(ws.listTrash()).size)
        assertTrue(ws.changes.first() > before, "the screens are told that the trash changed")

        // With nothing to tidy, a change that fails announces nothing.
        val clean = workspace(newRoot(), links = true)
        val quiet = clean.changes.first()
        assertIs<DocResult.NotFound>(clean.trash(RelativePath.of("missing.txt")))
        assertEquals(quiet, clean.changes.first())
    }

    /** A copy of [root] that keeps hard links as hard links: the directory as it would be found after a kill. */
    private fun snapshot(root: File): File {
        val target = Files.createTempDirectory("aicc-killed")
        val copied = HashMap<Any, Path>()
        Files.walk(root.toPath()).use { paths ->
            for (source in paths) {
                val to = target.resolve(root.toPath().relativize(source).toString())
                val attrs = Files.readAttributes(source, BasicFileAttributes::class.java)
                when {
                    attrs.isDirectory -> Files.createDirectories(to)
                    copied[attrs.fileKey()] != null -> Files.createLink(to, copied.getValue(attrs.fileKey()))
                    else -> {
                        Files.copy(source, to)
                        copied[attrs.fileKey()] = to
                    }
                }
            }
        }
        return target.toFile()
    }

    private companion object {
        const val REPLACEMENTS = 200
    }
}
