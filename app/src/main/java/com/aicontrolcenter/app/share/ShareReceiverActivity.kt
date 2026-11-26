package com.aicontrolcenter.app.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aicontrolcenter.app.AiControlCenterApp
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.health.GateHost
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.recovery.RecoveryHost
import com.aicontrolcenter.app.ui.shell.AppEnvironment
import com.aicontrolcenter.app.ui.shell.AppScreenWork
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.AppTheme
import kotlin.coroutines.CoroutineContext

/**
 * Receives text another app shares (owner request #43, rule 34, Tier B; WP16): `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
 * of `text/` types only (the manifest's filter, checked again here, since an app can start this activity by name with
 * anything), at most 200,000 characters of shared text and 10 files of at most 1 MB each ([ShareIntake]).
 *
 * - It passes the app's gate first (`AiControlCenterApp.gate`): while the data is being opened nothing shows, and when
 *   it cannot be opened the Recovery screen shows instead; the graph is touched only once the gate is open.
 * - It reads the shared files once, off the main thread, through the one-time read grant the other app gave, and never
 *   a `file:` path or one of this app's own providers.
 * - It never starts work, never calls a model and does not run the app's start-up (no `graph.start()`, which could
 *   resume interrupted objectives); building the graph starts no agent.
 * - Nothing is stored until the owner picks a memory space (or the workspace) and taps Save on [ShareScreen]; Back,
 *   "Don't save" and leaving the screen store nothing. Screen control cannot press Save: it never acts on this app's own
 *   windows.
 * - It is allowed in local-only mode: storing sends nothing anywhere.
 *
 * Its own activity, never [com.aicontrolcenter.app.MainActivity] (rule 23); not in the recent apps list.
 */
class ShareReceiverActivity : ComponentActivity() {
    private val app get() = application as AiControlCenterApp

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not a share of text (another action or type, started by name): closed at once, nothing read or stored.
        val request = ShareIntake.requestOf(intent) ?: return finish()
        val source = ContentResolverShareSource(applicationContext)
        // Reading starts now, while the gate looks at the data; a rotation keeps what was read.
        val intake = ViewModelProvider(
            this,
            viewModelFactory { initializer { ShareIntakeViewModel(request, source) } },
        )[ShareIntakeViewModel::class.java]
        val gate = app.gate
        setContent {
            AppTheme {
                GateHost(gate, recovery = { state -> RecoveryHost(state, app.recovery, onTryAgain = gate::recheck) }) {
                    ShareHost(app.graph, intake, screenWork = AppScreenWork, onDone = ::finish)
                }
            }
        }
    }
}

/**
 * The share screen over [graph] once [intake] has read the share: "Reading what was shared…" until then. The view
 * model is the owner's: the memory service as the owner, the workspace, the read-only settings and directory, the
 * activity log; never a settings writer, never anything an agent holds.
 *
 * @param screenWork where the view model works out sizes and parts (the background pool in the app); null keeps the
 *   caller's own (tests).
 */
@Composable
internal fun ShareHost(graph: AppGraph, intake: ShareIntakeViewModel, screenWork: CoroutineContext?, onDone: () -> Unit) {
    AppEnvironment(graph.settingsView, screenWork) {
        val read by intake.intake.collectAsStateWithLifecycle()
        val shared = read
        if (shared == null) {
            ScreenScaffold(title = ShareTexts.TITLE, onBack = onDone) { padding ->
                Working(ShareTexts.READING, Modifier.padding(padding), onCancel = onDone)
            }
        } else {
            val work = LocalScreenWork.current
            val viewModel = viewModel {
                ShareViewModel(
                    items = shared.items,
                    memory = graph.memoryService,
                    workspace = graph.workspace,
                    settings = graph.settingsView,
                    directory = graph.registryDirectory,
                    activity = graph.activityRecorder,
                    leftOut = shared.leftOut,
                    clock = graph.clock,
                    computation = work,
                )
            }
            ShareScreen(viewModel, onDone)
        }
    }
}
