package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** scripts.run_js, run by [engine]: the Rhino sandbox in this process unless the app hands another engine in. */
internal class ScriptsRunJs(private val d: OperatorToolDependencies, private val engine: JsEngine) : OperatorTool(
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
                answer(engine.run(code, input, host, timeout))
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
