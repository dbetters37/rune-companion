package com.aicontrolcenter.framework.operator

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F4, and the disguised setsid of F3: what a command line is refused for because it would leave
 * work running after the call. Words such as "bg" or "daemon" count where a command starts, not
 * as an argument or a file name; setsid, nohup and job control count however they are quoted.
 */
class ShellBackgroundTest {
    @Test
    fun `background words as arguments or file names are fine`() {
        val harmless = listOf(
            // The verifier's examples.
            "grep bg notes.md", "ls daemon", "cat docs/bg", "echo 'a & b'", "echo \"R&D\"", "cat file 2>&1", "a && b",
            "echo monitor", "grep -r coproc .", "ls bg daemon", "cp bg.png daemon.log out/", "echo \"the bg is blue\"", "wc -l monitor coproc disown",
            "sleep 5 \\&", "trap '' HUP; sleep 100", "wait", "[ -f bg ] && echo yes", "for f in bg daemon; do echo \$f; done",
            "case \$x in bg) echo yes;; esac", "cat <<EOF\nbg\ndaemon\nEOF", "x=bg; echo \$x", "ls | grep bg", "find . -name bg", "echo hi # bg",
            "set -e; ls", "set +m; ls", "sh -c 'ls bg'", "eval ls bg",
            // Everyday commands, none of which leaves anything running.
            "ls -la | head -20", "find . -name '*.md' -exec wc -l {} +", "for f in *.txt; do wc -l \"\$f\"; done", "cat f | while read l; do echo \"\$l\"; done",
            "X=\$(date +%s); echo \$X", "[ -d out ] || mkdir out", "awk '{print \$1}' data.txt", "sed -i 's/a/b/' x.txt", "tar -xf a.tar", "sh -c \"echo \$HOME\"",
            "time ls", "if grep -q x f; then echo y; fi", "a=1; b=2; echo \$((a+b))", ": > empty.txt", "printf '%s\\n' \"\$@\"", "cat > notes.md <<EOF\nline & more bg\nEOF",
            "echo \$(echo \$(echo \$(echo deep)))", "ls 2>/dev/null >&2", "grep -c '&' notes.md", "x=\${y:-z}; echo \"\$x\"", "f() { ls; }; f",
            // Text written to a file through a here-document is data, whatever it says; so is a comment.
            "tee out.txt <<'EOF'\nR&D; bg\nsetup & teardown\nEOF", "cat <<-EOF > x\n\tR&D\n\tEOF", "ls # list & count", "cat > a <<EOF\nx & y\nEOF\nls",
        )
        for (command in harmless) assertNull(ShellCommands.backgroundProblem(command), command)
    }

    @Test
    fun `background commands are refused where a command starts`() {
        val refused = listOf(
            "bg", "bg %1", "sleep 1; bg", "sleep 1\nbg", "true && bg", "false || daemon run", "ls | disown", "(coproc cat)", "`bg`", "echo \$(bg)",
            "if true; then bg; fi", "{ bg; }", "! bg", "sh -c 'bg'", "sh -c \"sleep 1; bg\"", "eval bg", "eval 'daemonize x'", "env bg", "exec bg",
            "/system/bin/bg", "BG", "x=1 bg", "sh <<EOF\nbg\nEOF", "echo bg | sh", "trap 'bg' EXIT", "start-stop-daemon --start x",
        )
        for (command in refused) assertNotNull(ShellCommands.backgroundProblem(command), command)
    }

    @Test
    fun `setsid, nohup and job control are refused however they are quoted, also inside text a shell is given`() {
        val refused = listOf(
            "setsid sleep 5", "nohup sleep 5", "toybox setsid sleep 5", "\$(echo setsid) sleep 5",
            // Disguised: the shell removes the quotes and runs setsid.
            "\"set\"\"sid\" sleep 5", "s\\etsid sleep 5", "set''sid sleep 5", "'setsid' sleep 5", "no\"hup\" sleep 5", "/system/bin/\"setsid\" sleep 5",
            "sh -c '\"set\"\"sid\" sh -c \"sleep 2; echo alive > late2.txt\" &'", "sh -c 's\\etsid sleep 5'", "eval 'set\"sid\" sleep 5'",
            // A program name only put together when the command runs could be any of these.
            "X=sid; set\$X sleep 5", "/system/bin/sets?d sleep 5", "\$CMD sleep 5", "\"\$@\"", "set* sleep 5",
            // Work sent to the background inside text a shell runs.
            "sh -c 'sleep 5 &'", "sh -c \"sleep 5 &\"", "eval 'sleep 5 &'", "echo 'sleep 5 &' | sh", "x=\$(sleep 5 & echo hi)", "echo \"\$(sleep 5 &)\"",
            "sleep 5 &", "sleep 5&", "sleep 5 & # later", "a & b", "ls |& cat", "echo hi &> out.txt", "cat x <<EOF &\ntext\nEOF",
            // A here-document that is not plainly data for cat or tee is read as commands.
            "sh <<EOF\nsleep 5 &\nEOF", "cat <<EOF | sh\ns\\etsid sleep 5\nEOF", "cat <<EOF | sed s/a/b/ | sh\nsleep 5 &\nEOF", "wc -l <<EOF\nsleep 5 &\nEOF",
            "(( 1 << 2 ))\ns\\etsid sleep 9\n", "cat <<\$X\ndata\n\$X\ns\\etsid sleep 9\n", "cat <<E\\OF\nsleep 5 &\nEOF\n",
            // Job control.
            "set -m; sleep 1", "set -\"m\"; sleep 1", "set -o monitor", "set -o 'monitor'", "sh -i -c ls", "sh -\"i\" -c ls", "sh -c 'set -\"m\"; sleep 1'", "bash -im",
        )
        for (command in refused) {
            val reason = ShellCommands.backgroundProblem(command)
            assertNotNull(reason, command)
            assertTrue("not run" in reason, "$command: $reason")
        }
        // Wrapped more deeply than is read: refused rather than run unchecked.
        assertTrue("not run" in ShellCommands.backgroundProblem("echo \$(echo \$(echo \$(echo \$(echo \$(echo \$(s\\etsid sleep 5))))))")!!)
        // The home and working folder may start a program's name; the test command is no pattern.
        for (command in listOf("\$HOME/tool.sh x", "~/tool.sh", "\"\$PWD\"/tool x", "[ -d x ] || mkdir x", "[[ -d x ]] || mkdir x")) {
            assertNull(ShellCommands.backgroundProblem(command), command)
        }
    }
}
