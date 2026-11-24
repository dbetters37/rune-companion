package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.framework.operator.ShellSyntax.Kind
import com.aicontrolcenter.framework.operator.ShellSyntax.Simple
import com.aicontrolcenter.framework.operator.ShellSyntax.Word
import java.util.Locale

/**
 * What the shell tool reads out of a command line before the owner is asked: the workspace
 * files and folders it names, and the scripts it runs among them, so they can be shown and
 * pinned. Text analysis over [ShellSyntax], not a shell.
 *
 * This is help for the owner, on a best-effort basis: a shell language cannot be read
 * completely, and the tool's guarantees (nothing else changes the workspace while a command is
 * asked for and runs; everything it starts is stopped when it ends) do not depend on it. What
 * it does promise is that it does not guess:
 *
 *  - a name is resolved the way the shell would for a static spelling (quotes and backslashes
 *    removed, `.` and `..` followed inside the workspace, `$PWD` and the workspace's own
 *    absolute path mapped into it, patterns such as `t*.sh` left for the caller to expand), and
 *    a script whose name it cannot resolve like that is reported with the reason ([Name.problem]);
 *  - whatever makes it unsure what the command line means is reported as a doubt
 *    ([Reading.doubts]): a shell option it does not know, a shell fed from something it cannot
 *    resolve, text for a shell that is only put together when the command runs, a program whose
 *    name is, and everything [ShellSyntax] is unsure of;
 *  - when the text changes folder or the way names are looked up ([Reading.unsettledBy]), the
 *    place a relative name points to is not certain, and the caller refuses scripts named so.
 *
 * The caller refuses a command with a problem or a doubt instead of running something the owner
 * was not shown. The lists of shells and of programs that start other programs are incomplete by
 * nature.
 */
internal object ShellCommands {
    private val SHELLS = setOf("sh", "ash", "bash", "dash", "ksh", "mksh", "lksh", "zsh", "rbash", "ksh93", "pdksh", "hush")

    /** The running shell under its other name, and the same for any process: starting it starts a shell. */
    private val PROCESS_PROGRAM = Regex("""^/proc/(?:self|thread-self|[0-9]+)(?:/task/[0-9]+)?/exe$""")

    /** Commands that run their first argument as a script in the current shell. */
    private val SOURCE = setOf(".", "source")

    /** Programs whose `-f` argument is a program file. */
    private val PROGRAM_FILE = setOf("awk", "gawk", "mawk", "nawk", "sed")

    /** Commands that start the command written after their own options. */
    private val PREFIXES = setOf(
        "toybox", "busybox", "toolbox", "env", "command", "exec", "builtin", "nice", "timeout", "time", "nohup", "setsid", "stdbuf", "ionice", "chrt",
        "taskset", "strace", "ltrace", "sudo", "doas", "uclampset", "logwrapper", "linker", "linker64", "linker_asan", "linker_asan64", "setarch",
        "linux32", "linux64", "prlimit", "setpriv", "run-as", "xargs",
    )

    /** Prefix commands that take a word of their own before the command they start (a duration, a mask, an architecture), so which word that command is, is not certain. */
    private val PREFIXES_WITH_OPERAND = setOf("taskset", "setarch", "prlimit", "setpriv", "run-as", "strace", "ltrace", "sudo", "doas", "logwrapper")

    private val DECLARATIONS = setOf("export", "readonly", "local", "typeset", "declare")

    /** Programs that hand what they read to the command they start, as its arguments. */
    private val ARGUMENT_FEEDERS = setOf("xargs", "parallel")

    /** Commands after which a bare name is a variable they set or unset. */
    private val VARIABLE_COMMANDS = DECLARATIONS + setOf("read", "unset", "getopts")

    /** The dynamic loader started by hand: it runs the program named after it. */
    private val LOADER = Regex("""^ld(?:64|-linux[^/]*|-musl[^/]*)?\.so(?:\.[0-9]+)*$""")

    /** Commands that start a command named somewhere among their arguments. */
    private val STARTERS = setOf("watch", "su", "flock", "script", "unshare", "chroot", "nsenter", "runcon", "parallel", "start-stop-daemon", "daemonize")

    private val FIND_EXEC = setOf("-exec", "-execdir", "-ok", "-okdir")

    /** Programs that take a here-document as plain data. */
    private val DATA_READERS = setOf("cat", "tee")


    private val FOLDER_CHANGES = setOf("cd", "pushd", "popd", "chdir")

    /** Variables that decide where a name is looked up, or how the words of a command are split. */
    private val LOOKUP_VARIABLES = listOf("PATH", "CDPATH", "ENV", "HOME", "IFS", "PWD", "OLDPWD")

    /** Variables that decide which start-up files a shell reads, and where a program is looked up. */
    private val START_UP_VARIABLES = setOf("HOME", "ENV", "PATH")

    /** The single-letter options of Android's shell (mksh) that take no value. `c`, `s` and `o` are read on their own. */
    private const val SHELL_FLAGS = "abCefhiklmnpruUvxX"

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
    const val HOME = "it is named through ~ or \$HOME, which is a folder outside the workspace"
    const val PATTERN = "its name uses a pattern this tool cannot match the way the shell does (a character class such as [[:alpha:]], " +
        "an escape inside brackets, or a pattern list such as @(a|b))"
    const val NO_SLASH = "a name without a slash is looked up through PATH by \".\" and source (write ./name)"

    const val DOUBT_TOO_MUCH = "runs more scripts, or wraps commands in commands (sh -c, eval, \$(…)) more deeply, than can be read"
    const val DOUBT_NO_INPUT = "starts a shell that reads its commands from its input, and where that input comes from cannot be told with certainty " +
        "(give the shell its commands directly: sh file, sh -c '…', sh < file or sh <<'EOF')"
    const val DOUBT_PIPE = "pipes into a shell something other than plain files (cat file | sh) or fixed text (echo text | sh)"
    const val DOUBT_OPEN_FILE = "feeds a shell from a file the command opened earlier (<&)"
    const val DOUBT_TEXT = "hands a shell text that is only put together when the command runs (sh -c, eval, trap), so what it runs cannot be read"
    const val DOUBT_HERE_EXPANDS = "feeds a shell a here-document whose text is changed before the shell reads it (quote the end word: <<'EOF')"
    const val DOUBT_ALIAS = "defines an alias, which changes what the words after it mean"
    fun doubtStartUp(name: String) = "sets $name to a place in the workspace, or to a value that is only known when the command runs; what is started " +
        "after that would be looked up there, or would read its start-up files from there, and could not be shown"

    const val DOUBT_FED_ARGUMENTS = "starts a shell through xargs without naming its script, so what it runs comes from what xargs reads"

    fun doubtProgram(raw: String) = "starts a program whose name is only put together when it runs (${OperatorText.q(raw.take(80))}); write the program's name plainly"

    fun doubtOption(option: String) = "starts a shell with an option this tool does not know (${OperatorText.q(option.take(40))})"

    /** What makes the place a relative name points to uncertain, for messages. */
    const val UNSETTLED_FOLDER = "changes folder (cd)"
    const val UNSETTLED_LOOKUP = "sets or may set a variable that decides where names are looked up (PATH, CDPATH, ENV, HOME, IFS, PWD)"

    // ---------------------------------------------------------------- names

    /** Where names resolve: the workspace root as the command may spell it ([roots], absolute paths) and the folder it runs in. */
    class Places(roots: List<String>, val dir: RelativePath) {
        val roots: List<String> = roots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

        /** The working folder as an absolute path: what `$PWD` stands for before any `cd`. */
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
     * @property asScript its text is run, or may be: show it
     * @property must it is the script a shell, `.`, `source` or `-f` runs, or what a shell is fed: it must be an existing workspace file, or the command must not run
     * @property shell it is run by a shell, so the scripts it names in turn can be followed
     * @property sourced it is read into the shell that names it (`.`, `source`, `eval "$(cat …)"`): what it changes (the folder, PATH) changes for that shell too
     * @property relative where it points depends on the folder the shell is in when it gets there (no leading `/`, or built on `$PWD`)
     * @property incidental it was only found as a piece of text (inside quotes, after `=`), not as a word of a command
     * @property onlyExisting it matters only when there is such an entry now
     * @property targets what it stands for; several when a variable has several values; empty with a [problem]
     * @property problem why it cannot be resolved to a place in the workspace, as the end of a sentence about it
     */
    class Name(
        val raw: String,
        val asScript: Boolean,
        val must: Boolean,
        val shell: Boolean,
        val sourced: Boolean,
        val relative: Boolean,
        val incidental: Boolean,
        val onlyExisting: Boolean,
        val targets: List<Target>,
        val problem: String?,
    )

    /**
     * What was read out of one text.
     *
     * @property names the names, at most [MAX_NAMES] that are not scripts
     * @property doubts why the text cannot be read with certainty; each completes "The command …" (or "The script … "). With one, nothing may run
     * @property unsettledBy what in this text makes the place of a relative name uncertain ([UNSETTLED_FOLDER], [UNSETTLED_LOOKUP]), or null
     */
    class Reading(val names: List<Name>, val doubts: List<String>, val unsettledBy: String?)

    /**
     * Reads [text], a command line or the text of a shell script. With [everyWord], every word
     * that could name a file or folder is returned (also the parts of words split at quotes and
     * `=`); without, only the scripts.
     */
    fun read(text: String, places: Places, everyWord: Boolean = true): Reading {
        val reader = Reader(places)
        reader.read(text, Variables.NONE, depth = 0)
        if (everyWord) reader.splitFurther(text)
        return Reading(reader.names.filter { everyWord || it.asScript }, reader.doubts.distinct(), reader.unsettledBy)
    }

    // ---------------------------------------------------------------- reading commands

    /** A command as it would be started: its [name] (the last part of its path, lower case; "sh" for the shell under another name) and arguments. */
    private class Call(val name: String, val word: Word, val args: List<Word>)

    /** `*`, `?` or a `[…]` set. A lone `[` (the test command) is not one. */
    private fun isPattern(text: String): Boolean = '*' in text || '?' in text || text.indexOf('[').let { it >= 0 && text.indexOf(']', it + 2) > 0 }

    /** True when [word] starts with an unquoted "-": an option to the program it is given to. */
    private fun isOption(word: Word): Boolean = word.pieces.firstOrNull()?.let { it.kind == Kind.LIT && !it.quoted && it.text.startsWith("-") } == true

    /** The first words of [simple] as far as they are plain names: enough to tell a declaration, `eval` or `.`. */
    private fun plainNames(simple: Simple): List<String> =
        simple.words.takeWhile { it.plain != null }.take(4).map { it.plain!!.substringAfterLast('/').lowercase(Locale.ROOT) }

    /** How a shell is started: with a command string (`-c`), a script file, files it reads first, or nothing (it reads its input). */
    private class ShellUse(val commandString: Word?, val script: Word?, val startFiles: List<Word>, val unknownOption: String?) {
        /** It has neither a script nor a command string: its commands come from its input. */
        val readsInput: Boolean get() = commandString == null && script == null
    }

    /**
     * Reads a shell's own arguments the way mksh does: single-letter options in groups after `-`
     * or `+`, where `o` takes the option's name from the rest of its group or from the next word
     * (`-o errexit`, `-eo errexit`, `-oerrexit`); `--` or a lone `-` ends the options; with `-c`
     * the first word after them is the command string, with `-s` the commands come from the
     * input, otherwise the first word after them is the script. Anything else that looks like an
     * option is not known here and is reported.
     */
    private fun shellUse(args: List<Word>): ShellUse {
        var takesCommand = false
        var fromInput = false
        var unknown: String? = null
        val startFiles = ArrayList<Word>()
        var i = 0
        while (i < args.size) {
            // A word that is not plain text could be an option, the script or nothing at all: it is taken as the operand and checked there.
            val option = args[i].plain ?: break
            if (option == "--" || option == "-") {
                i++
                break
            }
            if (option.length < 2 || (option[0] != '-' && option[0] != '+')) break
            i++
            if (option.startsWith("--")) {
                // bash's two options that name a file it reads first; no other long option is known.
                if (option == "--rcfile" || option == "--init-file") args.getOrNull(i++)?.let { startFiles += it } else unknown = unknown ?: option
                continue
            }
            var j = 1
            while (j < option.length) {
                when (val c = option[j++]) {
                    'c' -> takesCommand = true
                    's' -> fromInput = true
                    'o' -> {
                        val name = if (j < option.length) option.substring(j) else args.getOrNull(i++)?.plain
                        if (name == null || !OPTION_NAME.matches(name)) unknown = unknown ?: option
                        j = option.length
                    }
                    else -> if (c !in SHELL_FLAGS) unknown = unknown ?: option
                }
            }
        }
        val operand = args.getOrNull(i)
        return ShellUse(
            commandString = if (takesCommand) operand else null,
            script = if (takesCommand || fromInput) null else operand,
            startFiles = startFiles,
            unknownOption = unknown,
        )
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
                    val names = plainNames(simple)
                    val first = names.firstOrNull()
                    // A sourced or evaluated text can set anything, and so can changing how words are split.
                    if (names.any { it == "eval" || it in SOURCE }) allUnknown = true
                    // A name reference ("typeset -n ref=f", mksh's nameref): assigning to one variable then changes another.
                    if (first == "nameref" || (first in DECLARATIONS && simple.words.drop(1).any { isOption(it) && 'n' in it.plain.orEmpty() })) allUnknown = true
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

    /**
     * A word with nothing left to expand: its text, and which of its characters act as a pattern.
     * [home]: part of it is the home folder (`~`, `$HOME`), which is outside the workspace; the
     * text then holds `${HOME}` in its place. [fromFolder]: part of it is the folder the shell is
     * in (`$PWD`, `$(pwd)`).
     */
    private class Spelling(val text: String, val active: BooleanArray, val home: Boolean = false, val fromFolder: Boolean = false) {
        operator fun plus(other: Spelling) = Spelling(text + other.text, active + other.active, home || other.home, fromFolder || other.fromFolder)

        /** True when the text ends inside a `[…` set that an unquoted `[` opened. */
        fun inOpenSet(): Boolean {
            var k = 0
            while (k < text.length) {
                if (!(active[k] && text[k] == '[')) {
                    k++
                    continue
                }
                var j = k + 1
                if (j < text.length && (text[j] == '!' || text[j] == '^')) j++
                if (j < text.length && text[j] == ']') j++
                val close = text.indexOf(']', j)
                if (close < 0) return true
                k = close + 1
            }
            return false
        }

        /** True when the shell would expand part of it against file names: `*`, `?`, or a `[…]` set (a lone `[` is the test command). */
        fun hasPattern(): Boolean = text.indices.any { active[it] && (text[it] != '[' || text.indexOf(']', it + 2) > 0) }

        companion object {
            val EMPTY = Spelling("", BooleanArray(0))

            fun of(text: String, patterns: Boolean) = Spelling(text, BooleanArray(text.length) { patterns && text[it] in "*?[" })
        }
    }

    private class Reader(private val places: Places) {
        val names = ArrayList<Name>()
        val doubts = ArrayList<String>()
        var unsettledBy: String? = null
        private var plain = 0
        private var scripts = 0

        private fun doubt(reason: String) {
            doubts += reason
        }

        /** Reads [text] as commands; [parent] holds what the variables of the shell that runs it can be. */
        fun read(text: String, parent: Variables, depth: Int) {
            if (depth > MAX_DEPTH) {
                doubt(DOUBT_TOO_MUCH)
                return
            }
            val script = ShellSyntax.parse(text)
            doubts += script.doubts
            val variables = Variables.read(script, parent)
            if (setsLookupVariable(script)) unsettledBy = unsettledBy ?: UNSETTLED_LOOKUP
            startUpDoubts(script)
            val roles = java.util.IdentityHashMap<Word, Boolean>()
            // Here-documents that are certainly a shell's input: their text is commands, and it must reach the shell as written.
            val shellFed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ShellSyntax.Redirect, Boolean>())

            fun script(word: Word, shell: Boolean, sourced: Boolean = false) {
                roles[word] = true
                addScript(word, variables, depth, shell, sourced)
            }

            for ((index, simple) in script.commands.withIndex()) {
                val calls = callsOf(simple.words, variables, depth)
                // "xargs sh": the script's name is the first thing xargs reads.
                val fedArguments = calls.firstOrNull()?.name in ARGUMENT_FEEDERS
                for (call in calls) {
                    when (call.name) {
                        in SHELLS -> {
                            val use = shellUse(call.args)
                            use.unknownOption?.let { doubt(doubtOption(it)) }
                            use.startFiles.forEach { script(it, shell = true) }
                            use.script?.let { script(it, shell = true) }
                            use.commandString?.let { command ->
                                roles[command] = true
                                // The string is expanded by this shell, then read by the new one, which has none of this one's variables.
                                textFor(listOf(command), variables, depth, sameShell = false)
                            }
                            if (use.readsInput) {
                                if (fedArguments) doubt(DOUBT_FED_ARGUMENTS) else inputOf(simple, index, script, variables, depth, shellFed, roles)
                            }
                        }
                        in SOURCE -> if (call.args.isEmpty()) {
                            if (fedArguments) doubt(DOUBT_FED_ARGUMENTS)
                        } else call.args.first().let { file ->
                            // "." looks a name without a slash up through PATH first, so only "./name" is certain.
                            val spelled = spellings(file, variables, depth)
                            if (spelled != null && spelled.any { '/' !in it.text }) {
                                roles[file] = true
                                names += problemName(file.raw, NO_SLASH)
                            } else {
                                script(file, shell = true, sourced = true)
                            }
                        }
                        in PROGRAM_FILE -> programFiles(call).forEach { script(it, shell = false) }
                        "eval" -> {
                            call.args.forEach { roles[it] = true }
                            if (call.args.isNotEmpty()) textFor(call.args, variables, depth, sameShell = true)
                        }
                        "trap" -> call.args.firstOrNull { it.plain?.startsWith("-") != true || it.plain == "-" }?.let { action ->
                            // "trap - INT" and "trap '' INT" run nothing; "trap INT" alone names a signal.
                            if (call.args.size > 1 && action.plain != "-" && action.plain != "") {
                                roles[action] = true
                                textFor(listOf(action), variables, depth, sameShell = true)
                            }
                        }
                        "alias" -> if (call.args.any { it.assignment() != null || it.plain == null }) doubt(DOUBT_ALIAS)
                        in FOLDER_CHANGES -> unsettledBy = UNSETTLED_FOLDER
                    }
                    // A program started by its path: when that path is a workspace file, it is what runs.
                    if ('/' in call.word.raw || call.word.pieces.any { it.kind == Kind.VAR }) {
                        if (roles.put(call.word, true) == null) {
                            val spelled = spellings(call.word, variables, depth)?.singleOrNull()?.text
                            if (spelled != null && spelled.startsWith("/proc/") && !PROCESS_PROGRAM.matches(spelled)) {
                                // "/proc/self/cwd/x" is a workspace file under a name that cannot be resolved here.
                                names += problemName(call.word.raw, OUTSIDE)
                            } else {
                                add(call.word, variables, depth, asScript = true, shell = true, onlyExisting = true)
                            }
                        }
                    }
                }
                val rest = simple.words + simple.assignments.map { it.second } + simple.redirects.filter { it.op != "<<" && it.op != "<<-" }.mapNotNull { it.target }
                for (word in rest) {
                    if (roles[word] != true) add(word, variables, depth, asScript = false, shell = false, onlyExisting = true)
                    if (roles[word] != true || word === simple.words.firstOrNull()) substitutions(word, variables, depth)
                }
            }
            for (word in script.loops.flatMap { it.values } + script.extras) {
                add(word, variables, depth, asScript = false, shell = false, onlyExisting = true)
                substitutions(word, variables, depth)
            }
            hereDocuments(script, variables, depth, shellFed)
        }

        /**
         * True when [script] assigns, or may assign, one of [LOOKUP_VARIABLES]: plainly, in front
         * of a command, through export, read, unset and their like, as a loop's variable, or
         * with `${NAME:=…}`. The one thing left out is `IFS= read …`, the usual way of reading a
         * line, where the assignment holds for that `read` alone.
         */
        private fun setsLookupVariable(script: ShellSyntax.Script): Boolean {
            val set = HashSet<String>()
            for (simple in script.commands) {
                val first = plainNames(simple).firstOrNull()
                simple.assignments.forEach { (name, _) -> if (!(name == "IFS" && first == "read")) set += name }
                for (arg in simple.words.drop(1)) {
                    arg.assignment()?.let { set += it.first }
                    if (first in VARIABLE_COMMANDS || first == "env") arg.plain?.let { if (IDENTIFIER.matches(it)) set += it }
                }
            }
            script.loops.forEach { set += it.name }
            for (word in allWords(script)) {
                word.pieces.filter { it.kind == Kind.DYN }.forEach { p -> ASSIGNING_EXPANSION.findAll(p.text).forEach { set += it.groupValues[1] } }
                word.pieces.firstOrNull()?.let { p -> if (p.kind == Kind.LIT && !p.quoted) APPENDS.find(p.text)?.let { set += it.groupValues[1] } }
            }
            return LOOKUP_VARIABLES.any { it in set }
        }

        /**
         * HOME, ENV and PATH decide which start-up files a shell reads and where a program's name
         * is looked up. The runner sets them to places outside the workspace (G3); a text that
         * sets one of them to a place in the workspace, or to a value that cannot be read here,
         * takes that back, so it is a doubt. Setting them to a fixed place outside the workspace
         * is fine, and so are `unset` and a bare `export`.
         */
        private fun startUpDoubts(script: ShellSyntax.Script) {
            fun check(name: String, value: Word?) {
                if (name in START_UP_VARIABLES && (value == null || !outside(name, value))) doubt(doubtStartUp(name))
            }
            for (simple in script.commands) {
                val first = plainNames(simple).firstOrNull()
                simple.assignments.forEach { (name, value) -> check(name, value) }
                for (arg in simple.words.drop(1)) {
                    if (first in DECLARATIONS || first == "env") arg.assignment()?.let { check(it.first, it.second) }
                    // "read HOME", "getopts x HOME": a value from somewhere else.
                    if (first == "read" || first == "getopts") arg.plain?.let { check(it, null) }
                }
            }
            script.loops.forEach { check(it.name, null) }
            for (word in allWords(script)) {
                word.pieces.filter { it.kind == Kind.DYN }.forEach { p -> ASSIGNING_EXPANSION.findAll(p.text).forEach { check(it.groupValues[1], null) } }
                word.pieces.firstOrNull()?.let { p -> if (p.kind == Kind.LIT && !p.quoted) APPENDS.find(p.text)?.let { check(it.groupValues[1], null) } }
            }
        }

        /** True when [value], assigned to [name], is fixed text that names only places outside the workspace (or nothing). */
        private fun outside(name: String, value: Word): Boolean {
            val text = StringBuilder()
            for (piece in value.pieces) {
                when (piece.kind) {
                    Kind.LIT -> text.append(piece.text)
                    // The runner's own values: its PATH is a system folder, its HOME a folder outside the workspace.
                    Kind.VAR -> when {
                        piece.text == "PATH" && name == "PATH" -> text.append("/system/bin")
                        piece.text == "HOME" -> text.append("/home-outside-the-workspace")
                        else -> return false
                    }
                    else -> return false
                }
            }
            if (text.isEmpty()) return name != "PATH"
            // In PATH an empty part means the folder the shell is in.
            val parts = if (name == "PATH") text.split(':') else listOf(text.toString())
            return parts.all { part ->
                part.startsWith("/") && !part.startsWith("/proc/") && part.split('/').none { it == ".." } &&
                    places.roots.none { root -> part == root || part.startsWith("$root/") || root.startsWith("${part.trimEnd('/')}/") }
            }
        }

        /**
         * The here-documents of [script]. One that is plainly data (handed to `cat` or `tee`, the
         * way a file is written, in a command line where no shell reads from a pipe) is not read
         * as commands; every other one may be a shell's input and is. Whoever it is for, the
         * `$(…)` in a document whose end word is not quoted are commands that run.
         */
        private fun hereDocuments(script: ShellSyntax.Script, variables: Variables, depth: Int, shellFed: Set<ShellSyntax.Redirect>) {
            val pipedShell = script.commands.any { simple -> simple.pipedInto && plainNames(simple).any { it in SHELLS } }
            for (simple in script.commands) {
                val data = !pipedShell && plainNames(simple).firstOrNull() in DATA_READERS
                for (redirect in simple.redirects) {
                    if (redirect.op != "<<" && redirect.op != "<<-") continue
                    val body = redirect.body ?: continue
                    val forShell = redirect in shellFed
                    // A shell must get its commands as they are written here, or what it runs is not what was read.
                    if (forShell && !redirect.quotedEnd && redirect.expansions.isNotEmpty()) doubt(DOUBT_HERE_EXPANDS)
                    if (forShell || !data) read(body, Variables.NONE, depth + 1)
                    redirect.expansions.filter { it.kind == Kind.SUB }.forEach { read(it.text, variables, depth + 1) }
                }
            }
        }

        /**
         * Where the shell started by [simple] without a script or a command string gets its
         * commands: a file redirected into it, a here-document or here-string, or a pipe from
         * `cat` of plain files or `echo` of fixed text. Anything else is a doubt: a bare shell
         * inside braces or a subshell whose input is redirected outside, one fed by `exec <`, by
         * a pipe from another program, or by nothing that can be seen.
         */
        private fun inputOf(
            simple: Simple,
            index: Int,
            script: ShellSyntax.Script,
            variables: Variables,
            depth: Int,
            shellFed: MutableSet<ShellSyntax.Redirect>,
            roles: java.util.IdentityHashMap<Word, Boolean>,
        ) {
            var fed = false
            for (r in simple.redirects) {
                when (r.op) {
                    "<", "<>" -> {
                        fed = true
                        val target = r.target
                        if (target == null) doubt(DOUBT_NO_INPUT) else {
                            roles[target] = true
                            addScript(target, variables, depth, shell = true, sourced = false)
                        }
                    }
                    "<&" -> {
                        fed = true
                        doubt(DOUBT_OPEN_FILE)
                    }
                    "<<<" -> {
                        fed = true
                        r.target?.let { t ->
                            roles[t] = true
                            textFor(listOf(t), variables, depth, sameShell = false)
                        }
                    }
                    "<<", "<<-" -> {
                        fed = true
                        shellFed += r
                    }
                }
            }
            if (fed) return
            val before = if (simple.pipedInto) script.commands.getOrNull(index - 1) else null
            if (before == null) {
                doubt(DOUBT_NO_INPUT)
                return
            }
            // "( cat a; echo ls ) | sh": the command before the pipe is not all that is piped.
            if (simple.pipedFromGroup) {
                doubt(DOUBT_PIPE)
                return
            }
            val program = before.words.firstOrNull()?.takeIf { it.bare }?.plain
            val operands = before.words.drop(1)
            when {
                program == "cat" -> {
                    // Options change what cat prints; only "--" is read.
                    if (operands.any { it.plain != "--" && isOption(it) }) {
                        doubt(DOUBT_PIPE)
                        return
                    }
                    val files = operands.filter { it.plain != "--" }.toMutableList()
                    var other = false
                    for (r in before.redirects) {
                        when (r.op) {
                            "<", "<>" -> r.target?.let { files += it } ?: doubt(DOUBT_PIPE)
                            "<<", "<<-" -> {
                                shellFed += r
                                other = true
                            }
                            "<<<" -> {
                                other = true
                                r.target?.let { t ->
                                    roles[t] = true
                                    textFor(listOf(t), variables, depth, sameShell = false)
                                }
                            }
                            "<&" -> doubt(DOUBT_OPEN_FILE)
                        }
                    }
                    if (files.isEmpty() && !other) doubt(DOUBT_PIPE)
                    files.forEach { file ->
                        roles[file] = true
                        addScript(file, variables, depth, shell = true, sourced = false)
                    }
                }
                program == "echo" -> {
                    // "echo text | sh": what is printed is what runs. Options and backslashes change what is printed.
                    val words = if (operands.firstOrNull()?.plain == "-n") operands.drop(1) else operands
                    if (words.isEmpty() || words.any { w -> w.pieces.any { it.kind == Kind.LIT && '\\' in it.text } || (w.bare && w.plain.orEmpty().startsWith("-")) }) {
                        doubt(DOUBT_PIPE)
                        return
                    }
                    words.forEach { roles[it] = true }
                    textFor(words, variables, depth, sameShell = false)
                }
                else -> doubt(DOUBT_PIPE)
            }
        }

        /**
         * [words] joined by spaces are text a shell runs (`sh -c`, `eval`, `trap`, a here-string).
         * It is read when every word has one fixed spelling; or, for a single word that is just
         * `$(cat file …)`, those files are the script. Otherwise the text is only known when the
         * command runs, which is a doubt. [sameShell]: the text runs in the shell that holds
         * these variables (`eval`), not in a new one.
         */
        private fun textFor(words: List<Word>, variables: Variables, depth: Int, sameShell: Boolean) {
            val spelled = words.map { spellings(it, variables, depth)?.singleOrNull()?.takeIf { s -> !s.hasPattern() } }
            if (spelled.all { it != null }) {
                read(spelled.joinToString(" ") { it!!.text }, if (sameShell) variables else Variables.NONE, depth + 1)
                return
            }
            val files = words.singleOrNull()?.let(::catFiles)
            if (files == null) {
                doubt(DOUBT_TEXT)
                return
            }
            files.forEach { addScript(it, variables, depth, shell = true, sourced = sameShell) }
        }

        /** The files of a word that is exactly `$(cat file …)` or `$(<file)`, quoted or not; null for any other word. */
        private fun catFiles(word: Word): List<Word>? {
            val sub = word.pieces.filter { !(it.kind == Kind.LIT && it.text.isEmpty()) }.singleOrNull()?.takeIf { it.kind == Kind.SUB } ?: return null
            val parsed = ShellSyntax.parse(sub.text)
            val command = parsed.commands.singleOrNull() ?: return null
            if (parsed.doubts.isNotEmpty() || command.assignments.isNotEmpty() || command.pipedInto || command.pipesOn) return null
            val inputs = command.redirects.map { r -> r.target?.takeIf { r.op == "<" } ?: return null }
            if (command.words.isEmpty()) return inputs.takeIf { it.size == 1 }
            if (command.words.first().let { !it.bare || it.plain != "cat" }) return null
            val operands = command.words.drop(1)
            if (operands.any(::isOption)) return null
            return (operands + inputs).takeIf { it.isNotEmpty() }
        }

        /**
         * The command [words] start, and the commands that one starts in turn when it is a wrapper
         * (`env`, `toybox`, `find -exec` …). A program whose name is only known when the command
         * runs could be a shell, so it is a doubt.
         */
        private fun callsOf(words: List<Word>, variables: Variables, depth: Int): List<Call> {
            val out = ArrayList<Call>()
            val used = HashSet<Int>()
            var k = 0
            // The word taken as the wrapped command may be the value of the option before it.
            var unsure = false
            while (k < words.size) {
                val name = programName(words[k], variables, depth)
                if (name == null) {
                    doubt(doubtProgram(words[k].raw))
                    return out
                }
                out += Call(name, words[k], words.subList(k + 1, words.size))
                used += k
                if (name !in PREFIXES && !LOADER.matches(name)) break
                // "command -v sh" only asks where sh is.
                if (name == "command" && words.getOrNull(k + 1)?.plain.let { it == "-v" || it == "-V" }) break
                if (name in PREFIXES_WITH_OPERAND) unsure = true
                // What a prefix command starts: the first later word that is not an option, an assignment or a number.
                k++
                var afterOption = false
                while (k < words.size) {
                    val w = words[k].plain ?: break
                    val option = w.startsWith("-") || w.startsWith("+")
                    if (!option && !ASSIGNED.containsMatchIn(w) && !NUMBER.matches(w)) break
                    afterOption = option
                    k++
                }
                if (afterOption) unsure = true
            }
            val first = out.firstOrNull()?.name ?: return out
            if (first == "find") {
                // What follows -exec is a command of its own, up to ";" or "+".
                for (j in 1 until words.size) {
                    if (words[j - 1].plain !in FIND_EXEC) continue
                    val end = (j until words.size).firstOrNull { words[it].plain == ";" || words[it].plain == "+" } ?: words.size
                    out += callsOf(words.subList(j, end), variables, depth)
                }
            } else if (first in STARTERS || unsure) {
                // Which of the later words is the command they start is not clear (options take values), so every
                // word that names a shell or another program that runs a script file counts.
                for (j in 1 until words.size) {
                    if (j in used) continue
                    val name = words[j].plain?.let { programName(words[j], variables, depth) } ?: continue
                    // Not ".": as an argument it is the folder, far more often than the command.
                    if (name in SHELLS || name in PROGRAM_FILE || name == "eval" || name == "source") out += Call(name, words[j], words.subList(j + 1, words.size))
                }
            }
            return out
        }

        /** The program [word] starts: the last part of its path, lower case; "sh" for a process's own program; null when it is only known at run time. */
        private fun programName(word: Word, variables: Variables, depth: Int): String? {
            val text = word.plain?.also {
                if (word.pieces.any { p -> !p.quoted && isPattern(p.text) }) return null
            } ?: spellings(word, variables, depth)?.singleOrNull()?.takeIf { s -> !s.hasPattern() && s.text.isNotEmpty() }?.text ?: return null
            if (PROCESS_PROGRAM.matches(text)) return "sh"
            return text.substringAfterLast('/').lowercase(Locale.ROOT)
        }

        /** The commands inside the `$(…)` of [word]. */
        private fun substitutions(word: Word, variables: Variables, depth: Int) {
            word.pieces.filter { it.kind == Kind.SUB }.forEach { read(it.text, variables, depth + 1) }
        }

        /** The files `-f` and `--file` name for awk and sed, and gawk's other ways of naming a program file. */
        private fun programFiles(call: Call): List<Word> {
            val out = ArrayList<Word>()
            val sed = call.name == "sed"
            val glued = if (sed) SED_FILE else AWK_FILE
            val long = if (sed) listOf("--file") else listOf("--file", "--exec", "--include")
            var i = 0
            while (i < call.args.size) {
                val arg = call.args[i++]
                val first = arg.pieces.firstOrNull()?.takeIf { it.kind == Kind.LIT && !it.quoted }?.text ?: continue
                if (first == "--") break
                val match = glued.find(first)
                val option = long.firstOrNull { first == it || first.startsWith("$it=") }
                // An option that names a program file but has no word after it gets that word from elsewhere (xargs): not known here.
                val next = { call.args.getOrNull(i++)?.let { out += it } ?: run { names += problemName(first, RUN_TIME) } }
                when {
                    option != null && first.length > option.length -> out += rest(arg, option.length + 1)
                    option != null && arg.pieces.size == 1 -> next()
                    match != null && (match.value.length < first.length || arg.pieces.size > 1) -> out += rest(arg, match.value.length)
                    match != null -> next()
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

        private fun problemName(raw: String, problem: String) =
            Name(raw, asScript = true, must = true, shell = true, sourced = false, relative = false, incidental = false, onlyExisting = false, emptyList(), problem)

        /** [word] is the script a shell, `.`, `source` or `-f` runs, or a file a shell is fed: it must resolve to a place in the workspace. */
        private fun addScript(word: Word, variables: Variables, depth: Int, shell: Boolean, sourced: Boolean) {
            if (++scripts > MAX_SCRIPT_NAMES) {
                doubt(DOUBT_TOO_MUCH)
                return
            }
            val spellings = spellings(word, variables, depth)
            if (spellings == null) {
                names += problemName(word.raw, lastProblem ?: RUN_TIME)
                return
            }
            // Nothing to run: an empty input.
            if (spellings.all { it.text == "/dev/null" }) return
            // A word that expands to nothing is no word at all, and the shell takes the next one; one that expands to an option is not the script either.
            if (spellings.any { it.text.isEmpty() || (word.plain == null && (it.text.startsWith("-") || it.text.startsWith("+"))) }) {
                names += problemName(word.raw, RUN_TIME)
                return
            }
            val targets = ArrayList<Target>()
            for (spelling in spellings) {
                when (val located = locate(spelling)) {
                    is Target -> targets += located
                    is String -> {
                        names += problemName(word.raw, located)
                        return
                    }
                }
            }
            names += Name(
                word.raw, asScript = true, must = true, shell = shell, sourced = sourced, relative = spellings.any(::relative),
                incidental = false, onlyExisting = false, targets = targets, problem = null,
            )
        }

        /** [word] names, or may name, a workspace entry without being a script the reader is sure of. */
        private fun add(word: Word, variables: Variables, depth: Int, asScript: Boolean, shell: Boolean, onlyExisting: Boolean) {
            // Names that are not scripts are only pinned, so the hundredth may be left out; a script never is.
            if (asScript) {
                if (++scripts > MAX_SCRIPT_NAMES) {
                    doubt(DOUBT_TOO_MUCH)
                    return
                }
            } else if (++plain > MAX_NAMES) return
            if (isOption(word) || word.pieces.isEmpty()) return
            val spellings = spellings(word, variables, depth) ?: return
            val targets = spellings.filter { it.text.isNotEmpty() }.mapNotNull { locate(it) as? Target }
            if (targets.isEmpty()) return
            val ending = spellings.any { s -> SCRIPT_ENDINGS.any { s.text.lowercase(Locale.ROOT).endsWith(it) } }
            val shellEnding = spellings.any { s -> SHELL_ENDINGS.any { s.text.lowercase(Locale.ROOT).endsWith(it) } }
            names += Name(
                word.raw,
                asScript = asScript || ending,
                must = false,
                shell = shell || shellEnding,
                sourced = false,
                relative = spellings.any(::relative),
                incidental = false,
                // A script named by its ending is pinned even while it is missing: one that appears before the command runs stops it.
                onlyExisting = onlyExisting && !ending,
                targets = targets,
                problem = null,
            )
        }

        private fun relative(spelling: Spelling): Boolean = spelling.fromFolder || !spelling.text.startsWith("/")

        private var lastProblem: String? = null

        /** Every text [word] can stand for, or null when part of it is only known at run time ([lastProblem] says why, when it is not just that). */
        private fun spellings(word: Word, variables: Variables, depth: Int, nesting: Int = 0): List<Spelling>? {
            lastProblem = null
            var out = listOf(Spelling.EMPTY)
            for (piece in word.pieces) {
                // Inside "[…": a quoted character, or one from a variable, is an escape in a set, which is not matched here as the shell does.
                if (out.any { it.inOpenSet() } && !(piece.kind == Kind.LIT && !piece.quoted)) return null.also { lastProblem = PATTERN }
                val options: List<Spelling> = when (piece.kind) {
                    Kind.LIT -> {
                        // {a,b} and {1..3} make several words in Android's shell.
                        if (!piece.quoted && BRACES.containsMatchIn(piece.text)) return null
                        if (!piece.quoted && CHARACTER_CLASS.containsMatchIn(piece.text)) return null.also { lastProblem = PATTERN }
                        listOf(Spelling.of(piece.text, patterns = !piece.quoted))
                    }
                    Kind.VAR -> when (piece.text) {
                        "HOME" -> listOf(Spelling("\${HOME}", BooleanArray(7), home = true))
                        "PWD" -> listOf(Spelling(places.here, BooleanArray(places.here.length), fromFolder = true))
                        else -> {
                            val values = variables.of(piece.text)
                            if (values.isNullOrEmpty() || nesting > 3) return null
                            values.flatMap { value ->
                                val inner = spellings(value, variables, depth, nesting + 1) ?: return null
                                // Unquoted, the value is split at spaces and its patterns are expanded, however it was quoted when assigned.
                                if (!piece.quoted && inner.any { s -> s.text.any(Char::isWhitespace) }) return null
                                if (!piece.quoted && inner.any { s -> CHARACTER_CLASS.containsMatchIn(s.text) }) return null.also { lastProblem = PATTERN }
                                inner.map { Spelling(it.text, BooleanArray(it.text.length) { i -> !piece.quoted && it.text[i] in "*?[" }, it.home, it.fromFolder) }
                            }
                        }
                    }
                    Kind.SUB -> {
                        val printed = printed(piece.text, variables, depth, nesting) ?: return null
                        if (!piece.quoted && printed.text.any(Char::isWhitespace)) return null
                        listOf(Spelling(printed.text, BooleanArray(printed.text.length) { i -> !piece.quoted && printed.text[i] in "*?[" }, printed.home, printed.fromFolder))
                    }
                    Kind.PAT -> return null.also { lastProblem = PATTERN }
                    Kind.DYN -> return null
                }
                out = out.flatMap { a -> options.map { a + it } }
                if (out.size > MAX_SPELLINGS) return null
            }
            return out
        }

        /** What `$(…)` prints when that can be read off its text: `pwd`, or `echo` with fixed words. */
        private fun printed(inner: String, variables: Variables, depth: Int, nesting: Int): Spelling? {
            if (nesting > 3) return null
            val parsed = ShellSyntax.parse(inner)
            val command = parsed.commands.singleOrNull() ?: return null
            if (parsed.doubts.isNotEmpty() || command.assignments.isNotEmpty() || command.redirects.isNotEmpty() || command.pipedInto || command.pipesOn) return null
            val name = command.words.firstOrNull()?.takeIf { it.bare }?.plain ?: return null
            val args = command.words.drop(1)
            return when {
                name == "pwd" && args.isEmpty() -> Spelling(places.here, BooleanArray(places.here.length), fromFolder = true)
                name == "echo" && args.isNotEmpty() -> {
                    val parts = args.map { arg ->
                        if (arg.plain?.startsWith("-") == true) return null
                        (spellings(arg, variables, depth, nesting + 1) ?: return null).singleOrNull()?.takeIf { s -> !s.hasPattern() && '\\' !in s.text } ?: return null
                    }
                    val text = parts.joinToString(" ") { it.text }
                    Spelling(text, BooleanArray(text.length), parts.any { it.home }, parts.any { it.fromFolder })
                }
                else -> null
            }
        }

        /** [spelling] as a place in the workspace: a [Target], or why it is none (a String). */
        private fun locate(spelling: Spelling): Any {
            if (spelling.home) return HOME
            val text = spelling.text
            val start: List<String>
            val offset: Int
            if (text.startsWith("/")) {
                val root = places.roots.filter { text == it || text.startsWith("$it/") }.maxByOrNull { it.length } ?: return OUTSIDE
                start = emptyList()
                offset = root.length
            } else {
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
                val target = locate(spelling) as? Target ?: continue
                val lower = spelling.text.lowercase(Locale.ROOT)
                names += Name(
                    part, asScript = SCRIPT_ENDINGS.any { lower.endsWith(it) }, must = false, shell = SHELL_ENDINGS.any { lower.endsWith(it) },
                    sourced = false, relative = relative(spelling), incidental = true, onlyExisting = true, targets = listOf(target), problem = null,
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

    private val ASSIGNED = Regex("""^[A-Za-z_][A-Za-z0-9_]*=""")

    /** `${NAME:=value}` and `${NAME=value}`. */
    private val ASSIGNING_EXPANSION = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*):?=""")

    private val OPTION_NAME = Regex("""^[A-Za-z][A-Za-z0-9_-]*$""")

    private val BRACES = Regex("""\{[^{}]*(,|\.\.)[^{}]*\}""")

    /** `[[:alpha:]]`, `[[.x.]]`, `[[=x=]]`: a class, a collating symbol or an equivalence class inside a set. */
    private val CHARACTER_CLASS = Regex("""\[[^\]]*\[[:.=]""")

    private val AWK_FILE = Regex("""^-[fEi]""")

    /** `-f`, also after sed's options that take no value (`-nf`). */
    private val SED_FILE = Regex("""^-[nEirsuz]*f""")
}
