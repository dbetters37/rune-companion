package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shell's home folder when an earlier command locked it or left something that cannot be
 * removed: the next command must run all the same, with an empty home folder.
 *
 * The build machine runs everything as root, and no permission bit refuses root anything. So a
 * folder cannot really be locked here, and these tests go through [FolderOps]: one that refuses
 * a chosen path, and one that notes the rights a folder has at the moment it is listed. The
 * real thing, Android's shell and a folder with its rights taken away, is
 * ShellLeftoversDeviceTest.aCommandCannotLockTheHomeFolder on a device.
 */
class ShellHomeFolderTest {
    private val cache: File = Files.createTempDirectory("aicc-home-cache").toFile()
    private val home = File(cache, "shell-home")
    private val root: File = Files.createTempDirectory("aicc-home-ws").toFile()
    private val tmp: File = Files.createTempDirectory("aicc-home-tmp").toFile()
    private val limits = CommandLimits(timeoutMs = 10_000, maxOutputChars = 1_000)

    private fun workspace(): WorkspaceFile = (WorkspaceFile.resolve(root, RelativePath.ROOT) as DocResult.Ok).value

    private fun besideHome(): List<String> = cache.list().orEmpty().filter { it != home.name }.sorted()

    /** The real operations, but what [refuse] names cannot be deleted, and [noRename] switches renaming off. */
    private class Refusing(var refuse: (Path) -> Boolean = { false }, var noRename: Boolean = false) : FolderOps() {
        val renamed = ArrayList<Pair<String, String>>()

        override fun delete(path: Path) {
            if (refuse(path)) throw AccessDeniedException(path.toString())
            super.delete(path)
        }

        override fun rename(from: Path, to: Path) {
            if (noRename) throw AccessDeniedException(from.toString())
            super.rename(from, to)
            renamed += from.fileName.toString() to to.fileName.toString()
        }
    }

    private fun locked(dir: File, mode: String = "---------") {
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString(mode))
    }

    // --- the folder's rights are given back before it is listed ---

    @Test
    fun `a folder gets its owner's rights back before it is listed, not after`() {
        // What two commands left: a folder in HOME without any rights and a file in it; HOME itself without the right to list it.
        File(home, "a").mkdirs()
        File(home, "a/f").writeText("x")
        File(home, "top").writeText("x")
        locked(File(home, "a"))
        locked(home, "-wx------")
        val rightsWhenListed = HashMap<String, Set<PosixFilePermission>>()
        val watching = object : FolderOps() {
            override fun list(dir: Path, limit: Int): List<Path> {
                rightsWhenListed[dir.fileName.toString()] = Files.getPosixFilePermissions(dir)
                return super.list(dir, limit)
            }
        }

        val result = SystemShellRunner.emptyFolder(home, watching)

        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        assertEquals(setOf("shell-home", "a"), rightsWhenListed.keys)
        for ((folder, rights) in rightsWhenListed) assertTrue(rights.containsAll(owner), "$folder was listed with $rights")
        assertEquals(home, result)
        assertEquals(emptyList(), home.list()!!.toList())
        assertTrue(home.canWrite())
        assertEquals(emptyList(), besideHome(), "nothing had to be put aside")
    }

    @Test
    fun `a full folder is emptied in batches and a deep one without recursion`() {
        val many = File(home, "many").apply { mkdirs() }
        repeat(FolderOps.BATCH * 2 + 7) { File(many, "f$it").createNewFile() }
        var deep = File(home, "deep")
        repeat(300) { deep = File(deep, "d") }
        deep.mkdirs()
        File(deep, "bottom").writeText("x")
        var largestBatch = 0
        val counting = object : FolderOps() {
            override fun list(dir: Path, limit: Int): List<Path> = super.list(dir, limit).also { largestBatch = maxOf(largestBatch, it.size) }
        }

        SystemShellRunner.emptyFolder(home, counting)

        assertEquals(emptyList(), home.list()!!.toList())
        assertEquals(FolderOps.BATCH, largestBatch, "no more than one batch of a folder's entries is held at a time")
    }

    // --- the safety net ---

    @Test
    fun `a home folder that cannot be emptied is put aside and a new empty one is used`() {
        File(home, "a").mkdirs()
        File(home, "a/stubborn").writeText("x")
        File(home, ".profile").writeText("echo LEFT-BEHIND\n")
        val ops = Refusing(refuse = { it.fileName.toString() == "stubborn" })

        val result = SystemShellRunner.emptyFolder(home, ops)

        assertEquals(home, result, "HOME keeps its place")
        assertEquals(emptyList(), home.list()!!.toList())
        assertEquals(listOf("shell-home" to "shell-home.stuck-0"), ops.renamed)
        assertTrue(File(cache, "shell-home.stuck-0/a/stubborn").isFile, "what could not be removed went aside with its folder")

        // The next command: still stuck, so the one put aside stays and HOME is simply emptied.
        File(home, "note").writeText("from the command in between")
        assertEquals(home, SystemShellRunner.emptyFolder(home, ops))
        assertEquals(emptyList(), home.list()!!.toList())
        assertEquals(listOf("shell-home.stuck-0"), besideHome())

        // Once it can be removed (here: the refusal ends), the next command, or the app's start, removes what was put aside.
        ops.refuse = { false }
        SystemShellRunner.clearPutAside(home, ops)
        assertEquals(emptyList(), besideHome())
    }

    @Test
    fun `each home folder that cannot be emptied gets a number of its own`() {
        val ops = Refusing(refuse = { it.fileName.toString() == "stubborn" })
        repeat(3) {
            File(home, "stubborn").apply { parentFile!!.mkdirs() }.writeText("x")
            assertEquals(home, SystemShellRunner.emptyFolder(home, ops))
            assertEquals(emptyList(), home.list()!!.toList())
        }

        assertEquals(listOf("shell-home.stuck-0", "shell-home.stuck-1", "shell-home.stuck-2"), besideHome())
    }

    @Test
    fun `when it cannot even be put aside, a new folder beside it is used`() {
        File(home, "stubborn").apply { parentFile!!.mkdirs() }.writeText("x")
        val ops = Refusing(refuse = { it.fileName.toString() == "stubborn" }, noRename = true)

        val first = SystemShellRunner.emptyFolder(home, ops)

        assertNotEquals(home, first)
        assertEquals(cache, first.parentFile, "it is beside the usual one, in the app's cache, not in the workspace")
        assertTrue(first.name.startsWith("shell-home.fresh-"), first.name)
        assertEquals(emptyList(), first.list()!!.toList())
        assertTrue(File(home, "stubborn").isFile, "the one that is stuck stays where it is")

        // The next command gets another new one, and the one before it is removed.
        File(first, "left-by-the-command").writeText("x")
        val second = SystemShellRunner.emptyFolder(home, ops)

        assertNotEquals(first, second)
        assertFalse(first.exists(), "the folder made for the command before is gone")
        assertEquals(listOf(second.name), besideHome())
    }

    @Test
    fun `only when no folder can be made at all is there none`() {
        // The parent is a file: nothing can be emptied, renamed or made there.
        val impossible = File(File(cache, "a-file").apply { writeText("x") }, "shell-home")

        assertFailsWith<IOException> { SystemShellRunner.emptyFolder(impossible) }
    }

    @Test
    fun `a link in the place of a folder that was put aside is removed as a link`() {
        val precious = File(root, "keep").apply { mkdirs() }
        File(precious, "important.txt").writeText("owner data")
        // A command made the names this app uses for folders it puts aside, as links into the workspace.
        cache.mkdirs()
        Files.createSymbolicLink(File(cache, "shell-home.stuck-0").toPath(), precious.toPath())
        Files.createSymbolicLink(File(cache, "shell-home.fresh-1").toPath(), precious.toPath())

        SystemShellRunner.emptyFolder(home)

        assertEquals(emptyList(), besideHome())
        assertEquals("owner data", File(precious, "important.txt").readText(), "the links were removed, not followed")
    }

    // --- through the runner: the command runs ---

    @Test
    fun `the next command runs with an empty home folder although the earlier one's cannot be emptied`() = runBlocking {
        val ops = Refusing()
        val runner = SystemShellRunner(
            tempDir = { tmp }, homeDir = { home }, sweeper = FakeProc().sweeper(),
            shell = "/bin/sh", setsid = HOST_SETSID, folderOps = ops,
        )
        val first = runner.run("echo 'echo LEFT-BEHIND' > \"\$HOME/.profile\"; echo x > \"\$HOME/stubborn\"", workspace(), limits)
        assertIs<CommandOutcome.Finished>(first, "$first")
        ops.refuse = { it.fileName.toString() == "stubborn" }

        val next = runner.run("/bin/sh -l -c 'echo second'; for f in \"\$HOME\"/.* \"\$HOME\"/*; do echo \"in-home:\$f\"; done; echo ok > \"\$HOME/new\" && echo writable", workspace(), limits)

        val out = assertIs<CommandOutcome.Finished>(next, "the command must run: $next").stdout
        assertFalse("LEFT-BEHIND" in out, out)
        assertFalse("stubborn" in out, out)
        assertTrue("second\n" in out && "writable\n" in out, out)
        assertEquals(listOf("shell-home.stuck-0"), besideHome())

        // At the app's start what was put aside is removed, as soon as it can be.
        ops.refuse = { false }
        runner.stopLeftovers()
        assertEquals(emptyList(), besideHome())
    }

    @Test
    fun `a command is refused only when no home folder can be made`() = runBlocking {
        val impossible = File(File(cache, "a-file").apply { writeText("x") }, "shell-home")
        val runner = SystemShellRunner(
            tempDir = { tmp }, homeDir = { impossible }, sweeper = FakeProc().sweeper(),
            shell = "/bin/sh", setsid = HOST_SETSID,
        )

        val outcome = runner.run("echo ran > ran.txt", workspace(), limits)

        assertTrue("home folder" in assertIs<CommandOutcome.Failed>(outcome, "$outcome").reason)
        assertFalse(File(root, "ran.txt").exists())
    }

    private companion object {
        /** util-linux setsid on the build machine; Android's toybox one is at /system/bin/setsid. */
        const val HOST_SETSID = "/usr/bin/setsid"
    }
}
