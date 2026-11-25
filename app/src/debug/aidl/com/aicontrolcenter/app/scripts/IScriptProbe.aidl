package com.aicontrolcenter.app.scripts;

/** Debug builds only: what an isolated script process can see and do, for ScriptProcessDeviceTest (F26). */
interface IScriptProbe {
    boolean isolated();
    int pid();
    int uid();
    String processName();

    /** What happened when this process tried to list [folder] and read [file] of the app's own data, in words. */
    String tryAppData(String folder, String file);

    /** What happened when this process tried to connect to [host] on [port], in words. */
    String tryNetwork(String host, int port);

    /** Fills this process's memory until it runs out, with the script process's quiet end in place. */
    oneway void exhaustMemory();
}
