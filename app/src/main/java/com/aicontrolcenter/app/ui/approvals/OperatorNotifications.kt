package com.aicontrolcenter.app.ui.approvals

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.work.NotificationAccess
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/*
 * The phone operator's notifications: "Approval needed" when an agent waits for the owner's
 * answer, and "<agent> is operating <app> · Stop". On Android 13 and later they need the owner's
 * permission, which is asked for, with an explanation first, when the owner turns the phone
 * operator on (or at the first start with it already on), and while it is off the Approvals list
 * and Tools say so, with a way to the system settings. Requests still wait in the app either way.
 */

@Immutable
data class OperatorNotificationsUiState(
    /** Explain why, then let Android ask: the phone operator is on and the owner has not been asked for it yet. */
    val explain: Boolean = false,
)

/**
 * Whether to ask for the notification permission for the phone operator now. It asks once,
 * separately from the objectives' ask: when the phone operator is on and the permission is
 * missing ([NotificationAccess.shouldAskForOperator]). "Not now" counts as an answer; the warning
 * banners stay while notifications are off.
 */
class OperatorNotificationsViewModel(
    settings: SettingsRepository,
    private val notifications: NotificationAccess,
) : ViewModel() {
    private val handled = MutableStateFlow(false)

    val uiState: StateFlow<OperatorNotificationsUiState> =
        combine(settings.settings.map { it.operatorEnabled }.distinctUntilChanged(), notifications.enabled, handled) { operatorOn, _, handled ->
            OperatorNotificationsUiState(explain = operatorOn && !handled && notifications.shouldAskForOperator())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OperatorNotificationsUiState())

    /** The owner read the explanation and Android is asking now; [answered] follows. */
    fun explained() = handled.update { true }

    /** Android answered, or the owner chose "Not now": not asked again from here. */
    fun answered() {
        notifications.markAskedForOperator()
        handled.update { true }
    }
}

/** The explanation and Android's own question, shown over whatever screen is open. Placed once, in the shell. */
@Composable
fun OperatorNotificationsAsk() {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { OperatorNotificationsViewModel(graph.settings, graph.notifications) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val permission = graph.notifications.permission
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.answered() }
    if (!state.explain) return
    ConfirmDialog(
        title = "Be told when an agent waits for you?",
        message = "With the phone operator on, agents sometimes need your permission or approval before they act. A notification " +
            "tells you when one is waiting, also when the app is closed, and another shows while an agent operates an app's " +
            "screen, with Stop. Android asks for permission next. If you say no, requests still wait in Approvals and in the " +
            "chat or task that asked, and are refused if nobody answers in time.",
        confirmLabel = "Continue",
        dismissLabel = "Not now",
        icon = AppIcons.Info,
        onConfirm = {
            viewModel.explained()
            if (permission != null) launcher.launch(permission) else viewModel.answered()
        },
        onDismiss = viewModel::answered,
    )
}

/** Said plainly while notifications are off, with the way to turn them on. */
@Composable
fun ApprovalNotificationsOffBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        InfoBanner(
            title = "Notifications are off",
            text = "You are not told when an agent waits for your answer, or while an agent operates an app's screen. Requests " +
                "still wait in Approvals and in the chat or task that asked, and are refused if nobody answers in time.",
            tone = StatusTone.ATTENTION,
        )
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }) { Text("Notification settings") }
    }
}
