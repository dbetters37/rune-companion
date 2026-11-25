package com.aicontrolcenter.app.scripts

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Debug builds only (Phase 6, WP6; F26): an isolated process declared as the script service is, which answers what it
 * can see and do, for the device test. Its quiet end is the script service's own, so that a memory hog here ends the
 * process as a hog in the script process would.
 */
class ScriptProbeService : Service() {
    override fun onCreate() {
        super.onCreate()
        if (Process.isIsolated()) ScriptProcessService.quietEnd(null)
    }

    override fun onBind(intent: Intent?): IBinder = probe

    private val probe = object : IScriptProbe.Stub() {
        override fun isolated(): Boolean = Process.isIsolated()

        override fun pid(): Int = Process.myPid()

        override fun uid(): Int = Process.myUid()

        override fun processName(): String = Application.getProcessName()

        override fun tryAppData(folder: String, file: String): String {
            val listed = runCatching { File(folder).list()?.joinToString(",") ?: "null" }.fold({ "listed: $it" }, { "list failed: ${it.javaClass.simpleName}" })
            val read = runCatching { File(file).readText() }.fold({ "read: ${it.length} characters" }, { "read failed: ${it.javaClass.simpleName}" })
            return "$listed; $read"
        }

        override fun tryNetwork(host: String, port: Int): String =
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 3_000) } }.fold({ "connected" }, { "failed: ${it.javaClass.simpleName}" })

        override fun exhaustMemory() {
            Thread({
                val hog = ArrayList<ByteArray>()
                // Bounded: four thousand pieces of 4 MB are far more than a script process's heap; it runs out long before.
                repeat(4_000) { hog += ByteArray(4 shl 20) }
            }, "probe-hog").start()
        }
    }
}
