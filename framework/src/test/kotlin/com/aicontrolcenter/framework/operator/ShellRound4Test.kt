package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecution
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 4, set READER, from the third verification's probes (V7Reader, V7Mixed, V7Show, V7LockProbeTest).
 *
 * R1: the approval calls a file "the script this command runs" only for the plain form
 * `sh file [arguments]`; whatever the reader finds for any other command, and inside a script,
 * is shown as a reading ("Seems to run"), and a name it only finds mentioned is never shown with
 * its text. R2: five refusals. R3: HOME and ENV pointed into the workspace, in more spellings.
 * R4: a command that cannot be handed to a process exactly. R5: a command asked for while
 * another one runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellRound4Test {
    private val anyFile = "A shell command can run any file in the workspace. Only the scripts found in the command are shown here."
    private val notPlain = "This is not a plain \"sh file\" command, so the app cannot be sure which files it runs. What follows is its best reading."
    private val inTurn = "Scripts that script seems to run in turn (best reading):"
    private val noScript = "It found no script in this command, which does not mean that none runs."

    /** A real shell behind the port, started as the app's runner starts it: HOME is an empty folder outside the workspace. */
    private fun realShell(f: OperatorFixture, launcher: List<String> = listOf("/bin/sh")) {
        val home = Files.createTempDirectory("aicc-home").toFile()
        f.shell.respond = { command, dir ->
            val builder = ProcessBuilder(launcher + listOf("-c", command)).directory(dir.file).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment()["PATH"] = "/usr/bin:/bin"
            builder.environment()["HOME"] = home.path
            val p = builder.start()
            p.outputStream.close()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            CommandOutcome.Finished(p.exitValue(), text, "", false, 1)
        }
    }

    /** The lines of an approval that show a script as the one that runs, and those that show a reading. */
    private fun certain(a: ActionAssessment) = a.details.filter { it.startsWith("Script ") }
    private fun readings(a: ActionAssessment) = a.details.filter { it.startsWith("Seems to run ") }

    /** What the verifier's probes did: the approval of [command], or the refusal. */
    private class Probe(val refusal: String?, val assessment: ActionAssessment?, val ran: List<String>)

    private fun probe(f: OperatorFixture, command: String): Probe = runBlocking {
        val tool = f.tool("scripts.run_shell")
        val args = json("command" to command)
        val a = try {
            tool.assess(args, f.context)
        } catch (e: ToolCallRejected) {
            return@runBlocking Probe(e.message, null, emptyList())
        }
        val outcome = tool.execute(args, f.context, a)
        Probe(null, a, Regex("RAN<([^>]+)>").findAll(outcome.content).map { it.groupValues[1] }.toList().distinct())
    }

    // ------------------------------------------------------------------ R1

    @Test
    fun `R1 - only the plain form shows a file as the script this command runs, and that file is the one that runs`() = runBlocking<Unit> {
        val f = OperatorFixture()
        realShell(f)
        fun bait(path: String) = "echo \"RAN<$path>\"\n".also { f.put(path, it) }
        val tidy = bait("tidy.sh")
        val inner = bait("tools/tidy.sh")
        val named = bait("sh")
        val shown = "Script \"tidy.sh\" (${tidy.length} bytes), all of it: ${PreviewText.quoted(tidy)}"
        for (command in listOf("sh tidy.sh", "sh ./tidy.sh", "sh tidy.sh one -two 3", "sh\ttidy.sh\targ", "  sh tidy.sh ", "sh tidy.sh\n")) {
            val p = probe(f, command)
            val a = assertNotNull(p.assessment, "$command: ${p.refusal}")
            assertEquals(listOf(shown), certain(a), a.preview)
            assertEquals(emptyList(), readings(a), a.preview)
            assertFalse(notPlain in a.details, a.preview)
            assertEquals(anyFile, a.details[a.details.indexOf(shown) - 1], "the script follows the fixed line\n${a.preview}")
            assertEquals(listOf("tidy.sh"), p.ran, command)
        }
        // From the folder the command runs in, and a file that is called like the shell.
        val inTools = f.run("scripts.run_shell", "command" to "sh tidy.sh", "directory" to "tools")
        assertEquals(listOf("Script \"tools/tidy.sh\" (${inner.length} bytes), all of it: ${PreviewText.quoted(inner)}"), certain(inTools.first), inTools.first.preview)
        assertTrue("RAN<tools/tidy.sh>" in inTools.second.content, inTools.second.content)
        val called = probe(f, "sh sh")
        assertEquals(listOf("Script \"sh\" (${named.length} bytes), all of it: ${PreviewText.quoted(named)}"), certain(called.assessment!!), called.assessment.preview)
        assertEquals(listOf("sh"), called.ran)
    }

    @Test
    fun `R1 - for every other command the scripts found are shown as a reading, after a line that says so`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("tidy.sh", "echo tidying\n")
        f.put("a.sh", "sh tidy.sh\n")
        val root = f.root.canonicalPath
        val reading = "Seems to run \"tidy.sh\" (13 bytes); all of that file: ${PreviewText.quoted("echo tidying\n")}"
        for (command in listOf(
            "sh -x tidy.sh", "sh tidy.sh; id", "sh tidy.sh && ls", "sh tidy.sh &", "sh  tidy.sh", "sh 'tidy.sh'", "sh \"tidy.sh\"", "sh tidy\\.sh", "sh \$PWD/tidy.sh",
            "sh $root/tidy.sh", "f=tidy.sh; sh \$f", ". ./tidy.sh", "cat tidy.sh | sh", "sh < tidy.sh", "nice sh tidy.sh", "exec sh tidy.sh", "/system/bin/sh tidy.sh",
            "bash tidy.sh", "sh tidy.sh > out.txt", "sh tidy.sh # comment", "sh t*.sh", "sh -- tidy.sh", "sh -c 'sh tidy.sh'", "eval \"\$(cat tidy.sh)\"", "sh tidy.sh\nid",
            "sh tidy.sh x=1", "sh tidy.sh ~", "sh tidy.sh {a}",
        )) {
            val a = f.assess("scripts.run_shell", "command" to command)
            val name = command.replace("\n", "\\n")
            assertEquals(emptyList(), certain(a), "$name: nothing is called the script this command runs\n${a.preview}")
            assertEquals(notPlain, a.details[a.details.indexOf(anyFile) + 1], "$name: the line that says so comes right after the fixed one\n${a.preview}")
            assertTrue(reading in a.details, "$name\n${a.preview}")
            assertFalse(noScript in a.details, name)
        }
        // A command without any script carries the line too: the reader cannot know that it runs none, and says so.
        val ls = f.assess("scripts.run_shell", "command" to "ls -la")
        assertEquals(listOf(notPlain, noScript), ls.details.subList(ls.details.indexOf(anyFile) + 1, ls.details.indexOf(anyFile) + 3), ls.preview)
        assertEquals(emptyList(), certain(ls) + readings(ls), ls.preview)
        // Whatever a script names in turn is a reading too, also under a plain command, and is announced as one.
        val nested = f.assess("scripts.run_shell", "command" to "sh a.sh")
        assertEquals(listOf("Script \"a.sh\" (11 bytes), all of it: ${PreviewText.quoted("sh tidy.sh\n")}"), certain(nested), nested.preview)
        val at = nested.details.indexOf(certain(nested).single())
        assertEquals(inTurn, nested.details[at + 1], nested.preview)
        assertEquals("Seems to run \"tidy.sh\" (13 bytes), named in \"a.sh\"; all of that file: ${PreviewText.quoted("echo tidying\n")}", nested.details[at + 2], nested.preview)
        assertFalse(notPlain in nested.details, nested.preview)
        // Without further scripts there is no such heading.
        assertFalse(inTurn in f.assess("scripts.run_shell", "command" to "sh tidy.sh").details)
    }

    @Test
    fun `R1 - every way a script can be shown says whether it is the script that runs or a reading`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.file("bin.sh").writeBytes(byteArrayOf(0, 1, 2))
        f.put("big.sh", "echo 1234567\n".repeat(6_000))
        f.put("long.sh", "echo 1234567\n".repeat(2_000))
        f.put("one.sh", "sh two.sh\n")
        f.put("two.sh", "sh three.sh\n")
        f.put("three.sh", "sh four.sh\n")
        f.put("four.sh", "echo four\n")
        suspend fun lines(command: String) = f.assess("scripts.run_shell", "command" to command).details

        assertTrue("Script \"bin.sh\" (3 bytes) is not text, so it cannot be shown. Deny unless you know what it is." in lines("sh bin.sh"))
        assertTrue("Seems to run \"bin.sh\" (3 bytes); that file is not text, so it cannot be shown. Deny unless you know what it is." in lines("sh -e bin.sh"))
        assertTrue("Script \"big.sh\" (76.2 KB) is too long to show here. Read it in Workspace before you approve." in lines("sh big.sh"), lines("sh big.sh").toString().take(900))
        assertTrue("Seems to run \"big.sh\" (76.2 KB); that file is too long to show here. Read it in Workspace before you approve." in lines("sh -e big.sh"))
        val first = lines("sh long.sh").single { it.startsWith("Script ") }
        assertTrue(first.startsWith("Script \"long.sh\" (25.4 KB), the first 16000 of 26000 characters: \"echo 1234567"), first.take(200))
        assertTrue(first.endsWith(" The rest is not shown here. Read the whole file in Workspace before you approve."), first.takeLast(200))
        val firstRead = lines("sh -e long.sh").single { it.startsWith("Seems to run ") }
        assertTrue(firstRead.startsWith("Seems to run \"long.sh\" (25.4 KB); the first 16000 of 26000 characters of that file: \"echo 1234567"), firstRead.take(200))
        assertTrue(firstRead.endsWith(" The rest is not shown here. Read the whole file in Workspace before you approve."), firstRead.takeLast(200))
        assertTrue("Seems to run \"later.sh\", which does not exist now." in lines("cat later.sh"), lines("cat later.sh").toString())
        // Three steps from the command: pinned, named, not shown.
        val deep = lines("sh one.sh")
        assertTrue("Seems to run \"four.sh\" (10 bytes), named in \"three.sh\"; that file is not shown here. Read it in Workspace before you approve." in deep, deep.toString())
        assertEquals(1, deep.count { it.startsWith("Script ") }, deep.toString())
        assertFalse(deep.any { "echo four" in it }, deep.toString())
    }

    @Test
    fun `R1 - a name the reader only finds mentioned is listed and pinned, never shown with its text as a script`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("tidy.sh", "echo harmless: lists the folder; ls\n")
        f.put("sub/tidy.sh", "rm -rf ../keep\n")
        f.put("sub/marker", "x")
        for (command in listOf("awk 'BEGIN { system(\"cd sub; sh tidy.sh\") }'", "echo 'see tidy.sh for more'", "echo \"run tidy.sh now\"", "printf '%s' \"run tidy.sh now\"")) {
            val a = f.assess("scripts.run_shell", "command" to command)
            assertEquals(emptyList(), certain(a) + readings(a), "$command\n${a.preview}")
            assertTrue(notPlain in a.details && noScript in a.details, a.preview)
            assertFalse(a.details.any { "harmless" in it }, "$command: its text is not shown\n${a.preview}")
            val listed = a.details.single { it.startsWith("Workspace files and folders it names: ") }
            assertTrue("\"tidy.sh\" (a file of 36 bytes)" in listed, listed)
            assertNull(a.prepared!!.changedSince(), command)
        }
        // Still pinned: rewritten behind the workspace's back, it stops the command.
        val a = f.assess("scripts.run_shell", "command" to "echo 'see tidy.sh for more'")
        f.put("tidy.sh", "rm -rf keep\n")
        assertTrue("tidy.sh" in a.prepared!!.changedSince()!!)
        // What a file that is only mentioned names in turn is not shown with its text either; it is listed and pinned like the file itself.
        f.put("outer.sh", "sh inner.sh\n")
        f.put("inner.sh", "echo inner text\n")
        val nested = f.assess("scripts.run_shell", "command" to "echo 'see outer.sh for more'")
        assertEquals(emptyList(), certain(nested) + readings(nested), nested.preview)
        assertFalse(nested.details.any { "inner text" in it || "sh inner.sh" in it }, nested.preview)
        val names = nested.details.single { it.startsWith("Workspace files and folders it names: ") }
        assertTrue("\"outer.sh\"" in names && "\"inner.sh\"" in names, names)
        f.put("inner.sh", "rm -rf keep\n")
        assertTrue("inner.sh" in nested.prepared!!.changedSince()!!)
        // Named for real as well, it is a reading again.
        f.put("tidy.sh", "echo tidying\n")
        val both = f.assess("scripts.run_shell", "command" to "echo 'see tidy.sh for more'; cat tidy.sh | sh")
        assertTrue(readings(both).single().startsWith("Seems to run \"tidy.sh\" (13 bytes); all of that file: "), both.preview)
    }

    @Test
    fun `R1 - the verifier's misleading approvals are refused, or say that they are a reading`() {
        // Every bait prints its own path, so what ran is read off the output. bash stands in for mksh where dash lacks the construct.
        val baits = listOf("run.sh", "RUN.SH", "ok.sh", "evil.sh", "tidy.sh", "sub/tidy.sh", "sub/evil.sh", "sub/evil", "1", "2", "1+1", "ok", "evil", "{sub/,{x}}tidy.sh")
        fun fixture(launcher: List<String>) = OperatorFixture().also { f ->
            realShell(f, launcher)
            for (b in baits) f.put(b, "echo \"RAN<$b>\"\n")
            f.put("name.txt", "evil.sh\n")
            f.put("sub/marker", "x")
            f.put("lib", "x=evil\n")
            f.put("keep/important.txt", "owner data")
        }
        val dash = listOf("/bin/sh")
        val bash = listOf("/bin/bash")
        val cases = listOf(
            "find sub -name marker -execdir sh tidy.sh \\;" to dash, "find sub -name marker -execdir sh ./tidy.sh \\;" to dash,
            "awk 'BEGIN { system(\"cd sub; sh tidy.sh\") }'" to dash, "sh {sub/,{x}}tidy.sh" to bash,
            "typeset -u f=run.sh; sh \"\$f\"" to bash, "typeset -l f=RUN.SH; sh \$f" to bash, "typeset -i n=1+1; sh \$n" to bash, "x=1; ((x++)); sh \$x" to bash,
            "x=1; let x++; sh \$x" to bash, "x=1; let \"x = 2\"; sh \$x" to bash, "REPLY=ok.sh; read < name.txt; sh \$REPLY" to bash,
            "x=ok; trap 'x=evil' USR1; kill -USR1 \$\$; sh \$x" to dash, "x=ok; trap 'x=evil' USR1; kill -USR1 \$\$; sh \$x" to bash,
            "OPTARG=ok; getopts a: o -a evil; sh \$OPTARG" to dash, "d=.; x=ok; \$d ./lib; sh \$x" to dash,
            "alias \"sh=sh sub/e\"vil.sh\nsh tidy.sh" to dash, "alias 'sh=sh sub/evil'\nsh tidy.sh" to dash,
        )
        val wrong = ArrayList<String>()
        for ((command, launcher) in cases) {
            val p = probe(fixture(launcher), command)
            val name = command.replace("\n", "\\n")
            // All but one are refused now (R2); the awk program only mentions the name, which is then listed without its text.
            val a = p.assessment
            if (a == null) {
                if ("othing was run" !in p.refusal!!) wrong += "[$name] ${p.refusal}"
                continue
            }
            if (!command.startsWith("awk ")) wrong += "[$name] should be refused; ran=${p.ran}"
            if (certain(a).isNotEmpty()) wrong += "[$name] shows ${certain(a).map { it.take(40) }} as the script that runs; ran=${p.ran}"
            if (notPlain !in a.details) wrong += "[$name] does not say that what it shows is a reading"
            if (a.details.any { "RAN<" in it.replace("⟨\"⟩", "\"") }) wrong += "[$name] shows the text of a script it only found mentioned; ran=${p.ran}"
        }
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
    }

    // ------------------------------------------------------------------ R2

    private val places = ShellCommands.Places(listOf("/data/ws", "/real/ws"), RelativePath.of("proj"))
    private fun read(command: String) = ShellCommands.read(command, places)
    private fun scripts(command: String) = read(command).names.filter { it.must }

    @Test
    fun `R2 - a script named through a variable is refused unless the text plainly gives the variable its value`() = runBlocking<Unit> {
        val refused = listOf(
            // A declaration with options changes the value (upper case, lower case, a number), or does not set this variable at all.
            "typeset -u f=run.sh; sh \"\$f\"", "typeset -l f=RUN.SH; sh \$f", "typeset -i n=1+1; sh \$n", "declare -u f=run.sh; sh \$f", "local -u f=run.sh; sh \$f",
            "export -n f=run.sh; sh \$f", "readonly -p f=run.sh; sh \$f", "typeset -x -u f=run.sh; sh \$f", "f=run.sh; typeset -u f; sh \$f", "integer n=1+1; sh \$n",
            "command typeset -u f=run.sh; sh \$f", "typeset \"-u\" f=run.sh; sh \$f", "typeset +u f=run.sh; sh \$f", "typeset \$o f=run.sh; sh \$f",
            // An assignment handed to a declaration in quotes is one too, in a spelling whose value is not followed.
            "f=ok.sh; export \"f=evil.sh\"; sh \$f", "f=ok.sh; readonly 'f'=evil.sh; sh \$f", "f=ok.sh; typeset f\\=evil.sh; sh \$f",
            // Arithmetic, read, getopts and trap set variables without an assignment the reader could follow.
            "x=1; ((x++)); sh \$x", "x=1; (( x = 2 )); sh \$x", "x=1; let x++; sh \$x", "x=1; let \"x = 2\"; sh \$x", "REPLY=ok.sh; read < name.txt; sh \$REPLY",
            "x=ok.sh; read -r y < name.txt; sh \$x", "x=ok.sh; command read; sh \$x", "x=ok; trap 'x=evil' USR1; kill -USR1 \$\$; sh \$x", "x=ok.sh; trap : INT; sh \$x",
            "OPTARG=ok; getopts a: o -a evil; sh \$OPTARG", "x=ok.sh; while getopts a o; do :; done; sh \$x",
            // A command whose own name comes from a variable may be any of these.
            "d=.; x=ok; \$d ./lib; sh \$x", "r=read; x=ok.sh; \$r x < name.txt; sh \$x", "x=ok.sh; \"\$@\"; sh \$x", "d=.; x=ok; command \$d ./lib; sh \$x",
        )
        for (command in refused) {
            val found = scripts(command).filter { it.problem != null }
            assertEquals(listOf(ShellCommands.RUN_TIME), found.map { it.problem }, command)
        }
        // Plain assignments are still read, also several of them and a loop's values.
        for (command in listOf("f=run.sh; sh \$f", "f=run.sh; sh \"\${f}\"", "export f=run.sh; sh \$f", "readonly f=run.sh; sh \$f", "typeset f=run.sh; sh \$f", "d=\$PWD; sh \$d/run.sh")) {
            assertEquals(listOf<String?>(null), scripts(command).map { it.problem }, command)
        }
        assertEquals(2, scripts("for s in a.sh b.sh; do sh \$s; done").single().targets.size)
        // Through the tool: refused before anyone is asked, with a reason the model can act on.
        val f = OperatorFixture()
        f.put("run.sh", "echo harmless\n")
        f.put("RUN.SH", "rm -rf keep\n")
        val reason = f.rejected("scripts.run_shell", "command" to "typeset -u f=run.sh; sh \"\$f\"")
        assertTrue("only put together when the command runs" in reason && "othing was run" in reason, reason)
    }

    @Test
    fun `R2 - find -execdir and -okdir count as a change of folder`() = runBlocking<Unit> {
        for (command in listOf("find sub -name marker -execdir sh tidy.sh \\;", "find sub -okdir sh ./tidy.sh \\;", "nice find . -execdir sh tidy.sh {} +", "find . \"-execdir\" sh tidy.sh \\;")) {
            assertEquals(ShellCommands.UNSETTLED_FOLDER, read(command).unsettledBy, command)
        }
        for (command in listOf("find . -exec sh run.sh {} ;", "find . -name execdir", "find . -ok sh run.sh {} ;")) assertNull(read(command).unsettledBy, command)
        val f = OperatorFixture()
        f.put("tidy.sh", "echo harmless\n")
        f.put("sub/tidy.sh", "rm -rf ../keep\n")
        f.put("sub/marker", "x")
        for (command in listOf("find sub -name marker -execdir sh tidy.sh \\;", "find sub -name marker -execdir sh ./tidy.sh \\;", "find sub -name marker -okdir sh tidy.sh \\;")) {
            val reason = f.rejected("scripts.run_shell", "command" to command)
            assertTrue("by a relative path" in reason && "changes folder" in reason && "find -execdir" in reason && "othing was run" in reason, "$command: $reason")
        }
        // Named by its full path the script is certain wherever find runs it; -exec runs in the working folder.
        f.assess("scripts.run_shell", "command" to "find sub -name marker -execdir sh ${f.root.canonicalPath}/tidy.sh \\;")
        f.assess("scripts.run_shell", "command" to "find sub -name marker -exec sh tidy.sh \\;")
    }

    @Test
    fun `R2 - alias with any argument is refused, quoted or not`() = runBlocking<Unit> {
        for (command in listOf(
            "alias sh='sh sub/evil.sh'", "alias 'sh=sh sub/evil.sh'\nsh tidy.sh", "alias \"sh=sh sub/evil.sh\"\nsh tidy.sh", "alias s\\h='sh sub/evil.sh'\nsh tidy.sh",
            "alias \"sh=sh sub/e\"vil.sh\nsh tidy.sh", "alias 'cat=cat sub/evil.sh'\ncat ok.sh | sh", "alias -p", "alias ls", "alias -x l=ls", "command alias 'sh=sh x'",
            "\\alias 'sh=sh x'", "alias \$a",
        )) assertTrue(ShellCommands.DOUBT_ALIAS in read(command).doubts, "${command.replace("\n", "\\n")}: ${read(command).doubts}")
        for (command in listOf("alias", "unalias x", "echo alias x=y", "grep alias notes.md")) assertEquals(emptyList(), read(command).doubts, command)
        val f = OperatorFixture()
        f.put("tidy.sh", "echo harmless\n")
        f.put("sub/evil", "rm -rf ../keep\n")
        val reason = f.rejected("scripts.run_shell", "command" to "alias 'sh=sh sub/evil'\nsh tidy.sh")
        assertTrue("alias" in reason && "othing was run" in reason, reason)
    }

    @Test
    fun `R2 - a script's name with an open brace and a comma or two dots anywhere after it is refused`() = runBlocking<Unit> {
        for (command in listOf(
            "sh {sub/,{x}}tidy.sh", "sh {sub/,}tidy.sh", "sh {{a,b},c}.sh", "sh a{b,c.sh", "sh x{1..3}.sh", "sh {a..b", "sh {x}{y,z}.sh", "sh {\"sub/\",}tidy.sh", ". ./{a,{b}}.sh",
            "awk -f {a,{b}}.awk x", "cat {a,{b}}.sh | sh", "sh < {a,{b}}.sh",
        )) assertEquals(listOf(ShellCommands.RUN_TIME), scripts(command).map { it.problem }, command)
        // As a program's name it is a doubt, as before; quoted, a brace is a plain character, and so is one without a comma after it.
        assertTrue(read("{sh,{x}} run.sh").doubts.any { "only put together when it runs" in it })
        for (command in listOf("sh '{a,b}.sh'", "sh \\{a,b}.sh", "sh a{b}.sh", "sh a,b{c}.sh")) assertEquals(listOf<String?>(null), scripts(command).map { it.problem }, command)
        val f = OperatorFixture()
        f.put("{sub/,{x}}tidy.sh", "echo harmless\n")
        f.put("sub/tidy.sh", "rm -rf ../keep\n")
        val reason = f.rejected("scripts.run_shell", "command" to "sh {sub/,{x}}tidy.sh")
        assertTrue("only put together when the command runs" in reason && "othing was run" in reason, reason)
        // Not a script's name: such a word is simply not looked up.
        f.assess("scripts.run_shell", "command" to "echo {sub/,{x}}tidy.sh")
    }

    // ------------------------------------------------------------------ R3

    @Test
    fun `R3 - HOME, ENV and PATH pointed into the workspace are found in more spellings`() {
        val startUp = "to a place in the workspace, or to a value that is only known when the command runs"
        for (command in listOf(
            // Quotes and backslashes around the name, as export and env get them.
            "export \"HOME=\$PWD\"; sh -l -c 'echo hi'", "export \\HOME=\$PWD; sh -l -c 'echo hi'", "export 'HOME'=/data/ws; sh -l -c 'echo hi'", "export H\"OM\"E=/data/ws/proj",
            "\"export\" \"HOME=\$PWD\"", "command export \"HOME=\$PWD\"", "typeset -x 'HOME=/data/ws'", "readonly \"HOME=\$PWD\"", "env \"HOME=\$PWD\" sh -l", "env 'HOME'=/data/ws sh -l",
            "export \"ENV=\$PWD/rc\"; sh -i -c 'echo hi'", "export \"PATH=\$PWD/bin\"", "export 'PATH=bin'", "export \"HOME+=/x\"",
            // env behind other commands that start a command.
            "nice env HOME=\$PWD sh -l -c 'echo hi'", "nice env HOME=/data/ws sh -l -c 'echo hi'", "command env HOME=/data/ws sh -l -c 'echo hi'",
            "timeout 5 env HOME=/data/ws sh -l -c 'echo hi'", "nohup env HOME=/data/ws sh -l", "exec env HOME=/data/ws sh -l", "time env HOME=/data/ws sh -l",
            "nice -n 5 nohup env -i HOME=/data/ws sh -l", "nice env ENV=/data/ws/rc sh -i -c 'echo hi'", "timeout 5 env PATH=bin tool", "xargs env HOME=/data/ws sh -l -c true",
            "find . -exec env HOME=/data/ws sh -l -c true ;", "toybox env HOME=/data/ws sh -l",
            // The workspace under another spelling of its path.
            "HOME=/data/./ws sh -l -c 'echo hi'", "HOME=/data//ws sh -l", "HOME=/data/ws/ sh -l", "HOME=//data/ws/proj/. sh -l", "HOME=/./data/ws sh -l", "HOME=/data/ sh -l",
            "ENV=/data/./ws/rc sh -i -c 'echo hi'", "ENV=/real//ws/rc sh -i", "PATH=/system/bin:/data/./ws/bin tool", "export HOME=/data/ws/./proj",
        )) assertTrue(read(command).doubts.any { startUp in it }, "$command: ${read(command).doubts}")
        // A name reference can stand for HOME, ENV or PATH: refused when the text also starts a shell.
        for (command in listOf(
            "typeset -n r=HOME; r=\$PWD; sh -l -c 'echo hi'", "declare -n r=HOME; r=/data/ws; sh -l -c 'echo hi'", "nameref r=HOME; r=\$PWD; sh -l", "typeset -n r=ENV; r=rc; sh -i",
            "local -n r=HOME; r=\$PWD; bash -l", "typeset -rn r=HOME; r=\$PWD; nice sh -l", "typeset -n r=HOME; r=\$PWD; sh -c 'sh -l'",
        )) assertTrue(read(command).doubts.any { "name reference" in it }, "$command: ${read(command).doubts}")
        assertEquals(emptyList(), read("typeset -n r=x; r=1; echo \$x").doubts, "without a shell a name reference is no doubt")
        // Still fine: places outside the workspace, however they are spelled, and words that only look like an assignment.
        for (command in listOf(
            "HOME=/tmp sh -l -c true", "HOME=/tmp//x/./y/ sh -l -c true", "export \"HOME=/sdcard/Download\"", "nice env HOME=/tmp sh -l -c true", "env \"PATH=/system/bin:/vendor/bin\" ls",
            "make HOME=sub", "echo HOME=\$PWD", "echo \"HOME=\$PWD\"", "grep -r 'ENV=prod' .", "grep PATH= notes.md", "export \"LANG=C\"", "nice env LANG=C sort x",
            "HOME=/data/wsx sh -l -c true", "export HOME PATH", "unset ENV HOME",
        )) assertEquals(emptyList(), read(command).doubts, command)
    }

    @Test
    fun `R3 - the verifier's spellings no longer run a profile from the workspace - they are refused`() {
        val wrong = ArrayList<String>()
        for ((template, launcher) in listOf(
            "HOME=ROOT_DOTTED sh -l -c 'echo hi'", "export \"HOME=\$PWD\"; sh -l -c 'echo hi'", "export \\HOME=\$PWD; sh -l -c 'echo hi'", "export 'HOME'=ROOT; sh -l -c 'echo hi'",
            "nice env HOME=\$PWD sh -l -c 'echo hi'", "nice env HOME=ROOT sh -l -c 'echo hi'", "command env HOME=ROOT sh -l -c 'echo hi'", "timeout 5 env HOME=ROOT sh -l -c 'echo hi'",
            "export \"ENV=\$PWD/rc\"; sh -i -c 'echo hi'", "nice env ENV=ROOT/rc sh -i -c 'echo hi'", "ENV=ROOT_DOTTED/rc sh -i -c 'echo hi'",
        ).map { it to listOf("/bin/sh") } + listOf("typeset -n r=HOME; r=\$PWD; sh -l -c 'echo hi'", "declare -n r=HOME; r=ROOT; sh -l -c 'echo hi'").map { it to listOf("/bin/bash") }) {
            val f = OperatorFixture()
            realShell(f, launcher)
            f.put(".profile", "echo \"RAN<.profile>\"\n")
            f.put("rc", "echo \"RAN<rc>\"\n")
            val root = f.root.canonicalPath
            val dotted = f.root.canonicalFile.parent + "/./" + f.root.canonicalFile.name
            val p = probe(f, template.replace("ROOT_DOTTED", dotted).replace("ROOT", root))
            if (p.refusal == null) wrong += "[$template] was not refused; ran=${p.ran}"
            else if ("othing was run" !in p.refusal) wrong += "[$template] ${p.refusal}"
        }
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
        // Control: with nothing set, a login shell runs and reads no profile from the workspace (the runner's HOME is outside it).
        val f = OperatorFixture()
        realShell(f)
        f.put(".profile", "echo \"RAN<.profile>\"\n")
        val control = probe(f, "sh -l -c 'echo hi'")
        assertNull(control.refusal)
        assertEquals(emptyList(), control.ran)
    }

    // ------------------------------------------------------------------ R4

    @Test
    fun `R4 - a command that cannot be handed to a process exactly is refused before anyone is asked`() = runBlocking<Unit> {
        val f = OperatorFixture()
        realShell(f)
        f.put("keep1", "one")
        f.put("keepX", "two")
        f.put("sub/x.txt", "x")
        f.termux.availability = TermuxAvailability.READY
        // The verifier's command: Java hands an unpaired surrogate on as "?", which the shell reads as a pattern ("keep1 keepX").
        for (command in listOf("echo keep\uD800", "echo keep\uDC00", "\uD800", "echo \uDBFF x", "echo a\uDC00\uD800b", "ls\u0000 -la")) {
            for (id in listOf("scripts.run_shell", "termux.run")) {
                val reason = f.rejected(id, "command" to command)
                assertTrue("cannot be passed on exactly" in reason && "Nothing was asked and nothing was run" in reason, "$id: $reason")
            }
        }
        assertTrue("U+D800" in f.rejected("scripts.run_shell", "command" to "echo keep\uD800"))
        assertTrue("U+0000" in f.rejected("termux.run", "command" to "ls\u0000"))
        // The folder reaches the process too.
        val folder = f.rejected("scripts.run_shell", "command" to "ls", "directory" to "sub\uD800")
        assertTrue("\"directory\"" in folder && "cannot be passed on exactly" in folder, folder)
        assertTrue(f.shell.runs.isEmpty() && f.termux.runs.isEmpty())
        // A character outside the basic plane is a pair of surrogates and passes, as do other scripts and emoji.
        for (command in listOf("echo \uD83D\uDE00", "echo größe 日本語 \uD83D\uDC4D\uD83C\uDFFD", "echo keep?")) {
            assertTrue(f.ok("scripts.run_shell", "command" to command).ok)
            assertTrue(f.ok("termux.run", "command" to command).ok)
        }
        assertEquals("echo \uD83D\uDE00", f.shell.runs.first().command)
    }

    // ------------------------------------------------------------------ R5

    private class World(val b: BrokerFixture, val executor: ToolExecutor, val workspace: LocalWorkspace, val shellRuns: MutableList<String>, val second: AgentProfile) {
        private var calls = 0

        suspend fun shell(who: AgentProfile, task: String, command: String): ToolExecution =
            executor.execute(ToolCallRequest("c${++calls}", "scripts_run_shell", json("command" to command)), b.context(task = task, who = who))
    }

    /** Two agents that hold the shell permission, the real broker and executor, and a shell whose "sleep N" takes N seconds of virtual time. */
    private suspend fun TestScope.world(): World {
        val b = BrokerFixture(this).setUp()
        val f = OperatorFixture()
        val id = ToolId("scripts.run_shell")
        val agent = b.agent.copy(toolIds = b.agent.toolIds + id)
        b.agents.upsert(agent)
        b.agent = agent
        val second = Samples.agent(id = "agt_second", name = "Second").copy(toolIds = setOf(id))
        b.agents.upsert(second)
        val runs = CopyOnWriteArrayList<String>()
        val shell = object : ShellRunner {
            override suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome {
                runs += command
                val ms = Regex("sleep (\\d+)").find(command)?.groupValues?.get(1)?.toLong()?.times(1_000) ?: 10L
                delay(ms)
                return CommandOutcome.Finished(0, "ran: $command", "", false, ms)
            }
        }
        val base = f.dependencies
        val workspace = LocalWorkspace(FakeWorkspaceRoot(f.root), b.clock, io = EmptyCoroutineContext)
        val d = OperatorToolDependencies(
            workspace = workspace, sharedFolders = base.sharedFolders, folders = base.folders, media = base.media, apps = base.apps, web = base.web,
            screen = base.screen, authorisedApps = base.authorisedApps, settings = { b.settings.get() }, protectedPackages = base.protectedPackages,
            shell = shell, termux = base.termux, clock = b.clock, ownPackage = base.ownPackage, limits = base.limits, egress = OperatorEgress.logTo(b.activity),
        )
        val tool = OperatorToolset.create(d).single { it.spec.id == id }
        val registry = DefaultToolRegistry().also { it.register(tool) }
        // Both hold the permission itself: only the approval of each command is asked.
        for ((n, who) in listOf(agent, second).withIndex()) {
            b.grants.insert(PermissionGrant(GrantId("grt_$n"), who.id, tool.spec.capability, CapabilityConstraint.None, GrantScope.ALWAYS, grantedAt = 1))
        }
        b.runningTask("tsk_a", deadlineAt = START + 15 * MINUTE)
        b.runningTask("tsk_b", deadlineAt = START + 15 * MINUTE)
        return World(b, ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = testTimeSource, tasks = b.tasks), workspace, runs, second)
    }

    @Test
    fun `R5 - a command asked for while another one runs is refused at once and never put to the owner`() = runTest {
        val w = world()
        val first = async { w.shell(w.b.agent, "tsk_a", "sleep 30") }
        runCurrent()
        w.b.owner.approve(w.b.pending().id)
        runCurrent()
        assertTrue(w.workspace.commandRunning() && !first.isCompleted, "the first command runs")
        assertEquals(1, w.b.allRequests().size)

        // The verifier's case: Second asks while the first command runs.
        val asked = async { w.shell(w.second, "tsk_b", "echo b") }
        runCurrent()
        assertTrue(asked.isCompleted, "it ends at once")
        val refused = asked.await()
        assertEquals(ToolCallEnd.FAILED, refused.end)
        assertTrue(
            "Another shell command is running. Nothing was asked and nothing was run. Ask again when it has ended (about a minute at most)." in refused.outcome.content,
            refused.outcome.content,
        )
        assertEquals(1, w.b.allRequests().size, "no request was put to the owner for it")
        assertTrue(w.b.requests.observePending().first().isEmpty())
        assertEquals(listOf("sleep 30"), w.shellRuns.toList())

        // When the first has ended, asking again works: one request, one run.
        advanceTimeBy(31_000)
        assertTrue(first.await().outcome.ok)
        val again = async { w.shell(w.second, "tsk_b", "echo b") }
        runCurrent()
        w.b.owner.approve(w.b.pending().id)
        advanceTimeBy(1_000)
        assertTrue(again.await().outcome.ok, again.await().outcome.content)
        assertEquals(listOf("sleep 30", "echo b"), w.shellRuns.toList())
        assertEquals(2, w.b.allRequests().size, "the owner was asked once for each command that could run")
    }

    @Test
    fun `R5 - a command that was asked for before another one ran is told to wait for that one before asking anew`() = runBlocking<Unit> {
        val f = OperatorFixture()
        val tool = f.tool("scripts.run_shell")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.shell.respond = { command, _ ->
            if (command == "echo first") {
                started.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
            CommandOutcome.Finished(0, command, "", false, 1)
        }
        val first = json("command" to "echo first")
        val second = json("command" to "echo second")
        val a1 = tool.assess(first, f.context)
        val a2 = tool.assess(second, f.context)
        val running = async(Dispatchers.Default) { tool.execute(first, f.context, a1) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val refused = tool.execute(second, f.context, a2)
        assertFalse(refused.ok)
        assertTrue("another shell command ran" in refused.content && "if another shell command was running, wait until it has ended" in refused.content && "ask anew" in refused.content, refused.content)
        // And asking anew while it still runs is refused in the assessment, before anyone is asked.
        assertTrue("Another shell command is running." in f.rejected("scripts.run_shell", "command" to "echo second"))
        release.countDown()
        assertTrue(running.await().ok)
        assertTrue(f.ok("scripts.run_shell", "command" to "echo second").ok)
    }

    // ------------------------------------------------------------------ texts

    @Test
    fun `the tool's description says what R1, R3 and R5 mean, and fits`() {
        val description = OperatorFixture().tool("scripts.run_shell").spec.description
        assertTrue(description.length <= 1024, "some providers refuse a tool description longer than 1024 characters: ${description.length}")
        assertTrue("sh file" in description && "best reading" in description, description)
        assertFalse("with certainty" in description, description)
        assertTrue("while another runs" in description, description)
    }
}
