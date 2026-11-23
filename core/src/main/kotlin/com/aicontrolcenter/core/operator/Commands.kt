package com.aicontrolcenter.core.operator

/** Bounds on one command. */
data class CommandLimits(
    val timeoutMs: Long = 30_000,
    /** Each of stdout and stderr is cut to this many characters. */
    val maxOutputChars: Int = 16_000,
) {
    init {
        require(timeoutMs > 0 && maxOutputChars > 0) { "limits must be positive" }
    }
}

/** How a command ended. */
sealed interface CommandOutcome {
    /**
     * Ran to the end. [truncated]: some output was cut at the limit. [stoppedLeftovers]: it left
     * processes running (in the background), and they were stopped when it ended.
     */
    data class Finished(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        val durationMs: Long,
        val stoppedLeftovers: Boolean = false,
    ) : CommandOutcome

    /** Stopped at the time limit; what it printed until then. */
    data class TimedOut(val stdout: String, val stderr: String, val durationMs: Long) : CommandOutcome

    /** Could not run here; [access] names what the owner can turn on, if anything. */
    data class Unavailable(val reason: String, val access: DeviceAccess? = null) : CommandOutcome

    /** Could not be started or its result could not be read. */
    data class Failed(val reason: String) : CommandOutcome
}

/**
 * Android's own shell (`/system/bin/sh -c <command>`) as this app's user, in a workspace
 * directory, with a stripped environment (`:device`). The command is passed as one argument,
 * never split or re-quoted. It runs in a session and process group of its own, and when it
 * ends, reaches its time limit or the caller is cancelled, the whole group is killed: nothing it
 * started outlives the call unless it left the group itself (which the tool refuses to ask for:
 * `setsid`, `nohup`, `&`, job control). Where that cannot be arranged, nothing runs
 * ([CommandOutcome.Unavailable]).
 */
fun interface ShellRunner {
    suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome
}

/** Whether Termux can take commands from this app. */
enum class TermuxAvailability {
    NOT_INSTALLED,

    /** Installed, but "Run commands in Termux" is not granted to this app. */
    PERMISSION_MISSING,

    /** Installed and permitted. `allow-external-apps` can only be confirmed by running a command. */
    READY,
}

/**
 * Commands in Termux through its RUN_COMMAND intent (`:device`): `bash -s` in Termux's home with
 * the command on standard input, the result returned through a PendingIntent. Termux keeps
 * running a command after the caller is cancelled; the caller just stops waiting.
 */
interface TermuxRunner {
    suspend fun availability(): TermuxAvailability

    suspend fun run(command: String, limits: CommandLimits): CommandOutcome
}
