package com.aicontrolcenter.app.scripts;

import com.aicontrolcenter.app.scripts.IScriptHost;
import com.aicontrolcenter.app.scripts.IScriptResult;

/**
 * The isolated script process (ScriptProcessService), as the app calls it (Phase 6, rule 11). Only numbers, short texts
 * and pipes cross; the request and the answer travel in plain pipes as length-prefixed frames (ScriptFrames).
 */
interface IScriptRunner {
    /**
     * Runs one script. [request] is the read end of a pipe the app writes the request into; [host] is a binder the app
     * made for this run alone (null: no workspace); [result] hears how the run goes.
     */
    oneway void run(in ParcelFileDescriptor request, IScriptHost host, IScriptResult result);

    /** The script process's id (tests). */
    int pid();
}
