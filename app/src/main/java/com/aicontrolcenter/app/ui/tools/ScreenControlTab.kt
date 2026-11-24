package com.aicontrolcenter.app.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.ObscuredTouchGuard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Accessibility
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId

/** What the Screen control tab can ask for. */
class ScreenControlActions(
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val onCheckAgain: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onStop: () -> Unit,
    val onResume: () -> Unit,
    val onOpenPicker: () -> Unit,
    val onClosePicker: () -> Unit,
    val onAuthorise: (packageName: String, label: String) -> Unit,
    val onWithdraw: (String) -> Unit,
    val onSetAccess: (String, AgentId, AccessLevel) -> Unit,
)

/**
 * Screen control: Android's accessibility service and how to switch it on, Stop and Resume, and
 * the apps agents may be allowed to see and use, each checked to be that exact app. Touches are
 * refused while another app's window covers the tab or its dialogs, since they give agents access.
 */
@Composable
fun ScreenControlTab(state: ScreenControlUiState, actions: ScreenControlActions) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    ObscuredTouchGuard()
    var confirmWithdraw by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.md, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "service") { ServiceCard(state, actions) }
        item(key = "stop") { StopCard(state.stopped, state.isBusy, actions) }
        item(key = "apps-header") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeader("Authorised apps")
                Text(
                    "Only the apps listed here can be seen or used, and only by the agents you choose for each app. Each app is checked " +
                        "when you authorise it, so a look-alike installed later under the same name is not trusted. Android " +
                        "settings, installers, permission screens, the lock screen and this app are never operated.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = actions.onOpenPicker, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Authorise an app")
                }
            }
        }
        if (state.apps.isEmpty()) {
            item(key = "empty") {
                EmptyState(icon = AppIcons.Apps, title = "No apps authorised", message = "Until you authorise an app, no agent can read or use any screen.")
            }
        }
        items(state.apps, key = { it.packageName }) { app ->
            AuthorisedAppCard(app, state.isBusy, actions, onWithdraw = { confirmWithdraw = app.packageName })
        }
    }
    val withdrawing = state.apps.firstOrNull { it.packageName == confirmWithdraw }
    if (withdrawing != null) {
        ConfirmDialog(
            title = "Remove ${withdrawing.label}?",
            message = "No agent can see or use ${withdrawing.label}'s screen any more: every permission for it is removed. The app itself is not changed.",
            confirmLabel = "Remove",
            destructive = true,
            guarded = true,
            icon = AppIcons.Accessibility,
            onConfirm = {
                confirmWithdraw = null
                actions.onWithdraw(withdrawing.packageName)
            },
            onDismiss = { confirmWithdraw = null },
        )
    }
    state.picker?.let { AppPicker(it, actions) }
}

@Composable
private fun ServiceCard(state: ScreenControlUiState, actions: ScreenControlActions) {
    val service = state.service
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("Android's screen-control permission", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            StatusChip(service.label.text, service.label.tone)
            service.label.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (service.steps.isNotEmpty()) {
                SectionHeader("To switch it on")
                service.steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(step, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (service.offerAccessibilitySettings) {
                Button(onClick = actions.onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Open accessibility settings")
                }
            }
            if (service.offerAppInfo) {
                OutlinedButton(onClick = actions.onOpenAppInfo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Open App info")
                }
            }
            if (service.state != com.aicontrolcenter.core.operator.ScreenServiceState.READY) {
                TextButton(onClick = actions.onCheckAgain, modifier = Modifier.align(Alignment.End)) { Text("Check again") }
            }
            RowDivider(Modifier.padding(vertical = Spacing.xs))
            Caption("The switches in Settings")
            KeyValueRow("Phone operator", if (state.operatorEnabled) "On" else "Off")
            KeyValueRow("Screen control", if (state.screenControlEnabled) "On" else "Off")
            if (!state.operatorEnabled || !state.screenControlEnabled) {
                Caption("Both switches must be on before any agent can see or use a screen.")
                OutlinedButton(onClick = actions.onOpenSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Settings") }
            }
        }
    }
}

/** Stop and Resume. Like the whole tab, Resume refuses touches while another window covers this screen. */
@Composable
private fun StopCard(stopped: Boolean, isBusy: Boolean, actions: ScreenControlActions) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (stopped) {
                StatusChip("Stopped", StatusTone.ATTENTION, icon = AppIcons.Stop)
                Text(
                    "You pressed Stop. No agent can read or use any app's screen, and the action that was running ended. It stays " +
                        "this way, also after a restart, until you resume here. Agents cannot press Resume: they never operate this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    onClick = actions.onResume,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { isSensitiveData = true },
                ) { Text("Resume screen control") }
            } else {
                Text("Stop", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "While an agent operates an app, a banner with Stop shows over it, and a notification too while notifications are allowed. Stop here works the same: " +
                        "every screen action ends at once and nothing more happens until you resume.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = actions.onStop,
                    enabled = !isBusy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Stop screen control") }
            }
        }
    }
}

@Composable
private fun AuthorisedAppCard(app: OperatorAppUi, isBusy: Boolean, actions: ScreenControlActions, onWithdraw: () -> Unit) {
    var showDetails by rememberSaveable(app.packageName) { mutableStateOf(false) }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(app.packageName, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Caption(app.authorised)
            }
            TextButton(
                onClick = onWithdraw,
                enabled = !isBusy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { contentDescription = "Remove ${app.label}" },
            ) { Text("Remove") }
        }
        if (app.inEffect) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
                Caption(app.certificate, Modifier.weight(1f))
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.semantics { contentDescription = (if (showDetails) "Hide details of " else "Details of ") + app.label },
                ) { Text(if (showDetails) "Hide details" else "Details") }
            }
            if (showDetails) app.fingerprint?.let { Text(it, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            InfoBanner(app.certificate, tone = StatusTone.ATTENTION, modifier = Modifier.padding(top = Spacing.sm))
            OutlinedButton(
                onClick = { actions.onAuthorise(app.packageName, app.label) },
                enabled = !isBusy,
                modifier = Modifier.padding(top = Spacing.sm).fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Authorise again") }
        }
        RowDivider(Modifier.padding(vertical = Spacing.sm))
        SectionHeader("Who may use it")
        app.agents.forEach { access ->
            AgentAccessRow(
                access = access,
                labels = ScreenControlViewModel.KIND.labels,
                tools = "screen tools",
                enabled = !isBusy,
                onChange = { level -> access.agent.id?.let { actions.onSetAccess(app.packageName, it, level) } },
            )
        }
    }
}

/**
 * The installed apps to pick from. Protected apps are listed but cannot be picked, with the
 * reason. Picking an app only chooses it: the next step names it and says what authorising allows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppPicker(picker: AppPickerUi, actions: ScreenControlActions) {
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val app = picker.apps.firstOrNull { it.packageName == chosen && it.selectable }
    if (app != null) {
        ConfirmDialog(
            title = "Authorise ${app.label}?",
            message = "Agents you then choose can see ${app.label}'s screen, and tap and type in it as if they were you, one action " +
                "at a time. Take care with banking, payment, messaging and terminal apps: an agent allowed there can act as you " +
                "there. Buying, sending, deleting and similar still ask you every time.",
            confirmLabel = "Authorise",
            dismissLabel = "Back",
            icon = AppIcons.Accessibility,
            cautionary = true,
            widens = true,
            guarded = true,
            onConfirm = {
                chosen = null
                actions.onAuthorise(app.packageName, app.label)
            },
            onDismiss = { chosen = null },
        )
        return
    }
    AlertDialog(
        onDismissRequest = actions.onClosePicker,
        title = { Text("Authorise an app") },
        text = {
            ObscuredTouchGuard()
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Caption("Pick the app agents may be allowed to see and use. You confirm on the next step.")
                when {
                    picker.isLoading -> Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    picker.failure != null -> Text(picker.failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    picker.apps.isEmpty() -> Text("No apps that can be opened were found.", style = MaterialTheme.typography.bodyMedium)
                    else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(picker.apps, key = { it.packageName }) { item ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = item.selectable, role = Role.Button, onClickLabel = "Choose ${item.label}") { chosen = item.packageName }
                                    .heightIn(min = 48.dp)
                                    .padding(vertical = Spacing.sm),
                            ) {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (item.selectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    Text(item.packageName, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    item.note?.let { Caption(it) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = actions.onClosePicker) { Text("Cancel") } },
    )
}
