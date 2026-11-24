package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.RelativePath
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R1 of round 4: the test for the plain form `sh file [arguments]`, the only form of command
 * for which the approval calls a file "the script this command runs". It is an allow-list, so
 * the lists here are of two kinds: the few spellings that have the form, and near misses of
 * every kind, each of which must not have it.
 */
class ShellPlainFormTest {
    @Test
    fun `the text has the plain form only as sh, one blank, a relative script, and arguments, all in plain characters`() {
        val plain = mapOf(
            "sh f" to "f", "sh ./f" to "./f", "sh f arg" to "f", "sh f -x --flag a/b 1.2_3 -" to "f", "sh\tf\targ" to "f", "sh f\targ" to "f", "  sh f  " to "f", "sh f\n" to "f",
            "\n\tsh f \n" to "f", "sh tools/tidy.sh" to "tools/tidy.sh", "sh tools//./tidy.sh" to "tools//./tidy.sh", "sh sh" to "sh", "sh .hidden" to ".hidden", "sh 2" to "2",
            "sh a.b/c_d/e-f.sh x" to "a.b/c_d/e-f.sh", "sh ..." to "...", "sh a..b" to "a..b", "sh f sh -c id" to "f",
        )
        for ((command, script) in plain) assertEquals(script, ShellPlainForm.scriptWord(command), command.replace("\n", "\\n").replace("\t", "\\t"))

        val notPlain = listOf(
            // Quotes, backslashes, expansions.
            "sh 'f'", "sh \"f\"", "sh f\\ g", "sh f\\", "sh \$x", "sh \${x}", "sh f \$1", "sh f `id`", "sh f \$(id)", "sh ~/f", "sh f ~", "sh f*", "sh f?", "sh [f]", "sh {f,g}",
            "sh f {a}", "sh f=1", "sh f a=1", "X=1 sh f", "sh f!", "sh f%", "sh f+", "sh f:g", "sh f,g", "sh f@", "sh f^", "sh f #c", "sh f#",
            // A second command, a redirection, a job in the background.
            "sh f; id", "sh f;id", "sh f && id", "sh f || id", "sh f | cat", "sh f &", "sh f\nid", "sh f\n\nid", "sh f\rid", "sh f\r", "sh f > out", "sh f < in", "sh f 2>&1",
            "(sh f)", "sh f)", "{ sh f; }",
            // The shell's options, and anything but the bare word sh in front.
            "sh -x f", "sh - f", "sh -- f", "sh -c f", "sh +x f", "sh -", "sh", "sh ", "bash f", "ash f", "/system/bin/sh f", "./sh f", "SH f", "Sh f", "exec sh f", "command sh f",
            "nice sh f", "env sh f", ". f", ". ./f", "source f", "f", "./f", "shf", "sh.exe f",
            // The script's own name.
            "sh ../f", "sh a/../f", "sh ..", "sh a/..", "sh /abs/f", "sh /f", "sh f/", "sh f/.", "sh .", "sh ./", "sh /", "sh a/./",
            // Blanks: exactly one between two words, and only space and tab.
            "sh  f", "sh f  arg", "sh \tf", "sh\t\tf", "sh\u000Bf", "sh\u000Cf", "sh\u00A0f", "sh f\u00A0", "\u00A0sh f", "sh\u2003f", "sh f\u200B", "\u200Bsh f", "sh f\u0000",
            "sh f\u0000 x", "\rsh f",
            // Characters outside ASCII, look-alikes of the allowed ones among them.
            "sh größe", "sh f\u2010g", "sh f\uFF0Fg", "sh f\u2024sh", "ｓｈ f", "sh f\u0301", "sh \uD83D\uDE00", "sh f\uD800",
            "",
        )
        for (command in notPlain) assertNull(ShellPlainForm.scriptWord(command), command.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r"))
    }

    @Test
    fun `the script must be an existing regular workspace file that is no link and is not reached through one`() = runBlocking<Unit> {
        val f = OperatorFixture()
        f.put("f", "echo f\n")
        f.put("tools/tidy.sh", "echo tools\n")
        f.put("tools/deep/down.sh", "echo down\n")
        f.put("sh", "echo a file called sh\n")
        f.put("folder/x.txt", "x")
        f.put("2", "echo two\n")
        f.put(".trash/x.sh", "echo trashed\n")
        val elsewhere = Files.createTempDirectory("aicc-elsewhere").toFile().also { java.io.File(it, "out.sh").writeText("echo outside\n") }
        Files.createSymbolicLink(f.file("link.sh").toPath(), f.file("f").toPath())
        Files.createSymbolicLink(f.file("linked").toPath(), f.file("tools").toPath())
        Files.createSymbolicLink(f.file("away").toPath(), elsewhere.toPath())
        Files.createSymbolicLink(f.file("dangling.sh").toPath(), f.file("nothing-there").toPath())
        suspend fun script(command: String, directory: String = "") = ShellPlainForm.script(command, RelativePath.of(directory), f.workspace)?.value

        // Plain: the file, from the folder the command runs in.
        assertEquals("f", script("sh f"))
        assertEquals("f", script("sh ./f"))
        assertEquals("f", script("sh f arg -x"))
        assertEquals("f", script("sh\tf\targ"))
        assertEquals("tools/tidy.sh", script("sh tools/tidy.sh"))
        assertEquals("tools/tidy.sh", script("sh tools//./tidy.sh"))
        assertEquals("tools/tidy.sh", script("sh tidy.sh", "tools"))
        assertEquals("tools/tidy.sh", script("sh ./tidy.sh", "tools"))
        assertEquals("tools/deep/down.sh", script("sh deep/down.sh", "tools"))
        assertEquals("sh", script("sh sh"))
        assertEquals("2", script("sh 2"))

        // Not plain: no such file, a folder, a link, a file reached through a link, the trash, and a name that means another place from this folder.
        for (command in listOf(
            "sh missing.sh", "sh folder", "sh tools", "sh link.sh", "sh dangling.sh", "sh linked/tidy.sh", "sh away/out.sh", "sh .trash/x.sh", "sh tidy.sh", "sh f/x",
            "sh tools/tidy.sh/x", "sh tools/missing/tidy.sh",
        )) assertNull(script(command), command)
        assertNull(script("sh f", "tools"), "f is not in tools")
        assertNull(script("sh sh", "tools"))
        assertNull(script("sh tidy.sh", "linked"), "the folder itself is a link")
        assertNull(script("sh x.sh", ".trash"))
        // And whatever the text rules out is not looked up at all.
        for (command in listOf("sh 'f'", "sh f; id", "sh -x f", "sh ../f", "sh /abs", "sh f\nid", "sh f &", "sh \$x", "sh  f")) assertNull(script(command), command)
        assertNull(script("sh ../f", "tools"), "\"..\" is never followed, also where it would stay inside the workspace")
        assertNull(script("sh ${f.root.canonicalPath}/f"), "nor is the workspace's own absolute path")
    }
}
