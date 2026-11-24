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
 * F3: a script a shell command runs is shown and pinned however its name is spelled, as long as
 * the spelling is static; a script that cannot be resolved to a regular workspace file is
 * refused before the owner is asked. A real /bin/sh runs behind the port, so "ran" means the
 * rewritten script really removed the owner's folder.
 */
class ShellScriptPinningTest {
    /** A workspace with tidy.sh (harmless), a folder to lose, a script that runs tidy.sh, and a link to it. */
    private fun fixture(): OperatorFixture {
        val f = OperatorFixture()
        f.shell.respond = { command, dir ->
            val builder = ProcessBuilder("/bin/sh", "-c", command).directory(dir.file).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment()["PATH"] = "/usr/bin:/bin"
            builder.environment()["HOME"] = dir.file.path
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
        "sh tidy.sh", ". ./tidy.sh", "source tidy.sh", "sh -x tidy.sh", "sh < tidy.sh", "cat tidy.sh | sh", "sh ././tidy.sh",
        "sh \"\$HOME/tidy.sh\"", "sh ~/tidy.sh", "f=tidy.sh; sh \$f", "sh \$(echo tidy.sh)", "sh -c 'sh tidy.sh'", "eval \"\$(cat tidy.sh)\"",
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
            "cd keep && sh ../tidy.sh" to "changes folder with cd",
            "cd sub; sh tidy.sh" to "changes folder with cd",
            "exec 3< tidy.sh; sh <&3" to "opened earlier",
            // Text that looks like a here-document to a quick reader, but that the shell runs as commands.
            "(( 1 << 2 ))\nsh \$f\n" to "only put together when the command runs",
            "cat <<EOF | sh\nsh \$f\nEOF" to "only put together when the command runs",
        )
        for ((command, why) in refused) {
            val reason = f.rejected("scripts.run_shell", "command" to command)
            assertTrue(why in reason, "$command: $reason")
            assertTrue("plain path inside the workspace" in reason, "$command: $reason")
        }
        assertTrue(f.shell.runs.isEmpty())
        // Plain names still pass, also for the program files of awk and sed, and so does cd when the script is named from the home folder.
        for (command in listOf("awk -f prog name.txt", "cd keep && sh ~/tidy.sh", "sh -c 'echo hi'", "sh -s < tidy.sh", "cut -f 2 name.txt", "rm -f gone.txt", "ls *.txt")) {
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

        val none = f.assess("scripts.run_shell", "command" to "sh zz*.sh")
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
        f.put("leaves.sh", "cd keep\nsh ../tidy.sh\n")
        assertTrue("changes folder with cd" in f.rejected("scripts.run_shell", "command" to "sh leaves.sh"))
    }

    @Test
    fun `a script that would leave the call's reach is refused like such a command`() = runBlocking {
        val f = fixture()
        f.put("escape.sh", "echo start\nsetsid sh -c 'sleep 100' &\n")
        f.put("quoted.sh", "\"set\"\"sid\" sleep 100\n")
        f.put("nested.sh", "sh quoted.sh\n")
        f.put("jobs.sh", "set -m\nsleep 100 &\n")
        f.put("inner.sh", "sh -c 'nohup sleep 100'\n")
        for ((command, word) in listOf("sh escape.sh" to "setsid", ". ./quoted.sh" to "setsid", "sh nested.sh" to "setsid", "sh jobs.sh" to "job control", "sh < inner.sh" to "nohup")) {
            val reason = f.rejected("scripts.run_shell", "command" to command)
            assertTrue(word in reason && "is not run" in reason, "$command: $reason")
        }
        assertTrue("\"quoted.sh\"" in f.rejected("scripts.run_shell", "command" to "sh nested.sh"))
        // A plain "&" in a script is in the call's process group and ends with it; a word in a comment or in text is no command.
        f.put("fine.sh", "# nohup and setsid are not used here\nsleep 1 &\nwait\necho \"about setsid: see the manual\"\n")
        f.assess("scripts.run_shell", "command" to "sh fine.sh")
        assertTrue(f.shell.runs.isEmpty())
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
