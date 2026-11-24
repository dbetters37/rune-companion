package com.aicontrolcenter.framework.operator

import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass 4b, H1 (fourth verification, security new problem 1): the reader of a shell command reads a long word in time
 * that grows with its length, not with its square.
 *
 * Round 4's brace test (`\{.*(,|\.\.)`) ran from every "{" of a word to its end and back, and the older test for a
 * class in a set (`\[[^\]]*\[[:.=]`) did the same from every "[": a command of 8,000 "{" took 8 s to assess and a
 * script of 64,000 "[" that a plain "sh f" runs took 40 s, all inside the assessment, before the owner is asked, and
 * as often as an agent liked. The verifier's probe (verify8/security/src/V8Slow.kt) with its two worst shapes, at the
 * sizes the tool takes: a command of at most 8,000 characters and a script read up to 64 KB.
 *
 * TEST SAFETY: every text has a fixed size, so every assessment ends by itself. Each one runs on a daemon thread that
 * the test waits for at most [BOUND_MS]: without the fix the test fails at that bound instead of waiting minutes, and
 * the thread, left behind, ends by itself or with the test JVM.
 */
class ShellReadingSpeedTest {
    /** The fixed reader takes tens of milliseconds for each of these; the old one took 1.6 s to 90 s. Room for a busy machine. */
    private val BOUND_MS = 1_500L

    /** A fresh JVM spends a while on its first assessment (classes, compilation); that is not what is measured here. */
    @Before
    fun warmUp() {
        val f = OperatorFixture()
        f.put("f", "echo {a,b} [ab] x\n")
        repeat(3) { runBlocking { runCatching { f.assess("scripts.run_shell", "command" to "sh f") } } }
    }

    /** Assesses [command] in a workspace that holds [files], and returns how long it took; fails when that is over [BOUND_MS]. */
    private fun assessTime(command: String, files: Map<String, String> = emptyMap()): Long {
        val f = OperatorFixture()
        f.put("a", "echo a\n")
        files.forEach { (name, text) -> f.put(name, text) }
        val done = CountDownLatch(1)
        var outcome = "did not end"
        val thread = Thread {
            outcome = try {
                runBlocking { f.assess("scripts.run_shell", "command" to command) }
                "asked"
            } catch (e: ToolCallRejected) {
                "refused"
            } catch (e: Throwable) {
                "threw ${e::class.simpleName}"
            }
            done.countDown()
        }
        thread.isDaemon = true
        val started = System.nanoTime()
        thread.start()
        val ended = done.await(BOUND_MS, TimeUnit.MILLISECONDS)
        val ms = (System.nanoTime() - started) / 1_000_000
        val shown = command.take(20) + if (command.length > 20) "… (${command.length} characters)" else ""
        assertTrue(ended, "assessing \"$shown\" (files: ${files.mapValues { it.value.length }}) did not end within $BOUND_MS ms")
        assertTrue(outcome == "asked" || outcome == "refused", "\"$shown\": $outcome")
        println("$ms ms ($outcome): \"$shown\", files ${files.mapValues { it.value.length }}")
        return ms
    }

    @Test(timeout = 60_000)
    fun `a command of 8,000 open braces or brackets is assessed in well under a second`() {
        assessTime("echo " + "{".repeat(7_995))
        assessTime("echo " + "[".repeat(7_995))
        // The same with what makes a brace a list at the very end, and a class at the very end.
        assessTime("echo " + "{".repeat(7_990) + ",")
        assessTime("echo " + "[".repeat(7_990) + "[:a:]")
        assessTime("echo " + "[".repeat(7_990) + "]")
    }

    @Test(timeout = 60_000)
    fun `a script of 64,000 open braces or brackets that a plain sh f runs is assessed in well under a second`() {
        assessTime("sh f", mapOf("f" to "{".repeat(64_000) + "\n"))
        assessTime("sh f", mapOf("f" to "[".repeat(64_000) + "\n"))
        assessTime("sh f", mapOf("f" to "echo " + "{".repeat(63_990) + ",\n"))
        assessTime("sh f", mapOf("f" to "echo " + "[".repeat(63_990) + "]\n"))
    }

    @Test(timeout = 60_000)
    fun `a pattern name of 64,000 open brackets is made into a pattern at once`() {
        // A set that is never closed is a plain "[" to the shell; the pattern made of such a name is built in one pass.
        val text = "[".repeat(64_000)
        val segment = ShellCommands.Segment(text, BooleanArray(text.length) { true })
        val started = System.nanoTime()
        assertTrue(segment.matches(text))
        assertTrue(!segment.matches("a"))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < BOUND_MS, "took $ms ms")
    }

    @Test
    fun `the linear brace and class tests answer as the regular expressions did`() {
        val braces = Regex("""\{.*(,|\.\.)""", RegexOption.DOT_MATCHES_ALL)
        val classes = Regex("""\[[^\]]*\[[:.=]""")
        val alphabet = "{},.[]:=!^ab\u0001/"
        val random = Random(4242)
        repeat(200_000) {
            val text = buildString { repeat(random.nextInt(0, 12)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            assertEquals(braces.containsMatchIn(text), ShellSyntax.bracesExpand(text), "braces: \"$text\"")
            assertEquals(classes.containsMatchIn(text), ShellCommands.hasCharacterClass(text), "class: \"$text\"")
        }
        for (text in listOf("{a,b}", "x{a/,{x}}b", "{1..3}", "{a}", "a,b{", "{.a.}", "{\u0001,", "[[:alpha:]]", "[a[.b", "[x][:y]", "[[", "[]:", "a[=", "[[=")) {
            assertEquals(braces.containsMatchIn(text), ShellSyntax.bracesExpand(text), "braces: \"$text\"")
            assertEquals(classes.containsMatchIn(text), ShellCommands.hasCharacterClass(text), "class: \"$text\"")
        }
    }
}
