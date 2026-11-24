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
}

/** scripts.run_js */
internal class ScriptsRunJs(private val d: OperatorToolDependencies, private val sandbox: JsSandbox) : OperatorTool(
    "scripts.run_js", "Run JavaScript",
    "Runs a small JavaScript program in a sealed sandbox with no access to the phone, the network or Java. The value of the last " +
        "expression is returned as JSON; \"input\" is available as the global `input`; console.log output is returned too. " +
        "With \"workspace\": \"read\" the script can call workspace.read(path) and workspace.list(path); with \"write\" also " +
        "workspace.write(path, text), which only creates new files (at most 20). Time and instructions are limited.",
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
 * Finding the scripts a command runs, showing their text and pinning their content is help for
 * the owner on top of that, on a best-effort basis ([ShellCommands]): a shell language cannot be
 * read completely, and the approval says so. Where the reader is not sure what a command line
 * means, the command is refused before anyone is asked.
 */
internal class ScriptsRunShell(private val d: OperatorToolDependencies) : OperatorTool(
    "scripts.run_shell", "Run a shell command",
    "Runs one command with Android's shell (/system/bin/sh, toybox tools) as this app, in a workspace folder, with a time limit. " +
        "On Android 10 and later it cannot run programs downloaded into the workspace. Everything the command starts is stopped " +
        "when it ends, also background jobs (&, nohup, setsid): nothing keeps running after the call. The command can use the " +
        "internet, so it is blocked in local-only mode. The owner approves every command. While a command waits for approval " +
        "and runs, nothing else may change the workspace: if any file in it changes after you asked, the command is not run and " +
        "you ask again; one command runs at a time. A script it runs (sh file, . ./file, source ./file, awk -f file) must be an " +
        "existing workspace file named by its plain path: a script whose name is only put together when the command runs, that " +
        "lies outside the workspace or is a link, is refused. HOME (~) is an empty folder outside the workspace, so do not name " +
        "scripts through ~ or \$HOME. Use \"directory\" rather than cd to run in a folder: after cd, or after setting PATH, a " +
        "script named by a relative path is refused. Text for a shell must be fixed (sh -c '…', eval '…', sh <<'EOF'), and a " +
        "shell is only fed from a file (sh < file, cat file | sh) or fixed text (echo text | sh); anything the tool cannot read " +
        "with certainty is refused with the reason.",
    objectSchema("command") {
        stringProp("command", "The command line, e.g. \"ls -la && wc -l notes.md\".")
        stringProp("directory", "Workspace folder to run in. Default: the top level.")
        intProp("timeout_ms", "Time limit. Default 30000.", 1_000, 55_000)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val command = args.text("command", 8_000)!!
        val dir = args.path("directory", RelativePath.ROOT)
        val limits = ScriptTools.limits(d, args)
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
        // A script that cannot be shown and pinned, and a command that cannot be read with certainty, are refused here, before anyone is asked.
        val found = namedFiles(command, dir)
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
            details + preview(found),
            dataGoesTo = GOES_TO,
        )
    }

    /**
     * A workspace file or folder the command names, as it was when the owner was asked.
     *
     * @property runBy the script that names it, when the command itself does not
     * @property shown false for a script too many steps from the command to be shown: it is pinned, and the owner is told to read it
     */
    private class Named(val path: RelativePath, val state: EntryState, val asScript: Boolean, val text: String?, val runBy: RelativePath?, val shown: Boolean)

    /** What was found for one command: the entries, further checks that must still hold when it runs, and notes for the owner. */
    private class Found(val named: List<Named>, val pins: List<suspend () -> String?>, val notes: List<String>)

    /**
     * One text that was read: the command line ([file] null) or a script it runs, with what
     * decides whether the relative names in it are certain. A text is unsettled when it changes
     * folder or the way names are looked up itself, when a script it reads into itself (`.`,
     * `source`) does, or when the text that runs it is: the shell it runs in starts where that
     * one stands. A script that is run as a program of its own changes nothing for the text that
     * runs it.
     */
    private class Text(val file: RelativePath?, val parent: Text?, val level: Int) {
        var changes: Boolean = false
        val readIn = ArrayList<Text>()

        /** A script it reads into itself could not be read here, so what that changes is unknown. */
        var readInUnread = false

        /** The scripts it names by a relative path, as written. */
        val relativeScripts = ArrayList<String>()

        private fun changesItsShell(): Boolean = changes || readInUnread || readIn.any { it.changesItsShell() }

        fun unsettled(): Boolean = changesItsShell() || parent?.unsettled() == true
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
     * Nothing is guessed. The command is refused when it, or a script it runs: runs a script
     * that cannot be resolved to an existing regular file in the workspace; cannot be read with
     * certainty (a doubt of the reader); or names a script by a relative path while the folder
     * or the way names are looked up is not certain at that point.
     *
     * @throws ToolCallRejected with a message the model can act on
     */
    private suspend fun namedFiles(command: String, dir: RelativePath): Found {
        val places = ShellCommands.Places(roots(), dir)
        val named = LinkedHashMap<RelativePath, Named>()
        val pins = ArrayList<suspend () -> String?>()
        val notes = ArrayList<String>()
        val texts = ArrayList<Text>()

        fun reading(text: String, node: Text, everyWord: Boolean): ShellCommands.Reading {
            val reading = ShellCommands.read(text, places, everyWord)
            reading.doubts.firstOrNull()?.let { throw ToolCallRejected(doubtRefusal(it, node.file)) }
            node.changes = reading.unsettledBy != null
            texts += node
            return reading
        }

        val top = Text(null, null, 0)
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
                            exists = exists || known.state.exists
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
                        named[path] = Named(path, state, script, text, runBy, shown = node.level <= MAX_FOLLOWED)
                        // The scripts this one runs in turn.
                        if (script && name.shell && text != null && node.level <= MAX_FOLLOWED) {
                            val child = Text(path, node, node.level + 1)
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
        "${subject(runBy)} $doubt. This tool runs a command only when it can read with certainty which scripts it runs, so that " +
            "the owner sees them; nothing was run. Write it more plainly."

    private fun unsettledRefusal(raw: String, runBy: RelativePath?): String =
        "${subject(runBy)} names the script ${q(raw)} by a relative path, but it also changes folder (cd, pushd) or sets PATH, CDPATH, ENV, " +
            "HOME or IFS, itself or in a script it reads in or was started from, so which file that name means is only known when it " +
            "runs. Nothing was run. Name the script from the working folder and do not change folder in the same command: use the " +
            "\"directory\" argument instead of cd."

    /** Details lines: each script's text (bounded), then the other names that are checked again. */
    private fun preview(found: Found): List<String> {
        val named = found.named
        val lines = ArrayList<String>()
        var shown = 0
        for ((index, n) in named.filter { it.asScript }.withIndex()) {
            val size = OperatorText.size(n.state.sizeBytes)
            val by = n.runBy?.let { ", run by ${q(it.value)}" }.orEmpty()
            lines += when {
                !n.state.exists -> "Script ${q(n.path.value)}$by does not exist now."
                !n.shown || index >= MAX_SHOWN ->
                    "Script ${q(n.path.value)} ($size)$by is not shown here. Read it in Workspace before you approve."
                n.text == null && (n.state.sizeBytes ?: 0) > MAX_SCRIPT_BYTES ->
                    "Script ${q(n.path.value)} ($size)$by is too long to show here. Read it in Workspace before you approve."
                n.text == null -> "Script ${q(n.path.value)} ($size)$by is not text, so it cannot be shown. Deny unless you know what it is."
                shown + n.text.length <= MAX_SCRIPT_CHARS -> "Script ${q(n.path.value)} ($size)$by, all of it: ${q(n.text)}".also { shown += n.text.length }
                else -> {
                    val room = (MAX_SCRIPT_CHARS - shown).coerceAtLeast(MIN_SCRIPT_CHARS)
                    shown += room
                    "Script ${q(n.path.value)} ($size)$by, the first $room of ${n.text.length} characters: ${q(n.text.take(room))} " +
                        "The rest is not shown here. Read the whole file in Workspace before you approve."
                }
            }
        }
        lines += found.notes
        val others = named.filterNot { it.asScript }
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

        /** What the model is told when the change counter moved; it completes "Nothing was done: … since this action was checked and approved." */
        const val WORKSPACE_CHANGED = "something in the workspace changed (a file was written, moved or removed by a tool, an agent or the owner, or another shell command ran)"
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
