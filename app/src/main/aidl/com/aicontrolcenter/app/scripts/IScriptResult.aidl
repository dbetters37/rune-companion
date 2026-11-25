package com.aicontrolcenter.app.scripts;

/** How one run goes, told by the script process; a binder the app makes for that run alone. */
interface IScriptResult {
    /** The script's own code begins: its time limit counts from here (D2). */
    oneway void started();

    /**
     * The run ended. [status] is one of ScriptStatus; with ScriptStatus.OUTCOME, [outcome] is the read end of a pipe
     * holding the outcome frame, which the app reads with its own caps and time limit.
     */
    oneway void done(int status, in ParcelFileDescriptor outcome);
}
