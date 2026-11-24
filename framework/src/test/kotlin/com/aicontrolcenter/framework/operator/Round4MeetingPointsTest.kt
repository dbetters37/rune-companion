package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.ExactText
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 4, where the three sets meet. Each set tested its own fix; these tests hold two or three
 * of them together through the real tools and the real workspace.
 *
 *  - READER's refusal of a command asked for while another one runs (R5) with STATE's rules for
 *    the trash while the change lock is held (W2: nothing is called a leftover; W3: the tidy
 *    after a start waits) and with the change counter (W1): the tidy on a first look is a change
 *    like any other, so a command that waits for its answer is not run after it. Since
 *    corrective pass 4b (F3) that first look is the owner's ([Workspace.tidyTrash]); an agent's
 *    look tidies nothing.
 *  - READER's approval wording (R1: "Script …, all of it" for the plain form only, "Seems to run"
 *    and the "cannot be sure" line for everything else) with JS's further quote look-alikes
 *    (J2) inside those lines.
 *  - The one rule for a text that cannot be handed to a program exactly (R4 in the tools, D3 in
 *    the runners, J3 in previews): one wording ([ExactText]), the character always by its code.
 */
class Round4MeetingPointsTest {
    // The app's own fixed lines of a shell command's approval (R1).
    private val anyFile = "A shell command can run any file in the workspace. Only the scripts found in the command are shown here."
    private val notPlain = "This is not a plain \"sh file\" command, so the app cannot be sure which files it runs. What follows is its best reading."
    private val noScript = "It found no script in this command, which does not mean that none runs."

    private suspend fun OperatorFixture.trash(): List<TrashItem> = (workspace.listTrash() as DocResult.Ok).value

    /** What a kill can leave under the trash folder: a staged file, and content whose record was never written. */
    private fun leaveLeftovers(f: OperatorFixture) {
        f.put(".trash/.staging/half-written", "x")
        f.put(".trash/$LOST_ID/report.txt", "the owner's report")
    }

    /** A shell command that has started and runs until [release] is counted down. */
    private class Running(val command: Deferred<ToolOutcome>, val release: CountDownLatch)

    private suspend fun kotlinx.coroutines.CoroutineScope.startCommand(f: OperatorFixture, text: String): Running {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.shell.respond = { command, _ ->
            if (command == text) {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            CommandOutcome.Finished(0, "done", "", false, 1)
        }
        val tool = f.tool("scripts.run_shell")
        val args = json("command" to text)
        val assessment = tool.assess(args, f.context)
        val command = async(Dispatchers.Default) { tool.execute(args, f.context, assessment) }
        assertTrue(started.await(10, TimeUnit.SECONDS), "the command started")
        return Running(command, release)
    }

    private fun assertToldToAskAnew(refused: ToolOutcome) {
        assertFalse(refused.ok)
        assertTrue(
            "Nothing was done" in refused.content && "something in the workspace changed" in refused.content &&
                "if another shell command was running, wait until it has ended" in refused.content && "ask anew" in refused.content,
            refused.content,
        )
    }

    // ---- R5 with W1, W2 and W3 ----

    @Test
    fun `the owner's first look at the trash after a start counts as a change - a command that waits for its answer is not run`() = runBlocking<Unit> {
        val f = OperatorFixture()
        leaveLeftovers(f)
        val tool = f.tool("scripts.run_shell")
        val args = json("command" to "ls -la .trash")

        // The owner is asked; before the answer, agents look at the trash. That changes nothing (4b, F3: an assessment
        // has no side effects, and an agent's look does not tidy).
        val asked = tool.assess(args, f.context)
        val count = f.workspace.changeCount()
        f.ok("workspace.restore")
        f.assess("workspace.empty_trash")
        assertTrue(f.trash().isEmpty())
        assertEquals(count, f.workspace.changeCount())
        // Then the owner opens the trash screen, the first look since the start that tidies (W3): the tidy lists the
        // lost content again and clears what was staged. That changed files the command could read.
        f.workspace.tidyTrash()
        assertEquals(listOf(LOST_ID), f.trash().map { it.id })
        assertFalse(f.file(".trash/.staging/half-written").exists())
        assertEquals(count + 1, f.workspace.changeCount(), "the tidy on a first look is counted when it has ended (W1)")
        assertNotNull(asked.prepared!!.changedSince())
        assertToldToAskAnew(tool.execute(args, f.context, asked))
        assertTrue(f.shell.runs.isEmpty(), "it never reached the shell")

        // Asked anew, as the refusal says, it runs: a second look tidies nothing and counts for nothing.
        val again = tool.assess(args, f.context)
        f.workspace.tidyTrash()
        assertEquals(listOf(LOST_ID), f.trash().map { it.id })
        assertNull(f.workspace.trashLeftovers())
        assertEquals(count + 1, f.workspace.changeCount())
        assertNull(again.prepared!!.changedSince())
        assertTrue(tool.execute(args, f.context, again).ok)
        assertEquals(1, f.shell.runs.size)
    }

    @Test
    fun `a look at a trash that has nothing to tidy is no change - the command that waits runs`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("notes.md", "notes")
        f.ok("workspace.trash", "paths" to listOf("notes.md"))
        val tool = f.tool("scripts.run_shell")
        val args = json("command" to "ls")

        val asked = tool.assess(args, f.context)
        val count = f.workspace.changeCount()
        // Both looks, and an agent's own look through its tool.
        assertEquals(1, f.trash().size)
        assertNull(f.workspace.trashLeftovers())
        assertTrue(f.ok("workspace.restore").ok)
        assertEquals(count, f.workspace.changeCount(), "looking changes nothing")
        assertNull(asked.prepared!!.changedSince())
        assertTrue(tool.execute(args, f.context, asked).ok)
    }

    @Test
    fun `while a command runs another one is refused unasked, the trash claims and tidies nothing - the look after it counts as a change`() = runBlocking<Unit> {
        val f = OperatorFixture()
        leaveLeftovers(f)
        val running = startCommand(f, "sleep 20")
        try {
            // R5: a command asked for now could never run, so the owner is not asked for it.
            assertTrue("Another shell command is running. Nothing was asked and nothing was run." in f.rejected("scripts.run_shell", "command" to "echo second"))
            // W2 and W3: looking waits for nothing, claims no leftovers and tidies nothing under the command's feet,
            // through the workspace and through an agent's tools.
            assertTrue(withTimeout(5_000) { f.trash() }.isEmpty())
            assertNull(withTimeout(5_000) { f.workspace.trashLeftovers() })
            assertEquals("The workspace trash lists no items.", withTimeout(5_000) { f.ok("workspace.restore") }.content)
            withTimeout(5_000) { f.workspace.tidyTrash() }
            assertTrue(f.file(".trash/.staging/half-written").exists())
            assertFalse(f.file(".trash/$LOST_ID.json").exists())
            // And the refusal stays the same however often it is asked meanwhile: no look made the command "not running".
            assertTrue("Another shell command is running." in f.rejected("scripts.run_shell", "command" to "echo second"))
        } finally {
            running.release.countDown()
        }
        assertTrue(withTimeout(20_000) { running.command.await() }.ok)
        assertFalse(f.workspace.commandRunning())

        // The second agent asks again, as it was told. Before the owner answers, the owner's trash screen looks for the
        // first time since the start (it refreshes when the command has ended): now the tidy runs, and it is a change.
        val tool = f.tool("scripts.run_shell")
        val second = json("command" to "echo second")
        val asked = tool.assess(second, f.context)
        val count = f.workspace.changeCount()
        f.workspace.tidyTrash()
        assertEquals(listOf(LOST_ID), f.trash().map { it.id })
        assertNull(f.workspace.trashLeftovers(), "what could be listed again is no leftover")
        assertFalse(f.file(".trash/.staging/half-written").exists())
        assertEquals(count + 1, f.workspace.changeCount())
        assertToldToAskAnew(tool.execute(second, f.context, asked))
        assertEquals(listOf("sleep 20"), f.shell.runs.map { it.command })

        // Asked once more, nothing is left to tidy: it is put to the owner and runs.
        assertTrue(f.ok("scripts.run_shell", "command" to "echo second").ok)
        assertEquals(listOf("sleep 20", "echo second"), f.shell.runs.map { it.command })
    }

    // ---- R1 with J2 ----

    /** Characters that draw like a quote mark and are shown by their code since round 4 (J2), each with the codes that must appear. */
    private val lookAlikes: List<Pair<String, List<Int>>> = listOf(
        cp(0x1F676) to listOf(0x1F676),
        cp(0x1F677) to listOf(0x1F677),
        cp(0x1F678) to listOf(0x1F678),
        "᳓" to listOf(0x1CD3),
        // A half-width sound mark that follows no half-width katakana letter.
        "ﾞ" to listOf(0xFF9E),
        "ﾟ" to listOf(0xFF9F),
        // Two apostrophes or backticks with a combining mark between them: the mark is shown by its code too.
        "'̣'" to listOf(0x0027, 0x0323, 0x0027),
        "`̈`" to listOf(0x0060, 0x0308, 0x0060),
        // The apostrophes of other scripts, two in a row.
        "՚՚" to listOf(0x055A, 0x055A),
        "ߴߴ" to listOf(0x07F4, 0x07F4),
        "ߵߵ" to listOf(0x07F5, 0x07F5),
    )

    private fun cp(code: Int) = String(Character.toChars(code))

    private fun marked(codes: List<Int>) = codes.joinToString("") { "⟨U+%04X⟩".format(it) }

    /**
     * A script whose text tries to pass for the approval's own line about a harmless script, with [quote] as its quote
     * mark: the line stands in a here-document, so it is text for the shell and is drawn at the start of a line.
     */
    private fun faking(quote: String) = "cat <<'EOF'\nScript ${quote}safe.sh$quote (10 bytes), all of it: ${quote}echo safe$quote\nEOF\nrm -r keep\n"

    private fun scriptLines(a: ActionAssessment) = a.details.filter { it.startsWith("Script ") || it.startsWith("Seems to run ") }

    @Test
    fun `a script's text cannot pass for the line that names the script that runs - look-alikes are shown by their code in every script line`() = runBlocking<Unit> {
        for ((quote, codes) in lookAlikes) {
            val f = OperatorFixture()
            val text = faking(quote)
            f.put("tidy.sh", text)
            val bytes = text.toByteArray().size

            // The plain form: the one line that names the script that runs is the app's own, and it shows the text exactly.
            val plain = f.assess("scripts.run_shell", "command" to "sh tidy.sh")
            val certain = "Script \"tidy.sh\" ($bytes bytes), all of it: ${PreviewText.quoted(text)}"
            assertEquals(listOf(certain), scriptLines(plain), plain.preview)
            assertFalse(notPlain in plain.details, plain.preview)

            // Any other command: the same file is shown as a reading, after the line that says so.
            val other = f.assess("scripts.run_shell", "command" to "sh -x tidy.sh")
            val reading = "Seems to run \"tidy.sh\" ($bytes bytes); all of that file: ${PreviewText.quoted(text)}"
            assertEquals(listOf(reading), scriptLines(other), other.preview)
            assertEquals(notPlain, other.details[other.details.indexOf(anyFile) + 1], other.preview)

            for (line in listOf(certain, reading)) {
                // The faked line is drawn on a line of its own (a line break is shown and kept), but its quote marks are codes.
                val drawn = line.split('\n')
                assertEquals("Script ${marked(codes)}safe.sh${marked(codes)} (10 bytes), all of it: ${marked(codes)}echo safe${marked(codes)}⏎", drawn[1], line)
                assertFalse(quote in line, "the look-alike ${marked(codes)} is drawn as it is in: $line")
                // So only the app's own first line reads: Script "name" (n bytes) … with real quote marks.
                assertEquals(1, drawn.count { Regex("^(Script|Seems to run) \"[^\"]+\" \\(\\d+ bytes\\)").containsMatchIn(it) }, line)
            }
        }
    }

    @Test
    fun `look-alikes in a command and in a script's name are shown by their code in the command line and in the reading`() = runBlocking<Unit> {
        // Armenian apostrophes, two in a row, draw like a closing quote: the name seems to end at "tidy".
        val name = "tidy՚՚.sh"
        val f = OperatorFixture()
        f.put(name, "echo tidying\n")
        val shownName = "tidy⟨U+055A⟩⟨U+055A⟩.sh"

        val a = f.assess("scripts.run_shell", "command" to "sh $name")
        // Not the plain form (its characters are not all plain), so nothing is claimed about which script runs.
        assertTrue(notPlain in a.details, a.preview)
        assertTrue("Command: \"sh $shownName\"" in a.details, a.preview)
        assertEquals(listOf("Seems to run \"$shownName\" (13 bytes); all of that file: ${PreviewText.quoted("echo tidying\n")}"), scriptLines(a), a.preview)
        assertTrue(a.preview.none { it == '՚' }, a.preview)

        // A combining mark on punctuation, inside a quoted command: the fixed lines around it are the app's own and unchanged.
        val marks = f.assess("scripts.run_shell", "command" to "echo done.̎")
        assertTrue("Command: \"echo done.⟨U+030E⟩\"" in marks.details, marks.preview)
        assertEquals(
            listOf(anyFile, notPlain, noScript, "If anything in the workspace changes before it runs, it does not run."),
            marks.details.dropWhile { it != anyFile },
            marks.preview,
        )
    }

    // ---- R4 with J3 (and D3, which uses the same rule in the runners) ----

    @Test
    fun `a text that cannot be handed to a program exactly is refused in one wording and shown by its code wherever it is quoted`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("sub/x.txt", "x")
        for (text in listOf("echo keep\uD800", "rm keep\uDC00 *", "echo a\uDC00\uD800b", "ls\u0000 -la")) {
            val problem = assertNotNull(ExactText.problem(text))
            val expected = "\"command\" contains $problem. Nothing was asked and nothing was run. Write it without that character."
            for (id in listOf("scripts.run_shell", "termux.run")) {
                val refusal = f.rejected(id, "command" to text)

                assertEquals(expected, refusal, id)
                // The executor hands a refusal on to the model up to this length: nothing of it is cut.
                assertTrue(refusal.length <= 300, "${refusal.length} characters")
                assertTrue(refusal.none { it.isSurrogate() || it == '\u0000' }, "the character is named by its code, never repeated: $refusal")
            }
            // What a preview makes of the same text: the same character, by the same code, first.
            val code = Regex("U\\+[0-9A-F]{4}").find(problem)!!.value
            assertEquals("⟨$code⟩", Regex("⟨U\\+[0-9A-F]{4}⟩").find(PreviewText.quoted(text))!!.value)
        }
        assertEquals(
            "\"directory\" contains ${ExactText.problem("sub\uD800")}. Nothing was asked and nothing was run. Write it without that character.",
            f.rejected("scripts.run_shell", "command" to "ls", "directory" to "sub\uD800"),
        )
        assertTrue(f.shell.runs.isEmpty() && f.termux.runs.isEmpty())

        // Tools that only quote such a text (nothing is handed to a program) show it by its code in what the owner reads.
        val script = f.assess("scripts.run_js", "code" to "var s = 'keep\uD800'; s.length")
        assertTrue("Code: \"var s = 'keep⟨U+D800⟩'; s.length\"" in script.details, script.preview)
        assertTrue(script.preview.none { it.isSurrogate() }, script.preview)
    }

    private companion object {
        const val LOST_ID = "1700000000000-abc12345"
    }
}
