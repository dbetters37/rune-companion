package com.aicontrolcenter.device.screen

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.preferencesDataStoreFile
import com.aicontrolcenter.core.operator.ScreenControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Where the owner's Stop is kept across restarts. */
internal interface StopStore {
    /** The stored value; false when nothing was stored. */
    suspend fun read(): Boolean

    suspend fun write(stopped: Boolean)
}

/** [StopStore] on a Preferences DataStore file. A damaged file reads as stopped. */
internal class DataStoreStopStore(private val store: DataStore<Preferences>) : StopStore {
    override suspend fun read(): Boolean = store.data.first()[KEY] ?: false

    override suspend fun write(stopped: Boolean) {
        store.edit { it[KEY] = stopped }
    }

    /** The file. */
    companion object {
        const val FILE_NAME = "screen_operator"
        val KEY: Preferences.Key<Boolean> = booleanPreferencesKey("stopped")

        /** Opens the store; one per file per process, as DataStore requires. */
        fun create(context: Context, scope: CoroutineScope, fileName: String = FILE_NAME): DataStoreStopStore = DataStoreStopStore(
            PreferenceDataStoreFactory.create(
                // If the owner's choice cannot be read, operating stays stopped until they resume it.
                corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(KEY to true) },
                scope = scope,
                produceFile = { context.applicationContext.preferencesDataStoreFile(fileName) },
            ),
        )
    }
}

/**
 * The owner's Stop: takes effect in memory at once ([stop] tells its listeners synchronously, so
 * the call in progress is cancelled before it returns), is written to [store] right after, and is
 * read back at start-up. Until that read finishes, and whenever it fails, operating counts as
 * stopped; an owner action before the read finishes wins over the stored value.
 *
 * Writes run one at a time in [scope], each storing the value current when it runs, so the last
 * one written is the current one. [awaitWrites] waits for every write started so far. A write is
 * cancelled with [scope] (the process scope is never cancelled; a test closing a switch is), so
 * closing a switch never waits on a write that can no longer finish.
 */
internal class StopSwitch(private val store: StopStore, internal val scope: CoroutineScope) {
    private val lock = Any()
    private val flow = MutableStateFlow(true)
    private val loaded = CompletableDeferred<Unit>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val writes = Mutex()
    private var ownerActions = 0

    val stopped: StateFlow<Boolean> = flow.asStateFlow()

    init {
        scope.launch {
            try {
                val saved = store.read()
                synchronized(lock) { if (ownerActions == 0) flow.value = saved }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "The screen operator's Stop could not be read; it stays stopped.", e)
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    /** Returns once the stored value has been read (or could not be). */
    suspend fun awaitLoaded() = loaded.await()

    /** Runs [listener] on every [stop], on the caller's thread, before [stop] returns. */
    fun onStop(listener: () -> Unit) {
        listeners += listener
    }

    fun stop() {
        stopAndSave()
    }

    /** [stop], returning the write so a broadcast receiver can wait for it before its process may end. */
    fun stopAndSave(): Job {
        synchronized(lock) {
            ownerActions++
            flow.value = true
        }
        listeners.forEach { it() }
        return persist()
    }

    fun resume() {
        resumeAndSave()
    }

    /** [resume], returning the write. */
    fun resumeAndSave(): Job {
        synchronized(lock) {
            ownerActions++
            flow.value = false
        }
        return persist()
    }

    /** Writes started and not finished yet. */
    private val inFlight: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** Returns once every write started so far has finished (or was cancelled with [scope]). */
    suspend fun awaitWrites() {
        while (true) {
            val writing = inFlight.toList()
            if (writing.isEmpty()) return
            writing.joinAll()
        }
    }

    /** Writes the latest value; writes run one at a time, so the last one written is the current one. */
    private fun persist(): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            writes.withLock {
                try {
                    store.write(flow.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "The screen operator's Stop could not be saved; it holds until the app restarts.", e)
                }
            }
        }
        inFlight += job
        job.invokeOnCompletion { inFlight -= job }
        job.start()
        return job
    }

    /** The process-wide switch. */
    companion object {
        private const val TAG = "ScreenOperator"

        @Volatile
        private var process: StopSwitch? = null

        /** The one switch of this process, over the one DataStore file; the operator and the notification's Stop share it. */
        fun process(context: Context): StopSwitch = process ?: synchronized(this) {
            process ?: run {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                StopSwitch(DataStoreStopStore.create(context, scope), scope).also { process = it }
            }
        }

        /** Tests only: forgets the process switch and closes its file, as a process restart would. */
        @VisibleForTesting
        internal suspend fun resetProcess() {
            val old = synchronized(this) { process.also { process = null } } ?: return
            old.awaitWrites()
            old.scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}

/**
 * What the agent-facing screen operator may do with the owner's Stop: read it, be told of it,
 * wait for the stored value, and press Stop when the owner taps it on the operator's banner.
 * Never Resume, which is the owner's [ScreenControl] alone ([SwitchScreenControl]): the operator
 * holds this view, not the [StopSwitch].
 */
internal class StopSignal(private val switch: StopSwitch) {
    val stopped: StateFlow<Boolean> get() = switch.stopped

    /** Runs [listener] on every Stop, on the caller's thread, before Stop returns. */
    fun onStop(listener: () -> Unit) = switch.onStop(listener)

    /** Returns once the stored value has been read (or could not be). */
    suspend fun awaitLoaded() = switch.awaitLoaded()

    /** The owner tapped Stop on the banner. */
    fun stop() = switch.stop()
}

/** The owner's [ScreenControl] over a [StopSwitch]. Owner-only: handed to the Screen control UI, never to tools. */
internal class SwitchScreenControl(private val switch: StopSwitch) : ScreenControl {
    override val stopped: StateFlow<Boolean> get() = switch.stopped

    override fun stop() = switch.stop()

    override fun resume() = switch.resume()
}
