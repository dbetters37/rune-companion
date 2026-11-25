package com.aicontrolcenter.app.scripts

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Starts and lets go of script processes (Phase 6). The tests stand in for Android's binding. */
internal interface ScriptProcessLauncher {
    /** A binding of one script process. */
    interface Binding

    /** What happens to a binding; called on any thread. */
    interface Events {
        /** The process is up; [binder] is its [IScriptRunner]. */
        fun connected(binder: IBinder)

        /** The process died, or Android dropped or refused the binding: let go of it and bind afresh (F5). */
        fun ended()
    }

    /** Binds the script process named [instance]; null when Android refused (then nothing is bound). */
    fun bind(instance: String, events: Events): Binding?

    /** Lets go of [binding]; Android then ends its isolated process ("isolated not needed", 0.1-0.6 s; spike Q2). */
    fun unbind(binding: Binding)
}

/**
 * Android's binding of [ScriptProcessService]: on Android 10 and later one isolated process per [instance] name
 * (`bindIsolatedService`; names of letters, digits and "_" only, spike F-A: "js-0" is refused), on Android 9 the one
 * process of the service (`bindService`). Callbacks come on a thread of their own, never the main thread.
 *
 * A death of the process, a dropped binding, a dead binding and a null binding all end the binding ([Events.ended]);
 * the engine then unbinds at once and binds afresh for its next run, so Android's restart waits (1 s, 4 s, 16 s, and
 * 30 minutes after two crashes; spike Q6) never apply.
 */
internal class AndroidScriptLauncher(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** `Context.bindIsolatedService` (Android 10+); Robolectric has no isolated binding, so its tests pass another. */
    private val bindIsolated: (Context, Intent, String, Executor, ServiceConnection) -> Boolean = ::bindIsolatedService,
) : ScriptProcessLauncher {
    private val context: Context = context.applicationContext ?: context
    private val callbacks: Executor = Executors.newSingleThreadExecutor { Thread(it, "aicc-script-binding").apply { isDaemon = true } }

    override fun bind(instance: String, events: ScriptProcessLauncher.Events): ScriptProcessLauncher.Binding? {
        val intent = Intent(context, ScriptProcessService::class.java)
        val connection = Connection(events)
        val bound = try {
            if (sdkInt >= Build.VERSION_CODES.Q) {
                bindIsolated(context, intent, instance, callbacks, connection)
            } else {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        } catch (e: RuntimeException) {
            // A SecurityException or an IllegalArgumentException of a phone that does not allow it: refused.
            false
        }
        if (!bound) {
            // Android asks a client to unbind even a bind that returned false.
            connection.released = true
            runCatching { context.unbindService(connection) }
            return null
        }
        return connection
    }

    override fun unbind(binding: ScriptProcessLauncher.Binding) {
        val connection = binding as Connection
        if (connection.released) return
        connection.released = true
        connection.unlink()
        runCatching { context.unbindService(connection) }
    }

    private class Connection(private val events: ScriptProcessLauncher.Events) : ServiceConnection, IBinder.DeathRecipient, ScriptProcessLauncher.Binding {
        @Volatile
        var released = false

        @Volatile
        private var binder: IBinder? = null

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (released) return
            if (service == null) {
                events.ended()
                return
            }
            binder = service
            try {
                service.linkToDeath(this, 0)
            } catch (e: RemoteException) {
                events.ended()
                return
            }
            events.connected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) = events.ended()

        override fun onBindingDied(name: ComponentName?) = events.ended()

        override fun onNullBinding(name: ComponentName?) = events.ended()

        override fun binderDied() = events.ended()

        fun unlink() {
            binder?.let { runCatching { it.unlinkToDeath(this, 0) } }
        }
    }

    private companion object {
        /** Called only on Android 10 and later ([sdkInt] is checked first; it is injected, so lint cannot see the check). */
        @SuppressLint("NewApi")
        fun bindIsolatedService(context: Context, intent: Intent, instance: String, executor: Executor, connection: ServiceConnection): Boolean =
            context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instance, executor, connection)
    }
}
