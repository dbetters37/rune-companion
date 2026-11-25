package com.aicontrolcenter.app.work

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.wiring.RoutinesWiring
import com.aicontrolcenter.app.health.AppGate
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.health.StorageLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while objectives run or an agent is answering in a chat, so the work goes on when the owner leaves
 * the app or switches the screen off (Phase 6, goals 3 and 4; WP2 items 1, 2, 3, 10).
 *
 * It is a foreground service of type "specialUse": none of Android's predefined types fits agents working for the owner,
 * and the subtype in the manifest says what it does (Android 14-17 put no time limit on this type). It is started by
 * [ObjectiveWorkController], shows one ongoing notification (the oldest running objective with its progress and Pause;
 * or, while only chat answers are being written, "<agent> is answering" with Stop), and stops itself as soon as nothing
 * runs. It is not sticky: if Android kills the process, recovery at the next start marks the interrupted work.
 *
 * While it runs it also:
 * - keeps the phone awake only while agents really work or a wait is due within two minutes, with one wake alarm for a
 *   longer wait ([WakeDecision], [WakeHold], [WakeAlarms]; rule 5), and says so in the notification;
 * - measures the free room when an objective starts and every 5 minutes while one runs; when the phone is almost full it
 *   pauses the running objectives ("Paused: the phone is almost full") (R8);
 * - runs due routines when a routine's alarm asked for it ([RoutinesWiring.ACTION_RUN_DUE_ROUTINES]; WP17 fills
 *   `runDue`), after going into the foreground.
 *
 * The objectives themselves run in the application scope, not here: this service only holds the process up and reports.
 * Its notification's buttons only pause objectives or stop chat answers (rule 4); nothing in them answers or approves.
 *
 * Started while the app's graph is not built (a routine's alarm with the app closed), it builds the graph only once the
 * app's data opened ([AiControlCenterApp.gate]; rule 9, R5): while the data cannot be opened nothing runs and the service
 * stops; the Recovery screen shows when the owner opens the app. A failure nothing else caught is logged where the log
 * can be reached, and the process goes on (AND-W2B-2).
 */
class ObjectiveWorkService : Service() {
    private val graph: AppGraph get() = (application as AiControlCenterApp).graph
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + AppProcessHooks.scopeContext(application)) }
    private var watching: Job? = null
    private var latest: Shown = Shown.STARTING

    /** Work an intent asked for that is still going (routines): the service does not stop under it. */
    private val asked = MutableStateFlow(0)

    /** Whether the phone is kept awake now: the notification says so. */
    private val awake = MutableStateFlow(false)
    private lateinit var wakeHold: WakeHold
    private lateinit var alarms: WakeAlarms

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WorkNotifications.ensureChannel(this)
        wakeHold = WakeHold(this, scope)
        alarms = WakeAlarms(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start through startForegroundService must be answered with startForeground, first.
        goForeground()
        val action = intent?.action
        val app = application as AiControlCenterApp
        if (app.graphIfBuilt() != null) {
            begin(action)
        } else {
            // Not built yet (a routine's alarm with the app closed): only once the data opened, never while it cannot be.
            waitingForData += 1
            scope.launch {
                val opened = try {
                    app.gate.state.first { it != AppGate.State.Checking } == AppGate.State.Open
                } finally {
                    waitingForData -= 1
                }
                if (opened) begin(action) else if (watching == null && waitingForData == 0) stop()
            }
        }
        return START_NOT_STICKY
    }

    /** Starts waiting for the data to open; the service stops when none of them opened it. Main thread only. */
    private var waitingForData = 0

    /** Does what [action] asked, and watches the work, once the graph may be used. */
    private fun begin(action: String?) {
        when (action) {
            ACTION_PAUSE_ALL -> pauseAll()
            ACTION_STOP_CHATS -> stopChats()
            RoutinesWiring.ACTION_RUN_DUE_ROUTINES -> runDueRoutines()
        }
        if (watching == null) {
            watching = scope.launch { watch() }
            scope.launch { keepAwake() }
            scope.launch { watchStorage() }
        }
    }

    override fun onDestroy() {
        if (::wakeHold.isInitialized) wakeHold.release()
        if (::alarms.isInitialized) alarms.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** A log line that cannot be written (the data no longer opens) is left out; it never ends the service's work. */
    private suspend fun logSafely(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Nowhere to report to.
        }
    }

    /** What the notification shows. */
    private data class Shown(val objectives: WorkNotice?, val objectivesRunning: Boolean, val chat: ChatNotice?, val awake: Boolean) {
        companion object {
            val STARTING = Shown(objectives = null, objectivesRunning = true, chat = null, awake = false)
        }
    }

    private fun notification(shown: Shown) =
        if (!shown.objectivesRunning && shown.chat != null) {
            WorkNotifications.buildChat(this, shown.chat, shown.awake)
        } else {
            WorkNotifications.build(this, shown.objectives, shown.awake)
        }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, WorkNotifications.NOTIFICATION_ID, notification(latest), type)
        } catch (refused: IllegalStateException) {
            // Android refused to make it a foreground service (started from the background through
            // a stale notification). Whatever was asked (a pause) still happens; then it stops.
            val built = (application as AiControlCenterApp).graphIfBuilt()
            if (built == null || built.orchestrator.running.value.isEmpty() && built.agentChat.liveTurns.value.isEmpty()) stopSelf()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch() {
        val graph = graph
        val running = graph.orchestrator.running
        // Summaries only (P1, rule 16): the rows' titles and the plans' progress, never whole task trees.
        val notices: Flow<WorkNotice?> = running.flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(null)
            } else {
                combine(graph.tasks.observeObjectiveRows(ResultNotifier.ROWS), graph.tasks.observeProgress(ids)) { rows, progress ->
                    WorkNotice.of(ids, rows, progress)
                }
            }
        }
        val chats: Flow<ChatNotice?> = graph.agentChat.liveTurns.map { it.keys }.distinctUntilChanged().mapLatest { ids ->
            if (ids.isEmpty()) null else ChatNotice(ids.map { agentNameOf(graph, it) })
        }
        combine(running, notices, chats, asked, awake) { ids, notice, chat, busy, keptAwake ->
            if (ids.isEmpty() && chat == null && busy == 0) null else Shown(notice, ids.isNotEmpty(), chat, keptAwake)
        }.distinctUntilChanged().collect { shown ->
            if (shown == null) {
                stop()
            } else {
                latest = shown
                WorkNotifications.post(this, notification(shown))
            }
        }
    }

    /** Rule 5: the wake lock and the wake alarm follow [WakeDecision]. */
    private suspend fun keepAwake() {
        val graph = graph
        val inputs = combine(
            graph.settingsView.settings.map { it.keepAwakeWhileAgentsWork }.distinctUntilChanged(),
            graph.activityMonitor.states.map { states -> states.values.any { it.activity == AgentActivity.WORKING } }.distinctUntilChanged(),
            graph.agentChat.liveTurns.map { turns -> turns.values.any { it.waitingForOwner == null } }.distinctUntilChanged(),
            combine(graph.orchestrator.timedWaits.nextDueAt, graph.providerRechecks.timedWaits.nextDueAt) { a, b -> WakeDecision.earliest(a, b) },
            WakeSignals.signals,
        ) { allowed, working, answering, due, _ -> WakeDecision.Input(allowed, working, answering, due) }
        var last: WakeDecision? = null
        inputs.collectLatestDecisions(graph) { decision, now ->
            if (decision != last) {
                last = decision
                wakeHold.set(decision.hold)
                awake.value = decision.hold
                decision.alarmAt?.let { alarms.set(it, now) } ?: alarms.cancel()
            }
        }
    }

    private suspend fun Flow<WakeDecision.Input>.collectLatestDecisions(graph: AppGraph, apply: (WakeDecision, Long) -> Unit) {
        @OptIn(ExperimentalCoroutinesApi::class)
        mapLatest { input ->
            while (true) {
                val now = graph.clock.now()
                val decision = WakeDecision.of(input, now)
                apply(decision, now)
                val recheck = decision.recheckAt ?: break
                // Decide again when the wait comes within two minutes (if the phone sleeps through this, the alarm wakes it).
                delay((recheck - now).coerceAtLeast(MIN_RECHECK_MS))
            }
        }.collect { }
    }

    /** R8: free room when an objective starts and every 5 minutes while one runs; almost full pauses them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watchStorage() {
        val graph = graph
        graph.orchestrator.running.mapLatest { running ->
            while (running.isNotEmpty()) {
                if (graph.storageWatch.check() == StorageLevel.CRITICAL) {
                    // Pausing changes what runs, which restarts this loop: the pausing goes on on its own, and the service
                    // stays up until it is done.
                    whileHeld { pauseForStorage(graph, running) }
                    break
                }
                delay(STORAGE_CHECK_MS)
            }
        }.collect { }
    }

    private suspend fun pauseForStorage(graph: AppGraph, running: Set<TaskId>) {
        val titles = try {
            graph.tasks.observeObjectiveRows(ResultNotifier.ROWS).first().associate { it.rootId to it.title }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        for (id in running) {
            try {
                graph.orchestrator.pause(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            logSafely {
                graph.activityRecorder.record(
                    type = ActivityType.SYSTEM,
                    title = WorkResult.STORAGE_FULL,
                    detail = "The phone has less than 100 MB free, so the app paused this objective before it runs out of room. " +
                        "Free some room, then resume it.",
                    severity = Severity.WARNING,
                    rootTaskId = id,
                )
            }
            if (!graph.isInForeground.value && graph.settingsView.get().notifyWhenWorkEnds) {
                ResultNotifications.post(this, WorkResult.storageFull(id, titles[id] ?: "An objective"))
            }
        }
    }

    private suspend fun agentNameOf(graph: AppGraph, id: ConversationId): String =
        try {
            val conversation = graph.agentChat.observeConversations().first().firstOrNull { it.id == id }
            conversation?.let { graph.ports.agents.get(it.agentId)?.name } ?: "An agent"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "An agent"
        }

    /** Runs [block] on the service's scope, holding the service up until it is done (what it does may end the work). */
    private fun whileHeld(block: suspend () -> Unit) {
        asked.value += 1
        scope.launch {
            try {
                block()
            } finally {
                asked.value -= 1
            }
        }
    }

    private fun pauseAll() {
        whileHeld {
            for (id in graph.orchestrator.running.value) {
                try {
                    graph.orchestrator.pause(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Gone or already finished: nothing to pause. Anything else is worth a log line.
                    logSafely {
                        graph.activityRecorder.record(
                            type = ActivityType.ERROR,
                            title = "Could not pause an objective from the notification",
                            detail = failure.message ?: failure.javaClass.simpleName,
                            severity = Severity.WARNING,
                        )
                    }
                }
            }
        }
    }

    /** Stop in the chat notification: every answer being written stops; what was written so far is kept. */
    private fun stopChats() {
        whileHeld {
            for (id in graph.agentChat.liveTurns.value.keys) {
                try {
                    graph.agentChat.cancel(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Already over.
                }
            }
        }
    }

    /** A routine's alarm asked (WP17): runs the due routines, holding the service up until they have started. */
    private fun runDueRoutines() {
        whileHeld {
            try {
                runDue(graph)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logSafely {
                    graph.activityRecorder.record(
                        type = ActivityType.ERROR,
                        title = "Could not run the routines that were due",
                        detail = failure.javaClass.simpleName,
                        severity = Severity.WARNING,
                    )
                }
            }
        }
    }

    private fun stop() {
        wakeHold.release()
        alarms.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        /** The notification's Pause button: pauses every running objective. */
        const val ACTION_PAUSE_ALL = "com.aicontrolcenter.app.action.PAUSE_ALL_OBJECTIVES"

        /** The chat notification's Stop button: stops every answer being written. */
        const val ACTION_STOP_CHATS = "com.aicontrolcenter.app.action.STOP_CHAT_ANSWERS"

        /** How often the free room is measured while an objective runs. */
        const val STORAGE_CHECK_MS: Long = 5 * 60_000L

        private const val MIN_RECHECK_MS = 1_000L

        /** Runs the due routines (WP17's `RoutinesAssembly.runDue`); replaced by tests that count the calls. */
        @Volatile
        internal var runDue: suspend (AppGraph) -> Unit = { it.routinesAssembly.runDue() }
    }
}
