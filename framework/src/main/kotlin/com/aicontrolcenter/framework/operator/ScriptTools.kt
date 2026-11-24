package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.CommandTurn
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The script tools: JavaScript in the Rhino sandbox, Android's shell, and Termux. */
internal object ScriptTools {
    fun create(d: OperatorToolDependencies, sandbox: JsSandbox): List<Tool> = listOf(ScriptsRunJs(d, sandbox), ScriptsRunShell(d), TermuxRun(d))

    /** A command's result for the model; ok only for exit code 0. */
    fun answer(outcome: CommandOutcome): ToolOutcome = when (outcome) {
        is CommandOutcome.Finished -> ToolOutcome(
            outcome.exitCode == 0,
            "Exit code ${outcome.exitCode} after ${outcome.durationMs} ms.${if (outcome.truncated) " Output was cut at the limit." else ""}" +
                (if (outcome.stoppedLeftovers) " It left processes running, and they were stopped when it ended." else "") +
                (if (outcome.leftRunning) " $LEFT_RUNNING" else "") + "\n" +
                streams(outcome.stdout, outcome.stderr),
            buildJsonObject {
                put("exitCode", outcome.exitCode)
                put("durationMs", outcome.durationMs)
                put("truncated", outcome.truncated)
            },
        )
        is CommandOutcome.TimedOut -> ToolOutcome.failure(
            "Stopped at the time limit after ${outcome.durationMs} ms.${if (outcome.leftRunning) " $LEFT_RUNNING" else ""}\n" + streams(outcome.stdout, outcome.stderr),
        )
        is CommandOutcome.Unavailable -> ToolOutcome.failure("Not available: ${outcome.reason}")
        is CommandOutcome.Failed -> ToolOutcome.failure("Could not run: ${outcome.reason}")
    }

    private const val LEFT_RUNNING = "Some processes it started could not be stopped; tell the owner, who can stop them by closing the app."

    private fun streams(stdout: String, stderr: String): String = buildString {
        append("--- output ---\n").append(stdout.ifEmpty { "(none)" })
        if (stderr.isNotEmpty()) append("\n--- errors ---\n").append(stderr)
    }

    fun limits(d: OperatorToolDependencies, args: Args): CommandLimits =
        d.limits.command.copy(timeoutMs = args.long("timeout_ms", d.limits.command.timeoutMs, 1_000..d.limits.maxCommandMs))

    /**
     * R4 of round 4: what is handed to another process must arrive there as the owner is shown
     * it. Java cannot encode half of a surrogate pair on its own and hands "?" on in its place,
     * which a shell reads as a pattern ("rm keep?" removes files nobody named); and a NUL ends
     * the text for the process. So an argument with either, or one that does not come back
     * unchanged from UTF-8, is refused while the call is assessed, before anyone is asked.
     *
     * @throws ToolCallRejected naming the [argument] and the character
     */
    fun requireExact(argument: String, value: String) {
        val bad = value.indices.firstOrNull { i ->
            val c = value[i]
            c == '\u0000' ||
                (c.isHighSurrogate() && value.getOrNull(i + 1)?.isLowSurrogate() != true) ||
                (c.isLowSurrogate() && value.getOrNull(i - 1)?.isHighSurrogate() != true)
        }
        val unchanged = bad == null && String(value.toByteArray(Charsets.UTF_8), Charsets.UTF_8) == value
        if (unchanged) return
        val which = bad?.let { " (U+%04X)".format(value[it].code) }.orEmpty()
        throw ToolCallRejected(
            "\"$argument\" contains a character that cannot be passed on exactly to the program that runs it$which: the program would get " +
                "something else than the owner is shown. Nothing was asked and nothing was run. Write it without that character.",
        )
    }
}

/** scripts.run_js */
internal class ScriptsRunJs(private val d: OperatorToolDependencies, private val sandbox: JsSandbox) : OperatorTool(
    "scripts.run_js", "Run JavaScript",
    "Runs a small JavaScript program in a sealed sandbox with no access to the phone, the network or Java. The value of the last " +
        "expression is returned as JSON; \"input\" is available as the global `input`; console.log output is returned too. " +
        "With \"workspace\": \"read\" the script can call workspace.read(path) and workspace.list(path); with \"write\" also " +
        "workspace.write(path, text), which only creates new files (at most 20). Time and instructions are limited. " +
        "A result is cut after ${grouped(d.limits.js.maxResultChars)} characters: write a larger result to a workspace file with " +
        "workspace.write (a text of up to ${grouped(d.limits.js.maxStringChars)} characters) and return a short summary. The array " +
        "methods refuse arrays of more than ${grouped(d.limits.js.maxArrayLength)} elements.",
    objectSchema("code") {
        stringProp("code", "The JavaScript source (ES2015+).")
        putJsonObject("input") { put("description", "Any JSON value the script reads as `input`.") }
        stringProp("workspace", "Workspace access for the script. Default \"none\".", listOf("none", "read", "write"))
        intProp("timeout_ms", "Time limit. Default 5000.", 100, 10_000)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val code = args.text("code", d.limits.js.maxCodeChars)!!
        val input = args.element("input")?.toString()
        if (input != null && input.length > d.limits.js.maxInputChars) throw ToolCallRejected("\"input\" is longer than ${d.limits.js.maxInputChars} characters.")
        val access = (args.string("workspace") ?: "none").trim().lowercase()
        if (access !in setOf("none", "read", "write")) throw ToolCallRejected("\"workspace\" must be none, read or write.")
        val timeout = args.long("timeout_ms", d.limits.js.timeoutMs, 100L..10_000L)
        val accessText = when (access) {
            "read" -> "it may read workspace files"
            "write" -> "it may read workspace files and create up to 20 new ones (never replacing a file)"
            else -> "no access to files"
        }
        val lines = code.count { it == '\n' } + 1
        return assessment(
            "Run a JavaScript program (${OperatorText.count(lines, "line")}) in the sealed sandbox; $accessText",
            prepared {
                val host = if (access == "none") null else WorkspaceJsHost(d.workspace, canWrite = access == "write", d.limits.js.maxStringChars)
                answer(sandbox.run(code, input, host, timeout))
            },
            // JSON delimits itself, so the input is shown as it is rather than in quotes.
            listOfNotNull("Code: ${q(code)}", input?.let { "Input (JSON): ${PreviewText.exact(it)}" }, "Workspace access: $accessText", "Time limit: $timeout ms"),
        )
    }

    private companion object {
        /** 100000 as "100,000". */
        fun grouped(n: Int): String = String.format(java.util.Locale.ROOT, "%,d", n)
    }

    private fun answer(outcome: JsOutcome): ToolOutcome {
        val log = if (outcome.log.isEmpty()) "" else "\n--- console ---\n" + outcome.log.joinToString("\n") + if (outcome.logTruncated) "\n[console output cut]" else ""
        return when (outcome) {
            is JsOutcome.Finished -> ToolOutcome.success(
                when {
                    outcome.resultTruncated && outcome.resultJson == null -> "The result was too long to return; return less, or write it to the workspace."
                    outcome.resultTruncated -> "Result (cut at ${outcome.resultJson!!.length} characters): ${outcome.resultJson}"
                    else -> "Result: ${outcome.resultJson ?: "undefined"}"
                } + log,
            )
            is JsOutcome.ScriptError -> ToolOutcome.failure("The script failed${outcome.line?.let { " at line $it" } ?: ""}: ${outcome.message}$log")
            is JsOutcome.Stopped -> ToolOutcome.failure("The script was stopped: ${outcome.reason}$log")
        }
    }
}

/** The workspace for scripts: reads, lists, and creates new files only. Runs on the script thread, which may block. */
internal class WorkspaceJsHost(private val workspace: Workspace, override val canWrite: Boolean, private val maxChars: Int) : JsHost {
    private fun path(raw: String): RelativePath = when (val check = RelativePath.parse(raw)) {
        is RelativePath.Check.Valid -> check.path
        is RelativePath.Check.Invalid -> throw JsHostError(check.reason)
    }

    override fun read(path: String): String = runBlocking {
        val p = path(path)
        when (val r = workspace.read(p, 0, maxChars.toLong() * 4)) {
            is DocResult.Ok -> {
                if (r.value.truncated) throw JsHostError("${q(path)} is too large for a script.")
                OperatorText.decodeText(r.value.bytes, cutAtEnd = false) ?: throw JsHostError("${q(path)} is not text.")
            }
            else -> throw JsHostError(OperatorText.problem(r, "Reading ${q(path)}"))
        }
    }

    override fun list(path: String): List<Triple<String, Boolean, Long?>> = runBlocking {
        when (val r = workspace.list(path(path))) {
            is DocResult.Ok -> r.value.map { Triple(it.path.value, it.isFolder, it.sizeBytes) }
            else -> throw JsHostError(OperatorText.problem(r, "Listing ${q(path)}"))
        }
    }

    override fun write(path: String, text: String): String = runBlocking {
        if (!canWrite) throw JsHostError("This script may only read the workspace.")
        val p = path(path)
        if (p.isRoot) throw JsHostError("A file needs a name.")
        when (val r = workspace.write(p, text.toByteArray(Charsets.UTF_8), overwrite = false)) {
            is DocResult.Ok -> p.value
            is DocResult.AlreadyExists -> throw JsHostError("${q(path)} already exists; scripts never replace files.")
            else -> throw JsHostError(OperatorText.problem(r, "Writing ${q(path)}"))
        }
    }
}

/**
 * scripts.run_shell
 *
 * Two things hold for every command, however it is written:
 *  - nothing else changes the workspace between the question to the owner and the end of the
 *    command (the workspace's change counter and change lock, [Workspace.runCommand]);
 *  - everything the command starts is stopped when it ends (the runner, [com.aicontrolcenter.core.operator.ShellRunner]).
 *
 * A third thing holds for one form of command only, the plain `sh file [arguments]`
 * ([ShellPlainForm]): the approval shows that file as the script the command runs, and it is.
 *
 * Everything else the approval shows about scripts is help for the owner, on a best-effort
 * basis ([ShellCommands]): a shell language cannot be read completely, so what the reader finds
 * in any other command, and inside a script, is shown as a reading ("Seems to run …") after a
 * line that says so, and a name it only finds mentioned is listed without its text. The reader
 * also refuses many commands it cannot read well, before anyone is asked; that is help too, not
 * a promise that every doubtful command is refused.
 */
internal class ScriptsRunShell(private val d: OperatorToolDependencies) : OperatorTool(
    "scripts.run_shell", "Run a shell command",
    // Kept under 1024 characters: some providers refuse a longer tool description.
    "Runs one command with Android's shell (/system/bin/sh, toybox tools) as this app, in a workspace folder, with a time limit. " +
        "It can use the internet, so it is blocked in local-only mode. The owner approves every command. If anything in the " +
        "workspace changes after you asked, it is not run: ask again. One command runs at a time; while another runs, a new one " +
        "is refused: ask again when it has ended. Everything the command starts is stopped when it ends, also background jobs. " +
        "To run a script, write the plain form \"sh file arguments\" and use \"directory\" instead of cd: only then is the owner " +
        "shown the file as the script that runs. For any other command the scripts found are shown as a best reading, and many " +
        "commands are refused with the reason: a script that is missing, outside the workspace (also ~ and \$HOME) or named only " +
        "when the command runs; cd or PATH before a relative script; alias; HOME or ENV set to a place in the workspace. " +
        "On Android 10+ it cannot run programs downloaded into the workspace.",
    objectSchema("command") {
        stringProp("command", "The command line, e.g. \"ls -la && wc -l notes.md\".")
        stringProp("directory", "Workspace folder to run in. Default: the top level.")
        intProp("timeout_ms", "Time limit. Default 30000.", 1_000, 55_000)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val command = args.text("command", 8_000)!!
        ScriptTools.requireExact("command", command)
        args.string("directory")?.let { ScriptTools.requireExact("directory", it) }
        val dir = args.path("directory", RelativePath.ROOT)
        val limits = ScriptTools.limits(d, args)
        // R5: a command assessed while another one runs could never run (the other one's end counts as a change), so the owner is not asked for it.
        if (d.workspace.commandRunning()) throw ToolCallRejected(COMMAND_RUNNING)
        // Read before anything of the workspace is looked at: the command runs only if nothing changed the workspace
        // through this app from here until it has ended (G1), however the command names what it runs.
        val seen = d.workspace.changeCount()
        val summary = "Run the shell command ${q(command)} in ${OperatorText.path(dir)}"
        val details = listOf(
            "Command: ${q(command)}",
            "Folder: ${OperatorText.path(dir)}",
            "Time limit: ${limits.timeoutMs / 1000} s. Everything the command starts is stopped when it ends, at the limit or when you stop it.",
            "It runs as this app: it can read and change everything this app can, including the workspace and this app's own data.",
            "It can use the internet: whatever it can read, it can send anywhere.",
            ANY_FILE,
        )
        val before = WorkspaceStates.of(d.workspace, dir)
        if (!dir.isRoot && (!before.exists || !before.isFolder)) return assessment(summary, failing("There is no workspace folder ${q(dir.value)}."), details, dataGoesTo = GOES_TO)
        // A script that cannot be shown and pinned, and a command the reader cannot read well enough, are refused here, before anyone is asked.
        val found = namedFiles(command, dir)
        // R1: only for the plain form is a file shown as the script this command runs.
        val plain = ShellPlainForm.script(command, dir, d.workspace)
        return assessment(
            summary,
            prepared(check = {
                val now = WorkspaceStates.of(d.workspace, dir)
                // First the counter: any change through this app since the question was put, to a file the command names or not.
                if (d.workspace.changeCount() != seen) WORKSPACE_CHANGED
                else if (!dir.isRoot && !(now.exists && now.isFolder)) "the folder ${q(dir.value)} is gone"
                // Then, as a second check, every file the command names must be as the owner saw it.
                else found.named.firstNotNullOfOrNull { n -> n.state.changeTo(stateOrGone(n.path), q(n.path.value)) }
                    ?: found.pins.firstNotNullOfOrNull { it() }
            }) { ctx ->
                // The last check and the run are one step: the workspace holds every other change back from the moment it
                // has compared the counter until the command has ended, and it tells the owner's screens when it has.
                val turn = d.workspace.runCommand(seen) {
                    val folder = when (val r = d.workspace.resolve(dir)) {
                        is DocResult.Ok -> r.value
                        else -> return@runCommand ToolOutcome.failure(OperatorText.problem(r, "Running in ${OperatorText.path(dir)}"))
                    }
                    d.egress.handedOver(
                        ctx, "Shell command run (it can use the internet)",
                        "The owner approved a shell command that can reach the internet; anything it read may have been sent: ${q(command.take(300))}",
                        OperatorEgress.toHost(null, "a shell command, which can use the network"),
                    )
                    ScriptTools.answer(d.shell.run(command, folder, limits))
                }
                when (turn) {
                    is CommandTurn.Changed -> ToolOutcome.changedSinceApproved(WORKSPACE_CHANGED)
                    is CommandTurn.Ran -> turn.overLimit?.let { turn.result.copy(content = turn.result.content + "\nNote: $it") } ?: turn.result
                }
            },
            details + preview(found, plain),
            dataGoesTo = GOES_TO,
        )
    }

    /**
     * A workspace file or folder the command names, as it was when the owner was asked.
     *
     * @property runBy the script that names it, when the command itself does not
     * @property shown false for a script too many steps from the command to be shown: it is pinned, and the owner is told to read it
     * @property mentioned the reader only found its name inside other text (in quotes, after `=`), never as a word of a command: it is
     *   pinned and listed, and its text is not shown as a script's
     */
    private class Named(
        val path: RelativePath,
        val state: EntryState,
        val asScript: Boolean,
        val text: String?,
        val runBy: RelativePath?,
        val shown: Boolean,
        var mentioned: Boolean,
    )

    /** What was found for one command: the entries, further checks that must still hold when it runs, and notes for the owner. */
    private class Found(val named: List<Named>, val pins: List<suspend () -> String?>, val notes: List<String>)

    /**
     * One text that was read: the command line ([file] null) or a script it runs, with what
     * decides whether the relative names in it are certain. A text is unsettled when it changes
     * folder or the way names are looked up itself, when a script it reads into itself (`.`,
     * `source`) does, or when the text that runs it is: the shell it runs in starts where that
     * one stands (from any of them, when several texts run it). A script that is run as a
     * program of its own changes nothing for the text that runs it.
     */
    private class Text(val file: RelativePath?, val level: Int) {
        var changes: Boolean = false

        /** The texts that run it; one script may be run from several. */
        val parents = ArrayList<Text>()
        val readIn = ArrayList<Text>()

        /** A script it reads into itself could not be read here, so what that changes is unknown. */
        var readInUnread = false

        /** The scripts it names by a relative path, as written. */
        val relativeScripts = ArrayList<String>()

        /** Scripts may read each other in, in a circle: each text is looked at once ([seen]). */
        private fun changesItsShell(seen: MutableSet<Text>): Boolean =
            seen.add(this) && (changes || readInUnread || readIn.any { it.changesItsShell(seen) })

        fun unsettled(seen: MutableSet<Text> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())): Boolean =
            seen.add(this) && (changesItsShell(java.util.Collections.newSetFromMap(java.util.IdentityHashMap())) || parents.any { it.unsettled(seen) })
    }

    /** A file by its content (larger files by size and time), a folder by what is in it (the first entries, bounded). */
    private suspend fun stateOf(path: RelativePath): EntryState {
        val stat = WorkspaceStates.of(d.workspace, path, hashUpTo = MAX_HASH_BYTES)
        return if (stat.isFolder) stat.copy(tree = WorkspaceStates.treeOf(d.workspace, path, d.limits.maxCountedEntries)) else stat
    }

    /** [stateOf] for the check before running: an entry that can no longer be looked at (it became a link) counts as gone. */
    private suspend fun stateOrGone(path: RelativePath): EntryState = try {
        stateOf(path)
    } catch (e: ToolCallRejected) {
        EntryState.ABSENT
    }

    /** The workspace root as a command may spell it: its absolute path, and that path with every link resolved. */
    private suspend fun roots(): List<String> {
        val root = (d.workspace.resolve(RelativePath.ROOT) as? DocResult.Ok)?.value?.file ?: return emptyList()
        return listOfNotNull(root.absolutePath, runCatching { root.canonicalPath }.getOrNull()).distinct()
    }

    /**
     * The workspace entries [command] names (from [dir]), each with its state; scripts with their
     * text when it is short enough to show. The scripts a shown shell script runs are followed
     * the same way for [MAX_FOLLOWED] levels, and those one level further are pinned without
     * being shown.
     *
     * This is a reading, and it is shown as one ([preview]). The command is refused when it,
     * or a script it seems to run: runs a script that cannot be resolved to an existing regular
     * file in the workspace; has something the reader cannot read well enough (a doubt of the
     * reader); names a script by a relative path while the folder or the way names are looked
     * up is not settled at that point; or uses a name reference while a shell is started.
     *
     * @throws ToolCallRejected with a message the model can act on
     */
    private suspend fun namedFiles(command: String, dir: RelativePath): Found {
        val places = ShellCommands.Places(roots(), dir)
        val named = LinkedHashMap<RelativePath, Named>()
        val pins = ArrayList<suspend () -> String?>()
        val notes = ArrayList<String>()
        val texts = ArrayList<Text>()
        // R3: a name reference in one text and a shell started in another (the command and a script it runs) count together.
        var nameReference = false
        var startsShell = false

        fun reading(text: String, node: Text, everyWord: Boolean): ShellCommands.Reading {
            val reading = ShellCommands.read(text, places, everyWord)
            reading.doubts.firstOrNull()?.let { throw ToolCallRejected(doubtRefusal(it, node.file)) }
            node.changes = reading.unsettledBy != null
            texts += node
            nameReference = nameReference || reading.nameReference
            startsShell = startsShell || reading.startsShell
            return reading
        }

        val top = Text(null, 0)
        // The text of each script that was read, so that a script named a second time is tied to the text that names it too.
        val nodes = HashMap<RelativePath, Text>()
        // What is still to be read: the names of a text, and the text they come from.
        val queue = ArrayDeque(listOf(reading(command, top, everyWord = true) to top))
        var scripts = 0
        while (queue.isNotEmpty()) {
            val (reading, node) = queue.removeFirst()
            val runBy = node.file
            for (name in reading.names.sortedByDescending { (if (it.must) 2 else 0) + (if (it.asScript) 1 else 0) }) {
                name.problem?.let { throw ToolCallRejected(refusal(name.raw, it, runBy)) }
                var exists = false
                for (target in name.targets) {
                    val paths = when (target) {
                        is ShellCommands.Target.Entry -> listOf(target.path)
                        // Only a script's pattern is expanded; other patterns are left to the command, as before.
                        is ShellCommands.Target.Pattern -> if (!name.asScript) continue else {
                            val matches = expand(target) ?: if (name.must) throw ToolCallRejected(refusal(name.raw, "it matches too many files to show", runBy)) else continue
                            // A pattern that matches nothing is handed to the shell as it is written: what then runs is not known here.
                            if (matches.isEmpty() && name.must) throw ToolCallRejected(refusal(name.raw, "no workspace file matches it", runBy))
                            pins += { if (expand(target) == matches) null else "the files that match ${q(target.text)} changed" }
                            if (matches.isEmpty()) notes += "No file matches ${q(target.text)} now."
                            matches
                        }
                    }
                    // "sub/../x" means "x" only while "sub" really is a folder.
                    val notFolder = target.crossed.firstOrNull { !isFolder(it) }
                    if (notFolder != null) {
                        if (name.must) throw ToolCallRejected(refusal(name.raw, "it goes through ${q(notFolder.value)}, which is not a folder in the workspace", runBy))
                        continue
                    }
                    target.crossed.forEach { folder -> pins += { if (isFolder(folder)) null else "the folder ${q(folder.value)} changed" } }
                    for (path in paths) {
                        val known = named[path]
                        if (known != null && (known.asScript || !name.asScript)) {
                            // Seen before, perhaps only as a name that was allowed to be missing: the script a shell runs is not.
                            if (name.must && !known.state.exists) throw ToolCallRejected(refusal(name.raw, "there is no such file in the workspace", runBy))
                            if (name.must && known.state.isFolder) throw ToolCallRejected(refusal(name.raw, "it is a folder", runBy))
                            exists = exists || known.state.exists
                            // Found as a word of a command after all: no longer only mentioned.
                            if (name.asScript && !name.incidental) known.mentioned = false
                            // Seen before. If a shell runs it from here too, this text is tied to it as well.
                            if (name.asScript && name.shell && known.asScript) {
                                val read = nodes[path]
                                when {
                                    read != null -> {
                                        read.parents += node
                                        if (name.sourced) node.readIn += read
                                    }
                                    // It was seen as something else (a program file of awk, say), so its own scripts were not looked for.
                                    known.text != null && node.level <= MAX_FOLLOWED -> {
                                        val child = Text(path, node.level + 1).also { it.parents += node }
                                        nodes[path] = child
                                        if (name.sourced) node.readIn += child
                                        queue += reading(known.text, child, everyWord = false) to child
                                    }
                                    name.sourced -> node.readInUnread = true
                                }
                            }
                            continue
                        }
                        // Names that are only pinned may be left out past the limit; a script never is: then nothing runs.
                        if (!name.asScript && named.size >= MAX_ENTRIES) continue
                        if (name.asScript && ++scripts > MAX_SCRIPTS) {
                            throw ToolCallRejected(
                                "The command runs or names more than $MAX_SCRIPTS scripts, more than can be shown to the owner and checked, so nothing was run. " +
                                    "Run fewer scripts in one command.",
                            )
                        }
                        val state = try {
                            stateOf(path)
                        } catch (e: ToolCallRejected) {
                            if (name.must) throw ToolCallRejected(refusal(name.raw, "it is a link or cannot be read as a workspace file", runBy))
                            continue
                        }
                        // The script a shell runs must be there to be shown: one that is missing is not waved through.
                        if (name.must && !state.exists) throw ToolCallRejected(refusal(name.raw, "there is no such file in the workspace", runBy))
                        if (name.must && state.isFolder) throw ToolCallRejected(refusal(name.raw, "it is a folder", runBy))
                        if (!state.exists && name.onlyExisting) continue
                        exists = exists || state.exists
                        val script = name.asScript && !state.isFolder
                        val text = if (script && state.exists && (state.sizeBytes ?: Long.MAX_VALUE) <= MAX_SCRIPT_BYTES) {
                            (d.workspace.read(path, 0, MAX_SCRIPT_BYTES) as? DocResult.Ok)?.value?.bytes?.let { OperatorText.decodeText(it, cutAtEnd = false) }
                        } else null
                        named[path] = Named(path, state, script, text, runBy, shown = node.level <= MAX_FOLLOWED, mentioned = name.incidental)
                        // The scripts this one runs in turn.
                        if (script && name.shell && text != null && node.level <= MAX_FOLLOWED) {
                            val child = Text(path, node.level + 1).also { it.parents += node }
                            nodes[path] = child
                            if (name.sourced) node.readIn += child
                            queue += reading(text, child, everyWord = false) to child
                        } else if (script && name.sourced) {
                            node.readInUnread = true
                        }
                    }
                }
                // A word that was only found inside other text counts as a script's name when there is such a file.
                if (name.asScript && name.relative && (exists || !name.incidental)) node.relativeScripts += name.raw
            }
        }
        // Last, with every script read: where the folder or the look-up of names is not certain, a relative name is not either.
        for (node in texts) {
            if (node.relativeScripts.isNotEmpty() && node.unsettled()) throw ToolCallRejected(unsettledRefusal(node.relativeScripts.first(), node.file))
        }
        if (nameReference && startsShell) throw ToolCallRejected(doubtRefusal(ShellCommands.DOUBT_NAME_REFERENCE, null))
        return Found(named.values.toList(), pins, notes)
    }

    private suspend fun isFolder(path: RelativePath): Boolean = path.isRoot || (d.workspace.stat(path) as? DocResult.Ok)?.value?.isFolder == true

    /** The entries [pattern] matches now, sorted; null when there are more than [MAX_MATCHES]. */
    private suspend fun expand(pattern: ShellCommands.Target.Pattern): List<RelativePath>? {
        var found = listOf(pattern.base)
        for (segment in pattern.segments) {
            val next = ArrayList<RelativePath>()
            for (folder in found) {
                if (segment.isPattern) {
                    val entries = (d.workspace.list(folder) as? DocResult.Ok)?.value ?: continue
                    entries.filter { segment.matches(it.name) }.forEach { next += it.path }
                } else {
                    val child = (RelativePath.parse(if (folder.isRoot) segment.text else folder.value + "/" + segment.text) as? RelativePath.Check.Valid)?.path ?: continue
                    if (d.workspace.stat(child) is DocResult.Ok) next += child
                }
                if (next.size > MAX_MATCHES) return null
            }
            found = next
        }
        return found.sortedBy { it.value }
    }

    private fun subject(runBy: RelativePath?) = if (runBy == null) "The command" else "The script ${q(runBy.value)}, which the command runs,"

    private fun refusal(raw: String, problem: String, runBy: RelativePath?): String {
        val what = if (raw.isEmpty()) "a script" else "${q(raw)} as a script"
        return (if (runBy == null) "The command runs $what" else "The script ${q(runBy.value)}, which the command runs, runs $what in turn") +
            ", but $problem. So it could not be shown to the owner or checked before it runs, and nothing was run. " +
            "Name the script by its plain path inside the workspace, for example \"sh tools/tidy.sh\"."
    }

    private fun doubtRefusal(doubt: String, runBy: RelativePath?): String =
        "${subject(runBy)} $doubt. Nothing was run: this tool cannot read that well enough to show the owner which scripts would run. " +
            "Write it more plainly; a script is best run as \"sh file\"."

    /** The advice comes early: the executor hands the model only the first few hundred characters of a refusal. */
    private fun unsettledRefusal(raw: String, runBy: RelativePath?): String =
        "${subject(runBy)} names the script ${q(raw)} by a relative path, but it also changes folder (cd, pushd, find -execdir) or sets PATH, " +
            "CDPATH, ENV, HOME or IFS, itself or in a script it reads in or was started from. So use the \"directory\" argument instead of cd, " +
            "and name the script from the working folder. Nothing was run: which file that name means is only known when the command runs."

    /**
     * Details lines: what the reader found, worded by how sure it is (R1 of round 4).
     *
     * Only the script of a plain-form command ([plain], [ShellPlainForm]) is shown as a fact:
     * `Script "x" (n bytes), all of it: "…"`. Every other script the reader found, in a command
     * that is not of the plain form or inside a script at any level, is a reading and worded as
     * one (`Seems to run "x" …`), after a fixed line that says so. A name that was only found
     * mentioned is never shown with its text: it is listed with the other names, which are all
     * checked again before the command runs.
     */
    private fun preview(found: Found, plain: RelativePath?): List<String> {
        // A script that is only named inside a file the reader found mentioned is not shown with its text either.
        val byPath = found.named.associateBy { it.path }
        fun mentioned(n: Named, steps: Int = 0): Boolean = n.mentioned || (steps <= MAX_FOLLOWED + 1 && n.runBy?.let { byPath[it] }?.let { mentioned(it, steps + 1) } == true)
        val scripts = found.named.filter { it.asScript && !mentioned(it) }
        // The plain form's script, named by the command itself. If the reader did not find it as that, nothing is called certain.
        val certain = plain?.let { p -> scripts.firstOrNull { it.path == p && it.runBy == null && it.state.exists } }
        val lines = ArrayList<String>()
        var shown = 0

        /** [n]'s line: [fact] for the plain form's script, a reading for every other one. */
        fun line(n: Named, fact: Boolean, hidden: Boolean): String {
            val size = OperatorText.size(n.state.sizeBytes)
            val name = q(n.path.value)
            val by = n.runBy?.let { ", named in ${q(it.value)}" }.orEmpty()
            val start = if (fact) "Script $name ($size)" else "Seems to run $name ($size)$by;"
            val that = if (fact) "" else " that file"
            return when {
                !n.state.exists -> "Seems to run $name$by, which does not exist now."
                hidden -> "$start$that is not shown here. Read it in Workspace before you approve."
                n.text == null && (n.state.sizeBytes ?: 0) > MAX_SCRIPT_BYTES -> "$start$that is too long to show here. Read it in Workspace before you approve."
                n.text == null -> "$start$that is not text, so it cannot be shown. Deny unless you know what it is."
                shown + n.text.length <= MAX_SCRIPT_CHARS -> {
                    shown += n.text.length
                    if (fact) "$start, all of it: ${q(n.text)}" else "$start all of that file: ${q(n.text)}"
                }
                else -> {
                    val room = (MAX_SCRIPT_CHARS - shown).coerceAtLeast(MIN_SCRIPT_CHARS)
                    shown += room
                    (if (fact) "$start, the first $room of ${n.text.length} characters: " else "$start the first $room of ${n.text.length} characters of that file: ") +
                        "${q(n.text.take(room))} The rest is not shown here. Read the whole file in Workspace before you approve."
                }
            }
        }

        val readings = scripts.filter { it !== certain }
        if (certain == null) {
            lines += NOT_PLAIN
            if (readings.isEmpty()) lines += NO_SCRIPT
        } else {
            lines += line(certain, fact = true, hidden = false)
            if (readings.isNotEmpty()) lines += IN_TURN
        }
        val before = if (certain == null) 0 else 1
        for ((index, n) in readings.withIndex()) lines += line(n, fact = false, hidden = !n.shown || index + before >= MAX_SHOWN)
        lines += found.notes
        val others = found.named.filter { it !in scripts }
        if (others.isNotEmpty()) {
            val listed = others.take(MAX_LISTED).joinToString(", ") { "${q(it.path.value)} (${WorkspaceStates.describe(it.state)})" }
            lines += "Workspace files and folders it names: $listed" + (if (others.size > MAX_LISTED) " and ${others.size - MAX_LISTED} more" else "") + "."
        }
        lines += "If anything in the workspace changes before it runs, it does not run."
        return lines
    }

    private companion object {
        /** Shown as the destination: the command can reach the network. */
        const val GOES_TO = "any address the command connects to (it can use the internet)"

        /** True whatever the reader found, and said in every approval. */
        const val ANY_FILE = "A shell command can run any file in the workspace. Only the scripts found in the command are shown here."

        /** R1: said right after [ANY_FILE] for every command that is not of the plain form ([ShellPlainForm]). */
        const val NOT_PLAIN = "This is not a plain \"sh file\" command, so the app cannot be sure which files it runs. What follows is its best reading."

        /** R1: said after [NOT_PLAIN] when the reading has nothing to show, so that "what follows" is not followed by silence. */
        const val NO_SCRIPT = "It found no script in this command, which does not mean that none runs."

        /** R1: said after the script of a plain-form command, before what that script names in turn. */
        const val IN_TURN = "Scripts that script seems to run in turn (best reading):"

        /**
         * What the model is told when the change counter moved; it completes "Nothing was done: … since this action was checked
         * and approved. Look again and ask anew if it is still needed." A command that was running then must have ended first (R5).
         */
        const val WORKSPACE_CHANGED = "something in the workspace changed (a file was written, moved or removed by a tool, an agent or the owner, or " +
            "another shell command ran; if another shell command was running, wait until it has ended)"

        /** R5: the refusal of a command asked for while another one runs. Nobody is asked for it. */
        const val COMMAND_RUNNING = "Another shell command is running. Nothing was asked and nothing was run. Ask again when it has ended (about a minute at most)."
        const val MAX_SCRIPT_BYTES = 64L * 1024
        /** Files up to this size are pinned by their content, larger ones by size and time. */
        const val MAX_HASH_BYTES = 8L * 1024 * 1024
        /** Script text shown in one approval, all scripts together. */
        const val MAX_SCRIPT_CHARS = 16_000
        const val MIN_SCRIPT_CHARS = 2_000
        const val MAX_LISTED = 30
        /** How many levels of scripts run by a shown script are shown too; one level further is pinned only. */
        const val MAX_FOLLOWED = 2
        /** Scripts shown in one approval; the rest are pinned and the owner is told to read them. */
        const val MAX_SHOWN = 12
        /** Entries that are not scripts pinned for one command. */
        const val MAX_ENTRIES = 300
        /** Scripts shown or pinned for one command; with more, it is refused. */
        const val MAX_SCRIPTS = 60
        /** Most files one script pattern may match. */
        const val MAX_MATCHES = 50
    }
}

/** termux.run */
internal class TermuxRun(private val d: OperatorToolDependencies) : OperatorTool(
    "termux.run", "Run a Termux command",
    "Runs one command in the Termux app (bash, in Termux's home folder) and returns its output. Needs Termux installed and set up " +
        "by the owner. The command can use the internet, so it is blocked in local-only mode. The owner approves every command.",
    objectSchema("command") {
        stringProp("command", "The command, e.g. \"python3 --version\".")
        intProp("timeout_ms", "How long to wait for the result. Default 30000.", 1_000, 55_000)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val command = args.text("command", 8_000)!!
        ScriptTools.requireExact("command", command)
        val limits = ScriptTools.limits(d, args)
        val summary = "Run the command ${q(command)} in Termux"
        val details = listOf(
            "Command: ${q(command)}",
            "Time limit: ${limits.timeoutMs / 1000} s. This app stops waiting then, but Termux keeps running the command, and anything it " +
                "started in the background, until they end.",
            "It runs with everything Termux can reach: its own files, its installed programs, and shared storage if you allowed Termux that.",
            "It can use the internet: whatever it can read, it can send anywhere.",
        )
        val availability = d.termux.availability()
        val problem = when (availability) {
            TermuxAvailability.NOT_INSTALLED -> "Termux is not installed."
            TermuxAvailability.PERMISSION_MISSING -> "This app may not run commands in Termux yet; the owner allows it in Android's app permissions."
            TermuxAvailability.READY -> null
        }
        if (problem != null) return assessment(summary, failing(problem), details, dataGoesTo = GOES_TO)
        return assessment(
            summary,
            prepared(check = { if (d.termux.availability() == TermuxAvailability.READY) null else "Termux is no longer available" }) { ctx ->
                d.egress.handedOver(
                    ctx, "Command handed to Termux (it can use the internet)",
                    "The owner approved a Termux command, which can reach the internet: ${q(command.take(300))}",
                    OperatorEgress.toApp(TERMUX_PACKAGE, "a command run in Termux, which can use the network"),
                )
                ScriptTools.answer(d.termux.run(command, limits))
            },
            details,
            dataGoesTo = GOES_TO,
        )
    }

    private companion object {
        const val TERMUX_PACKAGE = "com.termux"

        /** Shown as the destination: Termux, and through it the network. */
        const val GOES_TO = "the Termux app, and any address its command connects to (it can use the internet)"
    }
}
