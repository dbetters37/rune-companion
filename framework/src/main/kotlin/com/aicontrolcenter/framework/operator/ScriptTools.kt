package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.ExactText
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolOutcome
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The script tools, and what they share: JavaScript through a [JsEngine] ([ScriptsRunJs], in
 * JsTools.kt), Android's shell ([ScriptsRunShell], in ShellTool.kt) and Termux ([TermuxRun], in
 * TermuxTool.kt).
 */
internal object ScriptTools {
    fun create(d: OperatorToolDependencies, js: JsEngine): List<Tool> = listOf(ScriptsRunJs(d, js), ScriptsRunShell(d), TermuxRun(d))

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
     * unchanged from UTF-8, is refused while the call is assessed, before anyone is asked. The
     * rule and its words are [ExactText]'s, the same the runners use as their last check.
     *
     * @throws ToolCallRejected naming the [argument] and the character, by its code
     */
    fun requireExact(argument: String, value: String) {
        val problem = ExactText.problem(value) ?: return
        throw ToolCallRejected("\"$argument\" contains $problem. Nothing was asked and nothing was run. Write it without that character.")
    }
}
