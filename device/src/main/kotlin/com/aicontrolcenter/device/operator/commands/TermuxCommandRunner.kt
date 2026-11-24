package com.aicontrolcenter.device.operator.commands

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.TermuxAvailability
import com.aicontrolcenter.core.operator.TermuxRunner
import com.aicontrolcenter.device.operator.ResultMailbox
import com.aicontrolcenter.device.platform.packageInfoOrNull
import com.aicontrolcenter.device.platform.toSingleLine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Commands in Termux through its documented `RUN_COMMAND` intent: `bash -s` in Termux's home
 * folder with the command on standard input (never split into arguments), run in the
 * background, the result coming back through a private [ResultMailbox]. A command that would
 * not arrive there as the same text ([notExactProblem]) is not sent.
 *
 * Termux must be installed, this app must hold Termux's "Run commands in Termux environment"
 * permission, and `allow-external-apps=true` must be set in `~/.termux/termux.properties`; each
 * missing piece is a [CommandOutcome.Unavailable] that says which, with
 * [DeviceAccess.TERMUX_RUN_COMMAND]. Termux keeps running a command after the limit or a
 * cancellation; this side only stops waiting ([CommandOutcome.TimedOut] carries no output then).
 */
internal class TermuxCommandRunner(context: Context) : TermuxRunner {
    private val appContext: Context = context.applicationContext ?: context

    override suspend fun availability(): TermuxAvailability = when {
        appContext.packageManager.packageInfoOrNull(PACKAGE) == null -> TermuxAvailability.NOT_INSTALLED
        ContextCompat.checkSelfPermission(appContext, PERMISSION) != PackageManager.PERMISSION_GRANTED -> TermuxAvailability.PERMISSION_MISSING
        else -> TermuxAvailability.READY
    }

    override suspend fun run(command: String, limits: CommandLimits): CommandOutcome {
        when (availability()) {
            TermuxAvailability.NOT_INSTALLED -> return CommandOutcome.Unavailable(NOT_INSTALLED, DeviceAccess.TERMUX_RUN_COMMAND)
            TermuxAvailability.PERMISSION_MISSING -> return CommandOutcome.Unavailable(PERMISSION_MISSING, DeviceAccess.TERMUX_RUN_COMMAND)
            TermuxAvailability.READY -> Unit
        }
        if (command.isBlank()) return CommandOutcome.Failed("There is no command.")
        // Termux hands the text to bash as UTF-8: what cannot be encoded would arrive as another character.
        notExactProblem(command)?.let { return CommandOutcome.Failed(it) }
        if (command.length > MAX_COMMAND_CHARS) {
            return CommandOutcome.Failed("The command is longer than $MAX_COMMAND_CHARS characters; put longer scripts in a file in Termux and run that.")
        }
        val mailbox = ResultMailbox(appContext, "termux", oneShot = true)
        val started = System.nanoTime()
        try {
            start(intentFor(command, mailbox.pendingIntent))?.let { return it }
            val answer = withTimeoutOrNull(limits.timeoutMs) { mailbox.receive() }
                ?: return CommandOutcome.TimedOut("", "", elapsedMs(started))
            return outcomeOf(answer, limits, elapsedMs(started))
        } finally {
            mailbox.close()
        }
    }

    /** Starts Termux's service; null when it was accepted, else why not. */
    private fun start(intent: Intent): CommandOutcome? = try {
        if (appContext.startService(intent) == null) NO_SERVICE else null
    } catch (_: SecurityException) {
        CommandOutcome.Unavailable(PERMISSION_MISSING, DeviceAccess.TERMUX_RUN_COMMAND)
    } catch (_: IllegalStateException) {
        // Android refuses background service starts into an app that is not running; Termux
        // accepts a foreground start and promotes itself at once.
        try {
            ContextCompat.startForegroundService(appContext, intent)
            null
        } catch (e: RuntimeException) {
            CommandOutcome.Failed("Android did not let this app start Termux right now (${e.javaClass.simpleName}). Open Termux once and try again.")
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    /** The intent and result format, as Termux documents them. */
    companion object {
        const val PACKAGE: String = "com.termux"
        const val PERMISSION: String = "com.termux.permission.RUN_COMMAND"
        const val SERVICE: String = "com.termux.app.RunCommandService"
        const val ACTION: String = "com.termux.RUN_COMMAND"
        const val BASH: String = "/data/data/com.termux/files/usr/bin/bash"
        const val HOME: String = "/data/data/com.termux/files/home"
        const val MAX_COMMAND_CHARS: Int = 64_000

        const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val RESULT_BUNDLE = "result"

        private const val NOT_INSTALLED =
            "Termux is not installed. Install it from F-Droid or GitHub (not the Play Store build), then allow this app to run commands in it."
        private const val PERMISSION_MISSING =
            "This app may not run commands in Termux yet. The owner can allow \"Run commands in Termux environment\" in Android's permissions for this app."
        private const val NOT_CONFIGURED =
            "Termux refuses commands from other apps until allow-external-apps=true is set in ~/.termux/termux.properties (then run termux-reload-settings)."
        private val NO_SERVICE = CommandOutcome.Unavailable(
            "This Termux version cannot take commands from other apps. Update Termux (0.109 or later).",
            DeviceAccess.TERMUX_RUN_COMMAND,
        )

        internal fun intentFor(command: String, result: PendingIntent): Intent = Intent(ACTION)
            .setClassName(PACKAGE, SERVICE)
            .putExtra(EXTRA_PATH, BASH)
            .putExtra(EXTRA_ARGUMENTS, arrayOf("-s"))
            .putExtra(EXTRA_STDIN, command)
            .putExtra(EXTRA_WORKDIR, HOME)
            .putExtra(EXTRA_BACKGROUND, true)
            .putExtra(EXTRA_LABEL, "AI Control Center")
            .putExtra(EXTRA_PENDING_INTENT, result)

        /** Reads Termux's result bundle; output is cut to [CommandLimits.maxOutputChars]. */
        internal fun outcomeOf(answer: Intent, limits: CommandLimits, elapsedMs: Long): CommandOutcome {
            val result: Bundle = answer.getBundleExtra(RESULT_BUNDLE) ?: return CommandOutcome.Failed("Termux answered without a result.")
            val error = result.getInt("err", Activity.RESULT_OK)
            if (error != Activity.RESULT_OK) {
                val message = result.getString("errmsg").orEmpty()
                if ("allow-external-apps" in message) return CommandOutcome.Unavailable(NOT_CONFIGURED, DeviceAccess.TERMUX_RUN_COMMAND)
                val detail = message.toSingleLine(300)
                return CommandOutcome.Failed(if (detail.isEmpty()) "Termux could not run the command." else "Termux could not run the command: $detail")
            }
            val stdout = result.getString("stdout").orEmpty()
            val stderr = result.getString("stderr").orEmpty()
            // Termux itself cuts long output and reports the original lengths.
            val cutByTermux = longer(result.getString("stdout_original_length"), stdout) || longer(result.getString("stderr_original_length"), stderr)
            val max = limits.maxOutputChars
            return CommandOutcome.Finished(
                exitCode = result.getInt("exitCode", -1),
                stdout = stdout.take(max),
                stderr = stderr.take(max),
                truncated = cutByTermux || stdout.length > max || stderr.length > max,
                durationMs = elapsedMs,
            )
        }

        private fun longer(original: String?, kept: String): Boolean = (original?.trim()?.toLongOrNull() ?: 0) > kept.length
    }
}
