package com.aicontrolcenter.framework.operator

/**
 * Runs one script for `scripts.run_js` ([ScriptsRunJs]). The tool reaches the engine through
 * this interface only, so the engine can run in this process ([JsSandbox]: Rhino on a thread of
 * its own, the default of [OperatorToolset.create]) or in a process of its own (Phase 6: an
 * isolated service reached over a binder, with [JsSandbox] in this process as its fallback when
 * that service cannot be reached).
 *
 * Everything that crosses this seam is plain data, so that an engine in another process can
 * carry it in pipes and in binder calls of numbers and texts (rule 11):
 *  - in: the code, the input (JSON text, or null) and the time limit of [run]. The other bounds
 *    of a run are the [JsLimits] the engine was made with; an engine is made for one set of
 *    limits (`(JsLimits) -> JsEngine`);
 *  - the [JsHost] stays in the caller's process. An engine in another process reaches it only
 *    through a host object it makes for that one run, which serves one call at a time and none
 *    once the run is over;
 *  - out: a [JsOutcome], which holds texts and numbers only.
 *
 * What the caller can rely on, whichever engine runs the script:
 *  - a script that fails, a budget it runs over, and an engine that cannot start end as a
 *    [JsOutcome], not as an exception; cancelling the calling coroutine ends the wait;
 *  - [run] returns about [run]'s time limit plus the engine's grace after the script started,
 *    also when the script does not stop;
 *  - the limits that protect the app hold whatever the engine does: no host call after [run]
 *    has returned, no write unless [JsHost.canWrite], only new files, and a bounded number of
 *    them. In this process [JsSandbox] and [WorkspaceJsHost] keep them. An engine in another
 *    process is not trusted to keep any of them (rule 11), so its part in this process keeps
 *    them on the host object it hands over, and bounds the sizes of what comes back.
 */
interface JsEngine {
    /**
     * Runs [code] with [inputJson] as the global `input`, and the workspace functions of [host]
     * when given, for at most [timeoutMs] (positive) of the script's time.
     */
    suspend fun run(code: String, inputJson: String?, host: JsHost?, timeoutMs: Long): JsOutcome
}

/** How a script run ended. [log] is what console.log printed, within the limits. */
sealed interface JsOutcome {
    val log: List<String>
    val logTruncated: Boolean

    /** The script ran to the end; [resultJson] is the JSON of its last expression (null for undefined or a function). */
    data class Finished(val resultJson: String?, val resultTruncated: Boolean, override val log: List<String>, override val logTruncated: Boolean) : JsOutcome

    /** The script threw, or did not compile; [line] when known. */
    data class ScriptError(val message: String, val line: Int?, override val log: List<String>, override val logTruncated: Boolean) : JsOutcome

    /** The sandbox stopped it: a budget (instructions, time, memory, recursion) or cancellation; [reason] says which. */
    data class Stopped(val reason: String, override val log: List<String>, override val logTruncated: Boolean) : JsOutcome
}

/**
 * Optional host functions, exposed to scripts as `workspace.read(path)`, `workspace.list(path)`
 * and `workspace.write(path, text)`. Called on the script thread, or, for an engine in another
 * process, on a thread of this process that serves the script's call; they may block. Throw
 * [JsHostError] with a sentence the script can read; anything else is reported by type only.
 */
interface JsHost {
    val canWrite: Boolean

    fun read(path: String): String

    /** Entries as (name, isFolder, sizeBytes). */
    fun list(path: String): List<Triple<String, Boolean, Long?>>

    /** Creates a new file; returns its path. */
    fun write(path: String, text: String): String
}

/** A refusal from a [JsHost] function, shown to the script as an `Error`. */
class JsHostError(message: String) : RuntimeException(message)
