package com.aicontrolcenter.app.scripts;

/**
 * The workspace functions of one run, served by the app (RunHost), which keeps every limit itself: one call at a time,
 * write access, at most 20 new files, sizes, and nothing once the run is over. Replies that begin with "+" carry the
 * answer, replies that begin with "!" a refusal the script reads.
 */
interface IScriptHost {
    /** A pipe holding the file's text, or the refusal, as a reply frame. */
    ParcelFileDescriptor read(String path);

    /** The folder's entries as JSON after "+", or "!" and a refusal; at most 32,768 characters. */
    String list(String path);

    /** Creates a new file with the text the script process writes into [content]; "+" and its path, or "!" and a refusal. */
    String write(String path, in ParcelFileDescriptor content);
}
