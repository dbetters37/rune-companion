package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W2: the workspace's size limit counts everything on disk, the trash's records too. Each trash
 * item adds a record of about 115 bytes; a change that writes one (a trash, a replace that keeps
 * the old version, a move over a file) checks that it fits, and a change that adds content leaves
 * room for records, so a workspace filled to its limit can still move a file to the trash. No
 * change made through the workspace ends over the limit, and the owner is never left without a
 * way to make room.
 */
class WorkspaceSizeLimitTest {
    private val clock = FakeClock(autoAdvanceMs = 1)

    private fun p(s: String) = RelativePath.of(s)

    private fun <T> ok(r: DocResult<T>): T = assertIs<DocResult.Ok<T>>(r, r.toString()).value

    /** Every byte under [dir], counted by the test itself. */
    private fun sizeOf(dir: File): Long = dir.listFiles().orEmpty().sumOf { if (it.isDirectory) sizeOf(it) else it.length() }

    private fun workspace(root: File, limits: WorkspaceLimits) = LocalWorkspace(FakeWorkspaceRoot(root), clock, limits)

    @Test
    fun `filled to its limit and then trashed file by file, the workspace does not end over its limit`() = runBlocking<Unit> {
        // The verifier's one-thread case: 20 000-byte files in a 100 000-byte workspace, then every one of them trashed.
        val root = Files.createTempDirectory("aicc-cap1").toFile()
        val limits = WorkspaceLimits(maxFileBytes = 20_000, maxTotalBytes = 100_000, maxTextEditBytes = 20_000, maxSearchFileBytes = 20_000)
        val ws = workspace(root, limits)
        val written = (0 until 8).filter { ws.write(p("f$it.bin"), ByteArray(20_000), overwrite = false) is DocResult.Ok }
        // Filled up byte by byte until nothing more goes in.
        var filler = 0
        for (size in listOf(10_000, 1_000, 100, 10, 1)) {
            while (ws.write(p("fill-${filler}.bin"), ByteArray(size), overwrite = false) is DocResult.Ok) filler++
        }
        assertTrue(written.size >= 4, "files written: $written")
        assertIs<DocResult.Failed>(ws.write(p("one-more.bin"), ByteArray(1), overwrite = false), "it is full")
        assertTrue(sizeOf(root) <= limits.maxTotalBytes)

        for (i in written) {
            ok(ws.trash(p("f$i.bin")))
            assertTrue(sizeOf(root) <= limits.maxTotalBytes, "after trashing f$i.bin the workspace holds ${sizeOf(root)} bytes, over its limit of ${limits.maxTotalBytes}")
        }
        assertEquals(sizeOf(root), ws.usedBytes())
        assertNull(ws.changedOutside(), "and it does not report itself as over its limit")
        assertEquals(written.size, ok(ws.listTrash()).size)
    }

    @Test
    fun `under parallel writers, trashes and gateway copies the workspace ends within its limit, records included`() = runBlocking<Unit> {
        // The earlier probe whose strict assertion (everything on disk <= the limit) failed in some runs, by less than the trash records' bytes.
        for (round in 0 until ROUNDS) {
            val links = round % 2 == 0
            val root = Files.createTempDirectory("aicc-cap").toFile()
            val limits = WorkspaceLimits(maxFileBytes = 20_000, maxTotalBytes = 200_000, maxTextEditBytes = 20_000, maxSearchFileBytes = 20_000)
            val ws = workspace(root, limits).also { it.linksWork = links }
            val names = listOf("a.txt", "b.txt", "dir/c.txt", "dir/d.txt", "e.bin")
            (0 until 6).map { worker ->
                async(Dispatchers.Default) {
                    val random = Random(worker * 31 + round)
                    repeat(400) {
                        val path = p(names.random(random))
                        when (random.nextInt(9)) {
                            0, 1 -> ws.write(path, ByteArray(random.nextInt(1, 6_000)) { 'x'.code.toByte() }, overwrite = true)
                            2 -> ws.append(path, ByteArray(random.nextInt(1, 2_000)) { 'y'.code.toByte() })
                            3 -> ws.move(path, p(names.random(random)), overwrite = true)
                            4 -> ws.trash(path)
                            5 -> {
                                val items = (ws.listTrash() as? DocResult.Ok)?.value.orEmpty()
                                if (items.isNotEmpty()) ws.restore(items.random(random).id)
                            }
                            6 -> {
                                // A gateway copy: sometimes it writes more than it was allowed.
                                val want = random.nextInt(1, 30_000)
                                val cheat = random.nextInt(4) == 0
                                ws.receive(p("in/copy-$worker-${random.nextInt(1_000_000)}.bin"), 25_000) { file, max ->
                                    if (want > max && !cheat) {
                                        DocResult.TooLarge(want.toLong(), max)
                                    } else {
                                        file.file.parentFile.mkdirs()
                                        file.file.writeBytes(ByteArray(want))
                                        DocResult.Ok(want.toLong())
                                    }
                                }
                            }
                            7 -> if (random.nextInt(6) == 0) ws.emptyTrash(null) else ws.list(RelativePath.ROOT)
                            else -> ws.read(path, 0, 1_000)
                        }
                    }
                }
            }.awaitAll()
            val total = sizeOf(root)
            val trash = File(root, Workspace.TRASH_FOLDER)
            val records = trash.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
            val holders = trash.listFiles().orEmpty().filter { it.isDirectory && it.name != ".staging" }
            assertTrue(
                total <= limits.maxTotalBytes,
                "round $round, links=$links: the workspace holds $total bytes, over its limit by ${total - limits.maxTotalBytes}; " +
                    "${records.size} trash records hold ${records.sumOf { it.length() }} bytes",
            )
            assertEquals(total, ws.usedBytes())
            assertEquals(records.map { it.name.removeSuffix(".json") }.toSet(), holders.map { it.name }.toSet(), "round $round: every holder has its record")
            assertTrue(File(trash, ".staging").list().orEmpty().isEmpty(), "round $round: nothing is left staged")
            root.deleteRecursively()
        }
    }

    @Test
    fun `a replace, a move over a file and a trash are refused when their trash record does not fit`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("aicc-cap2").toFile()
        // Room for records: 300 bytes, so two short records fit and a third does not.
        val limits = WorkspaceLimits(maxFileBytes = 50_000, maxTotalBytes = 100_000, maxTextEditBytes = 50_000, maxSearchFileBytes = 50_000, trashRecordRoomBytes = 300)
        val ws = workspace(root, limits)
        ok(ws.write(p("big.bin"), ByteArray(50_000), overwrite = false))
        ok(ws.write(p("rest.bin"), ByteArray(49_000), overwrite = false))
        for (name in listOf("a.txt", "b.txt", "c.txt", "d.txt")) ok(ws.write(p(name), ByteArray(100) { 'x'.code.toByte() }, overwrite = false))
        ok(ws.write(p("pad.bin"), ByteArray(300), overwrite = false))
        assertEquals(99_700L, ws.usedBytes(), "content up to the room kept for records")
        assertIs<DocResult.Failed>(ws.append(p("pad.bin"), ByteArray(1)))

        ok(ws.trash(p("a.txt")))
        ok(ws.trash(p("b.txt")))
        val before = ws.usedBytes()
        assertTrue(before in 99_900L..100_000L, "two records are in: $before")

        // A third record does not fit: nothing is trashed, moved over or replaced, and the message says what to do.
        val refused = assertIs<DocResult.Failed>(ws.trash(p("c.txt")))
        assertTrue("full" in refused.reason && "Empty the trash" in refused.reason, refused.reason)
        assertTrue(File(root, "c.txt").isFile)
        assertIs<DocResult.Failed>(ws.move(p("c.txt"), p("d.txt"), overwrite = true))
        assertIs<DocResult.Failed>(ws.write(p("c.txt"), ByteArray(0), overwrite = true))
        assertIs<DocResult.Failed>(ws.replaceText(p("c.txt"), "x", "", null))
        assertEquals(before, ws.usedBytes(), "nothing changed")
        assertEquals(2, ok(ws.listTrash()).size)
        assertEquals(setOf(".staging") + ok(ws.listTrash()).flatMap { listOf(it.id, "${it.id}.json") }, File(root, ".trash").list().orEmpty().toSet(), "no half item is left")
        assertEquals("x".repeat(100), File(root, "c.txt").readText())
        assertEquals("x".repeat(100), File(root, "d.txt").readText())

        // Emptying the trash makes room, and then it goes through.
        assertEquals(2, ok(ws.emptyTrash(null)))
        ok(ws.trash(p("c.txt")))
        assertTrue(sizeOf(root) <= limits.maxTotalBytes)
    }

    @Test
    fun `with the trash empty one item can always be trashed, also after a shell command filled the workspace to the brim`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("aicc-cap3").toFile()
        val limits = WorkspaceLimits(maxFileBytes = 50_000, maxTotalBytes = 100_000, maxTextEditBytes = 50_000, maxSearchFileBytes = 50_000)
        val ws = workspace(root, limits)
        ok(ws.write(p("notes.txt"), ByteArray(1_000), overwrite = false))
        // A shell command writes past the room the workspace keeps for records, and past the limit.
        File(root, "dump.bin").writeBytes(ByteArray(98_995))
        assertEquals(99_995L, ws.usedBytes())
        assertIs<DocResult.Failed>(ws.write(p("more.txt"), ByteArray(1), overwrite = false))

        // The way out is the trash: the first item goes in although its record takes the workspace over its limit...
        val item = ok(ws.trash(p("dump.bin")))
        assertTrue("full" in ws.changedOutside()!!, "it is over its limit by the record, and says so")
        // ...a second one waits until the trash was emptied...
        val waits = assertIs<DocResult.Failed>(ws.trash(p("notes.txt")))
        assertTrue("Empty the trash" in waits.reason, waits.reason)
        // ...which then frees what was trashed.
        assertEquals(1, ok(ws.emptyTrash(setOf(item.id))))
        assertEquals(1_000L, ws.usedBytes())
        ok(ws.trash(p("notes.txt")))

        // The same when the command went far over the limit.
        File(root, "huge.bin").writeBytes(ByteArray(150_000))
        ok(ws.emptyTrash(null))
        ok(ws.trash(p("huge.bin")))
        ok(ws.emptyTrash(null))
        assertEquals(0L, ws.usedBytes())
    }

    private companion object {
        const val ROUNDS = 8
    }
}
