package com.aicontrolcenter.app.testing

import android.content.Intent
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.wiring.BackgroundPorts
import com.aicontrolcenter.app.di.wiring.BackgroundScreens
import com.aicontrolcenter.app.di.wiring.BackupWiring
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.BackupSummary
import com.aicontrolcenter.core.backup.RestoreReport
import com.aicontrolcenter.core.backup.SaveReport
import com.aicontrolcenter.data.BackupStores
import com.aicontrolcenter.data.Transactions
import com.aicontrolcenter.data.backup.DetailedOwnerBackup
import com.aicontrolcenter.data.backup.RestoreOutcome
import com.aicontrolcenter.data.backup.RestorePreview
import com.aicontrolcenter.data.backup.SaveOutcome
import com.aicontrolcenter.framework.llm.SimulatorProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext

/*
 * WP10b's test helpers (Settings › Working in the background, the backup screens, the Recovery screen): Android's
 * background screens as plain intents a test recognises, a test app whose graph has the real backup over its in-memory
 * stores, and a scripted backup port for the view models and screens.
 */

/** Android's background screens as intents a test recognises; null stands for a phone without that screen. */
class TestBackgroundScreens(
    @Volatile var battery: Intent? = Intent(BATTERY_ACTION),
    @Volatile var appPage: Intent? = Intent(APP_PAGE_ACTION),
) : BackgroundScreens {
    override fun batteryExemption(): Intent? = battery

    override fun appDetails(): Intent? = appPage

    companion object {
        const val BATTERY_ACTION = "test.action.BATTERY_EXEMPTION"
        const val APP_PAGE_ACTION = "test.action.APP_DETAILS"
    }
}

/**
 * A graph over [TestApp]'s ports with what TestApp leaves out: the real backup (`BackupWiring.production`, the app's
 * `RoomOwnerBackup`) over the test app's in-memory stores, and Android's background screens ([screens]). Everything
 * else is the test app's, so its stores and fakes still script and show what happens.
 */
fun TestApp.wp10bGraph(
    scope: CoroutineScope,
    screens: BackgroundScreens = TestBackgroundScreens(),
    freeBytes: Long = Long.MAX_VALUE / 4,
): AppGraph {
    val stores = BackupStores(
        agents = agents, providers = providers, models = models, modelPrices = prices, ownerModelPrices = prices, aiApps = aiApps,
        memories = memories, conversations = conversations, tasks = tasks, settings = settings, activityLog = activityLog,
        // In-memory stores have no transaction; the restore's checks still run before anything is written.
        transactions = object : Transactions {
            override suspend fun <R> run(block: suspend () -> R): R = block()
        },
    )
    val backup = BackupWiring.production(stores, maintenanceGate) { freeBytes }
    val background = BackgroundPorts(
        health = backgroundHealth, exits = exits, storage = storage, recovery = recovery, safeStart = safeStart, screens = screens,
    )
    return AppGraph(
        ports.copy(backup = backup, background = background), scope,
        factories.values.toList() + SimulatorProviderFactory(chunkDelayMs = 0),
        backgroundContext = EmptyCoroutineContext,
        notifications = notifications,
    ).also { graph -> BackupWiring.useToolTitles(graph.ownerBackup, graph.toolRegistry) }
}

/**
 * A backup port a test scripts: each call is recorded and answered by its lambda (by default a plain success). A call
 * can be held until [hold] completes, to see the screen while it works.
 */
class ScriptedBackup : DetailedOwnerBackup {
    class SaveCall(val passphrase: String, val generatedShown: String?)

    val saves = CopyOnWriteArrayList<SaveCall>()
    val previews = CopyOnWriteArrayList<String>()
    val restores = CopyOnWriteArrayList<String>()

    /** While set, a call reports [heldProgress] and waits for it before answering. */
    @Volatile var hold: CompletableDeferred<Unit>? = null
    @Volatile var heldProgress: BackupProgress? = null

    @Volatile var onSave: suspend (OutputStream) -> BackupResult<SaveOutcome> = { out ->
        out.write(byteArrayOf(1, 2, 3))
        out.close()
        BackupResult.Done(SaveOutcome(SaveReport(com.aicontrolcenter.core.backup.BackupCounts(agents = 6, memories = 2), 2_048), emptyList()))
    }
    @Volatile var onPreview: suspend (String) -> BackupResult<RestorePreview> = { BackupResult.Done(samplePreview()) }
    @Volatile var onRestore: suspend (String) -> BackupResult<RestoreOutcome> = {
        BackupResult.Done(RestoreOutcome(RestoreReport(com.aicontrolcenter.core.backup.BackupCounts(agents = 6), 0, emptyList()), emptyList()))
    }

    private suspend fun waitIfHeld(progress: (BackupProgress) -> Unit) {
        val gate = hold ?: return
        heldProgress?.let(progress)
        gate.await()
    }

    override suspend fun saveWithNotes(
        passphrase: CharSequence,
        generatedShown: String?,
        open: suspend () -> OutputStream,
        progress: (BackupProgress) -> Unit,
    ): BackupResult<SaveOutcome> {
        saves += SaveCall(passphrase.toString(), generatedShown)
        waitIfHeld(progress)
        return onSave(open())
    }

    override suspend fun preview(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit): BackupResult<RestorePreview> {
        previews += passphrase.toString()
        waitIfHeld(progress)
        return onPreview(passphrase.toString())
    }

    override suspend fun restoreWithNotes(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit): BackupResult<RestoreOutcome> {
        restores += passphrase.toString()
        waitIfHeld(progress)
        return onRestore(passphrase.toString())
    }

    companion object {
        /** A preview of a file made on 14 November 2026 holding six agents and two memories, with a few setting changes. */
        fun samplePreview(refusal: BackupProblem? = null, directorFrom: com.aicontrolcenter.core.AgentId? = null): RestorePreview = RestorePreview(
            summary = BackupSummary(
                createdAt = TestApp.START_TIME, appVersion = "0.6.0", schemaVersion = 6,
                counts = com.aicontrolcenter.core.backup.BackupCounts(agents = 6, providers = 1, models = 2, spaces = 1, memories = 2, conversations = 1, conversationEntries = 4),
                settingChanges = listOf(
                    com.aicontrolcenter.core.backup.SettingChange("Local-only mode", "Off", "On"),
                    com.aicontrolcenter.core.backup.SettingChange(
                        "Director", com.aicontrolcenter.core.backup.RestoreSettings.UNKNOWN_AGENT, "Planner",
                        agentFrom = directorFrom, agentTo = com.aicontrolcenter.core.AgentId("agt_planner"),
                    ),
                ),
                sizeBytes = 48_000,
            ),
            keptBack = listOf(com.aicontrolcenter.core.backup.SettingKeptBack("Daily limit of model answers", "1,000 model answers", "200 model answers")),
            alwaysOff = com.aicontrolcenter.core.backup.RestoreSettings.ALWAYS_OFF_AFTER_RESTORE,
            notes = listOf(com.aicontrolcenter.data.backup.BackupTexts.KEYS_AGAIN),
            refusal = refusal,
        )
    }
}

/** Shows [graph]'s app, as [AppRobot.launch] does for the test app's own graph; [results] answers Android's pickers. */
fun androidx.compose.ui.test.junit4.ComposeContentTestRule.showApp(
    graph: AppGraph,
    darkTheme: Boolean = true,
    results: androidx.activity.result.ActivityResultRegistry? = null,
) {
    setContent {
        if (results == null) {
            com.aicontrolcenter.app.ui.shell.AiControlCenterRoot(graph, darkTheme)
        } else {
            val owner = androidx.compose.runtime.remember(results) {
                object : androidx.activity.result.ActivityResultRegistryOwner {
                    override val activityResultRegistry: androidx.activity.result.ActivityResultRegistry = results
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                com.aicontrolcenter.app.ui.shell.AiControlCenterRoot(graph, darkTheme)
            }
        }
    }
    waitForIdle()
}
