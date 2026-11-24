package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F3 of round 2, as G4 of round 3 left it: a script a shell command runs is shown and pinned for
 * the static spellings the reader resolves, and a script that cannot be resolved to an existing
 * regular workspace file is refused before the owner is asked. Finding scripts is help for the
 * owner, not the guarantee (that is the workspace's change counter, ShellWorkspaceLockTest); the
 * rewrites here are made behind the workspace's back, so only the content pins can see them. A
 * real /bin/sh runs behind the port, so "ran" means the rewritten script really removed the
 * owner's folder.
 */
class ShellScriptPinningTest {
    /** A workspace with tidy.sh (harmless), a folder to lose, a script that runs tidy.sh, and a link to it. */
    private fun fixture(): OperatorFixture {
        val f = OperatorFixture()
        // As the app's runner does: HOME is an empty folder outside the workspace.
        val home = Files.createTempDirectory("aicc-home").toFile()
        f.shell.respond = { command, dir ->
            val builder = ProcessBuilder("/bin/sh", "-c", command).directory(dir.file).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment()["PATH"] = "/usr/bin:/bin"
            builder.environment()["HOME"] = home.path
            val p = builder.start()
            p.outputStream.close()
            p.waitFor(10, TimeUnit.SECONDS)
            CommandOutcome.Finished(p.exitValue(), p.inputStream.bufferedReader().readText(), "", false, 1)
        }
        f.put("tidy.sh", "echo tidying\n")
        f.put("keep/important.txt", "owner data")
        f.put("sub/note.txt", "x")
        f.put("a.sh", "sh tidy.sh\n")
        Files.createSymbolicLink(File(f.root, "link.sh").toPath(), File(f.root, "tidy.sh").toPath())
        return f
    }

    /** The static spellings of "run tidy.sh": the verifier's list, the ones that already worked and the ones that did not. */
    private fun spellings(root: String) = listOf(
        "sh tidy.sh", ". ./tidy.sh", "source ./tidy.sh", "sh -x tidy.sh", "sh < tidy.sh", "cat tidy.sh | sh", "sh ././tidy.sh",
        "f=tidy.sh; sh \$f", "sh \$(echo tidy.sh)", "sh -c 'sh tidy.sh'", "eval \"\$(cat tidy.sh)\"",
        // Round 3: the shell's options read as mksh reads them, and the shell under other names.
        "sh -xo errexit tidy.sh", "sh - tidy.sh", "sh -eo errexit ./tidy.sh", "sh <> tidy.sh", "nice sh tidy.sh", "timeout 5 sh tidy.sh", "env -i sh tidy.sh",
        // The rest of P2: none of these was shown or pinned.
        "sh ./sub/../tidy.sh", "sh $root/tidy.sh", "sh \$PWD/tidy.sh", "sh \${PWD}/tidy.sh", "sh \"\$(pwd)\"/tidy.sh", "sh t*.sh", "sh tidy.s[h]", "sh tid?.sh",
        "sh \"tidy\".sh", "sh tidy\\.sh", "sh ti''dy.sh", "sh 'tidy.sh'", "sh a.sh", "toybox sh tidy.sh || sh tidy.sh", "exec sh tidy.sh",
        "for s in tidy.sh; do sh \$s; done", "sh -c \". ./tidy.sh\"", "./tidy.sh || sh ./tidy.sh",
    )

    @Test
    fun `every static spelling of a script is shown, and the script rewritten after approval does not run`() = runBlocking {
        for (template in spellings("ROOT")) {
            val f = fixture()
            val command = template.replace("ROOT", f.root.canonicalPath)
            val args = json("command" to command)
            val tool = f.tool("scripts.run_shell")
            val assessment = tool.assess(args, f.context)
            assertTrue(assessment.details.any { it.startsWith("Script \"tidy.sh\"") && "echo tidying" in it }, "$command: the script is not shown\n${assessment.preview}")
            assertNull(assessment.prepared!!.changedSince(), command)

            // After the owner approved, the script is rewritten.
            f.put("tidy.sh", "rm -rf keep; echo removed\n")
            assertNotNull(assessment.prepared!!.changedSince(), "$command: the rewritten script was not noticed")
            val outcome = tool.execute(args, f.context, assessment)
            assertFalse(outcome.ok, "$command: ${outcome.content}")
            assertTrue(f.shell.runs.isEmpty(), "$command ran")
            assertTrue(f.file("keep/important.txt").exists(), "$command removed the owner's folder")
        }
    }

    @Test
    fun `an unchanged script runs, by every spelling`() = runBlocking {
        for (template in spellings("ROOT").filterNot { it.startsWith("toybox") || it.startsWith("./tidy.sh") || it.startsWith("source") }) {
            // Left out: toybox is not on the build machine, a file there is not executable, and its /bin/sh has no "source".
            val f = fixture()
            val command = template.replace("ROOT", f.root.canonicalPath)
            val (_, outcome) = f.run("scripts.run_shell", "command" to command)
            assertTrue(outcome.ok && "tidying" in outcome.content, "$command: ${outcome.content}")
        }
    }

    @Test
    fun `a script that cannot be resolved to a regular workspace file is refused before the owner is asked`() = runBlocking {
        val f = fixture()
        f.put("name.txt", "tidy.sh")
        f.put("prog", "{ print }")
        val refused = listOf(
            "sh /proc/self/cwd/tidy.sh" to "outside the workspace",
            "sh /tmp/x.sh" to "outside the workspace",
            "sh ../tidy.sh" to "outside the workspace",
            "sh sub/../../tidy.sh" to "outside the workspace",
            "sh link.sh" to "a link",
            ". ./link.sh" to "a link",
            "sh < link.sh" to "a link",
            "sh \$f" to "only put together when the command runs",
            "sh \"\$1\"" to "only put together when the command runs",
            "sh \$(ls | head -1)" to "only put together when the command runs",
            "sh \$(cat name.txt)" to "only put together when the command runs",
            "f=\$(cat name.txt); sh \$f" to "only put together when the command runs",
            "f=tidy.sh; read f < name.txt; sh \$f" to "only put together when the command runs",
            "f=tidy.sh; . ./a.sh; sh \$f" to "only put together when the command runs",
            "sh {tidy,a}.sh" to "only put together when the command runs",
            "source \"\$HOME/\$x.sh\"" to "only put together when the command runs",
            "awk -f \$PROG name.txt" to "only put together when the command runs",
            "sed -nf /tmp/x.sed name.txt" to "outside the workspace",
            "sh nothere/../tidy.sh" to "not a folder",
            "cd keep && sh ../tidy.sh" to "outside the workspace",
            "cd keep && sh ./tidy.sh" to "changes folder",
            "cd sub; sh tidy.sh" to "changes folder",
            "exec 3< tidy.sh; sh <&3" to "opened earlier",
            // Text that looks like a here-document to a quick reader, but that the shell runs as commands.
            "(( 1 << 2 ))\nsh \$f\n" to "here-document",
            "cat <<EOF | sh\nsh \$f\nEOF" to "here-document whose text is changed",
            // Round 3 (G4): a script that is not there is not waved through, and a pattern that matches nothing is not either.
            "sh missing.sh" to "there is no such file in the workspace",
            ". ./missing.sh" to "there is no such file in the workspace",
            "sh -xo errexit missing.sh" to "there is no such file in the workspace",
            "awk -f missing.awk name.txt" to "there is no such file in the workspace",
            "cat missing.sh | sh" to "there is no such file in the workspace",
            "sh zz*.sh" to "no workspace file matches it",
            "sh keep" to "it is a folder",
            "find . -name '*.txt' -exec sh {} ;" to "there is no such file in the workspace",
            // The home folder is outside the workspace (G3), and "." needs a path.
            "sh ~/tidy.sh" to "outside the workspace",
            "sh \"\$HOME/tidy.sh\"" to "outside the workspace",
            ". tidy.sh" to "looked up through PATH",
            "source tidy.sh" to "looked up through PATH",
        )
        for ((command, why) in refused) {
            val reason = f.rejected("scripts.run_shell", "command" to command)
            assertTrue(why in reason, "$command: $reason")
            assertTrue("othing was run" in reason, "$command: $reason")
        }
        assertTrue(f.shell.runs.isEmpty())
        // Plain names still pass, also for the program files of awk and sed, and so does cd when the script is named by its full path.
        for (command in listOf("awk -f prog name.txt", "cd keep && sh ${f.root.canonicalPath}/tidy.sh", "sh -c 'echo hi'", "sh -s < tidy.sh", "cut -f 2 name.txt", "rm -f gone.txt", "ls *.txt")) {
            f.assess("scripts.run_shell", "command" to command)
        }
        val awk = f.assess("scripts.run_shell", "command" to "awk -f prog name.txt")
        assertTrue(awk.details.any { it.startsWith("Script \"prog\"") && "print" in it }, awk.preview)
    }

    @Test
    fun `a pattern is pinned with the files it matches, and a folder stepped through must stay a folder`() = runBlocking {
        val f = fixture()
        val pattern = f.assess("scripts.run_shell", "command" to "sh t*.sh")
        assertNull(pattern.prepared!!.changedSince())
        // A new file that sorts first would be the one the shell runs.
        f.put("taaa.sh", "rm -rf keep\n")
        assertTrue("match" in pattern.prepared!!.changedSince()!!, pattern.prepared!!.changedSince())

        // A pattern a shell would run must match something to be shown; one that is only an argument may match nothing yet.
        assertTrue("no workspace file matches it" in f.rejected("scripts.run_shell", "command" to "sh zz*.sh"))
        val none = f.assess("scripts.run_shell", "command" to "cat zz*.sh")
        assertTrue(none.details.any { "No file matches \"zz*.sh\" now" in it }, none.preview)
        f.put("zz1.sh", "rm -rf keep\n")
        assertNotNull(none.prepared!!.changedSince())

        val through = f.assess("scripts.run_shell", "command" to "sh sub/../tidy.sh")
        assertNull(through.prepared!!.changedSince())
        // "sub" becomes a link to another folder: "sub/.." is then somewhere else.
        val elsewhere = Files.createTempDirectory("aicc-elsewhere").toFile()
        File(elsewhere, "inner").mkdirs()
        File(elsewhere, "tidy.sh").writeText("rm -rf keep\n")
        f.file("sub").deleteRecursively()
        Files.createSymbolicLink(f.file("sub").toPath(), File(elsewhere, "inner").toPath())
        assertTrue("sub" in through.prepared!!.changedSince()!!, through.prepared!!.changedSince())
    }

    @Test
    fun `scripts run by a shown script are shown two levels down, pinned one further, and must be resolvable too`() = runBlocking {
        val f = fixture()
        f.put("one.sh", ". ./two.sh\n")
        f.put("two.sh", "sh \"three\".sh\n")
        f.put("three.sh", "echo three; sh four.sh\n")
        f.put("four.sh", "echo four; sh tidy.sh\n")
        val a = f.assess("scripts.run_shell", "command" to "sh one.sh")
        for (shown in listOf("one.sh" to ". ./two.sh", "two.sh" to "three", "three.sh" to "echo three")) {
            assertTrue(a.details.any { it.startsWith("Script \"${shown.first}\"") && shown.second in it }, "${shown.first}\n${a.preview}")
        }
        assertTrue(a.details.any { it.startsWith("Script \"two.sh\"") && "run by \"one.sh\"" in it }, a.preview)
        assertTrue(a.details.any { it.startsWith("Script \"four.sh\"") && "is not shown here" in it && "run by \"three.sh\"" in it }, a.preview)
        assertFalse(a.details.any { "echo four" in it }, a.preview)
        assertNull(a.prepared!!.changedSince())
        f.put("four.sh", "rm -rf keep\n")
        assertTrue("four.sh" in a.prepared!!.changedSince()!!)

        f.put("four.sh", "echo four\n")
        val again = f.assess("scripts.run_shell", "command" to "sh one.sh")
        f.put("three.sh", "rm -rf keep\n")
        assertTrue("three.sh" in again.prepared!!.changedSince()!!)

        // A script that runs a script by a name built at run time: the command is refused, and the answer names the script.
        f.put("outer.sh", "echo start\nsh \"\$1\"\n")
        val reason = f.rejected("scripts.run_shell", "command" to "sh outer.sh tidy.sh")
        assertTrue("\"outer.sh\"" in reason && "only put together when the command runs" in reason && "plain path inside the workspace" in reason, reason)
        f.put("leaves.sh", "cd sub\nsh tidy.sh\n")
        val leaves = f.rejected("scripts.run_shell", "command" to "sh leaves.sh")
        assertTrue("The script \"leaves.sh\", which the command runs, names the script \"tidy.sh\" by a relative path" in leaves && "changes folder" in leaves, leaves)
    }

    @Test
    fun `where the folder or the look-up of names is not certain, a script named by a relative path is refused, not shown`() = runBlocking {
        // The verifier's cases: the approval showed the top-level tidy.sh while sub/tidy.sh ran.
        val f = fixture()
        f.put("sub/tidy.sh", "echo the other one\n")
        f.put("go.sh", "cd sub\n")
        f.put("path.sh", "PATH=sub:\$PATH\n")
        f.put("inner.sh", ". ./go.sh\n")
        val root = f.root.canonicalPath
        val refused = listOf(
            ". ./go.sh; sh tidy.sh", ". ./go.sh; . ./tidy.sh", "PATH=sub:\$PATH; . ./tidy.sh", "cd sub && cat tidy.sh | sh", "cd sub && eval \"\$(cat tidy.sh)\"",
            "cd sub && /proc/self/exe tidy.sh", "cd sub; sh \$PWD/tidy.sh", "cd sub; sh \$(pwd)/tidy.sh", "pushd sub; sh tidy.sh",
            // Also a script the reader did not think must run: after cd, "cat tidy.sh" reads another file than the one that would be shown.
            "cd sub && cat tidy.sh", "cd sub && ./tidy.sh", "export PATH=sub; sh tidy.sh", "IFS=x; sh tidy.sh", ". ./path.sh; sh tidy.sh",
            // It is handed on: a script read in by a script that is read in, and a script started from where the folder is not certain.
            ". ./inner.sh; sh tidy.sh", "cd sub; sh $root/a.sh", "eval 'cd sub'; sh tidy.sh", "sh -c 'cd sub'; sh tidy.sh",
        )
        for (command in refused) {
            val reason = f.rejected("scripts.run_shell", "command" to command)
            assertTrue("by a relative path" in reason && "use the \"directory\" argument instead of cd" in reason, "$command: $reason")
        }
        assertTrue("The script \"a.sh\", which the command runs, names the script \"tidy.sh\"" in f.rejected("scripts.run_shell", "command" to "cd sub; sh $root/a.sh"))
        // The same through a subshell: what a group pipes into a shell is not read at all.
        assertTrue("pipes into a shell" in f.rejected("scripts.run_shell", "command" to "(cd sub; cat tidy.sh) | sh"))
        // A script named twice counts each time: run as a program first, then read in; run from a settled text first, then from an unsettled one.
        assertTrue("by a relative path" in f.rejected("scripts.run_shell", "command" to "sh go.sh; . ./go.sh; sh tidy.sh"))
        f.put("from-sub.sh", "cd sub\nsh $root/a.sh\n")
        assertTrue("The script \"a.sh\"" in f.rejected("scripts.run_shell", "command" to "sh a.sh; sh from-sub.sh"))
        // Seen first as awk's program file, then run by a shell: its own scripts are looked for then.
        f.put("both.sh", "sh /proc/self/cwd/tidy.sh\n")
        assertTrue("outside the workspace" in f.rejected("scripts.run_shell", "command" to "awk -f both.sh name.txt; sh both.sh"))
        // Scripts that read each other in, in a circle, are read once each.
        f.put("ring1.sh", ". ./ring2.sh\n")
        f.put("ring2.sh", ". ./ring1.sh\ncd sub\n")
        assertTrue("by a relative path" in f.rejected("scripts.run_shell", "command" to ". ./ring1.sh"))
        f.put("ring2.sh", ". ./ring1.sh\n")
        f.assess("scripts.run_shell", "command" to ". ./ring1.sh")
        assertTrue(f.shell.runs.isEmpty())

        // Certain: a name with the workspace's full path, and names without a script after cd.
        val full = f.assess("scripts.run_shell", "command" to "cd sub && sh $root/tidy.sh")
        assertTrue(full.details.any { it.startsWith("Script \"tidy.sh\"") && "echo tidying" in it }, full.preview)
        f.assess("scripts.run_shell", "command" to "cd sub && cat note.txt && ls")

        // A script that changes folder for itself, run as a program of its own, changes nothing for the command line:
        // "sh tidy.sh" after it still means the file that is shown, and that is the one that runs.
        f.put("build.sh", "cd sub\necho building in \$(basename \$PWD)\n")
        val (assessment, outcome) = f.run("scripts.run_shell", "command" to "sh build.sh; sh tidy.sh")
        assertTrue(assessment.details.any { it.startsWith("Script \"build.sh\"") } && assessment.details.any { it.startsWith("Script \"tidy.sh\"") && "echo tidying" in it }, assessment.preview)
        assertTrue(outcome.ok && "building in sub" in outcome.content && "tidying" in outcome.content && "the other one" !in outcome.content, outcome.content)
    }

    @Test
    fun `a script that sends work to the background is not refused for it any more`() = runBlocking {
        // G2: the runner stops everything a command started, so the text is no longer searched for setsid, nohup or job control.
        val f = fixture()
        f.put("escape.sh", "echo start\nsetsid sh -c 'sleep 100' &\n")
        f.put("quoted.sh", "\"set\"\"sid\" sleep 100\n")
        f.put("nested.sh", "sh quoted.sh\n")
        f.put("jobs.sh", "set -m\nsleep 100 &\n")
        f.put("inner.sh", "sh -c 'nohup sleep 100'\n")
        for (command in listOf("sh escape.sh", ". ./quoted.sh", "sh nested.sh", "sh jobs.sh", "sh < inner.sh")) {
            val a = f.assess("scripts.run_shell", "command" to command)
            assertTrue(a.details.any { "Everything the command starts is stopped when it ends" in it }, a.preview)
            assertTrue(a.details.any { it.startsWith("Script ") }, "$command: its script is still shown\n${a.preview}")
        }
    }

    @Test
    fun `a script is never left out because the command names many other things`() = runBlocking {
        val f = fixture()
        // Hundreds of words before the script: the limit on names that are only pinned must not reach the script.
        val padding = (1..400).joinToString(" ") { "w$it" }
        val padded = f.assess("scripts.run_shell", "command" to "echo $padding; sh tidy.sh")
        assertTrue(padded.details.any { it.startsWith("Script \"tidy.sh\"") && "echo tidying" in it }, padded.preview.take(400))
        // Hundreds of existing files named first, then a script that runs another one.
        repeat(320) { f.put("data/f$it.txt", "x") }
        val many = f.assess("scripts.run_shell", "command" to "wc -l ${(0 until 320).joinToString(" ") { "data/f$it.txt" }}; sh a.sh")
        assertTrue(many.details.any { it.startsWith("Script \"tidy.sh\"") && "run by \"a.sh\"" in it }, many.preview.takeLast(600))
        f.put("tidy.sh", "rm -rf keep\n")
        assertNotNull(padded.prepared!!.changedSince())
        assertNotNull(many.prepared!!.changedSince())

        // More scripts than can be shown and checked, or commands wrapped too deeply to read: refused, not run half seen.
        repeat(120) { f.put("s${it + 1}.sh", "echo $it\n") }
        assertTrue("more than 60 scripts" in f.rejected("scripts.run_shell", "command" to (1..70).joinToString("; ") { "sh s$it.sh" }))
        assertTrue("than can be read" in f.rejected("scripts.run_shell", "command" to (1..120).joinToString("; ") { "sh s$it.sh" }))
        val deep = f.rejected("scripts.run_shell", "command" to "x=\$(echo \$(echo \$(echo \$(echo \$(echo \$(sh tidy.sh))))))")
        assertTrue("deep" in deep, deep)
        assertTrue(f.shell.runs.isEmpty())
    }

    @Test
    fun `names a command only mentions are still listed and pinned`() = runBlocking {
        val f = fixture()
        val a = f.assess("scripts.run_shell", "command" to "wc -l sub/note.txt && ls keep && grep -c x \"sub/note.txt\" out=keep")
        val listed = a.details.single { it.startsWith("Workspace files and folders it names") }
        assertTrue("\"sub/note.txt\"" in listed && "\"keep\"" in listed, listed)
        assertEquals(0, a.details.count { it.startsWith("Script") }, a.preview)
        f.put("keep/new.txt", "x")
        assertTrue("keep" in a.prepared!!.changedSince()!!)
    }
}
