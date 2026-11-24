package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.RelativePath
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader of shell text (F3 of round 2, G4 of round 3): the names it reads out of a command
 * line or a script, resolved as the shell would for a static spelling, and what it reports
 * instead of guessing: a script it cannot resolve, a doubt about what the text means, a text
 * that makes relative names uncertain.
 */
class ShellCommandsTest {
    private val places = ShellCommands.Places(listOf("/data/ws", "/real/ws"), RelativePath.of("proj"))

    private fun read(command: String) = ShellCommands.read(command, places)

    private fun scripts(command: String) = read(command).names.filter { it.must }

    private fun paths(names: List<ShellCommands.Name>) =
        names.flatMap { it.targets }.map { (it as? ShellCommands.Target.Entry)?.path?.value ?: (it as ShellCommands.Target.Pattern).let { p -> "${p.base.value}|${p.text}" } }

    /** [command] is read without a doubt, and names exactly this one script, which a shell (or awk, sed) certainly runs. */
    private fun assertRuns(path: String, command: String) {
        val reading = read(command)
        assertEquals(emptyList(), reading.doubts, command)
        val found = reading.names.filter { it.must }
        assertEquals(listOf(path), paths(found), command)
        assertNull(found.single().problem, command)
    }

    @Test
    fun `a script's name is resolved as the shell reads it`() {
        val same = listOf(
            "sh run.sh", "sh ./run.sh", "sh \"run.sh\"", "sh 'run'.sh", "sh r\\un.sh", "sh ru''n.sh", "sh x/../run.sh", "sh ./x/y/../.././run.sh",
            "sh /data/ws/proj/run.sh", "sh /real/ws/proj/run.sh", "sh /data/ws/other/../proj/run.sh",
            "sh \$PWD/run.sh", "sh \"\$PWD\"/run.sh", "sh \$(pwd)/run.sh", "sh `pwd`/run.sh", "sh \$(echo run.sh)", "sh `echo run.sh`", "f=run.sh; sh \$f",
            "f=run.sh; sh \"\${f}\"", "export f=run.sh; sh \$f", "d=\$PWD; sh \$d/run.sh", ". ./run.sh", "source ./run.sh", "bash -x -e run.sh arg", "sh -o errexit run.sh",
            "sh -- run.sh", "/system/bin/sh run.sh", "env A=1 sh run.sh", "toybox sh run.sh", "nice -n 5 sh run.sh", "timeout 5 sh run.sh", "find . -exec sh run.sh {} ;",
            "xargs -n 1 sh run.sh", "sh < run.sh", "sh -s < run.sh", "sh 0< run.sh", "ls && sh run.sh", "if true; then sh run.sh; fi", "x=1 sh run.sh",
            "bash --rcfile run.sh -c ls", "awk -f run.sh data", "awk -frun.sh data", "sed -f run.sh data", "sed -n -f run.sh data", "sed -nf run.sh data", "sed --file=run.sh data",
        )
        for (command in same) assertRuns("proj/run.sh", command)
        assertEquals(listOf("run.sh"), paths(ShellCommands.read("sh run.sh", ShellCommands.Places(listOf("/data/ws"), RelativePath.ROOT)).names.filter { it.must }))
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
        assertRuns("proj/run.sh", "sh -c 'sh run.sh'")
        assertRuns("proj/run.sh", "eval 'sh run.sh'")
        assertRuns("proj/run.sh", "f=run.sh; sh -c \"sh \$f\"")
        assertRuns("proj/run.sh", "sh <<EOF\n. ./run.sh\nEOF")
        assertRuns("proj/run.sh", "sh <<'EOF'\n. ./run.sh\nEOF")
        assertRuns("proj/run.sh", "trap 'sh run.sh' EXIT")
        // No script: a command string, an option's value, other programs' -f.
        for (command in listOf("sh -c 'ls'", "sh -c ls run.sh", "rm -f run.sh", "cut -f 2 run.sh", "tail -f run.sh", "grep -f run.sh x", "echo sh run.sh", "ls .", "trap - INT", "trap '' HUP")) {
            assertTrue(scripts(command).isEmpty(), command)
            assertEquals(emptyList(), read(command).doubts, command)
        }
    }

    @Test
    fun `the shell's own options are read as mksh reads them, and an unknown one is a doubt`() {
        // The verifier's spellings: -o takes its name also inside a group, a lone "-" ends the options.
        for (command in listOf(
            "sh -xo errexit run.sh", "sh -eo errexit run.sh", "sh -eo pipefail -x run.sh", "sh -oerrexit run.sh", "sh +o errexit run.sh", "sh - run.sh",
            "sh -e - run.sh", "sh -l run.sh", "sh -i run.sh", "sh -li run.sh", "sh -m run.sh", "sh -eo monitor run.sh", "sh -abCefhiklmnpruUvxX run.sh", "sh \"-e\" run.sh",
        )) assertRuns("proj/run.sh", command)
        // With -c the first word after the options is the command string, wherever -c stands; with -s there is no script.
        assertRuns("proj/run.sh", "sh -ec 'sh run.sh' name")
        assertRuns("proj/run.sh", "sh -c -e 'sh run.sh'")
        assertRuns("proj/run.sh", "sh -o errexit -c 'sh run.sh'")
        assertRuns("proj/run.sh", "sh -l -c 'sh run.sh'")
        assertTrue(scripts("sh -s other.sh < run.sh").let { paths(it) == listOf("proj/run.sh") })
        // Not known: refused rather than guessed at.
        for (command in listOf("sh -T x run.sh", "sh -O extglob run.sh", "bash --norc run.sh", "sh --login run.sh", "sh -q run.sh", "sh -eZ run.sh", "sh -o 'a b' run.sh", "sh -o \$x run.sh")) {
            assertTrue(read(command).doubts.any { "option this tool does not know" in it }, "$command: ${read(command).doubts}")
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
            "f=run.sh cat; sh \$f" to ShellCommands.RUN_TIME, "f=run.sh; typeset -n r=f; r=other.sh; sh \$f" to ShellCommands.RUN_TIME,
            "f=run.sh; nameref r=f; r=other.sh; sh \$f" to ShellCommands.RUN_TIME, "sh {a,b}.sh" to ShellCommands.RUN_TIME, "sh r*/../run.sh" to ShellCommands.RUN_TIME,
            "sh -c 'sh \$f'" to ShellCommands.RUN_TIME, "f=run.sh; sh -c 'sh \$f'" to ShellCommands.RUN_TIME,
            // A word that expands to nothing is no word, and the shell takes the next one (the verifier's "E=; sh $E FILE").
            "E=; sh \$E run.sh" to ShellCommands.RUN_TIME, "E=; sh \$E /proc/self/cwd/run.sh" to ShellCommands.RUN_TIME, "o=-x; sh \$o run.sh" to ShellCommands.RUN_TIME,
            "sh '' run.sh" to ShellCommands.RUN_TIME,
            // G3: the home folder is outside the workspace.
            "sh ~/run.sh" to ShellCommands.HOME, "sh \$HOME/run.sh" to ShellCommands.HOME, "sh \${HOME}/run.sh" to ShellCommands.HOME, "d=\$HOME; sh \$d/run.sh" to ShellCommands.HOME,
            ". ~/run.sh" to ShellCommands.HOME, "sh -c 'sh ~/run.sh'" to ShellCommands.HOME, "sh -c \"sh \$HOME/run.sh\"" to ShellCommands.HOME,
            // Patterns the shell matches and this tool does not.
            "sh [[:alpha:]]un.sh" to ShellCommands.PATTERN, "sh run.s[[:lower:]]" to ShellCommands.PATTERN, ". ./[[:alpha:]]un.sh" to ShellCommands.PATTERN,
            "sh sub/[[:alnum:]]*" to ShellCommands.PATTERN, "sh r[[.u.]]n.sh" to ShellCommands.PATTERN, "sh r[[=u=]]n.sh" to ShellCommands.PATTERN,
            "sh ru[n\\]].sh" to ShellCommands.PATTERN, "sh ru[\"n\"].sh" to ShellCommands.PATTERN, "x=n; sh ru[\$x].sh" to ShellCommands.PATTERN,
            "sh @(ru)n.sh" to ShellCommands.PATTERN, "sh +(run).sh" to ShellCommands.PATTERN, "sh run?(.sh)" to ShellCommands.PATTERN, "sh ru*(n).sh" to ShellCommands.PATTERN,
            "sh !(zzz)" to ShellCommands.PATTERN, ". ./@(run.sh)" to ShellCommands.PATTERN, "f='[[:alpha:]]un.sh'; sh \$f" to ShellCommands.PATTERN,
            // "." and source look a bare name up through PATH.
            ". run.sh" to ShellCommands.NO_SLASH, "source run.sh" to ShellCommands.NO_SLASH, ". \"run.sh\"" to ShellCommands.NO_SLASH, "command . run.sh" to ShellCommands.NO_SLASH,
            // A workspace file under a name of /proc, started as a program.
            "/proc/self/cwd/run.sh" to ShellCommands.OUTSIDE, "/proc/self/root/data/ws/proj/run.sh x" to ShellCommands.OUTSIDE,
            // Another way of naming a program file.
            "awk -f /proc/self/cwd/prog" to ShellCommands.OUTSIDE, "awk -v a=1 -f/proc/self/cwd/prog" to ShellCommands.OUTSIDE, "awk --exec /tmp/prog" to ShellCommands.OUTSIDE,
            "awk -E /tmp/prog" to ShellCommands.OUTSIDE, "awk --include=/tmp/prog 'BEGIN{}'" to ShellCommands.OUTSIDE, "sed -s -n --file /tmp/prog x" to ShellCommands.OUTSIDE,
        )
        for ((command, why) in unresolved) {
            val found = scripts(command).filter { it.problem != null }
            assertEquals(1, found.size, command)
            assertEquals(why, found.single().problem, command)
            assertTrue(found.single().targets.isEmpty(), command)
        }
        // A plain set, a negated one and a pattern in quotes are still matched.
        for (command in listOf("sh ru[n].sh", "sh ru[!]z].sh", "sh r[a-z]n.sh")) assertNull(scripts(command).single().problem, command)
        // Nothing to run is no script.
        for (command in listOf("sh /dev/null", "sh < /dev/null", "cat /dev/null | sh")) assertEquals(emptyList(), scripts(command), command)
    }

    @Test
    fun `the shell under another name, and the programs that start others, are seen through`() {
        for (command in listOf(
            "/proc/self/exe run.sh", "/proc/1234/exe run.sh", "/proc/thread-self/exe run.sh", "/system/bin/linker64 /system/bin/sh run.sh", "linker /system/bin/sh run.sh",
            "uclampset sh run.sh", "uclampset -m 0 -M 512 sh run.sh", "logwrapper sh run.sh", "nice sh run.sh", "nohup sh run.sh", "timeout -s KILL 5 sh run.sh",
            "time sh run.sh", "time -p sh run.sh", "chrt -b 0 sh run.sh", "ionice -c 3 sh run.sh", "taskset 1 sh run.sh", "stdbuf -o0 sh run.sh", "unshare -n sh run.sh",
            "busybox sh run.sh", "toolbox sh run.sh", "setsid sh run.sh", "env -i sh run.sh", "env -u A sh run.sh", "exec sh run.sh", "command -p sh run.sh",
            "nice nohup env X=1 sh run.sh", "find . -name x -exec env sh run.sh ;", "xargs -I {} sh run.sh {}", "setarch x86_64 sh run.sh", "busybox ash run.sh", "mksh run.sh", "nice awk -f run.sh x",
        )) assertRuns("proj/run.sh", command)
        // The same under each name with a script outside the workspace: reported.
        for (command in listOf("/proc/self/exe /proc/self/cwd/run.sh", "/system/bin/linker64 /system/bin/sh /proc/self/cwd/run.sh", "uclampset sh /proc/self/cwd/run.sh", "logwrapper sh /tmp/x.sh")) {
            assertEquals(ShellCommands.OUTSIDE, scripts(command).single().problem, command)
        }
        // A word that only looks like a shell's name, as an argument of an ordinary program, is none.
        for (command in listOf(
            "nice grep sh notes.md", "find . -name sh", "xargs grep -c sh", "env A=1 ls sh", "echo sh run.sh", "nohup cat bash", "timeout 5 grep -r bash .",
            "command -v sh", "command -V bash", "nice -n 5 ls . notes.md", "timeout -k 1 5 ls .", "type sh", "which sh",
        )) {
            assertEquals(emptyList(), scripts(command), command)
            assertEquals(emptyList(), read(command).doubts, command)
        }
    }

    @Test
    fun `a shell must get its commands from something that can be read`() {
        // Read: a file, a here-document, a here-string, cat of plain files, echo of fixed text.
        assertRuns("proj/run.sh", "sh < run.sh")
        assertRuns("proj/run.sh", "sh <> run.sh")
        assertRuns("proj/run.sh", "cat run.sh | sh")
        assertRuns("proj/run.sh", "cat < run.sh | sh")
        assertRuns("proj/run.sh", "cat -- run.sh | sh -e")
        assertRuns("proj/run.sh", "cat run.sh | env sh")
        assertRuns("proj/run.sh", "echo sh run.sh | sh")
        assertRuns("proj/run.sh", "echo 'sh run.sh' | sh")
        assertRuns("proj/run.sh", "sh <<< 'sh run.sh'")
        assertRuns("proj/run.sh", "cat <<'EOF' | sh\nsh run.sh\nEOF")
        assertRuns("proj/run.sh", "eval \"\$(cat run.sh)\"")
        assertRuns("proj/run.sh", "eval \"\$(< run.sh)\"")
        assertRuns("proj/run.sh", "sh -c \"\$(cat run.sh)\"")
        assertEquals(listOf("proj/a.sh", "proj/b.sh"), paths(scripts("cat a.sh b.sh | sh")))
        assertTrue(scripts("eval \"\$(cat run.sh)\"").single().sourced, "what eval reads changes the shell that reads it")
        assertFalse(scripts("sh -c \"\$(cat run.sh)\"").single().sourced)
        assertTrue(scripts(". ./run.sh").single().sourced)
        assertFalse(scripts("sh run.sh").single().sourced)
        // The file must be in the workspace: the verifier's cases with one that is not.
        for (command in listOf(
            "sh <> /proc/self/cwd/run.sh", "cat /proc/self/cwd/run.sh | sh", "cat < /proc/self/cwd/run.sh | sh", "eval \"\$(cat /proc/self/cwd/run.sh)\"",
            "eval \"\$(</proc/self/cwd/run.sh)\"", "sh -c \"\$(cat /tmp/x)\"",
        )) assertEquals(ShellCommands.OUTSIDE, scripts(command).single().problem, command)
        // Not read: a doubt.
        val doubts = mapOf(
            "exec < run.sh; sh" to ShellCommands.DOUBT_NO_INPUT, "exec < /proc/self/cwd/run.sh; sh" to ShellCommands.DOUBT_NO_INPUT,
            "{ sh; } < run.sh" to ShellCommands.DOUBT_NO_INPUT, "{ sh; } < /proc/self/cwd/run.sh" to ShellCommands.DOUBT_NO_INPUT,
            "(sh) < /proc/self/cwd/run.sh" to ShellCommands.DOUBT_NO_INPUT, "cat run.sh | (sh)" to ShellCommands.DOUBT_NO_INPUT,
            "cat run.sh | { read x; sh; }" to ShellCommands.DOUBT_NO_INPUT, "f() { sh; }; f < run.sh" to ShellCommands.DOUBT_NO_INPUT, "sh" to ShellCommands.DOUBT_NO_INPUT,
            "sh -s" to ShellCommands.DOUBT_NO_INPUT, "nice sh" to ShellCommands.DOUBT_NO_INPUT, "/proc/self/exe" to ShellCommands.DOUBT_NO_INPUT,
            "sed s/a/b/ run.sh | sh" to ShellCommands.DOUBT_PIPE, "cat run.sh | sed s/a/b/ | sh" to ShellCommands.DOUBT_PIPE, "curl http://x | sh" to ShellCommands.DOUBT_PIPE,
            "cat -v run.sh | sh" to ShellCommands.DOUBT_PIPE, "cat | sh" to ShellCommands.DOUBT_PIPE, "cat - run.sh | sh" to ShellCommands.DOUBT_PIPE,
            "echo -e 'sh\\tx' | sh" to ShellCommands.DOUBT_PIPE, "echo 'a\\nb' | sh" to ShellCommands.DOUBT_PIPE, "printf 'ls' | sh" to ShellCommands.DOUBT_PIPE,
            "base64 -d x | sh" to ShellCommands.DOUBT_PIPE, "sh <&3" to ShellCommands.DOUBT_OPEN_FILE,
            // The output of a group is more than what its last command prints.
            "(cat a; echo ls) | sh" to ShellCommands.DOUBT_PIPE, "{ cat a; echo ls; } | sh" to ShellCommands.DOUBT_PIPE,
            "for f in a b; do cat \$f; done | sh" to ShellCommands.DOUBT_PIPE, "if true; then cat a; fi | sh" to ShellCommands.DOUBT_PIPE,
            "eval \"\$x\"" to ShellCommands.DOUBT_TEXT, "eval \$(cat run.sh; echo x)" to ShellCommands.DOUBT_TEXT, "eval \"\$(grep x run.sh)\"" to ShellCommands.DOUBT_TEXT,
            "sh -c \"\$1\"" to ShellCommands.DOUBT_TEXT, "sh -c \"ls \$dir\"" to ShellCommands.DOUBT_TEXT, "sh -c \"echo \$(date)\"" to ShellCommands.DOUBT_TEXT,
            "trap \"\$cleanup\" EXIT" to ShellCommands.DOUBT_TEXT, "sh <<< \"\$x\"" to ShellCommands.DOUBT_TEXT, "eval ls *.txt" to ShellCommands.DOUBT_TEXT,
            "sh <<EOF\nsh \$f\nEOF" to ShellCommands.DOUBT_HERE_EXPANDS, "cat <<EOF | sh\necho \$(date)\nEOF" to ShellCommands.DOUBT_HERE_EXPANDS,
            "alias x='sh '; x run.sh" to ShellCommands.DOUBT_ALIAS, "alias ls=sh" to ShellCommands.DOUBT_ALIAS,
            // A function named like a command the reader gives a meaning to.
            "cat() { command cat /proc/self/cwd/hidden.sh; }; cat run.sh | sh" to ShellCommands.doubtFunction("cat"),
            "function echo { command echo sh /proc/self/cwd/hidden.sh; }; echo ls | sh" to ShellCommands.doubtFunction("echo"),
            "sh() { command sh sub/run.sh; }; sh run.sh" to ShellCommands.doubtFunction("sh"), "cd() { :; }" to ShellCommands.doubtFunction("cd"),
            // Through xargs the script's name is what xargs reads.
            "echo run | xargs sh" to ShellCommands.DOUBT_FED_ARGUMENTS, "ls | xargs -n 1 sh -e" to ShellCommands.DOUBT_FED_ARGUMENTS, "echo ./run | xargs ." to ShellCommands.DOUBT_FED_ARGUMENTS,
        )
        for ((command, doubt) in doubts) assertTrue(doubt in read(command).doubts, "$command: ${read(command).doubts}")
        assertEquals(ShellCommands.RUN_TIME, scripts("echo prog | xargs awk -f").single().problem)
        assertRuns("proj/run.sh", "ls | xargs -n 1 sh run.sh")
        assertRuns("proj/run.sh", "ls | xargs sh -c 'sh run.sh'")
        // Other functions are fine; their bodies are read like the rest.
        assertEquals(emptyList(), read("tidy() { ls; }; tidy").doubts)
        assertRuns("proj/run.sh", "go() { sh run.sh; }; go")
        // Fixed text is read, also with values the text itself assigns.
        for (command in listOf("sh -c 'echo \"\$1\"' x arg", "dir=out; sh -c \"ls \$dir\"", "sh -c \"echo \$HOME\"", "eval 'x=1; echo \$x'", "sh <<'EOF'\necho \$HOME \$(date)\nEOF", "alias", "unalias x")) {
            assertEquals(emptyList(), read(command).doubts, command)
        }
    }

    @Test
    fun `a program whose name is only put together when the command runs is a doubt`() {
        for (command in listOf("\$CMD run.sh", "\"\$@\"", "/system/bin/s? run.sh", "s* run.sh", "\$(cat name) run.sh", "env \$S run.sh", "find . -exec \$S {} ;", "@(sh|ls) run.sh")) {
            assertTrue(read(command).doubts.any { "only put together when it runs" in it }, "$command: ${read(command).doubts}")
        }
        // Brace expansion makes the words, also across quotes.
        for (command in listOf("{sh,/proc/self/cwd/run.sh}", "{s,}h run.sh", "s{h,x} run.sh", "{sh,'/proc/self/cwd/run.sh'}")) {
            assertTrue(read(command).doubts.any { "only put together when it runs" in it }, "$command: ${read(command).doubts}")
        }
        assertEquals(ShellCommands.RUN_TIME, scripts("sh {-c,'sh /proc/self/cwd/run.sh'}").single().problem)
        assertEquals(ShellCommands.RUN_TIME, scripts("sh ru{n,x}.sh").single().problem)
        // A name the text itself fixes is read through; the test command is no pattern.
        assertRuns("proj/run.sh", "S=sh; \$S run.sh")
        assertRuns("proj/run.sh", "X=sh; ba\$X run.sh")
        assertRuns("proj/run.sh", "\$(echo sh) run.sh")
        for (command in listOf("[ -d x ] || mkdir x", "[[ -d x ]] || mkdir x", "\$PWD/tool x", "\$HOME/tool x", "~/tool")) assertEquals(emptyList(), read(command).doubts, command)
    }

    @Test
    fun `what the lexer is not sure of is a doubt, never an approximation`() {
        val doubts = mapOf(
            // The verifier's here-document: mksh joins "EO\" and "F" and ends the document there.
            "cat <<EOF\nEO\\\nF\nsh /proc/self/cwd/hidden.sh\nEOF" to ShellSyntax.DOUBT_HERE_CONTINUED,
            "cat <<EOF\nline one \\\nline two\nEOF" to ShellSyntax.DOUBT_HERE_CONTINUED,
            "cat <<E\\OF\ntext\nEOF" to ShellSyntax.DOUBT_HERE_END, "cat <<\\EOF\ntext\nEOF" to ShellSyntax.DOUBT_HERE_END, "cat <<\$X\ndata\n\$X" to ShellSyntax.DOUBT_HERE_END,
            "cat <<'E O F'\ntext\nE O F" to ShellSyntax.DOUBT_HERE_END, "cat <<E'O'F\ntext\nEOF" to ShellSyntax.DOUBT_HERE_END, "(( 1 << 2 ))\nls" to ShellSyntax.DOUBT_HERE_OPEN,
            "cat <<EOF\nnever closed" to ShellSyntax.DOUBT_HERE_OPEN, "cat <<EOF" to ShellSyntax.DOUBT_HERE_OPEN,
            "echo \$(cat <<EOF\nx\nEOF\n)" to ShellSyntax.DOUBT_HERE_NESTED,
            // The verifier's case inside a substitution: the first ")" is the pattern's.
            "echo \"\$(case x in x) sh /proc/self/cwd/hidden.sh ;; esac)\"" to ShellSyntax.DOUBT_CASE,
            "x=\$(case \$y in a) echo 1;; esac)" to ShellSyntax.DOUBT_CASE,
            "echo \${x:-\$(sh /proc/self/cwd/hidden.sh)}" to ShellSyntax.DOUBT_HIDDEN_COMMAND, "echo \${x:-`sh hidden.sh`}" to ShellSyntax.DOUBT_HIDDEN_COMMAND,
            "echo \$(( \$(sh hidden.sh) + 1 ))" to ShellSyntax.DOUBT_HIDDEN_COMMAND,
            // mksh runs the command in "${ command;}" and "${|command;}".
            "echo \${ sh /proc/self/cwd/hidden.sh;}" to ShellSyntax.DOUBT_HIDDEN_COMMAND, "x=\${|sh hidden.sh;}" to ShellSyntax.DOUBT_HIDDEN_COMMAND,
            "sh <(cat /proc/self/cwd/run.sh)" to ShellSyntax.DOUBT_PROCESS_SUBSTITUTION, "tee >(sh)" to ShellSyntax.DOUBT_PROCESS_SUBSTITUTION,
            "echo 'never closed" to ShellSyntax.DOUBT_UNCLOSED, "echo \"never closed" to ShellSyntax.DOUBT_UNCLOSED, "echo \$(ls" to ShellSyntax.DOUBT_UNCLOSED,
            "echo \${x" to ShellSyntax.DOUBT_UNCLOSED, "echo `ls" to ShellSyntax.DOUBT_UNCLOSED,
            "echo \"`echo \\\"x\\\"`\"" to ShellSyntax.DOUBT_BACKQUOTES,
            "ls\r\nsh run.sh" to ShellSyntax.DOUBT_CARRIAGE_RETURN,
        )
        for ((command, doubt) in doubts) assertTrue(doubt in read(command).doubts, "${command.replace("\n", "\\n")}: ${read(command).doubts}")
        // Read without a doubt: the same constructs written so that there is none.
        for (command in listOf(
            "cat <<EOF\nplain text & more\nEOF", "cat > notes.md <<'EOF'\nline \\\nmore\nEOF", "cat <<-END\n\ttext\n\tEND", "cat <<\"EOF\"\n\$x\nEOF", "tee out.txt <<EOF-1\nx\nEOF-1",
            "echo \"\$(case x in (x) echo yes ;; esac)\"", "echo \$(grep -c case notes.md)", "case \$x in a) echo 1;; *) echo 2;; esac", "echo \${x:-y} \${#x} \$((1 + 2))",
            "echo \$( (ls) )", "echo \"a \$(echo \"b \$(echo c)\") d\"", "echo `echo \\`pwd\\``", "ls # a 'comment", "echo \$(ls # ) comment\n)", "x=\$((2 << 1))",
        )) assertEquals(emptyList(), read(command).doubts, command.replace("\n", "\\n"))
        // What is hidden from a careless reader is read: a command in a here-document's text, in a case pattern, in a subshell behind "$((".
        assertEquals(ShellCommands.OUTSIDE, scripts("cat <<EOF\n\$(sh /proc/self/cwd/hidden.sh)\nEOF").single().problem)
        assertEquals(listOf("proj/run.sh"), paths(scripts("cat <<EOF\ntext `sh run.sh`\nEOF")))
        assertEquals(emptyList(), scripts("cat <<'EOF'\n\$(sh /proc/self/cwd/hidden.sh)\nEOF"), "with a quoted end word the text is passed on as it is")
        assertEquals(listOf("proj/run.sh"), paths(scripts("case x in \$(sh run.sh)) echo ;; esac")))
        assertEquals(listOf("proj/run.sh"), paths(scripts("echo \$((sh run.sh) )")))
        assertEquals(listOf("proj/run.sh"), paths(scripts("echo \"\$(echo \"\$(echo \")\")\"; sh run.sh)\"")))
        assertEquals(listOf("proj/run.sh"), paths(scripts("echo \${x:-\"}\"}; sh run.sh; echo \"}\"")))
    }

    @Test
    fun `a text that changes folder or the way names are looked up says so, and each name says whether it depends on that`() {
        for (command in listOf(
            "cd x && sh run.sh", "(cd x; sh ./run.sh)", "sh -c 'cd x'; sh run.sh", "pushd x; . ./run.sh", "popd", "cd", "builtin cd x", "eval 'cd x'", "x=\$(cd sub; pwd)",
            "f() { cd x; }; f",
        )) assertEquals(ShellCommands.UNSETTLED_FOLDER, read(command).unsettledBy, command)
        for (command in listOf(
            "PATH=sub:\$PATH; . ./run.sh", "export PATH=sub", "PATH=sub sh run.sh", "env PATH=sub sh run.sh", "CDPATH=sub", "ENV=x.sh sh -i", "HOME=/data/ws", "IFS=n", "PWD=/x",
            "unset PATH", "read PATH", "for PATH in a b; do :; done", ": \${PATH:=sub}",
        )) assertEquals(ShellCommands.UNSETTLED_LOOKUP, read(command).unsettledBy, command)
        for (command in listOf(
            "sh run.sh", "ls && wc -l notes.md", "echo \$PATH", "x=1; y=\$HOME", "sh -c 'ls'", "echo cd", "echo PATH", "printenv HOME", "echo \${PATH:-none}",
            // The usual way of reading lines: the assignment holds for that read alone.
            "while IFS= read -r line; do echo \"\$line\"; done < notes.md",
        )) assertNull(read(command).unsettledBy, command)

        fun relative(command: String) = scripts(command).single().relative
        for (command in listOf("sh run.sh", "sh ./run.sh", "sh x/../run.sh", "sh \$PWD/run.sh", "sh \$(pwd)/run.sh", "sh r*.sh", "cat run.sh | sh", ". ./run.sh")) assertTrue(relative(command), command)
        for (command in listOf("sh /data/ws/proj/run.sh", "sh /real/ws/proj/run.sh", "d=/data/ws; sh \$d/proj/run.sh")) assertFalse(relative(command), command)
    }

    @Test
    fun `HOME, ENV and PATH may not be pointed into the workspace or at something unknown`() {
        // G3 from the other side: the runner keeps them outside the workspace, and a command that takes that back is not read as safe.
        for (command in listOf(
            "HOME=\$PWD sh -l -c true", "HOME=. sh -l", "export HOME=/data/ws/proj; sh -l -c ls", "HOME=/data/ws sh -i", "HOME=/real/ws/x sh -l", "HOME=/data sh -l",
            "HOME=\$x sh -l", "HOME=\$(pwd) sh -l", "env HOME=sub sh -l -c true", "read HOME < name; sh -l", "for HOME in a b; do sh -l; done", "HOME+=/x",
            "HOME=/proc/self/cwd sh -l", "HOME=/tmp/../data/ws sh -l", ": \${HOME:=sub}",
            "ENV=run.sh sh -i -c true", "ENV=\$PWD/run.sh sh -i", "export ENV=./rc; sh -i", "typeset -x ENV=rc", "ENV=\$f sh -i",
            "PATH=sub:\$PATH; tidy", "PATH=.:\$PATH", "PATH=\$PWD/bin:\$PATH tool", "export PATH=\$PATH:", "PATH=:/system/bin", "PATH=\$x", "PATH=/data/ws/proj/bin",
            "env PATH=bin tool", "PATH+=:bin",
        )) assertTrue(read(command).doubts.any { "to a place in the workspace, or to a value that is only known when the command runs" in it }, "$command: ${read(command).doubts}")
        for (command in listOf(
            "HOME=/tmp sh -l -c true", "HOME=\$HOME sh -l -c true", "HOME=~ ls", "ENV= sh -i -c true", "unset ENV HOME", "export HOME PATH", "PATH=\$PATH:/system/xbin ls",
            "PATH=/system/bin:/vendor/bin; ls", "export PATH=/system/bin", "HOME=/sdcard/Download tool", "make HOME=sub", "echo HOME=\$PWD", "x=\$PWD", "CDPATH=sub",
        )) assertEquals(emptyList(), read(command).doubts, command)
    }

    @Test
    fun `other names are returned for pinning, scripts by their ending among them`() {
        val names = read("wc -l notes.md \"my file.txt\" && cat lib.sh | sh; x=out/data.csv ./tool arg > log.txt").names
        fun flags(path: String) = names.filter { n -> n.targets.any { (it as? ShellCommands.Target.Entry)?.path?.value == path } }
        assertTrue(flags("proj/notes.md").let { it.isNotEmpty() && it.none { n -> n.asScript } })
        assertTrue(flags("proj/my file.txt").isNotEmpty())
        assertTrue(flags("proj/lib.sh").any { it.asScript && it.shell && it.must }, "a file piped into a shell is what runs")
        assertTrue(flags("proj/out/data.csv").isNotEmpty())
        assertTrue(flags("proj/log.txt").isNotEmpty())
        assertTrue(flags("proj/tool").any { it.asScript && it.onlyExisting && !it.must }, "a program started by its path is shown when it is a workspace file")
        assertFalse(names.any { it.raw == "-l" })
        // A name only found inside other text is marked as such.
        assertTrue(read("echo 'see run.sh'").names.single { it.raw == "run.sh" }.incidental)
        assertFalse(read("cat run.sh").names.first { it.asScript }.incidental)
        // Only the scripts of a script's text are followed.
        assertEquals(listOf("proj/b.sh"), paths(ShellCommands.read("echo notes.md\n. ./b.sh\n", places, everyWord = false).names))
        // Wrapped too deeply to be read: a doubt, not a guess.
        assertNotNull(read("echo \$(echo \$(echo \$(echo \$(echo \$(echo \$(sh run.sh))))))").doubts.singleOrNull { it == ShellCommands.DOUBT_TOO_MUCH })
    }

    @Test
    fun `everyday commands are read without a doubt and without a script that is none`() {
        val everyday = listOf(
            "ls -la | head -20", "find . -name '*.md' -exec wc -l {} +", "for f in *.txt; do wc -l \"\$f\"; done", "cat f | while read l; do echo \"\$l\"; done",
            "X=\$(date +%s); echo \$X", "[ -d out ] || mkdir out", "awk '{print \$1}' data.txt", "sed -i 's/a/b/' x.txt", "tar -xf a.tar", "time ls",
            "if grep -q x f; then echo y; fi", "a=1; b=2; echo \$((a+b))", ": > empty.txt", "printf '%s\\n' \"\$@\"", "cat > notes.md <<EOF\nline & more bg\nEOF",
            "echo \$(echo \$(echo \$(echo deep)))", "ls 2>/dev/null >&2", "grep -c '&' notes.md", "x=\${y:-z}; echo \"\$x\"", "f() { ls; }; f", "mkdir -p a/b && cp x a/b/",
            "wc -l < notes.md", "sort data.csv | uniq -c | sort -rn | head", "grep -rn TODO . | cut -d: -f1 | sort -u", "date; uname -a; id",
            "echo \"\$HOME\" \"\$PWD\" \"\$TMPDIR\"", "while IFS= read -r line; do echo \"\$line\"; done < notes.md", "test -f x && echo yes || echo no",
            "cat a.txt b.txt > c.txt", "head -c 100 big.bin | xxd", "du -sh * | sort -h", "sleep 1 & wait", "trap 'rm -f tmp.\$\$' EXIT; echo x > tmp.\$\$",
            "case \$x in bg) echo yes;; *) echo no;; esac", "tee out.txt <<'EOF'\nR&D; bg\nsetup & teardown\nEOF", "cat <<-EOF > x\n\tR&D\n\tEOF", "ls # list & count",
            "nohup sleep 5 &", "setsid sleep 5", "set -m; sleep 1 &", "sleep 5 & disown", "ls |& cat", "echo hi &> out.txt", "(sleep 1; echo late > late.txt) &",
            "grep -n 'sh -c' notes.md", "echo 'eval \$x'", "ls ~", "cp notes.md ~/copy.md", "curl -s https://example.org | head -5", "echo done | tee log.txt",
            "xargs -n 1 echo < list.txt", "env | sort", "export LANG=C; sort x", "command -v toybox", "echo \"a\" 'b' c\\ d", "cat <<EOF\n\$HOME is home\nEOF",
        )
        for (command in everyday) {
            val reading = read(command)
            assertEquals(emptyList(), reading.doubts, command.replace("\n", "\\n"))
            assertEquals(emptyList(), reading.names.filter { it.must || it.problem != null }.map { it.raw }, command.replace("\n", "\\n"))
        }
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
