package com.aicontrolcenter.device.operator.commands

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shell's home folder after an earlier command left something in it, as much as it liked,
 * locked or not: the next command must start at once, with an empty home folder outside the
 * workspace. Before a command the folder is only put aside (one rename, the same work whatever
 * was left); what was put aside is deleted afterwards, off the command's path, with a fixed
 * amount of memory and a few seconds at a time.
 *
 * The build machine runs everything as root, and no permission bit refuses root anything. So a
 * folder cannot really be locked here, and these tests go through [FolderOps]: one that refuses
 * a chosen path, one that counts what is held, and one that notes the rights a folder has at the
 * moment it is listed. The real thing, Android's shell and folders with their rights taken away,
 * is ShellLeftoversDeviceTest on a device.
 */
class ShellHomeFolderTest {
    private val cache: File = Files.createTempDirectory("aicc-home-cache").toFile()
    private val home = File(cache, "shell-home")
    private val temp = File(cache, "shell")
    private val root: File = Files.createTempDirectory("aicc-home-ws").toFile()
    private val elsewhere: File = Files.createTempDirectory("aicc-home-tmp").toFile()
    private val limits = CommandLimits(timeoutMs = 10_000, maxOutputChars = 1_000)

    private fun workspace(): WorkspaceFile = (WorkspaceFile.resolve(root, RelativePath.ROOT) as DocResult.Ok).value

    private fun besideHome(): List<String> = cache.list().orEmpty().filter { it != home.name && it != temp.name }.sorted()

    private fun folders(ops: FolderOps = FolderOps(), temp: File = this.temp): ShellFolders = SystemShellRunner.foldersFor({ home }, { temp }, root, ops)

    private fun ready(ops: FolderOps = FolderOps()): ShellFolders.Ready = assertIs<ShellFolders.Ready>(folders(ops), "the folders must be there")

    private fun runner(ops: FolderOps = FolderOps(), sweeper: LeftoverSweeper = FakeProc().sweeper(), tidyBudgetMs: Long = SystemShellRunner.TIDY_BUDGET_MS) = SystemShellRunner(
        tempDir = { temp }, homeDir = { home }, sweeper = sweeper,
        shell = "/bin/sh", setsid = HOST_SETSID, folderOps = ops, tidyBudgetMs = tidyBudgetMs,
    )

    /** The real operations, but what [refuse] names cannot be deleted, and [noRename] switches renaming off. */
    private class Refusing(var refuse: (Path) -> Boolean = { false }, var noRename: Boolean = false) : FolderOps() {
        val renamed = ArrayList<Pair<String, String>>()
        var deleted = 0

        override fun delete(path: Path) {
            if (refuse(path)) throw AccessDeniedException(path.toString())
            super.delete(path)
            deleted++
        }

        override fun rename(from: Path, to: Path) {
            if (noRename) throw AccessDeniedException(from.toString())
            super.rename(from, to)
            renamed += from.fileName.toString() to to.fileName.toString()
        }
    }

    /**
     * The real operations, counting the listings that are open and the entries they have handed
     * out that are still there. An entry stops counting when it is deleted or its listing is
     * closed. More than [limit] at a time is a failure, here and in [most].
     */
    private class Counting(private val limit: Int) : FolderOps() {
        var handedOut = 0
        var open = 0
        var mostOpen = 0
        var held = 0
        var most = 0
        private val heldBy = HashMap<Path, HashSet<Path>>()

        override fun entries(dir: Path): DirectoryStream<Path> {
            val real = super.entries(dir)
            val mine = HashSet<Path>()
            open++
            mostOpen = maxOf(mostOpen, open)
            return object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> {
                    val entries = real.iterator()
                    return object : MutableIterator<Path> {
                        override fun hasNext(): Boolean = entries.hasNext()

                        override fun next(): Path = entries.next().also {
                            handedOut++
                            mine.add(it)
                            heldBy[it] = mine
                            held++
                            most = maxOf(most, held)
                            check(held <= limit) { "more than $limit entries are held at a time" }
                        }

                        override fun remove() = throw UnsupportedOperationException()
                    }
                }

                override fun close() {
                    real.close()
                    open--
                    held -= mine.size
                    mine.forEach { heldBy.remove(it) }
                    mine.clear()
                }
            }
        }

        override fun delete(path: Path) {
            super.delete(path)
            if (heldBy.remove(path)?.remove(path) == true) held--
        }
    }

    private fun locked(dir: File, mode: String = "---------") {
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString(mode))
    }

    /** What a command can leave: [bare] folders one in the other, then [levels] more, each with [filesPerLevel] files beside the next folder. */
    private fun deepAndFull(top: File, bare: Int, levels: Int, filesPerLevel: Int): Int {
        var dir = top.toPath()
        Files.createDirectories(dir)
        repeat(bare) { dir = Files.createDirectory(dir.resolve("d")) }
        repeat(levels) {
            repeat(filesPerLevel) { n -> Files.createFile(dir.resolve("f$n")) }
            dir = Files.createDirectory(dir.resolve("d"))
        }
        return levels * filesPerLevel
    }

    // --- before a command: the same short work, whatever was left ---

    @Test
    fun `before a command nothing is walked or deleted, the folder is put aside by one rename`() {
        // What the fourth verification's command left, scaled down: a deep tree with thousands of files.
        val files = deepAndFull(home, bare = 40, levels = 6, filesPerLevel = 500)
        File(home, ".profile").writeText("echo LEFT-BEHIND\n")
        val counting = Counting(limit = 1)
        val renames = Refusing()

        val first = assertIs<ShellFolders.Ready>(folders(counting))

        assertTrue(counting.handedOut <= 1, "only whether there is a first entry is looked at, not the $files files: ${counting.handedOut}")
        assertEquals(1, counting.mostOpen, "one listing, of the home folder itself")
        assertEquals(0, counting.open, "and the listing is closed again")
        assertEquals(home, first.home, "HOME keeps its place")
        assertEquals(emptyList(), home.list()!!.toList())
        val aside = besideHome().single()
        assertTrue(aside.startsWith("shell-home.old-"), aside)
        assertTrue(File(cache, "$aside/.profile").isFile, "what was left went aside with its folder")

        // An empty folder is used as it is: nothing is renamed, nothing deleted.
        assertEquals(home, ready(renames).home)
        assertEquals(emptyList(), renames.renamed)
        assertEquals(0, renames.deleted)
        assertEquals(listOf(aside), besideHome())
    }

    @Test
    fun `a locked home folder is put aside, also an empty one`() {
        home.mkdirs()
        locked(home, "-wx------")
        val ops = Refusing()

        val made = ready(ops)

        assertEquals(home, made.home)
        assertEquals(1, ops.renamed.size, "it was not opened and looked into before the command, but renamed: ${ops.renamed}")
        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        assertTrue(Files.getPosixFilePermissions(home.toPath()).containsAll(owner), "the new one is the app's to use")
    }

    @Test
    fun `a folder that cannot be listed counts as not empty, whatever the reason`() {
        home.mkdirs()
        for (failure in listOf<Throwable>(AccessDeniedException("shell-home"), OutOfMemoryError("as if"), IllegalStateException("as if"))) {
            val ops = object : FolderOps() {
                override fun entries(dir: Path): DirectoryStream<Path> = throw failure
            }

            val made = assertIs<ShellFolders.Ready>(folders(ops), "$failure")

            assertEquals(home, made.home)
            assertEquals(emptyList(), home.list()!!.toList())
        }
        // The folder for temporary files beside it cannot be looked into either, and goes aside the same way.
        assertEquals(3, besideHome().count { it.startsWith("shell-home.old-") }, "each time the folder that could not be looked into was put aside: ${besideHome()}")
    }

    @Test
    fun `when the folder cannot be put aside, a new folder beside it is used`() {
        File(home, "stubborn").apply { parentFile!!.mkdirs() }.writeText("x")
        val ops = Refusing(noRename = true)

        val first = ready(ops).home

        assertNotEquals(home, first)
        assertEquals(cache, first.parentFile, "it is beside the usual one, in the app's cache, not in the workspace")
        assertTrue(first.name.startsWith("shell-home.fresh-"), first.name)
        assertEquals(emptyList(), first.list()!!.toList())
        assertTrue(File(home, "stubborn").isFile, "the one that is stuck stays where it is")

        // The next command gets another new one; the one before it is deleted afterwards, off the command's path.
        File(first, "left-by-the-command").writeText("x")
        val second = ready(ops).home
        assertNotEquals(first, second)
        assertTrue(first.exists(), "nothing is deleted before a command")
        SystemShellRunner.clearPutAside(home, temp, ops)
        assertEquals(emptyList(), besideHome(), "the folders made for the earlier commands are gone")
    }

    @Test
    fun `only when no folder can be made at all is there none`() {
        // The cache folder would have to be made inside a file.
        val impossible = File(File(File(cache, "a-file").apply { writeText("x") }, "cache"), "shell-home")

        val refused = assertIs<ShellFolders.Refused>(SystemShellRunner.foldersFor({ impossible }, { File(impossible.parentFile, "shell") }, root))

        assertTrue("home folder" in refused.reason && "was not run" in refused.reason, refused.reason)
    }

    @Test
    fun `a failure of any kind is an answer, never an exception`() {
        for (failure in listOf<Throwable>(OutOfMemoryError("as if"), StackOverflowError(), IllegalStateException("as if"))) {
            val refused = assertIs<ShellFolders.Refused>(SystemShellRunner.foldersFor({ throw failure }, { temp }, root), "$failure")

            assertTrue("was not run" in refused.reason, refused.reason)
        }
    }

    // --- the cache folder itself, and the workspace ---

    @Test
    fun `a cache folder that became a link is made a folder again, and nothing in the workspace is touched`() {
        // What a command can do: put the cache folder away and a link to the workspace in its place.
        assertTrue(cache.renameTo(File(cache.path + ".real")))
        Files.createSymbolicLink(cache.toPath(), root.toPath())
        File(root, "shell-home").mkdirs()
        File(root, "shell-home/owner-notes.txt").writeText("owner data")
        File(root, "shell-home/.profile").writeText("echo PROFILE-RAN\n")
        File(root, "shell-home.old-x").mkdirs()
        File(root, "shell-home.old-x/data.txt").writeText("owner data")
        File(root, "shell-home.fresh-x.txt").writeText("owner data")
        File(root, "shell").mkdirs()
        File(root, "shell/tmp.txt").writeText("owner data")
        val inWorkspace = root.walkTopDown().map { it.relativeTo(root).path }.sorted().toList()

        // The pass that deletes what was put aside does not go through the link.
        SystemShellRunner.clearPutAside(home, temp)
        assertEquals(inWorkspace, root.walkTopDown().map { it.relativeTo(root).path }.sorted().toList())

        val made = ready()

        assertFalse(Files.isSymbolicLink(cache.toPath()), "the link is gone")
        assertTrue(cache.isDirectory)
        assertEquals(home, made.home)
        assertEquals(temp, made.temp)
        assertEquals(emptyList(), home.list()!!.toList())
        assertFalse(made.home.toPath().toRealPath().startsWith(root.toPath().toRealPath()), "HOME is outside the workspace")
        assertFalse(made.temp.toPath().toRealPath().startsWith(root.toPath().toRealPath()), "TMPDIR is outside the workspace")
        assertEquals(inWorkspace, root.walkTopDown().map { it.relativeTo(root).path }.sorted().toList(), "the workspace is as it was")
        assertEquals("owner data", File(root, "shell-home/owner-notes.txt").readText())
    }

    @Test
    fun `a cache folder that became a file is made a folder again`() {
        assertTrue(cache.deleteRecursively())
        cache.writeText("not a folder")

        val made = ready()

        assertTrue(cache.isDirectory)
        assertEquals(home, made.home)
    }

    @Test
    fun `when the cache folder cannot be made a folder again, the command is refused in plain words`() {
        assertTrue(cache.renameTo(File(cache.path + ".real")))
        Files.createSymbolicLink(cache.toPath(), root.toPath())
        File(root, "keep.txt").writeText("owner data")
        val ops = Refusing(refuse = { it == cache.toPath() })

        val refused = assertIs<ShellFolders.Refused>(folders(ops))

        assertEquals(SystemShellRunner.NO_CACHE_FOLDER, refused.reason)
        assertEquals(listOf("keep.txt"), root.list()!!.toList(), "nothing was made in the workspace through the link")
    }

    @Test
    fun `a home folder that would lie inside the workspace is refused before anything is touched`() {
        // The path of the cache folder leads into the workspace further up, through a link.
        File(root, "sub/cache/shell-home").mkdirs()
        File(root, "sub/cache/shell-home/owner-notes.txt").writeText("owner data")
        val outer = File(cache, "outer")
        Files.createSymbolicLink(outer.toPath(), File(root, "sub").toPath())
        val inside = File(outer, "cache/shell-home")
        val inWorkspace = root.walkTopDown().map { it.relativeTo(root).path }.sorted().toList()
        val ops = Refusing()

        val refused = assertIs<ShellFolders.Refused>(SystemShellRunner.foldersFor({ inside }, { File(outer, "cache/shell") }, root, ops))

        assertEquals(SystemShellRunner.HOME_IN_WORKSPACE, refused.reason)
        assertEquals(emptyList(), ops.renamed)
        assertEquals(inWorkspace, root.walkTopDown().map { it.relativeTo(root).path }.sorted().toList(), "the workspace is as it was")
    }

    @Test
    fun `a folder for temporary files inside the workspace is refused`() {
        val refused = assertIs<ShellFolders.Refused>(folders(temp = File(root, "tmp")))

        assertEquals(SystemShellRunner.TEMP_IN_WORKSPACE, refused.reason)
    }

    @Test
    fun `the folder for temporary files beside the home folder follows the same rule`() {
        val precious = File(root, "keep").apply { mkdirs() }
        File(precious, "important.txt").writeText("owner data")
        temp.mkdirs()
        File(temp, "left.tmp").writeText("x")

        val first = ready()

        assertEquals(temp, first.temp)
        assertEquals(emptyList(), temp.list()!!.toList(), "what was left in it went aside")
        assertTrue(besideHome().single().startsWith("shell.old-"), "${besideHome()}")

        // A command put a link into the workspace in its place: the link goes aside, it is not followed and not used.
        assertTrue(temp.delete())
        Files.createSymbolicLink(temp.toPath(), precious.toPath())
        val second = ready()
        assertEquals(temp, second.temp)
        assertFalse(Files.isSymbolicLink(temp.toPath()))
        assertEquals(2, besideHome().size)

        SystemShellRunner.clearPutAside(home, temp)
        assertEquals(emptyList(), besideHome())
        assertEquals("owner data", File(precious, "important.txt").readText(), "the link was removed, not followed")

        // Elsewhere it is only made, as before.
        File(elsewhere, "kept.tmp").writeText("x")
        assertEquals(elsewhere, assertIs<ShellFolders.Ready>(folders(temp = elsewhere)).temp)
        assertTrue(File(elsewhere, "kept.tmp").isFile)
    }

    // --- afterwards: what was put aside is deleted with a fixed amount of memory, a few seconds at a time ---

    @Test
    fun `what was put aside is deleted without keeping its entries waiting, however deep and full it is`() {
        // The verifier's shape, scaled down: bare folders one in the other, then levels of files with one folder each.
        deepAndFull(File(cache, "shell-home.old-1"), bare = 150, levels = 12, filesPerLevel = 400)
        // Wide: many folders that are not empty in one folder, and trees deeper than the listings that stay open, side by side.
        val wide = File(cache, "shell-home.old-2")
        repeat(60) { n ->
            File(wide, "sub$n/a/b").mkdirs()
            File(wide, "sub$n/a/b/f").createNewFile()
            File(wide, "sub$n/g").createNewFile()
        }
        repeat(3) { n ->
            var deep = File(wide, "deep$n")
            repeat(SystemShellRunner.MAX_OPEN + 5) { deep = File(deep, "d") }
            deep.mkdirs()
            File(deep, "bottom").createNewFile()
            File(deep.parentFile!!.parentFile, "beside").createNewFile()
        }
        File(cache, "shell-home.fresh-3").mkdirs()
        File(cache, "shell.old-4").writeText("a file with such a name goes too")
        File(cache, "other-cache-data").writeText("not ours")
        val limit = SystemShellRunner.MAX_OPEN + 2
        val counting = Counting(limit)

        SystemShellRunner.clearPutAside(home, temp, counting)

        assertTrue(counting.most <= limit, "${counting.most} entries were held at a time, more than $limit")
        assertTrue(counting.mostOpen <= SystemShellRunner.MAX_OPEN + 1, "${counting.mostOpen} listings were open at a time")
        assertEquals(listOf("other-cache-data"), besideHome(), "everything that was put aside is gone, and nothing else")
        assertEquals(0, counting.open, "every listing was closed")
        assertTrue(counting.handedOut > 4_800, "the files were really gone through: ${counting.handedOut}")
    }

    @Test
    fun `a folder gets its owner's rights back before it is listed, also one whose name is no valid text`() {
        // What commands left and what was then put aside: folders without any rights, with files in them.
        val aside = File(cache, "shell-home.old-1")
        File(aside, "a").mkdirs()
        File(aside, "a/f").writeText("x")
        // A name that cannot be written as a String; the shell makes it. Through a File and its String the rights could not be given back.
        val made = ProcessBuilder("/bin/sh", "-c", "d=\"\$(printf 'lock\\377ed')\"; mkdir \"\$d\" && echo x > \"\$d/f\" && chmod 000 \"\$d\"")
            .directory(aside).redirectErrorStream(true).start()
        assertEquals(0, made.waitFor(), made.inputStream.readBytes().decodeToString())
        locked(File(aside, "a"))
        locked(aside, "-wx------")
        val rightsWhenListed = ArrayList<Pair<String, Set<PosixFilePermission>>>()
        val watching = object : FolderOps() {
            override fun entries(dir: Path): DirectoryStream<Path> {
                if (dir != cache.toPath()) rightsWhenListed += dir.fileName.toString() to Files.getPosixFilePermissions(dir)
                return super.entries(dir)
            }
        }

        SystemShellRunner.clearPutAside(home, temp, watching)

        val owner = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        assertEquals(3, rightsWhenListed.map { it.first }.distinct().size, "the folder put aside and the two in it: $rightsWhenListed")
        for ((folder, rights) in rightsWhenListed) assertTrue(rights.containsAll(owner), "$folder was listed with $rights")
        assertEquals(emptyList(), besideHome())
    }

    @Test
    fun `a pass whose time is up stops, and the next pass continues`() {
        val aside = File(cache, "shell-home.old-1")
        repeat(4) { dir -> repeat(25) { File(aside, "d$dir").mkdirs(); File(aside, "d$dir/f$it").createNewFile() } }
        fun left(): Int = aside.walkTopDown().count()
        val before = left()
        var passes = 0

        while (aside.exists() && passes < 100) {
            passes++
            var asked = 0
            val atStart = left()
            // The time of this pass is up after ten entries.
            SystemShellRunner.clearPutAside(home, temp, FolderOps()) { ++asked > 10 }
            val now = if (aside.exists()) left() else 0
            assertTrue(atStart - now <= 12, "the pass went on after its time was up: $atStart, then $now")
            assertTrue(now < atStart, "the pass removed nothing: $atStart")
        }

        assertFalse(aside.exists(), "after $passes passes everything is gone")
        assertTrue(passes >= before / 12, "it took several passes: $passes for $before entries")
    }

    @Test
    fun `a pass whose time is up also stops on its way back up a deep tree`() {
        val aside = File(cache, "shell-home.old-1")
        deepAndFull(aside, bare = 300, levels = 0, filesPerLevel = 0)
        val counting = object : FolderOps() {
            var deleted = 0

            override fun delete(path: Path) {
                super.delete(path)
                deleted++
            }
        }

        // Its time is up once five folders are deleted: all of them on the way up, where no entry is handed out.
        SystemShellRunner.clearPutAside(home, temp, counting) { counting.deleted >= 5 }

        assertEquals(5, counting.deleted, "the pass went on after its time was up")
        assertTrue(aside.isDirectory)
    }

    @Test
    fun `links are removed as links, wherever they are`() {
        val precious = File(root, "keep").apply { mkdirs() }
        File(precious, "important.txt").writeText("owner data")
        // A command made the names this app uses for folders it puts aside, as links into the workspace.
        Files.createSymbolicLink(File(cache, "shell-home.old-0").toPath(), precious.toPath())
        Files.createSymbolicLink(File(cache, "shell-home.fresh-1").toPath(), precious.toPath())
        // And it left links in what was then put aside.
        val aside = File(cache, "shell-home.old-2")
        File(aside, "sub/deeper").mkdirs()
        Files.createSymbolicLink(File(aside, "to-folder").toPath(), precious.toPath())
        Files.createSymbolicLink(File(aside, "sub/deeper/to-file").toPath(), File(precious, "important.txt").toPath())
        Files.createSymbolicLink(File(aside, "sub/to-nothing").toPath(), File(root, "missing").toPath())

        SystemShellRunner.clearPutAside(home, temp)

        assertEquals(emptyList(), besideHome())
        assertEquals(listOf("important.txt"), precious.list()!!.toList())
        assertEquals("owner data", File(precious, "important.txt").readText(), "the links were removed, not followed")
    }

    @Test
    fun `what cannot be deleted stays and does not keep the rest from going`() {
        File(cache, "shell-home.old-1/a").mkdirs()
        File(cache, "shell-home.old-1/a/stubborn").writeText("x")
        File(cache, "shell-home.old-2/b").mkdirs()
        File(cache, "shell-home.old-2/b/f").writeText("x")
        File(cache, "shell-home.fresh-3").mkdirs()
        val ops = Refusing(refuse = { it.fileName.toString() == "stubborn" })

        SystemShellRunner.clearPutAside(home, temp, ops)
        SystemShellRunner.clearPutAside(home, temp, ops)

        assertEquals(listOf("shell-home.old-1"), besideHome())
        assertTrue(File(cache, "shell-home.old-1/a/stubborn").isFile)

        // Once it can be removed (here: the refusal ends), a later pass removes it.
        ops.refuse = { false }
        SystemShellRunner.clearPutAside(home, temp, ops)
        assertEquals(emptyList(), besideHome())
    }

    @Test
    fun `whatever goes wrong in a pass ends it quietly`() {
        File(cache, "shell-home.old-1").mkdirs()
        File(cache, "shell-home.old-1/f").writeText("x")
        for (failure in listOf<Throwable>(OutOfMemoryError("as if"), StackOverflowError(), IllegalStateException("as if"))) {
            val ops = object : FolderOps() {
                override fun delete(path: Path) = throw failure
            }

            SystemShellRunner.clearPutAside(home, temp, ops)

            assertEquals(listOf("shell-home.old-1"), besideHome(), "$failure")
        }
    }

    // --- through the runner ---

    @Test
    fun `the next command runs with an empty home folder whatever the earlier one left, and that is deleted afterwards`() = runBlocking {
        val ops = Refusing()
        val runner = runner(ops)
        // PATH is Android's /system/bin, which the build machine does not have: mkdir by its full path.
        val first = runner.run("echo 'echo LEFT-BEHIND' > \"\$HOME/.profile\"; /bin/mkdir \"\$HOME/a\"; echo x > \"\$HOME/a/f\"; echo t > \"\$TMPDIR/t\"", workspace(), limits)
        assertEquals(0, assertIs<CommandOutcome.Finished>(first, "$first").exitCode, "$first")
        runner.awaitTidying()
        assertEquals(0, ops.deleted, "what a command left is not touched until the next one comes")

        val next = runner.run(
            "/bin/sh -l -c 'echo second'; for f in \"\$HOME\"/.* \"\$HOME\"/* \"\$TMPDIR\"/*; do echo \"in:\$f\"; done; echo ok > \"\$HOME/new\" && echo writable; echo \"home=\$HOME\"",
            workspace(), limits,
        )

        val out = assertIs<CommandOutcome.Finished>(next, "the command must run: $next").stdout
        assertFalse("LEFT-BEHIND" in out, out)
        assertEquals(
            "second\nin:${home.path}/.\nin:${home.path}/..\nin:${home.path}/*\nin:${temp.path}/*\nwritable\nhome=${home.path}\n", out,
            "HOME and TMPDIR are empty and in their usual places",
        )
        assertEquals(
            listOf("shell-home" to "shell-home.old-", "shell" to "shell.old-"),
            ops.renamed.map { (from, to) -> from to to.take(from.length + ".old-".length) },
            "both were put aside by a rename",
        )

        // Afterwards, off the command's path, what was put aside is deleted.
        runner.awaitTidying()
        assertEquals(emptyList(), besideHome())
        assertEquals(6, ops.deleted, "the two folders put aside, and .profile, a/f, a and t in them")
    }

    @Test
    fun `nothing is deleted while a command runs, and a command does not wait for the deleting to end`() = runBlocking {
        val slow = object : FolderOps() {
            override fun delete(path: Path) {
                Thread.sleep(20)
                super.delete(path)
            }
        }
        // Deleting these takes eight seconds, and the pass has all the time it wants.
        home.mkdirs()
        repeat(400) { File(home, "f$it").createNewFile() }
        val runner = runner(slow, tidyBudgetMs = 60_000)
        assertIs<CommandOutcome.Finished>(runner.run("true", workspace(), limits))
        val aside = File(cache, besideHome().single())
        Thread.sleep(200)
        val count = "n=0; for f in '${aside.path}'/*; do n=\$((n+1)); done; echo \$n"

        val started = System.nanoTime()
        val out = assertIs<CommandOutcome.Finished>(runner.run("$count; /bin/sleep 0.5; $count", workspace(), limits)).stdout
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        val (atStart, atEnd) = out.trim().lines().map { it.toInt() }
        assertTrue(atStart in 100..399, "the pass had begun and was not waited for: $atStart of 400 files were left when the command started")
        assertEquals(atStart, atEnd, "the pass went on deleting while the command ran")
        assertTrue(took < 5_000, "the command waited for the deleting: $took ms")

        // The pass after it goes on where the one before was stopped.
        runner.awaitTidying()
        assertEquals(emptyList(), besideHome())
    }

    @Test
    fun `a pass ends by itself when its time is up, and the pass after the next command or sweep continues`() = runBlocking {
        val slow = object : FolderOps() {
            override fun delete(path: Path) {
                Thread.sleep(20)
                super.delete(path)
            }
        }
        // Two seconds of deleting, and 300 ms for each pass.
        home.mkdirs()
        repeat(100) { File(home, "f$it").createNewFile() }
        val runner = runner(slow, tidyBudgetMs = 300)
        assertIs<CommandOutcome.Finished>(runner.run("true", workspace(), limits))
        val aside = File(cache, besideHome().single())

        runner.awaitTidying()
        val afterOne = aside.list()!!.size
        assertTrue(afterOne >= 80, "the first pass stopped when its time was up: $afterOne of 100 files are left")

        var passes = 1
        while (besideHome().isNotEmpty() && passes < 200) {
            runner.stopLeftovers()
            runner.awaitTidying()
            passes++
        }
        assertEquals(emptyList(), besideHome(), "after $passes passes")
        assertTrue(passes >= 5, "it took several passes: $passes")
    }

    @Test
    fun `nothing is deleted while a process of an earlier command may still run`() = runBlocking {
        var left = 0
        val sweeper = object : LeftoverSweeper {
            override fun sweep(): Sweep = Sweep.Done(stopped = 0, left = left)
        }
        val runner = runner(sweeper = sweeper)
        File(home, "left").apply { parentFile!!.mkdirs() }.writeText("x")
        assertIs<CommandOutcome.Finished>(runner.run("true", workspace(), limits))
        runner.awaitTidying()
        assertEquals(emptyList(), besideHome(), "with nothing running, what was put aside is deleted")

        // The sweep at the end of the next command cannot stop everything.
        File(home, "left").writeText("x")
        val command = "true"
        val outcome = run {
            val before = sweeper
            object : LeftoverSweeper {
                var calls = 0

                override fun sweep(): Sweep = if (calls++ == 0) before.sweep() else Sweep.Done(stopped = 0, left = 1)
            }.let { runner(sweeper = it).run(command, workspace(), limits) to it }
        }
        assertTrue(assertIs<CommandOutcome.Finished>(outcome.first).leftRunning)
        Thread.sleep(300)
        assertEquals(1, besideHome().size, "nothing was deleted: ${besideHome()}")

        // Once a sweep says that nothing runs, the pass after it deletes it.
        left = 0
        runner.stopLeftovers()
        runner.awaitTidying()
        assertEquals(emptyList(), besideHome())
    }

    @Test
    fun `a command is refused only when no home folder can be made`() = runBlocking {
        val impossible = File(File(File(cache, "a-file").apply { writeText("x") }, "cache"), "shell-home")
        val runner = SystemShellRunner(
            tempDir = { elsewhere }, homeDir = { impossible }, sweeper = FakeProc().sweeper(),
            shell = "/bin/sh", setsid = HOST_SETSID,
        )

        val outcome = runner.run("echo ran > ran.txt", workspace(), limits)

        assertTrue("home folder" in assertIs<CommandOutcome.Failed>(outcome, "$outcome").reason)
        assertFalse(File(root, "ran.txt").exists())
    }

    @Test
    fun `a command is not run with a home folder inside the workspace`() = runBlocking {
        val inside = File(root, "cache/shell-home")
        val runner = SystemShellRunner(
            tempDir = { elsewhere }, homeDir = { inside }, sweeper = FakeProc().sweeper(),
            shell = "/bin/sh", setsid = HOST_SETSID,
        )

        // From a folder further down: the workspace's top folder is what counts.
        val sub = (WorkspaceFile.resolve(root, RelativePath.of("project/src")) as DocResult.Ok).value.also { it.file.mkdirs() }
        val outcome = runner.run("echo ran > ran.txt", sub, limits)

        assertEquals(SystemShellRunner.HOME_IN_WORKSPACE, assertIs<CommandOutcome.Failed>(outcome, "$outcome").reason)
        assertFalse(File(sub.file, "ran.txt").exists())
        assertFalse(File(root, "cache").exists(), "nothing was made in the workspace")
    }

    private companion object {
        /** util-linux setsid on the build machine; Android's toybox one is at /system/bin/setsid. */
        const val HOST_SETSID = "/usr/bin/setsid"
    }
}
