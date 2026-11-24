package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G4, from the second verification's probe: for every spelling the verifier found, either the
 * command is refused before the owner is asked, or the scripts that really run are the ones the
 * approval shows. Never the third thing: a script that runs unshown, or the text of one script
 * shown while another one runs.
 *
 * A real shell runs behind the tool's port (dash, and bash with extglob for the pattern lists
 * Android's mksh always understands), with HOME outside the workspace as the app's runner sets
 * it. Every bait script prints its own marker, so what ran is read off the output.
 */
class ShellShownIsWhatRunsTest {
    private val shBaits = listOf("tidy.sh", "tidy", "sub/tidy.sh", "sub/tidy", ".profile")
    private val awkBaits = listOf("prog.awk", "prog")
    private fun text(bait: String) = if (bait in awkBaits) "BEGIN { print \"RAN<$bait>\" }\n" else "echo \"RAN<$bait>\"\n"

    private fun fixture(launcher: List<String>): OperatorFixture {
        val f = OperatorFixture()
        val home = Files.createTempDirectory("aicc-home").toFile()
        f.shell.respond = { command, dir ->
            val builder = ProcessBuilder(launcher + listOf("-c", command)).directory(dir.file).redirectErrorStream(true)
            builder.environment().clear()
            builder.environment()["PATH"] = "/usr/bin:/bin"
            builder.environment()["HOME"] = home.path
            val p = builder.start()
            p.outputStream.close()
            p.waitFor(10, TimeUnit.SECONDS)
            CommandOutcome.Finished(p.exitValue(), p.inputStream.bufferedReader().readText(), "", false, 1)
        }
        for (bait in shBaits + awkBaits) f.put(bait, text(bait))
        f.put("keep/important.txt", "owner data")
        f.put("name.txt", "tidy.sh\n")
        f.put("go.sh", "cd sub\n")
        f.put("a.sh", "sh b.sh\n")
        f.put("b.sh", "sh tidy\n")
        return f
    }

    private enum class Expect {
        /** Not refused, a bait runs, and every bait that runs is shown. */
        SHOWN,

        /** Refused before the owner is asked. */
        REFUSED,

        /** Either: refused, or whatever runs is shown (on the build machine's tools some of these run nothing). */
        EITHER,
    }

    private class Result(val refusal: String?, val ran: List<String>, val shown: List<String>)

    private fun probe(template: String, launcher: List<String>): Result = runBlocking {
        val f = fixture(launcher)
        val args = json("command" to template.replace("ROOT", f.root.canonicalPath))
        val tool = f.tool("scripts.run_shell")
        val assessment = try {
            tool.assess(args, f.context)
        } catch (e: ToolCallRejected) {
            return@runBlocking Result(e.message, emptyList(), emptyList())
        }
        val outcome = tool.execute(args, f.context, assessment)
        val ran = Regex("RAN<([^>]+)>").findAll(outcome.content).map { it.groupValues[1] }.toList().distinct()
        val shown = (shBaits + awkBaits).filter { bait -> assessment.details.any { it.startsWith("Script ") && "RAN<$bait>" in it.replace("⟨\"⟩", "\"") } }
        Result(null, ran, shown)
    }

    private fun check(cases: List<Pair<String, Expect>>, launcher: List<String>) {
        val wrong = ArrayList<String>()
        for ((command, expect) in cases) {
            val r = probe(command, launcher)
            val name = command.replace("\n", "\\n")
            val unshown = r.ran - r.shown.toSet()
            when {
                r.refusal != null -> if (expect == Expect.SHOWN) wrong += "[$name] should run with its script shown, but was refused: ${r.refusal}"
                expect == Expect.REFUSED -> wrong += "[$name] should be refused; ran=${r.ran} shown=${r.shown}"
                unshown.isNotEmpty() -> wrong += "[$name] ran $unshown without showing it; shown=${r.shown}"
                expect == Expect.SHOWN && r.ran.isEmpty() -> wrong += "[$name] ran no bait; shown=${r.shown}"
            }
            // A refusal is something the model can act on: it says why and that nothing ran.
            r.refusal?.let { assertTrue("othing was run" in it, "[$name] $it") }
        }
        assertEquals(emptyList(), wrong, wrong.joinToString("\n"))
    }

    private val dash = listOf("/bin/sh")

    @Test
    fun `the verifier's spellings - what runs was shown, or the command is refused`() = check(
        listOf(
            // Controls.
            "sh tidy.sh" to Expect.SHOWN, "sh sub/../tidy.sh" to Expect.SHOWN, "sh t*.sh" to Expect.SHOWN, "sh a.sh" to Expect.SHOWN,
            // (1) The shell's own options.
            "sh -xo errexit tidy.sh" to Expect.SHOWN, "sh -xo errexit ./tidy" to Expect.SHOWN, "sh -xo errexit /proc/self/cwd/tidy.sh" to Expect.REFUSED,
            "sh - tidy" to Expect.SHOWN, "sh - /proc/self/cwd/tidy.sh" to Expect.REFUSED, "sh -eo errexit sub/tidy" to Expect.SHOWN,
            "E=; sh \$E tidy" to Expect.REFUSED, "E=; sh \$E /proc/self/cwd/tidy.sh" to Expect.REFUSED,
            // (3) Patterns the shell matches and the tool does not.
            "sh [[:alpha:]]idy.sh" to Expect.REFUSED, "sh tidy.s[[:lower:]]" to Expect.REFUSED, ". ./[[:alpha:]]idy.sh" to Expect.REFUSED,
            "sh sub/[[:alnum:]]*" to Expect.REFUSED, "sh t[[.i.]]dy.sh" to Expect.REFUSED, "sh tid[!]z].sh" to Expect.SHOWN, "sh ti[d\\]]y.sh" to Expect.REFUSED,
            // (4) A bare shell fed otherwise than by "<" from a workspace file.
            "sh <> tidy.sh" to Expect.SHOWN, "sh <> /proc/self/cwd/tidy.sh" to Expect.REFUSED, "exec < /proc/self/cwd/tidy.sh; sh" to Expect.REFUSED,
            "{ sh; } < /proc/self/cwd/tidy.sh" to Expect.REFUSED, "(sh) < /proc/self/cwd/tidy.sh" to Expect.REFUSED, "{ sh; } < tidy" to Expect.REFUSED,
            "cat /proc/self/cwd/tidy.sh | sh" to Expect.REFUSED, "cat < /proc/self/cwd/tidy.sh | sh" to Expect.REFUSED,
            "eval \"\$(cat /proc/self/cwd/tidy.sh)\"" to Expect.REFUSED, "cat tidy.sh | sh" to Expect.SHOWN, "eval \"\$(cat tidy.sh)\"" to Expect.SHOWN,
            // (5) The shell under another fixed name.
            "/proc/self/exe tidy" to Expect.SHOWN, "/proc/self/exe /proc/self/cwd/tidy.sh" to Expect.REFUSED,
            "/lib64/ld-linux-x86-64.so.2 /bin/sh /proc/self/cwd/tidy.sh" to Expect.REFUSED, "setarch x86_64 sh /proc/self/cwd/tidy.sh" to Expect.REFUSED,
            "nice sh /proc/self/cwd/tidy.sh" to Expect.REFUSED, "timeout 5 sh tidy.sh" to Expect.SHOWN,
            // (6) The here-document whose end word mksh joins from two lines, and the case inside a substitution.
            "cat <<EOF\nEO\\\nF\nsh /proc/self/cwd/tidy.sh\nEOF" to Expect.REFUSED,
            "echo \"\$(case x in x) sh /proc/self/cwd/tidy.sh ;; esac)\"" to Expect.REFUSED,
            // The new problem: one script shown while another runs.
            ". ./go.sh; sh tidy.sh" to Expect.REFUSED, ". ./go.sh; . ./tidy.sh" to Expect.REFUSED, "PATH=sub:\$PATH; . tidy.sh" to Expect.REFUSED,
            "PATH=sub:\$PATH; . ./tidy.sh" to Expect.REFUSED, "cd sub && cat tidy.sh | sh" to Expect.REFUSED, "cd sub && eval \"\$(cat tidy.sh)\"" to Expect.REFUSED,
            "cd sub && /proc/self/exe tidy.sh" to Expect.REFUSED, "(cd sub; cat tidy.sh) | sh" to Expect.REFUSED, "cd sub && sh ROOT/tidy.sh" to Expect.SHOWN,
            // awk and sed.
            "awk -f prog" to Expect.SHOWN, "awk -fprog" to Expect.SHOWN, "awk -v a=1 -f/proc/self/cwd/prog" to Expect.REFUSED, "awk --file prog" to Expect.EITHER,
            "awk -vf=1 -f prog.awk" to Expect.SHOWN, "awk -e 'BEGIN{}' -f prog" to Expect.EITHER, "awk -E prog" to Expect.EITHER, "awk --exec prog" to Expect.EITHER,
            "awk -f /proc/self/cwd/prog" to Expect.REFUSED, "awk -i prog 'BEGIN{}'" to Expect.EITHER, "awk --include=prog 'BEGIN{}'" to Expect.EITHER,
            "sed -n -f prog name.txt" to Expect.EITHER, "sed -nfprog name.txt" to Expect.EITHER, "sed --file=prog name.txt" to Expect.EITHER,
            "sed -s -n --file prog name.txt" to Expect.EITHER, "sed --posix -f prog name.txt" to Expect.EITHER,
        ),
        dash,
    )

    @Test
    fun `ksh pattern lists and other shells' spellings are refused in a script's name`() {
        // bash with extglob stands in for mksh, which has these patterns always on.
        check(
            listOf(
                "sh @(tid)y.sh" to Expect.REFUSED, "sh +(tidy).sh" to Expect.REFUSED, "sh ti@(d)y" to Expect.REFUSED,
                "sh !(zzz|a.sh|b.sh|go.sh|keep|name.txt|prog*|sub|tidy)" to Expect.REFUSED, ". ./@(tidy.sh)" to Expect.REFUSED, "sh tidy?(.sh)" to Expect.REFUSED,
                "sh ti*(d)y" to Expect.REFUSED,
            ),
            listOf("/bin/bash", "-O", "extglob"),
        )
        check(
            listOf(
                "sh \$'tidy.sh'" to Expect.SHOWN, "sh <(cat /proc/self/cwd/tidy.sh)" to Expect.REFUSED, "eval \"\$(</proc/self/cwd/tidy.sh)\"" to Expect.REFUSED,
                "sh ~+/tidy.sh" to Expect.REFUSED, "sh ~-/tidy.sh" to Expect.REFUSED, "cd sub; cd ..; sh ~-/tidy.sh" to Expect.REFUSED,
            ),
            listOf("/bin/bash"),
        )
    }

    @Test
    fun `a login or interactive shell is not refused, and reads no profile from the workspace`() {
        // (2) of the verifier's list. G3 is the runner's doing (HOME is an empty folder outside the workspace, as in this
        // fixture); what is checked here is that the tool does not refuse these, and that the profile an agent wrote does not run.
        for (command in listOf("sh -l -c 'echo hi'", "sh -lc true", "env sh -l -c true", "sh -l tidy.sh")) {
            val r = probe(command, dash)
            assertEquals(null, r.refusal, command)
            assertFalse(".profile" in r.ran, "$command ran the workspace's .profile")
        }
    }
}
