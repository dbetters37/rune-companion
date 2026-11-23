package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DocResult
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
            "Exit code ${outcome.exitCode} after ${outcome.durationMs} ms.${if (outcome.truncated) " Output was cut at the limit." else ""}\n" +
                streams(outcome.stdout, outcome.stderr),
            buildJsonObject {
                put("exitCode", outcome.exitCode)
                put("durationMs", outcome.durationMs)
                put("truncated", outcome.truncated)
            },
        )
        is CommandOutcome.TimedOut -> ToolOutcome.failure("Stopped at the time limit after ${outcome.durationMs} ms.\n" + streams(outcome.stdout, outcome.stderr))
        is CommandOutcome.Unavailable -> ToolOutcome.failure("Not available: ${outcome.reason}")
        is CommandOutcome.Failed -> ToolOutcome.failure("Could not run: ${outcome.reason}")
    }

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
            listOfNotNull("Code: ${q(code)}", input?.let { "Input: ${q(it)}" }, "Workspace access: $accessText", "Time limit: $timeout ms"),
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

/** scripts.run_shell */
internal class ScriptsRunShell(private val d: OperatorToolDependencies) : OperatorTool(
    "scripts.run_shell", "Run a shell command",
    "Runs one command with Android's shell (/system/bin/sh, toybox tools) as this app, in a workspace folder, with a time limit. " +
        "It cannot run downloaded programs. The owner approves every command.",
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
        val summary = "Run the shell command ${q(command)} in ${OperatorText.path(dir)}"
        val details = listOf(
            "Command: ${q(command)}",
            "Folder: ${OperatorText.path(dir)}",
            "Time limit: ${limits.timeoutMs / 1000} s",
            "It runs as this app: it can read and change everything this app can, including the workspace and this app's own data.",
        )
        val before = WorkspaceStates.of(d.workspace, dir)
        if (!dir.isRoot && (!before.exists || !before.isFolder)) return assessment(summary, failing("There is no workspace folder ${q(dir.value)}."), details)
        return assessment(
            summary,
            prepared(check = {
                val now = WorkspaceStates.of(d.workspace, dir)
                if (dir.isRoot || (now.exists && now.isFolder)) null else "the folder ${q(dir.value)} is gone"
            }) {
                val folder = when (val r = d.workspace.resolve(dir)) {
                    is DocResult.Ok -> r.value
                    else -> return@prepared ToolOutcome.failure(OperatorText.problem(r, "Running in ${OperatorText.path(dir)}"))
                }
                ScriptTools.answer(d.shell.run(command, folder, limits))
            },
            details,
        )
    }
}

/** termux.run */
internal class TermuxRun(private val d: OperatorToolDependencies) : OperatorTool(
    "termux.run", "Run a Termux command",
    "Runs one command in the Termux app (bash, in Termux's home folder) and returns its output. Needs Termux installed and set up " +
        "by the owner. The owner approves every command.",
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
            "Time limit: ${limits.timeoutMs / 1000} s",
            "It runs with everything Termux can reach: its own files, its installed programs, and shared storage if you allowed Termux that.",
        )
        val availability = d.termux.availability()
        val problem = when (availability) {
            TermuxAvailability.NOT_INSTALLED -> "Termux is not installed."
            TermuxAvailability.PERMISSION_MISSING -> "This app may not run commands in Termux yet; the owner allows it in Android's app permissions."
            TermuxAvailability.READY -> null
        }
        if (problem != null) return assessment(summary, failing(problem), details)
        return assessment(
            summary,
            prepared(check = { if (d.termux.availability() == TermuxAvailability.READY) null else "Termux is no longer available" }) {
                ScriptTools.answer(d.termux.run(command, limits))
            },
            details,
            dataGoesTo = "the Termux app",
        )
    }
}
