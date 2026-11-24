package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.RelativePath
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F3: the names read out of a command line or a script, resolved as the shell would for every static spelling. */
class ShellCommandsTest {
    private val places = ShellCommands.Places(listOf("/data/ws", "/real/ws"), RelativePath.of("proj"))

    private fun scripts(command: String) = ShellCommands.names(command, places).filter { it.must }

    private fun paths(names: List<ShellCommands.Name>) =
        names.flatMap { it.targets }.map { (it as? ShellCommands.Target.Entry)?.path?.value ?: (it as ShellCommands.Target.Pattern).let { p -> "${p.base.value}|${p.text}" } }

    @Test
    fun `a script's name is resolved as the shell reads it`() {
        val same = listOf(
            "sh run.sh", "sh ./run.sh", "sh \"run.sh\"", "sh 'run'.sh", "sh r\\un.sh", "sh ru''n.sh", "sh x/../run.sh", "sh ./x/y/../.././run.sh",
            "sh /data/ws/proj/run.sh", "sh /real/ws/proj/run.sh", "sh /data/ws/other/../proj/run.sh", "sh \$HOME/run.sh", "sh \${HOME}/run.sh", "sh ~/run.sh",
            "sh \$PWD/run.sh", "sh \"\$PWD\"/run.sh", "sh \$(pwd)/run.sh", "sh `pwd`/run.sh", "sh \$(echo run.sh)", "sh `echo run.sh`", "f=run.sh; sh \$f",
            "f=run.sh; sh \"\${f}\"", "export f=run.sh; sh \$f", "d=\$HOME; sh \$d/run.sh", ". run.sh", "source ./run.sh", "bash -x -e run.sh arg", "sh -o errexit run.sh",
            "sh -- run.sh", "/system/bin/sh run.sh", "env A=1 sh run.sh", "toybox sh run.sh", "nice -n 5 sh run.sh", "timeout 5 sh run.sh", "find . -exec sh run.sh {} ;",
            "xargs -n 1 sh run.sh", "sh < run.sh", "sh -s < run.sh", "sh 0< run.sh", "ls && sh run.sh", "if true; then sh run.sh; fi", "x=1 sh run.sh",
            "bash --rcfile run.sh -c ls", "awk -f run.sh data", "awk -frun.sh data", "sed -f run.sh data", "sed -n -f run.sh data", "sed -nf run.sh data", "sed --file=run.sh data",
        )
        for (command in same) {
            val found = scripts(command)
            assertEquals(listOf("proj/run.sh"), paths(found), command)
            assertNull(found.single().problem, command)
        }
        assertEquals(listOf("run.sh"), paths(ShellCommands.names("sh run.sh", ShellCommands.Places(listOf("/data/ws"), RelativePath.ROOT)).filter { it.must }))
        assertEquals(listOf(RelativePath.of("proj/x")), (scripts("sh x/../run.sh").single().targets.single()).crossed)
        // Several values, several scripts.
        assertEquals(listOf("proj/a.sh", "proj/b.sh"), paths(scripts("for s in a.sh b.sh; do sh \$s; done")))
        assertEquals(listOf("proj/a.sh", "proj/b.sh"), paths(scripts("f=a.sh; [ -f c ] && f=b.sh; sh \$f")))
        // A pattern is left to be expanded against the workspace, from the folder its first pattern name is in.
        assertEquals(listOf("proj|proj/r*.sh"), paths(scripts("sh r*.sh")))
        assertEquals(listOf("proj/tools|proj/tools/r?n.[sb]h"), paths(scripts("sh tools/r?n.[sb]h")))
        assertEquals(listOf("proj/r*.sh"), paths(scripts("sh 'r*.sh'")), "a quoted star is a plain character")
        assertEquals(listOf("proj|proj/r*.sh"), paths(scripts("f='r*.sh'; sh \$f")), "unquoted, a variable's value is expanded as a pattern")
        assertEquals(listOf("proj/r*.sh"), paths(scripts("f='r*.sh'; sh \"\$f\"")))
        // What a command string or evaluated text runs is read the same way.
        assertEquals(listOf("proj/run.sh"), paths(scripts("sh -c 'sh run.sh'")))
        assertEquals(listOf("proj/run.sh"), paths(scripts("eval 'sh run.sh'")))
        assertEquals(listOf("proj/run.sh"), paths(scripts("f=run.sh; sh -c \"sh \$f\"")))
        assertEquals(listOf("proj/run.sh"), paths(scripts("sh <<EOF\n. ./run.sh\nEOF")))
        // No script: a command string, an option's value, other programs' -f.
        for (command in listOf("sh -c 'ls'", "sh -c ls run.sh", "rm -f run.sh", "cut -f 2 run.sh", "tail -f run.sh", "grep -f run.sh x", "echo sh run.sh", "ls .")) {
            assertTrue(scripts(command).isEmpty(), command)
        }
    }

    @Test
    fun `a script that is not a static name inside the workspace is reported, never guessed`() {
        val unresolved = mapOf(
            "sh /proc/self/cwd/run.sh" to ShellCommands.OUTSIDE, "sh /data/wsx/run.sh" to ShellCommands.OUTSIDE, "sh ../../run.sh" to ShellCommands.OUTSIDE,
            "sh /data/ws/../run.sh" to ShellCommands.OUTSIDE, "sh ~root/run.sh" to ShellCommands.RUN_TIME, "sh \$x" to ShellCommands.RUN_TIME,
            "sh \"\$1\"" to ShellCommands.RUN_TIME, "sh \${f:-run.sh}" to ShellCommands.RUN_TIME, "sh \$(cat name)" to ShellCommands.RUN_TIME,
            "sh \$(echo run.sh; echo x)" to ShellCommands.RUN_TIME, "sh \$(echo -n run.sh)" to ShellCommands.RUN_TIME, "sh run.\$((1+1))" to ShellCommands.RUN_TIME,
            "f=run.sh; read f; sh \$f" to ShellCommands.RUN_TIME, "f=run.sh; f=\$(cat n); sh \$f" to ShellCommands.RUN_TIME, "f=run.sh; eval f=x; sh \$f" to ShellCommands.RUN_TIME,
            "f=run.sh; unset f; sh \$f" to ShellCommands.RUN_TIME, "f=run.sh; : \${f:=x.sh}; sh \$f" to ShellCommands.RUN_TIME, "f=run.sh; f+=x; sh \$f" to ShellCommands.RUN_TIME,
            "f=run.sh; IFS=n; sh \$f" to ShellCommands.RUN_TIME, "f=run.sh; . ./lib; sh \$f" to ShellCommands.RUN_TIME, "f='a b'; sh \$f" to ShellCommands.RUN_TIME,
            "f=run.sh cat; sh \$f" to ShellCommands.RUN_TIME, "sh {a,b}.sh" to ShellCommands.RUN_TIME, "sh r*/../run.sh" to ShellCommands.RUN_TIME,
            "sh -c 'sh \$f'" to ShellCommands.RUN_TIME, "f=run.sh; sh -c 'sh \$f'" to ShellCommands.RUN_TIME,
        )
        for ((command, why) in unresolved) {
            val found = scripts(command).filter { it.problem != null }
            assertEquals(1, found.size, command)
            assertEquals(why, found.single().problem, command)
            assertTrue(found.single().targets.isEmpty(), command)
        }
        // After a cd, a relative name or one from the working folder is not known; one from the home folder still is.
        for (command in listOf("cd x && sh run.sh", "cd x; sh \$PWD/run.sh", "(cd x; sh ./run.sh)", "sh -c 'cd x'; sh run.sh", "pushd x; . run.sh")) {
            assertTrue(scripts(command).single().problem!!.startsWith("the command changes folder with cd"), command)
        }
        assertEquals(listOf("proj/run.sh"), paths(scripts("cd x && sh ~/run.sh")))
        assertEquals(listOf("proj/run.sh"), paths(scripts("cd x && sh /data/ws/proj/run.sh")))
    }

    @Test
    fun `other names are returned for pinning, scripts by their ending among them`() {
        val names = ShellCommands.names("wc -l notes.md \"my file.txt\" && cat lib.sh | sh; x=out/data.csv ./tool arg > log.txt", places)
        fun flags(path: String) = names.filter { n -> n.targets.any { (it as? ShellCommands.Target.Entry)?.path?.value == path } }
        assertTrue(flags("proj/notes.md").let { it.isNotEmpty() && it.none { n -> n.asScript } })
        assertTrue(flags("proj/my file.txt").isNotEmpty())
        assertTrue(flags("proj/lib.sh").any { it.asScript && it.shell && !it.must })
        assertTrue(flags("proj/out/data.csv").isNotEmpty())
        assertTrue(flags("proj/log.txt").isNotEmpty())
        assertTrue(flags("proj/tool").any { it.asScript && it.onlyExisting }, "a program started by its path is shown when it is a workspace file")
        assertFalse(names.any { it.raw == "-l" })
        // Only the scripts of a script's text are followed.
        assertEquals(listOf("proj/b.sh"), paths(ShellCommands.names("echo notes.md\n. ./b.sh\n", places, everyWord = false)))
    }

    @Test
    fun `patterns match as the shell matches file names`() {
        fun segment(pattern: String) = (scripts("sh $pattern").single().targets.single() as ShellCommands.Target.Pattern).segments.single()
        assertTrue(segment("t*.sh").matches("tidy.sh") && segment("t*.sh").matches("t.sh") && !segment("t*.sh").matches("a.sh") && !segment("t*.sh").matches("tidy.sh.bak"))
        assertTrue(segment("tid?.sh").matches("tidy.sh") && !segment("tid?.sh").matches("tid.sh"))
        assertTrue(segment("tidy.s[h]").matches("tidy.sh") && !segment("tidy.s[h]").matches("tidy.sx"))
        assertTrue(segment("[a-c]x").matches("bx") && !segment("[a-c]x").matches("dx") && segment("[!a-c]x").matches("dx") && !segment("[!a-c]x").matches("ax"))
        assertTrue(!segment("*.sh").matches(".hidden.sh") && segment(".*.sh").matches(".hidden.sh"))
        assertTrue(segment("a.*").matches("a.b") && !segment("a.*").matches("ab"), "a dot is a dot")
    }
}
