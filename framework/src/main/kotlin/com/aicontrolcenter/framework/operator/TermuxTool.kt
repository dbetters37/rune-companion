package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import kotlinx.serialization.json.JsonObject

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
