package com.aicontrolcenter.app.ui.tools

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ObscuredTouchGuard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Accessibility
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.StatusDisabled
import com.aicontrolcenter.app.ui.icons.StatusNotDone
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.ScreenServiceState
import kotlinx.coroutines.launch

/** What the Screen control tab can ask for. */
class ScreenControlActions(
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val onCheckAgain: () -> Unit,
    /** Settings, opened at the Phone operator switch (setup step 1). */
    val onOpenOperatorSettings: () -> Unit,
    /** Settings, opened at the Screen control switch (setup step 2). */
    val onOpenScreenControlSettings: () -> Unit,
    val onStop: () -> Unit,
    val onResume: () -> Unit,
    val onOpenPicker: () -> Unit,
    val onClosePicker: () -> Unit,
    val onAuthorise: (packageName: String, label: String) -> Unit,
    val onWithdraw: (String) -> Unit,
    val onSetAccess: (String, AgentId, AccessLevel) -> Unit,
    val onSetPolicy: (String, ScreenActionPolicy) -> Unit = { _, _ -> },
)

/** The authorised apps' header is the third item of the tab, after the setup steps and Stop; step 5 scrolls to it. */
private const val APPS_HEADER_INDEX = 2

/**
 * Screen control: the five steps of setting it up, each one tap from where it is done and marked done or not; Stop and
 * Resume; the apps agents may be allowed to see and use, each checked to be that exact app. Touches are refused while
 * another app's window covers the tab or its dialogs, since they give agents access.
 */
@Composable
fun ScreenControlTab(state: ScreenControlUiState, actions: ScreenControlActions) {
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    ObscuredTouchGuard()
    var confirmWithdraw by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val calm = LocalCalmMotion.current
    val showApps: () -> Unit = {
        scope.launch { if (calm) list.scrollToItem(APPS_HEADER_INDEX) else list.animateScrollToItem(APPS_HEADER_INDEX) }
    }
    LazyColumn(
        state = list,
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.md, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "setup") { SetupChecklistCard(state.checklist, state.service.state, actions, onShowApps = showApps) }
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

/** The title of the setup card on top of Tools › Screen control. */
const val SETUP_TITLE: String = "Set up phone control"

/** Where each setup step leads, as TalkBack offers it ("Double tap to open App info"). */
internal val SetupStep.destination: String
    get() = when (this) {
        SetupStep.PHONE_OPERATOR -> "Open Settings at Phone operator"
        SetupStep.SCREEN_CONTROL -> "Open Settings at Screen control"
        SetupStep.RESTRICTED_SETTINGS -> "Open App info"
        SetupStep.ACCESSIBILITY -> "Open accessibility settings"
        SetupStep.APPS -> "Show the authorised apps"
    }

/**
 * "Set up phone control": how far the owner is, then the five steps in order. Each step is one row that opens where it
 * is done and says whether it is, with an icon and a word, never colour alone. The steps are read again when the owner
 * comes back from Android's screens (the Tools section's resume effect) and with Check again.
 */
@Composable
private fun SetupChecklistCard(checklist: SetupChecklistUi, service: ScreenServiceState, actions: ScreenControlActions, onShowApps: () -> Unit) {
    AppCard {
        Text(
            SETUP_TITLE,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            modifier = Modifier.padding(top = Spacing.xs).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (checklist.allDone) {
                // As tall as the summary's first line, at any font size (titleSmall's line height).
                Icon(AppIcons.StatusReady, contentDescription = null, tint = appColors.ready, modifier = Modifier.size(textSize(20.sp)))
            }
            Text(checklist.summary, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        checklist.steps.forEach { step ->
            RowDivider(Modifier.padding(vertical = Spacing.xs))
            SetupStepRow(
                step,
                onClick = when (step.step) {
                    SetupStep.PHONE_OPERATOR -> actions.onOpenOperatorSettings
                    SetupStep.SCREEN_CONTROL -> actions.onOpenScreenControlSettings
                    SetupStep.RESTRICTED_SETTINGS -> actions.onOpenAppInfo
                    SetupStep.ACCESSIBILITY -> actions.onOpenAccessibilitySettings
                    SetupStep.APPS -> onShowApps
                },
            )
        }
        // Android 9 to 12 send no signal when the service is switched on; coming back to the tab reads it again too.
        if (service != ScreenServiceState.READY) {
            TextButton(onClick = actions.onCheckAgain, modifier = Modifier.padding(top = Spacing.xs).heightIn(min = 48.dp)) {
                Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Check again", modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }
}

/**
 * One step: its number, title, status (icon and word) and, while it is not done, what to do there. The whole row is one
 * target of at least 48 dp, which TalkBack reads as "Step 1, Turn on Phone operator, Done" with where it leads.
 */
@Composable
private fun SetupStepRow(step: SetupStepUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touchTarget)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = step.step.destination, onClick = onClick)
            .clearAndSetSemantics { contentDescription = step.spoken }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        StepNumber(step.step.number)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(step.step.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            StepStatusLine(step.status)
            step.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically).size(24.dp),
        )
    }
}

/** [size] in dp, so an icon beside text grows with the owner's font size as the text does. */
@Composable
private fun textSize(size: TextUnit): Dp = with(LocalDensity.current) { size.toDp() }

/** The step's number in a circle that grows with the font size, so a large number never spills out of it. */
@Composable
private fun StepNumber(number: Int) {
    Box(
        modifier = Modifier.size(textSize(28.sp)).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Done (a check mark), Not done (an empty circle) or Not needed on this phone (a dash), each with its word. */
@Composable
private fun StepStatusLine(status: StepStatus) {
    val (icon, color) = when (status) {
        StepStatus.DONE -> AppIcons.StatusReady to appColors.status(StatusTone.READY)
        // INFO: a step of setting up that has not happened, deliberately calm.
        StepStatus.NOT_DONE -> AppIcons.StatusNotDone to appColors.status(StatusTone.INFO)
        StepStatus.NOT_NEEDED -> AppIcons.StatusDisabled to appColors.status(StatusTone.DISABLED)
    }
    // The icon sits on the word's first line ("Not needed on this phone" can wrap at large font sizes).
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = textSize(1.sp)).size(textSize(18.sp)))
        Text(status.label, style = MaterialTheme.typography.titleSmall, color = color)
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
        PolicyChoice(app, isBusy, actions)
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
 * How often the app's screen actions ask the owner. Asking before every action is the default;
 * asking less is confirmed first, because a control worded in a way the app does not recognise
 * then runs without asking.
 */
@Composable
private fun PolicyChoice(app: OperatorAppUi, isBusy: Boolean, actions: ScreenControlActions) {
    var confirmRelaxed by rememberSaveable(app.packageName) { mutableStateOf(false) }
    SectionHeader("When it asks you")
    Column(Modifier.selectableGroup()) {
        for (policy in ScreenActionPolicy.entries) {
            val chosen = app.policy == policy
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = chosen, enabled = !isBusy, role = Role.RadioButton) {
                        when {
                            chosen -> Unit
                            policy == ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY -> confirmRelaxed = true
                            else -> actions.onSetPolicy(app.packageName, policy)
                        }
                    }
                    .padding(vertical = Spacing.xs),
            ) {
                RadioButton(selected = chosen, onClick = null, enabled = !isBusy)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ScreenPolicyText.title(policy), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Caption(ScreenPolicyText.description(policy, app.label))
                }
            }
        }
    }
    if (confirmRelaxed) {
        ConfirmDialog(
            title = "Ask less in ${app.label}?",
            message = ScreenPolicyText.description(ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY, app.label) +
                " Avoid this for banking, payment, shopping, mail and messaging apps.",
            confirmLabel = "Ask less",
            dismissLabel = "Back",
            icon = AppIcons.Accessibility,
            cautionary = true,
            widens = true,
            guarded = true,
            onConfirm = {
                confirmRelaxed = false
                actions.onSetPolicy(app.packageName, ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
            },
            onDismiss = { confirmRelaxed = false },
        )
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
                "at a time. Each of those actions asks you first, with what the screen shows; you can make ${app.label} ask " +
                "less afterwards. Take care with banking, payment, messaging and terminal apps: an agent allowed there can act " +
                "as you there.",
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
