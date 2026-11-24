package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.RelativePath
import java.util.Locale

/**
 * What the shell tool reads out of a command line before the owner is asked: whether it would
 * start work that runs on after the call, and which workspace files and folders it names.
 * Plain text analysis, not a shell: it errs towards naming too much and refusing too much.
 */
internal object ShellCommands {
    /** Words that start or keep work running after the command returns, or put it out of reach of the call's process group. */
    private val BACKGROUND_WORDS = listOf("nohup", "setsid", "disown", "daemon", "daemonize", "start-stop-daemon", "coproc", "bg")

    private val SHELLS = setOf("sh", "ash", "bash", "dash", "ksh", "mksh", "zsh")

    /** Words after which a file is run as a script. */
    private val SCRIPT_RUNNERS = SHELLS + setOf(".", "source")

    private val SCRIPT_ENDINGS = listOf(".sh", ".bash", ".ksh", ".mksh", ".zsh", ".awk", ".sed")

    /** Most names looked up for one command. */
    const val MAX_NAMES = 100

    /**
     * Why [command] is refused because it would leave something running after the call, or
     * null. Refused: a `&` that sends work to the background (outside quotes; `&&` and
     * redirections such as `2>&1` are fine), `&>` and `|&`, the words in [BACKGROUND_WORDS] anywhere
     * (also inside quotes, where `sh -c` or `eval` could run them), and job control (`set -m`,
     * `set -o monitor`, a shell started with `-m` or `-i`), which moves jobs out of the call's
     * process group.
     */
    fun backgroundProblem(command: String): String? {
        backgroundAmpersand(command)?.let { return it }
        // Command words, wherever they are (also inside quotes): "nohup", "/system/bin/nohup", "toybox nohup".
        val words = TOKEN.findAll(command.lowercase(Locale.ROOT)).map { it.value.substringAfterLast('/') }.toSet()
        BACKGROUND_WORDS.firstOrNull { it in words }?.let {
            return "\"$it\" keeps work running after the command ends, and such commands are not run: everything a command starts " +
                "must end with it. Run the work in the foreground instead."
        }
        if ("monitor" in words || JOB_CONTROL.containsMatchIn(command)) {
            return "The command turns on job control, which lets work escape when the command ends, so it is not run."
        }
        return null
    }

    private fun backgroundAmpersand(command: String): String? {
        var single = false
        var double = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c == '\\' && !single -> i++
                c == '\'' && !double -> single = !single
                c == '"' && !single -> double = !double
                c == '&' && !single && !double -> {
                    val prev = command.getOrNull(i - 1)
                    val next = command.getOrNull(i + 1)
                    // ">&2", "2>&1" and "<&3" are redirections; "&>" is not one in every shell, where it backgrounds the command.
                    val redirection = prev == '>' || prev == '<'
                    when {
                        next == '&' -> i++
                        prev == '&' -> Unit
                        prev == '|' -> return "\"|&\" starts a co-process that keeps running in the background, so the command is not run."
                        next == '>' -> return "\"&>\" can send work to the background in Android's shell, so the command is not run. " +
                            "Write \">file 2>&1\" instead."
                        redirection -> Unit
                        else -> return "A single \"&\" sends work to the background, where it would keep running after the call ends, " +
                            "so the command is not run. Run the work in the foreground (use \"&&\" to chain commands)."
                    }
                }
            }
            i++
        }
        return null
    }

    /** A name in a command that may be a workspace path, and whether it is run as a script. */
    data class Name(val raw: String, val asScript: Boolean)

    /**
     * Every word of [command] that could name a file or folder, with quotes taken off and also
     * split further (so names inside `sh -c '…'` count), at most [MAX_NAMES]. A word is
     * [Name.asScript] when the nearest word before it that is not an option runs scripts
     * (`sh`, `.`, `source` …), when it follows `-f`, or when it ends like a script (`.sh`, `.awk` …).
     */
    fun names(command: String): List<Name> {
        val out = LinkedHashMap<String, Boolean>()
        var lastCommandWord: String? = null
        var previous: String? = null
        var previousEnd = -1
        for (match in TOKEN.findAll(command)) {
            val name = match.value
            // "." where a command starts is the shell's "source", not the folder.
            val commandPosition = previousEnd < 0 || command.substring(previousEnd, match.range.first).any { it in COMMAND_SEPARATORS }
            previousEnd = match.range.last + 1
            val script = (lastCommandWord != null && lastCommandWord in SCRIPT_RUNNERS) || previous == "-f" || previous == "--file" ||
                SCRIPT_ENDINGS.any { name.lowercase(Locale.ROOT).endsWith(it) }
            if (!name.startsWith("-") && !(commandPosition && name == ".")) {
                out[name] = (out[name] ?: false) || script
                if (out.size >= MAX_NAMES) break
            }
            previous = name
            if (!name.startsWith("-")) lastCommandWord = name.substringAfterLast('/')
        }
        // Quoted names with spaces in them.
        QUOTED.findAll(command).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { ' ' in it && it.length <= RelativePath.MAX_LENGTH }.forEach {
            if (out.size < MAX_NAMES) out.putIfAbsent(it, SCRIPT_ENDINGS.any { e -> it.lowercase(Locale.ROOT).endsWith(e) })
        }
        return out.map { (raw, script) -> Name(raw, script) }
    }

    /** [raw] as a workspace path from the folder [dir], or null when it cannot be one (absolute, outside, not a valid path). */
    fun pathOf(raw: String, dir: RelativePath): RelativePath? {
        var name = raw
        for (prefix in HOME_PREFIXES) if (name.startsWith(prefix)) name = name.removePrefix(prefix)
        while (name.startsWith("./")) name = name.removePrefix("./")
        name = name.trimEnd('/')
        if (name.isEmpty() || name == ".") return dir
        if (name.startsWith("/") || name.startsWith("~") || '$' in name || '*' in name || '?' in name) return null
        val full = if (dir.isRoot) name else dir.value + "/" + name
        return (RelativePath.parse(full) as? RelativePath.Check.Valid)?.path
    }

    /** `HOME` is the working folder, so these all start at it. */
    private val HOME_PREFIXES = listOf("\$HOME/", "\${HOME}/", "~/", "\"\$HOME\"/")

    /** Characters after which a new command starts. */
    private const val COMMAND_SEPARATORS = ";&|(\n`{"

    /** A name: no spaces, quotes or shell punctuation. */
    private val TOKEN = Regex("""[^\s;&|<>()`'"=]+""")

    private val QUOTED = Regex("""'([^']*)'|"([^"]*)"""")

    /** `set -m`, `set -o monitor`, or a shell started with -m or -i (interactive shells turn job control on). */
    private val JOB_CONTROL = Regex("""(?:^|[\s;&|(`'"])(?:set\s+-[A-Za-z]*m|(?:sh|ash|bash|dash|ksh|mksh|zsh)\s+(?:-[A-Za-z]+\s+)*-[A-Za-z]*[mi])""")
}
