package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.framework.operator.ShellSyntax.Kind
import com.aicontrolcenter.framework.operator.ShellSyntax.Simple
import com.aicontrolcenter.framework.operator.ShellSyntax.Word
import java.util.Locale

/**
 * What the shell tool reads out of a command line before the owner is asked: whether it would
 * start work that runs on after the call, and which workspace files and folders it names, the
 * scripts it runs among them. Text analysis over [ShellSyntax], not a shell: a name is resolved
 * the way the shell would for every static spelling (quotes and backslashes removed, `.` and
 * `..` followed inside the workspace, `~`, `$HOME`, `$PWD` and the workspace's own absolute path
 * mapped into it, patterns such as `t*.sh` left for the caller to expand against the workspace),
 * and a script whose name is only known when the command runs is reported as such, so the tool
 * can refuse it instead of running something the owner was never shown. It errs towards naming
 * too much and refusing too much.
 */
internal object ShellCommands {
    /** Words that put work out of reach of the call's process group, wherever they stand (also inside quotes). */
    private val ESCAPE_WORDS = listOf("nohup", "setsid")

    /** Commands that start or keep work running after the command returns; refused where a command starts, not as an argument. */
    private val BACKGROUND_COMMANDS = listOf("disown", "daemon", "daemonize", "start-stop-daemon", "coproc", "bg")

    private val SHELLS = setOf("sh", "ash", "bash", "dash", "ksh", "mksh", "zsh")

    /** Commands that run their first argument as a script in the current shell. */
    private val SOURCE = setOf(".", "source")

    /** Programs whose `-f` argument is a program file. */
    private val PROGRAM_FILE = setOf("awk", "gawk", "mawk", "nawk", "sed")

    /** Commands that start the command written after their own options. */
    private val PREFIXES = setOf(
        "toybox", "busybox", "env", "command", "exec", "builtin", "nice", "timeout", "nohup", "setsid", "stdbuf", "ionice", "chrt", "taskset", "strace", "sudo",
    )

    /** Commands that start a command named somewhere among their arguments. */
    private val STARTERS = PREFIXES + setOf("find", "xargs", "watch", "su", "flock", "script", "unshare", "chroot", "nsenter", "runcon")

    private val FIND_EXEC = setOf("-exec", "-execdir", "-ok", "-okdir")

    /** Programs that take a here-document as plain data. */
    private val DATA_READERS = setOf("cat", "tee")

    private val DECLARATIONS = setOf("export", "readonly", "local", "typeset", "declare")

    private val FOLDER_CHANGES = setOf("cd", "pushd", "popd", "chdir")

    private val SHELL_ENDINGS = listOf(".sh", ".bash", ".ksh", ".mksh", ".zsh")

    private val SCRIPT_ENDINGS = SHELL_ENDINGS + listOf(".awk", ".sed")

    /** Most names that are not scripts looked up for one command or script; further ones are left out. */
    const val MAX_NAMES = 100

    /** Most scripts one command or script may run by name; with more, it is refused rather than run with some of them unseen. */
    const val MAX_SCRIPT_NAMES = 100

    /** How deep commands inside commands (`sh -c`, `eval`, `$(…)`) are read. */
    private const val MAX_DEPTH = 4

    /** Most spellings one word may stand for (a variable with several values). */
    private const val MAX_SPELLINGS = 16

    const val RUN_TIME = "its name is only put together when the command runs"
    const val OUTSIDE = "it is outside the workspace"
    const val TOO_MUCH = "the command runs more scripts, or wraps commands in commands more deeply, than can be read"
    private const val AFTER_CD = "the command changes folder with cd, so the place a relative name points to is only known when it runs " +
        "(use the \"directory\" argument instead of cd, or start the name with ~/)"
    private const val OPEN_FILE = "it is read from a file the command opened earlier"

    // ---------------------------------------------------------------- background work

    /**
     * Why [command] is refused because it would leave something running after the call, or
     * null. Refused: a `&` that sends work to the background (`&&` and redirections such as
     * `2>&1` are fine), `&>` and `|&`; `nohup` and `setsid` anywhere, however they are quoted;
     * `bg`, `disown`, `coproc` and the daemon starters where a command starts (not as an
     * argument: `grep bg notes.md` is fine); a command whose own name is only put together when
     * it runs, since it could be any of these; and job control (`set -m`, `set -o monitor`, a
     * shell started with `-m` or `-i`), which moves jobs out of the call's process group. All of
     * this is also looked for in the commands a command runs from text: `sh -c '…'`, `eval`,
     * `trap`, `$(…)`, here-documents and text piped into a shell.
     */
    fun backgroundProblem(command: String): String? = background(command, 0)

    private fun background(text: String, depth: Int): String? {
        val script = ShellSyntax.parse(text)
        backgroundOperator(script)?.let { return it }
        // As written (any quoting splits a word here) and as the shell reads it (quotes removed).
        val written = TOKEN.findAll(text.lowercase(Locale.ROOT)).map { it.value.substringAfterLast('/') }.toSet()
        val read = wordsRead(script)
        ESCAPE_WORDS.firstOrNull { it in written || it in read }?.let { return keepsRunning(it) }
        for (simple in script.commands) {
            for ((index, call) in calls(simple).withIndex()) {
                if (index == 0 && builtAtRunTime(call.word)) {
                    return "The command starts a program whose name is only put together when it runs (${OperatorText.q(call.word.raw.take(80))}), so it cannot be " +
                        "checked for work it would leave running, and it is not run. Write the program's name plainly."
                }
                if (call.name in BACKGROUND_COMMANDS) return keepsRunning(call.name!!)
                val jobControl = when (call.name) {
                    "set" -> jobControlOptions(call.args, stopAtOperand = false)
                    in SHELLS -> shellUse(call.args).jobControl
                    else -> false
                }
                if (jobControl) return JOB_CONTROL_PROBLEM
            }
        }
        if (JOB_CONTROL.containsMatchIn(text)) return JOB_CONTROL_PROBLEM
        val inner = innerTexts(script)
        if (depth >= MAX_DEPTH && inner.isNotEmpty()) return TOO_DEEP
        for (text in inner) background(text, depth + 1)?.let { return it }
        return null
    }

    private const val TOO_DEEP = "The command wraps commands in commands (sh -c, eval, \$(…)) more than $MAX_DEPTH deep, too deep to be " +
        "checked for work it would leave running, so it is not run. Write it more plainly."

    private const val JOB_CONTROL_PROBLEM = "The command turns on job control, which lets work escape when the command ends, so it is not run."

    /**
     * Why the shell script [text] must not be run because it would put work out of reach of the
     * call, as the end of a sentence about it; null when it would not. A script may use a plain
     * `&`: what that starts stays in the call's process group and ends with it. It may not use
     * `nohup` or `setsid` (however they are quoted) or turn on job control, here or in text it
     * hands to a shell. Comments do not count.
     */
    fun escapeProblem(text: String): String? = escape(text, 0)

    private fun escape(text: String, depth: Int): String? {
        val script = ShellSyntax.parse(text)
        val read = wordsRead(script)
        ESCAPE_WORDS.firstOrNull { it in read }?.let { return "it uses \"$it\", which keeps work running after the command ends" }
        for (simple in script.commands) {
            for (call in calls(simple)) {
                val jobControl = when (call.name) {
                    "set" -> jobControlOptions(call.args, stopAtOperand = false)
                    in SHELLS -> shellUse(call.args).jobControl
                    else -> false
                }
                if (jobControl) return "it turns on job control, which lets work escape when the command ends"
            }
        }
        val inner = innerTexts(script)
        if (depth >= MAX_DEPTH && inner.isNotEmpty()) return "it wraps commands in commands more than $MAX_DEPTH deep, too deep to be checked"
        for (text in inner) escape(text, depth + 1)?.let { return it }
        return null
    }

    /** Every word of [script] as the shell reads it (quotes removed), lower case, also the parts around `=` and `:`, without folders. */
    private fun wordsRead(script: ShellSyntax.Script): Set<String> =
        allWords(script).mapNotNull { it.plain?.lowercase(Locale.ROOT) }.flatMap { it.split('=', ':') }.map { it.substringAfterLast('/') }.toSet()

    private fun keepsRunning(word: String) =
        "\"$word\" keeps work running after the command ends, and such commands are not run: everything a command starts " +
            "must end with it. Run the work in the foreground instead."

    /**
     * A `&` that sends work to the background, `|&` or `&>`, read as the shell reads the text:
     * not inside quotes, comments or the text of a here-document, and neither `&&` nor a
     * redirection such as `2>&1`.
     */
    private fun backgroundOperator(script: ShellSyntax.Script): String? {
        for (operator in script.operators) {
            when (operator) {
                "|&" -> return "\"|&\" starts a co-process that keeps running in the background, so the command is not run."
                "&>" -> return "\"&>\" can send work to the background in Android's shell, so the command is not run. Write \">file 2>&1\" instead."
                "&" -> return "A single \"&\" sends work to the background, where it would keep running after the call ends, " +
                    "so the command is not run. Run the work in the foreground (use \"&&\" to chain commands)."
            }
        }
        return null
    }

    /** True when part of the command's own name is only known at run time: an expansion other than the home or working folder, or a pattern. */
    private fun builtAtRunTime(word: Word): Boolean =
        word.pieces.any { p ->
            when (p.kind) {
                Kind.LIT -> !p.quoted && isPattern(p.text)
                Kind.VAR -> p.text != "HOME" && p.text != "PWD"
                Kind.SUB, Kind.DYN -> true
            }
        }

    /** `*`, `?` or a `[…]` set. A lone `[` (the test command) is not one. */
    private fun isPattern(text: String): Boolean = '*' in text || '?' in text || text.indexOf('[').let { it >= 0 && text.indexOf(']', it + 2) > 0 }

    // ---------------------------------------------------------------- reading commands

    /** A command as it would be started: its [name] (the last part of its path, lower case; null when only known at run time) and arguments. */
    private class Call(val name: String?, val word: Word, val args: List<Word>)

    private fun nameOf(word: Word): String? = word.plain?.substringAfterLast('/')?.lowercase(Locale.ROOT)

    /** The command [simple] starts, and the commands that one starts in turn when it is a wrapper (`env`, `toybox`, `find -exec` …). */
    private fun calls(simple: Simple): List<Call> {
        val words = simple.words
        val out = ArrayList<Call>()
        val used = HashSet<Int>()
        var k = 0
        while (k < words.size) {
            val call = Call(nameOf(words[k]), words[k], words.subList(k + 1, words.size))
            out += call
            used += k
            if (call.name !in PREFIXES) break
            // What a prefix command starts: the first later word that is not an option, an assignment or a number.
            k++
            while (k < words.size && words[k].plain.let { it != null && (it.startsWith("-") || it.startsWith("+") || '=' in it || NUMBER.matches(it)) }) k++
        }
        if (out.firstOrNull()?.name in STARTERS) {
            // Which of the later words is the command they start is not always clear (options take values), so every
            // word that names a shell or another program that runs a script file counts, and what follows find's -exec.
            for (j in 1 until words.size) {
                if (j in used) continue
                val name = nameOf(words[j])
                val afterExec = words[j - 1].plain in FIND_EXEC
                if (afterExec || (name != null && (name in SHELLS || name in PROGRAM_FILE || name == "eval" || name == "source"))) {
                    out += Call(name, words[j], words.subList(j + 1, words.size))
                }
            }
        }
        return out
    }

    /** How a shell is started: with a command string (`-c`), a script file, files it reads first, or nothing (it reads its input). */
    private class ShellUse(val jobControl: Boolean, val commandString: Word?, val script: Word?, val startFiles: List<Word>)

    private fun shellUse(args: List<Word>): ShellUse {
        var takesCommand = false
        var readsInput = false
        val startFiles = ArrayList<Word>()
        var i = 0
        while (i < args.size) {
            val option = args[i].plain
            if (option == null || option.length < 2 || (option[0] != '-' && option[0] != '+')) break
            i++
            when {
                option == "--" -> break
                option == "--rcfile" || option == "--init-file" -> args.getOrNull(i++)?.let { startFiles += it }
                option.startsWith("--") -> Unit
                option.drop(1) == "o" -> i++
                else -> {
                    if ('c' in option) takesCommand = true
                    if ('s' in option) readsInput = true
                }
            }
        }
        val operand = args.getOrNull(i)
        return ShellUse(
            jobControl = jobControlOptions(args, stopAtOperand = true),
            commandString = if (takesCommand) operand else null,
            script = if (takesCommand || readsInput) null else operand,
            startFiles = startFiles,
        )
    }

    /** `-m`, `-i` (for a shell) or `-o monitor` among [args]. */
    private fun jobControlOptions(args: List<Word>, stopAtOperand: Boolean): Boolean {
        var i = 0
        while (i < args.size) {
            val option = args[i].plain
            val isOption = option != null && option.length >= 2 && (option[0] == '-' || option[0] == '+')
            if (!isOption) {
                if (stopAtOperand) return false
                i++
                continue
            }
            if (option == "--") return false
            if (option[0] == '-' && !option.startsWith("--")) {
                if (option == "-o") {
                    if (args.getOrNull(i + 1)?.plain == "monitor") return true
                    i += 2
                    continue
                }
                if (option == "-omonitor") return true
                // For "set", -i does nothing; for a shell it makes it interactive, which turns job control on.
                if ('m' in option || (stopAtOperand && 'i' in option)) return true
            }
            i++
        }
        return false
    }

    private fun allWords(script: ShellSyntax.Script): List<Word> = buildList {
        for (simple in script.commands) {
            addAll(simple.words)
            simple.assignments.forEach { add(it.second) }
            simple.redirects.forEach { r -> r.target?.let(::add) }
        }
        script.loops.forEach { addAll(it.values) }
        addAll(script.extras)
    }

    /** Text the commands of [script] hand to a shell to run: command substitutions, `sh -c`, `eval`, `trap`, `alias`, and what a bare shell is fed. */
    private fun innerTexts(script: ShellSyntax.Script): List<String> = buildList {
        for (word in allWords(script)) word.pieces.filter { it.kind == Kind.SUB }.forEach { add(it.text) }
        addAll(hereTexts(script))
        for ((index, simple) in script.commands.withIndex()) {
            for (call in calls(simple)) {
                when (call.name) {
                    in SHELLS -> {
                        val use = shellUse(call.args)
                        use.commandString?.plain?.let(::add)
                        if (use.commandString == null && use.script == null) {
                            simple.redirects.forEach { r ->
                                if (r.op == "<<<") r.target?.plain?.let(::add)
                            }
                            // "echo '…' | sh": what is printed is what runs.
                            if (simple.pipedInto) script.commands.getOrNull(index - 1)?.let { before ->
                                if (nameOf(before.words.firstOrNull() ?: return@let) in setOf("echo", "printf")) {
                                    add(before.words.drop(1).mapNotNull { it.plain }.joinToString(" "))
                                }
                            }
                        }
                    }
                    "eval" -> add(call.args.mapNotNull { it.plain }.joinToString(" "))
                    "trap" -> call.args.firstOrNull { it.plain?.startsWith("-") != true }?.plain?.let(::add)
                    "alias" -> call.args.forEach { a -> a.assignment()?.second?.plain?.let(::add) }
                }
            }
        }
    }

    /**
     * The here-documents of [script] that may be run as commands: all of them, except a document
     * that is plainly data, which is one handed to `cat` or `tee` (the way a file is written),
     * with a plain end word, in a command line where no shell reads from a pipe.
     */
    private fun hereTexts(script: ShellSyntax.Script): List<String> {
        val pipedShell = script.commands.any { simple -> simple.pipedInto && calls(simple).any { it.name in SHELLS } }
        return script.commands.flatMap { simple ->
            val data = !pipedShell && nameOf(simple.words.firstOrNull() ?: return@flatMap emptyList<String>()) in DATA_READERS
            simple.redirects.filter { (it.op == "<<" || it.op == "<<-") && !(data && it.plainEnd) }.mapNotNull { it.body }
        }
    }

    private fun changesFolder(text: String, depth: Int): Boolean {
        val script = ShellSyntax.parse(text)
        if (script.commands.any { simple -> calls(simple).any { it.name in FOLDER_CHANGES } }) return true
        return depth < MAX_DEPTH && innerTexts(script).any { changesFolder(it, depth + 1) }
    }

    // ---------------------------------------------------------------- names

    /** Where names resolve: the workspace root as the command may spell it ([roots], absolute paths) and the folder it runs in. */
    class Places(roots: List<String>, val dir: RelativePath) {
        val roots: List<String> = roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

        /** The working folder as an absolute path: what `$HOME`, `~` and, before any `cd`, `$PWD` stand for. */
        val here: String = (this.roots.firstOrNull() ?: "/") + if (dir.isRoot) "" else "/" + dir.value
    }

    /** One name of a path, with the characters that act as a pattern (`*`, `?`, `[`) marked. */
    class Segment(val text: String, private val active: BooleanArray) {
        val isPattern: Boolean get() = active.any { it }

        /** True when [name] matches, as the shell matches file names: a leading dot must be written. */
        fun matches(name: String): Boolean {
            if (name.startsWith(".") && !(text.startsWith(".") && !active[0])) return false
            // A set the shell would not accept either ("[z-a]") matches only a file called just that.
            return regex?.matches(name) ?: (name == text)
        }

        private val regex: Regex? by lazy {
            val out = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    !active[i] -> out.append(Regex.escape(c.toString()))
                    c == '*' -> out.append(".*")
                    c == '?' -> out.append('.')
                    else -> {
                        // "[…]": up to the next "]" that is not the first character of the set.
                        var j = i + 1
                        val negated = j < text.length && (text[j] == '!' || text[j] == '^')
                        if (negated) j++
                        val start = j
                        if (j < text.length && text[j] == ']') j++
                        while (j < text.length && text[j] != ']') j++
                        if (j >= text.length) out.append(Regex.escape("[")) else {
                            out.append(if (negated) "[^" else "[")
                            for (k in start until j) {
                                val d = text[k]
                                val range = d == '-' && k > start && k < j - 1
                                if (!range && d in "\\[]^-&") out.append('\\')
                                out.append(d)
                            }
                            out.append(']')
                            i = j
                        }
                    }
                }
                i++
            }
            try {
                Regex(out.toString(), RegexOption.DOT_MATCHES_ALL)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

    /** What a name stands for inside the workspace. */
    sealed interface Target {
        /** Folders the name steps out of with `..`; each must really be a folder for the name to mean [Entry.path]. */
        val crossed: List<RelativePath>

        /** One path. */
        data class Entry(val path: RelativePath, override val crossed: List<RelativePath>) : Target

        /** A pattern: [base] is the folder its first pattern name is looked up in, [segments] the names from there on. */
        class Pattern(val base: RelativePath, val segments: List<Segment>, override val crossed: List<RelativePath>, val text: String) : Target
    }

    /**
     * A word of a command that names, or may name, something in the workspace.
     *
     * @property raw how it was written
     * @property asScript its text is run: show it
     * @property must it is the script a shell, `.`, `source` or `-f` runs: when it cannot be resolved ([problem]), the command must not run
     * @property shell it is run by a shell, so the scripts it names in turn can be followed
     * @property onlyExisting it matters only when there is such an entry now
     * @property targets what it stands for; several when a variable has several values; empty with a [problem]
     * @property problem why it cannot be resolved to a place in the workspace, as the end of a sentence about it
     */
    class Name(
        val raw: String,
        val asScript: Boolean,
        val must: Boolean,
        val shell: Boolean,
        val onlyExisting: Boolean,
        val targets: List<Target>,
        val problem: String?,
    )

    /**
     * The names in [text] (a command line, or the text of a shell script), at most [MAX_NAMES].
     * With [everyWord], every word that could name a file or folder is returned (also the parts
     * of words split at quotes and `=`); without, only the scripts. [afterFolderChange]: the
     * text runs where an earlier `cd` may have changed the folder.
     */
    fun names(text: String, places: Places, everyWord: Boolean = true, afterFolderChange: Boolean = false): List<Name> {
        val reader = Reader(places, afterFolderChange || changesFolder(text, 0))
        reader.read(text, Variables.NONE, depth = 0, feedsShell = false)
        if (everyWord) reader.splitFurther(text)
        // What could not be read is reported as a script that cannot be resolved, so nothing runs unseen.
        val unread = if (reader.unread) listOf(Name("", asScript = true, must = true, shell = true, onlyExisting = false, emptyList(), TOO_MUCH)) else emptyList()
        return unread + reader.names.filter { everyWord || it.asScript }
    }

    /** True when [text] has a command that changes the working folder. */
    fun changesFolder(text: String): Boolean = changesFolder(text, 0)

    /** What the variables of a piece of shell text can hold, as far as the text itself says. */
    private class Variables(val values: Map<String, List<Word>>, val unknown: Set<String>, val allUnknown: Boolean) {
        fun of(name: String): List<Word>? = if (allUnknown || name in unknown) null else values[name]

        companion object {
            val NONE = Variables(emptyMap(), emptySet(), false)

            fun read(script: ShellSyntax.Script, parent: Variables): Variables {
                val values = HashMap<String, MutableList<Word>>()
                parent.values.forEach { (k, v) -> values[k] = v.toMutableList() }
                val unknown = HashSet(parent.unknown)
                var allUnknown = parent.allUnknown
                for (simple in script.commands) {
                    val calls = calls(simple)
                    val first = calls.firstOrNull()?.name
                    // A sourced or evaluated text can set anything, and so can changing how words are split.
                    if (calls.any { it.name == "eval" || it.name in SOURCE }) allUnknown = true
                    if (simple.words.isEmpty()) {
                        simple.assignments.forEach { (name, value) ->
                            if (name == "IFS") allUnknown = true
                            values.getOrPut(name) { ArrayList() } += value
                        }
                        continue
                    }
                    simple.assignments.forEach { unknown += it.first }
                    for (arg in simple.words.drop(1)) {
                        val assigned = arg.assignment()
                        when {
                            assigned != null && first in DECLARATIONS -> values.getOrPut(assigned.first) { ArrayList() } += assigned.second
                            assigned != null -> unknown += assigned.first
                            else -> arg.plain?.let { if (IDENTIFIER.matches(it)) unknown += it }
                        }
                    }
                }
                script.loops.forEach { loop -> values.getOrPut(loop.name) { ArrayList() } += loop.values }
                for (word in allWords(script)) {
                    word.pieces.filter { it.kind == Kind.DYN }.forEach { p -> IDENTIFIER_IN.findAll(p.text).forEach { unknown += it.value } }
                    word.pieces.firstOrNull()?.let { p -> if (p.kind == Kind.LIT && !p.quoted) APPENDS.find(p.text)?.let { unknown += it.groupValues[1] } }
                }
                return Variables(values, unknown, allUnknown)
            }
        }
    }

    /** A word with nothing left to expand: its text, and which of its characters act as a pattern. */
    private class Spelling(val text: String, val active: BooleanArray) {
        operator fun plus(other: Spelling) = Spelling(text + other.text, active + other.active)

        companion object {
            val EMPTY = Spelling("", BooleanArray(0))

            fun of(text: String, patterns: Boolean) = Spelling(text, BooleanArray(text.length) { patterns && text[it] in "*?[" })
        }
    }

    private class Reader(private val places: Places, private val afterFolderChange: Boolean) {
        val names = ArrayList<Name>()

        /** True when part of the text was not read: it is nested too deeply, or names too many scripts. */
        var unread = false
        private var plain = 0
        private var scripts = 0

        fun read(text: String, parent: Variables, depth: Int, feedsShell: Boolean) {
            if (depth > MAX_DEPTH) {
                unread = true
                return
            }
            val script = ShellSyntax.parse(text)
            val variables = Variables.read(script, parent)
            val roles = java.util.IdentityHashMap<Word, Boolean>()

            fun script(word: Word, shell: Boolean) {
                roles[word] = true
                add(word, variables, depth, asScript = true, must = true, shell = shell, onlyExisting = false, feedsShell = false)
            }

            for ((index, simple) in script.commands.withIndex()) {
                for (call in calls(simple)) {
                    when (call.name) {
                        in SHELLS -> {
                            val use = shellUse(call.args)
                            use.startFiles.forEach { script(it, shell = true) }
                            use.script?.let { script(it, shell = true) }
                            use.commandString?.let { command ->
                                roles[command] = true
                                // The string is expanded by this shell, then read by the new one, which has none of this one's variables.
                                spellings(command, variables, depth)?.forEach { read(it.text, Variables.NONE, depth + 1, feedsShell = false) }
                                substitutions(command, variables, depth, feedsShell = true)
                            }
                            if (use.script == null && use.commandString == null) {
                                // A shell with no script reads its input: a file redirected into it is the script.
                                for (r in simple.redirects) {
                                    when (r.op) {
                                        "<" -> r.target?.let { script(it, shell = true) }
                                        "<&" -> names += Name("<&" + r.target?.raw.orEmpty(), asScript = true, must = true, shell = true, onlyExisting = false, emptyList(), OPEN_FILE)
                                        "<<<" -> r.target?.let { t ->
                                            roles[t] = true
                                            spellings(t, variables, depth)?.forEach { read(it.text, Variables.NONE, depth + 1, feedsShell = false) }
                                            substitutions(t, variables, depth, feedsShell = true)
                                        }
                                    }
                                }
                                // "cat x | sh": the files the command before names are what runs.
                                if (simple.pipedInto) script.commands.getOrNull(index - 1)?.let { before ->
                                    before.words.drop(1).forEach { w ->
                                        add(w, variables, depth, asScript = true, must = false, shell = true, onlyExisting = true, feedsShell = false)
                                    }
                                }
                            }
                        }
                        in SOURCE -> call.args.firstOrNull()?.let { script(it, shell = true) }
                        in PROGRAM_FILE -> programFiles(call).forEach { script(it, shell = false) }
                        "eval" -> {
                            call.args.forEach { roles[it] = true }
                            val joined = call.args.map { spellings(it, variables, depth)?.singleOrNull()?.text }
                            if (joined.all { it != null }) read(joined.joinToString(" "), variables, depth + 1, feedsShell = false)
                            call.args.forEach { substitutions(it, variables, depth, feedsShell = true) }
                        }
                        "trap" -> call.args.firstOrNull { it.plain?.startsWith("-") != true }?.plain?.let { read(it, variables, depth + 1, feedsShell = false) }
                        "alias" -> call.args.forEach { a -> a.assignment()?.second?.plain?.let { read(it, variables, depth + 1, feedsShell = true) } }
                    }
                    // A program started by its path: when that path is a workspace file, it is what runs.
                    if ('/' in call.word.raw || call.word.pieces.any { it.kind == Kind.VAR }) {
                        if (roles.put(call.word, true) == null) {
                            add(call.word, variables, depth, asScript = true, must = false, shell = true, onlyExisting = true, feedsShell = false)
                        }
                    }
                }
                val rest = simple.words + simple.assignments.map { it.second } + simple.redirects.filter { it.op != "<<" && it.op != "<<-" }.mapNotNull { it.target }
                for (word in rest) {
                    if (roles[word] != true) add(word, variables, depth, asScript = false, must = false, shell = false, onlyExisting = true, feedsShell = feedsShell)
                    if (roles[word] != true || word === simple.words.firstOrNull()) substitutions(word, variables, depth, feedsShell)
                }
            }
            for (word in script.loops.flatMap { it.values } + script.extras) {
                add(word, variables, depth, asScript = false, must = false, shell = false, onlyExisting = true, feedsShell = feedsShell)
                substitutions(word, variables, depth, feedsShell)
            }
            // A here-document a shell may run: its commands count like the command line's own.
            hereTexts(script).forEach { read(it, Variables.NONE, depth + 1, feedsShell = false) }
        }

        /** The commands inside the `$(…)` of [word]. With [feedsShell], what they print is run, so the files they name are scripts. */
        private fun substitutions(word: Word, variables: Variables, depth: Int, feedsShell: Boolean) {
            word.pieces.filter { it.kind == Kind.SUB }.forEach { read(it.text, variables, depth + 1, feedsShell) }
        }

        /** The files `-f` and `--file` name for awk and sed. */
        private fun programFiles(call: Call): List<Word> {
            val out = ArrayList<Word>()
            val glued = if (call.name == "sed") SED_FILE else AWK_FILE
            var i = 0
            while (i < call.args.size) {
                val arg = call.args[i++]
                val first = arg.pieces.firstOrNull()?.takeIf { it.kind == Kind.LIT && !it.quoted }?.text ?: continue
                if (first == "--") break
                val match = glued.find(first)
                when {
                    first.startsWith("--file=") -> out += rest(arg, "--file=".length)
                    first == "--file" && arg.pieces.size == 1 -> call.args.getOrNull(i++)?.let { out += it }
                    match != null && (match.value.length < first.length || arg.pieces.size > 1) -> out += rest(arg, match.value.length)
                    match != null -> call.args.getOrNull(i++)?.let { out += it }
                }
            }
            return out
        }

        /** [word] without the first [chars] characters of its first, literal part. */
        private fun rest(word: Word, chars: Int): Word {
            val first = word.pieces.first()
            val pieces = (if (first.text.length > chars) listOf(first.copy(text = first.text.substring(chars))) else emptyList()) + word.pieces.drop(1)
            return Word(pieces, word.raw)
        }

        private fun add(word: Word, variables: Variables, depth: Int, asScript: Boolean, must: Boolean, shell: Boolean, onlyExisting: Boolean, feedsShell: Boolean) {
            // Names that are not scripts are only pinned, so the hundredth may be left out; a script never is.
            if (must || asScript) {
                if (++scripts > MAX_SCRIPT_NAMES) {
                    unread = true
                    return
                }
            } else if (++plain > MAX_NAMES) return
            val option = word.pieces.firstOrNull()?.let { it.kind == Kind.LIT && !it.quoted && it.text.startsWith("-") } == true
            if (!must && (option || word.pieces.isEmpty())) return
            val spellings = spellings(word, variables, depth)
            if (spellings == null) {
                if (must) names += Name(word.raw, asScript, must = true, shell, onlyExisting, emptyList(), lastProblem ?: RUN_TIME)
                return
            }
            val targets = ArrayList<Target>()
            var problem: String? = null
            for (spelling in spellings) {
                if (spelling.text.isEmpty()) continue
                when (val located = locate(spelling, must)) {
                    is Target -> targets += located
                    is String -> problem = problem ?: located
                }
            }
            if (problem != null && !must) problem = null
            if (targets.isEmpty() && problem == null) return
            val ending = spellings.any { s -> SCRIPT_ENDINGS.any { s.text.lowercase(Locale.ROOT).endsWith(it) } }
            val shellEnding = spellings.any { s -> SHELL_ENDINGS.any { s.text.lowercase(Locale.ROOT).endsWith(it) } }
            names += Name(
                word.raw,
                asScript = asScript || ending || feedsShell,
                must = must,
                shell = shell || shellEnding || feedsShell,
                // A script named by its ending is pinned even while it is missing: one that appears before the command runs stops it.
                onlyExisting = !must && onlyExisting && !ending,
                targets = if (problem != null) emptyList() else targets,
                problem = problem,
            )
        }

        private var lastProblem: String? = null

        /** Every text [word] can stand for, or null when part of it is only known at run time ([lastProblem] says why). */
        private fun spellings(word: Word, variables: Variables, depth: Int, nesting: Int = 0): List<Spelling>? {
            lastProblem = null
            var out = listOf(Spelling.EMPTY)
            for (piece in word.pieces) {
                val options: List<Spelling> = when (piece.kind) {
                    Kind.LIT -> {
                        // {a,b} and {1..3} make several words in Android's shell.
                        if (!piece.quoted && BRACES.containsMatchIn(piece.text)) return null
                        listOf(Spelling.of(piece.text, patterns = !piece.quoted))
                    }
                    Kind.VAR -> when (piece.text) {
                        "HOME" -> listOf(Spelling.of(places.here, false))
                        "PWD" -> if (afterFolderChange) return null.also { lastProblem = AFTER_CD } else listOf(Spelling.of(places.here, false))
                        else -> {
                            val values = variables.of(piece.text)
                            if (values.isNullOrEmpty() || nesting > 3) return null
                            values.flatMap { value ->
                                val inner = spellings(value, variables, depth, nesting + 1) ?: return null
                                // Unquoted, the value is split at spaces and its patterns are expanded, however it was quoted when assigned.
                                if (!piece.quoted && inner.any { s -> s.text.any(Char::isWhitespace) }) return null
                                inner.map { Spelling.of(it.text, patterns = !piece.quoted) }
                            }
                        }
                    }
                    Kind.SUB -> {
                        val printed = printed(piece.text, variables, depth, nesting) ?: return null
                        if (!piece.quoted && printed.any(Char::isWhitespace)) return null
                        listOf(Spelling.of(printed, patterns = !piece.quoted))
                    }
                    Kind.DYN -> return null
                }
                out = out.flatMap { a -> options.map { a + it } }
                if (out.size > MAX_SPELLINGS) return null
            }
            return out
        }

        /** What `$(…)` prints when that can be read off its text: `pwd`, or `echo` with fixed words. */
        private fun printed(inner: String, variables: Variables, depth: Int, nesting: Int): String? {
            if (nesting > 3) return null
            val command = ShellSyntax.parse(inner).commands.singleOrNull() ?: return null
            if (command.assignments.isNotEmpty() || command.redirects.isNotEmpty() || command.pipedInto || command.pipesOn) return null
            val name = command.words.firstOrNull()?.takeIf { it.bare }?.plain ?: return null
            val args = command.words.drop(1)
            return when {
                name == "pwd" && args.isEmpty() -> if (afterFolderChange) null.also { lastProblem = AFTER_CD } else places.here
                name == "echo" && args.isNotEmpty() -> args.map { arg ->
                    if (arg.plain?.startsWith("-") == true) return null
                    (spellings(arg, variables, depth, nesting + 1) ?: return null).singleOrNull()?.takeIf { s -> s.active.none { it } }?.text ?: return null
                }.joinToString(" ")
                else -> null
            }
        }

        /** [spelling] as a place in the workspace: a [Target], or why it is none (a String). */
        private fun locate(spelling: Spelling, must: Boolean): Any {
            val text = spelling.text
            val start: List<String>
            val offset: Int
            if (text.startsWith("/")) {
                val root = places.roots.filter { text == it || text.startsWith("$it/") }.maxByOrNull { it.length } ?: return OUTSIDE
                start = emptyList()
                offset = root.length
            } else {
                if (must && afterFolderChange) return AFTER_CD
                start = places.dir.segments
                offset = 0
            }
            val stack = ArrayList<Segment>()
            start.forEach { stack += Segment(it, BooleanArray(it.length)) }
            val crossed = ArrayList<RelativePath>()
            var at = offset
            while (at <= text.length) {
                val end = text.indexOf('/', at).let { if (it < 0) text.length else it }
                val name = text.substring(at, end)
                val active = spelling.active.copyOfRange(at, end)
                at = end + 1
                when (name) {
                    "", "." -> Unit
                    ".." -> {
                        if (stack.isEmpty()) return OUTSIDE
                        // "x*/..": where that leads depends on what the pattern matches.
                        if (stack.any { it.isPattern }) return RUN_TIME
                        crossed += path(stack) ?: return RUN_TIME
                        stack.removeAt(stack.size - 1)
                    }
                    else -> stack += Segment(name, active)
                }
            }
            val firstPattern = stack.indexOfFirst { it.isPattern }
            if (firstPattern < 0) return Target.Entry(path(stack) ?: return RUN_TIME, crossed)
            val base = path(stack.subList(0, firstPattern)) ?: return RUN_TIME
            return Target.Pattern(base, stack.subList(firstPattern, stack.size).toList(), crossed, stack.joinToString("/") { it.text })
        }

        private fun path(segments: List<Segment>): RelativePath? =
            (RelativePath.parse(segments.joinToString("/") { it.text }) as? RelativePath.Check.Valid)?.path?.takeIf { it.segments.size == segments.size }

        /**
         * The parts of words as the text spells them, split at quotes, `=` and punctuation: a name
         * inside a quoted string or after `name=` counts too. Only what exists is of interest.
         */
        fun splitFurther(text: String) {
            val parts = TOKEN.findAll(text).map { it.value }.toList() +
                QUOTED.findAll(text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.filter { ' ' in it && it.length <= RelativePath.MAX_LENGTH }.toList()
            for (part in parts.distinct().take(MAX_NAMES)) {
                if (part.startsWith("-")) continue
                val spelling = if (' ' in part) Spelling.of(part, false) else {
                    val word = ShellSyntax.word(part) ?: continue
                    spellings(word, Variables.NONE, 0)?.singleOrNull() ?: continue
                }
                val target = locate(spelling, must = false) as? Target ?: continue
                val lower = spelling.text.lowercase(Locale.ROOT)
                names += Name(
                    part, asScript = SCRIPT_ENDINGS.any { lower.endsWith(it) }, must = false, shell = SHELL_ENDINGS.any { lower.endsWith(it) },
                    onlyExisting = true, targets = listOf(target), problem = null,
                )
            }
        }
    }

    /** A name: no spaces, quotes or shell punctuation. */
    private val TOKEN = Regex("""[^\s;&|<>()`'"=]+""")

    private val QUOTED = Regex("""'([^']*)'|"([^"]*)"""")

    private val NUMBER = Regex("""^[0-9]+(\.[0-9]+)?[smhd]?$""")

    private val IDENTIFIER = Regex("""^[A-Za-z_][A-Za-z0-9_]*$""")

    private val IDENTIFIER_IN = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    private val APPENDS = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\+=""")

    private val BRACES = Regex("""\{[^{}]*(,|\.\.)[^{}]*\}""")

    private val AWK_FILE = Regex("""^-f""")

    /** `-f`, also after sed's options that take no value (`-nf`). */
    private val SED_FILE = Regex("""^-[nEirsuz]*f""")

    /** `set -m`, `set -o monitor`, or a shell started with -m or -i (interactive shells turn job control on). */
    private val JOB_CONTROL = Regex("""(?:^|[\s;&|(`'"])(?:set\s+-[A-Za-z]*m|(?:sh|ash|bash|dash|ksh|mksh|zsh)\s+(?:-[A-Za-z]+\s+)*-[A-Za-z]*[mi])""")
}
