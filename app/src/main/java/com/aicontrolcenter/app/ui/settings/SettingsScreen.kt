package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.BuildConfig
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.RowText
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StepperRow
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.settings.FrameworkSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** What the settings screen can ask for. */
class SettingsActions(
    val onLocalOnlyModeChange: (Boolean) -> Unit,
    val onRetentionChange: (Int) -> Unit,
    val onClearActivityLog: () -> Unit,
    val onLimitChange: (OrchestrationLimit, Int) -> Unit,
    val onAutoResumeChange: (Boolean) -> Unit,
    val onRefreshDevice: () -> Unit,
    val onMessageShown: () -> Unit,
    val onOperatorEnabledChange: (Boolean) -> Unit = {},
    val onScreenControlEnabledChange: (Boolean) -> Unit = {},
)

/** Where the Settings section leads (supplied by the navigation shell; wave 3). */
class SettingsNavigation(
    val onOpenDailyLimits: () -> Unit = {},
    val onSaveBackup: () -> Unit = {},
    val onRestoreBackup: () -> Unit = {},
    val onOpenRoutines: () -> Unit = {},
    val onOpenPickUpRules: () -> Unit = {},
)

/**
 * The Settings section's Phase 6 parts, each owned by its package and placed by [SettingsScreen] (wave 3, WP8): null
 * shows nothing, which is what previews and the existing screenshots use.
 */
class SettingsSlots(
    /** Daily limits (WP10a). */
    val dailyLimits: (@Composable () -> Unit)? = null,
    /** Usage history: keep for, clear (WP10a). */
    val usageHistory: (@Composable () -> Unit)? = null,
    /** Questions from agents and their time-out (WP10b, Tier B). */
    val questions: (@Composable () -> Unit)? = null,
    /** Working in the background (WP10b). */
    val background: (@Composable () -> Unit)? = null,
    /** Routines and folder pick-up, the two master switches (WP10c, Tier B). */
    val routines: (@Composable () -> Unit)? = null,
    /** Backup (WP10b, Tier B). */
    val backup: (@Composable () -> Unit)? = null,
    /** Appearance: Calm (WP10b). */
    val motion: (@Composable () -> Unit)? = null,
)

/**
 * A part of Settings the section can open scrolled to (wave 3, handoff-WP10b.md 3.1): one tap from the Dashboard's
 * phone item, or from the New objective form's background warning, to the fix in Settings › Working in the
 * background; from a Routines or Folder pick-up screen whose master switch is off, to that switch.
 */
@Serializable
enum class SettingsAnchor { BACKGROUND, ROUTINES }

/** [scrollTo] opens the section scrolled to that part, once (not again when the owner comes back to it). */
@Composable
fun SettingsRoute(navigation: SettingsNavigation = SettingsNavigation(), scrollTo: SettingsAnchor? = null) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        SettingsViewModel(
            settings = graph.settings,
            activityLog = graph.activityLog,
            activity = graph.activityRecorder,
            deviceInspector = graph.deviceInspector,
            appVersion = BuildConfig.VERSION_NAME,
            directory = graph.registryDirectory,
            tools = graph.toolRegistry.all().map { it.spec },
            memoryInfo = MemoryInfoUi.of(graph.memoryConfig.shortTermTtlMs, graph.embedder.locality),
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Phase 6 (wave 3): each section's own view model, with the narrow ports it may use.
    val dailyLimits = viewModel { DailyLimitsViewModel(graph.settings, graph.todayUsage) }
    val usageHistory = viewModel { UsageHistoryViewModel(graph.settings, graph.ownerUsage) }
    val questions = viewModel { QuestionSettingsViewModel(graph.settings) }
    val background = viewModel { BackgroundSectionViewModel(graph.backgroundHealth, graph.backgroundScreens, graph.settings) }
    val motion = viewModel { MotionSettingsViewModel(graph.settings) }
    val routinesPort = graph.ownerRoutines
    val pickUpPort = graph.ownerPickUpRules
    val routines = if (routinesPort != null || pickUpPort != null) viewModel { RoutinesSettingsViewModel(graph.settings, routinesPort, pickUpPort) } else null
    val hasBackup = graph.ownerBackup != null
    val anchor = rememberAnchorScroll(scrollTo)
    val slots = SettingsSlots(
        dailyLimits = { DailyLimitsSection(dailyLimits, navigation.onOpenDailyLimits) },
        usageHistory = { UsageHistorySection(usageHistory) },
        questions = { QuestionTimeoutSection(questions) },
        background = { BackgroundSection(background, anchor.modifier(SettingsAnchor.BACKGROUND)) },
        routines = routines?.let { { RoutinesSection(it, navigation.onOpenRoutines, navigation.onOpenPickUpRules, anchor.modifier(SettingsAnchor.ROUTINES)) } },
        backup = if (hasBackup) ({ BackupSection(navigation.onSaveBackup, navigation.onRestoreBackup) }) else null,
        motion = { MotionSection(motion) },
    )
    SettingsScreen(
        state = state,
        actions = SettingsActions(
            onLocalOnlyModeChange = viewModel::setLocalOnlyMode,
            onRetentionChange = viewModel::setActivityRetentionDays,
            onClearActivityLog = viewModel::clearActivityLog,
            onLimitChange = viewModel::setLimit,
            onAutoResumeChange = viewModel::setAutoResumeInterruptedTasks,
            onRefreshDevice = viewModel::refreshDeviceReport,
            onMessageShown = viewModel::messageShown,
            onOperatorEnabledChange = viewModel::setOperatorEnabled,
            onScreenControlEnabledChange = viewModel::setScreenControlEnabled,
        ),
        slots = slots,
    )
}

/**
 * Brings [target]'s section into view the first time it has a size (a section draws nothing while it loads), once:
 * [pending] is saved with the screen, so coming back to Settings later does not scroll again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
private class AnchorScroll(private val target: SettingsAnchor?, private val scope: CoroutineScope, private val pending: MutableState<Boolean>) {
    private val requester = BringIntoViewRequester()

    fun modifier(anchor: SettingsAnchor): Modifier =
        if (anchor != target) Modifier
        else Modifier.bringIntoViewRequester(requester).onSizeChanged { size ->
            if (pending.value && size.height > 0) {
                pending.value = false
                scope.launch { requester.bringIntoView() }
            }
        }
}

@Composable
private fun rememberAnchorScroll(target: SettingsAnchor?): AnchorScroll {
    val pending = rememberSaveable(target) { mutableStateOf(target != null) }
    val scope = rememberCoroutineScope()
    return remember(target, scope) { AnchorScroll(target, scope, pending) }
}

@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, slots: SettingsSlots = SettingsSlots()) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(title = "Settings", message = state.message, onMessageShown = actions.onMessageShown) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            PrivacySection(state.settings, state.localOnlyEffect, actions, onClearRequested = { confirmClear = true })
            // Cost first among the Phase 6 parts: the owner's spending and its history (goals 1-2).
            slots.dailyLimits?.invoke()
            slots.usageHistory?.invoke()
            PhoneOperatorSection(state.settings, actions)
            slots.questions?.invoke()
            slots.background?.invoke()
            slots.routines?.invoke()
            state.memory?.let { MemorySection(it) }
            OrchestrationSection(state.settings, actions)
            slots.backup?.invoke()
            slots.motion?.invoke()
            DeviceSection(state.device, actions.onRefreshDevice)
            AboutSection(state.appVersion)
        }
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the activity log?",
            message = "Every recorded event is deleted from this device. This cannot be undone. " +
                "The log then starts again with a note that it was cleared.",
            confirmLabel = "Clear log",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmClear = false
                actions.onClearActivityLog()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrivacySection(
    settings: FrameworkSettings,
    effect: LocalOnlyEffectUi,
    actions: SettingsActions,
    onClearRequested: () -> Unit,
) {
    Column {
        SectionHeader("Privacy")
        AppCard {
            SwitchRow(
                title = "Local-only mode",
                description = "Blocks every provider that is not on this device, and the tools that send something off " +
                    "this phone or hand it to another app (listed below). Blocked providers are not even contacted for a " +
                    "status check, and their agents cannot run until you turn it off.",
                checked = settings.localOnlyMode,
                onCheckedChange = actions.onLocalOnlyModeChange,
            )
            LocalOnlyEffect(effect, settings.localOnlyMode)
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            RowText(
                title = "Keep activity history for",
                description = "Older events are deleted automatically. The log never leaves this device.",
            )
            val choices = (ActivityRetentionChoices + settings.activityRetentionDays).distinct()
            FlowRow(
                modifier = Modifier.padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                choices.forEach { days ->
                    FilterChip(
                        selected = settings.activityRetentionDays == days,
                        onClick = { actions.onRetentionChange(days) },
                        label = { Text(retentionLabel(days)) },
                    )
                }
            }
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            OutlinedButton(
                onClick = onClearRequested,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Clear activity log", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * The phone operator's two master switches. Both are off until the owner turns them on, and
 * each says exactly what turning it on allows. Screen control needs the phone operator on too.
 */
@Composable
private fun PhoneOperatorSection(settings: FrameworkSettings, actions: SettingsActions) {
    Column {
        SectionHeader("Phone operator")
        AppCard {
            SwitchRow(
                title = "Phone operator",
                description = "On: agents may use the phone tools switched on for them: folders you share, photos you allow, " +
                    "apps and links, web pages and scripts. Each needs your permission first, and deleting, installing or running " +
                    "a command asks you every time, as does each action on another app's screen unless you set that app to ask " +
                    "less in Tools › Screen control. Off: agents can still read and save files in their own workspace (a folder " +
                    "inside this app, saving asks your permission first), but cannot touch anything else on the phone.",
                checked = settings.operatorEnabled,
                onCheckedChange = actions.onOperatorEnabledChange,
            )
            RowDivider()
            SwitchRow(
                title = "Screen control",
                description = "On: agents may also read and use the screen of apps you authorise in Tools › Screen, through " +
                    "Android's accessibility service: they see what those apps show and tap and type in them, one action at a time, " +
                    "with a Stop banner showing. Settings, installers, the lock screen, password fields and this app are never " +
                    "operated. Off: no agent can see or use any screen." +
                    if (!settings.operatorEnabled) " Needs Phone operator on as well." else "",
                checked = settings.accessibilityOperatorEnabled,
                onCheckedChange = actions.onScreenControlEnabledChange,
                // Always possible to turn off; turning on only makes sense with the phone operator on.
                enabled = settings.operatorEnabled || settings.accessibilityOperatorEnabled,
            )
        }
    }
}

/** Where memories live, how they are searched and how long short-term memory lasts: facts, not switches. */
@Composable
private fun MemorySection(info: MemoryInfoUi) {
    Column {
        SectionHeader("Memory")
        AppCard {
            KeyValueRow("Stored", "Encrypted, on this phone only")
            KeyValueRow("Semantic search", if (info.searchOnDevice) "Runs offline, on this phone" else "Uses an outside service")
            KeyValueRow("Short-term memory", if (info.shortTermDays == 1) "Kept for 1 day, then deleted" else "Kept for ${info.shortTermDays} days, then deleted")
            InfoBanner(
                icon = AppIcons.OnDevice,
                text = "Memories are kept in the app's encrypted database and searched without a network. They reach a model " +
                    "only when an agent uses them in a turn: with a cloud model, recalled memories are part of what is sent, " +
                    "which the activity log records and local-only mode blocks.",
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
private fun OrchestrationSection(settings: FrameworkSettings, actions: SettingsActions) {
    Column {
        SectionHeader("Orchestration limits")
        AppCard {
            Text(
                "Loop and runaway protection for agent runs. Model calls, tool calls and the task time-out apply to every " +
                    "chat turn and task; the others apply when the Director runs objectives.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
            OrchestrationLimit.entries.forEach { limit ->
                val value = limit.valueIn(settings)
                StepperRow(
                    title = limit.title,
                    description = limit.description,
                    value = value,
                    onValueChange = { actions.onLimitChange(limit, it) },
                    range = limit.range,
                    step = limit.step,
                    valueText = limit.format(value),
                )
                RowDivider()
            }
            SwitchRow(
                title = "Resume interrupted tasks",
                description = "Re-queue tasks that were running when the app was closed, instead of leaving them paused.",
                checked = settings.autoResumeInterruptedTasks,
                onCheckedChange = actions.onAutoResumeChange,
            )
        }
    }
}

@Composable
private fun AboutSection(appVersion: String) {
    Column {
        SectionHeader("About")
        AppCard {
            KeyValueRow("App", "AI Control Center")
            KeyValueRow("Version", appVersion)
            InfoBanner(
                icon = AppIcons.OnDevice,
                title = "Everything is stored on this device",
                text = "Agents, the registry, settings and the activity log live in this app's private storage. " +
                    "This version has no accounts, no analytics and no connection to any service.",
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/** What the switch does with the registry as it is now, so its effect is never a guess. */
@Composable
private fun LocalOnlyEffect(effect: LocalOnlyEffectUi, on: Boolean) {
    val verb = if (on) "Blocked now" else "Would be blocked"
    val lines = buildList {
        add(
            if (effect.providers.isEmpty()) {
                "Providers: none. Every enabled provider is on this device."
            } else {
                "$verb: ${effect.providers.joinToString(", ")}."
            },
        )
        if (effect.agents.isNotEmpty()) {
            add((if (on) "Cannot run: " else "Could not run: ") + effect.agents.joinToString(", ") + ".")
        }
        add(
            if (effect.tools.isEmpty()) {
                "Tools: none of the registered tools send anything off this device or hand it to another app."
            } else {
                "Tools ${if (on) "blocked" else "that would be blocked"}: ${effect.tools.joinToString(", ")}."
            },
        )
    }
    InfoBanner(
        text = lines.joinToString("\n"),
        tone = if (on && effect.providers.isNotEmpty()) StatusTone.ATTENTION else null,
        modifier = Modifier.padding(bottom = Spacing.sm),
    )
}
