package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
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
import kotlin.test.assertTrue

/**
 * F2: replacing a workspace file (write, edit, move over, import over) keeps the old version in
 * the trash without the file ever leaving its place. A reader sees the old file or the new one,
 * never none; a kill at any step leaves the file where it is, and nothing in the trash that
 * emptying it would not clean. Both ways of keeping are covered: the hard link, and the copy a
 * phone falls back to when Android refuses links in app storage.
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

                for ((step, dir) in killed) {
                    val where = "$way, links=$links, killed before \"$step\""
                    val file = File(dir, live.value)
                    assertTrue(file.isFile, "$where: the file is gone")
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
                    val skipped = if (how == 2) steps.size - 5 else 0
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
