package com.aicontrolcenter.app.ui.routines

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.NumericStepper
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.RowText
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.atMostChars
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.routines.RoutineLimits
import java.time.DayOfWeek

/** What the routine editor can do. */
class RoutineEditorActions(
    val onClose: () -> Unit,
    val onKind: (ScheduleKind) -> Unit = {},
    val onHour: (Int) -> Unit = {},
    val onMinute: (Int) -> Unit = {},
    val onEveryHours: (Int) -> Unit = {},
    val onToggleDay: (DayOfWeek) -> Unit = {},
    val onDays: (Set<DayOfWeek>) -> Unit = {},
    val onReviewResults: (Boolean) -> Unit = {},
    val onScreenActions: (Boolean) -> Unit = {},
    val onConfirmScreenActions: () -> Unit = {},
    val onCancelScreenActions: () -> Unit = {},
    val onApprovalTimeout: (Int?) -> Unit = {},
    val onSave: () -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onOpenObjective: (TaskId) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/** A new routine ([routineId] null) or one to change (WP10c); [onSaved] gets the saved routine's id. */
@Composable
fun RoutineEditorRoute(
    routineId: RoutineId?,
    onClose: () -> Unit,
    onSaved: (RoutineId) -> Unit,
    onOpenObjective: (TaskId) -> Unit,
) {
    val graph = LocalAppGraph.current
    val routines = graph.ownerRoutines
    if (routines == null) {
        NotInThisVersion(if (routineId == null) "New routine" else "Routine", onClose)
        return
    }
    val work = LocalScreenWork.current
    val viewModel = viewModel(key = "routine-${routineId?.value}") { RoutineEditorViewModel(routineId, routines, graph.clock, graph.zone, work) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        state.saved?.let {
            viewModel.savedHandled()
            onSaved(it)
        }
    }
    LaunchedEffect(state.deleted) { if (state.deleted) onClose() }
    RoutineEditorScreen(
        state = state,
        title = viewModel.title,
        objective = viewModel.objective,
        actions = RoutineEditorActions(
            onClose = onClose,
            onKind = viewModel::setKind,
            onHour = viewModel::setHour,
            onMinute = viewModel::setMinute,
            onEveryHours = viewModel::setEveryHours,
            onToggleDay = viewModel::toggleDay,
            onDays = viewModel::setDays,
            onReviewResults = viewModel::setReviewResults,
            onScreenActions = viewModel::requestScreenActions,
            onConfirmScreenActions = viewModel::confirmScreenActions,
            onCancelScreenActions = viewModel::cancelScreenActions,
            onApprovalTimeout = viewModel::setApprovalTimeout,
            onSave = viewModel::save,
            onEnabled = viewModel::setEnabled,
            onDelete = viewModel::askDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
            onOpenObjective = onOpenObjective,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun RoutineEditorScreen(state: RoutineEditorUiState, title: TextFieldState, objective: TextFieldState, actions: RoutineEditorActions) {
    ScreenScaffold(
        title = if (state.isNew) "New routine" else "Routine",
        onBack = actions.onClose,
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.missing -> EmptyState(
                icon = AppIcons.Schedule,
                title = "This routine is gone",
                message = "It was deleted. Its objectives are kept in Tasks.",
                modifier = Modifier.padding(padding),
                actionLabel = "Back",
                onAction = actions.onClose,
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .imePadding()
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (!state.isNew) OnOffCard(state, actions)
                state.problem?.let { InfoBanner(title = "Not saved", text = it, tone = StatusTone.UNAVAILABLE) }
                WhatSection(state, title, objective)
                WhenSection(state, actions)
                WhileItRunsSection(state, actions)
                SaveButton(state, actions.onSave)
                if (!state.isNew) {
                    HistorySection(state, actions.onOpenObjective)
                    OutlinedButton(
                        onClick = actions.onDelete,
                        enabled = !state.isSaving,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("Delete routine", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
    if (state.confirmScreens) {
        ConfirmDialog(
            title = "Let this routine use other apps' screens?",
            message = SCREENS_CONFIRM,
            confirmLabel = "Allow screens",
            onConfirm = actions.onConfirmScreenActions,
            onDismiss = actions.onCancelScreenActions,
            cautionary = true,
            widens = true,
            guarded = true,
        )
    }
    if (state.confirmDelete) {
        ConfirmDialog(
            title = "Delete this routine?",
            message = "It stops for good and its run history is deleted. The objectives it started are kept in Tasks.",
            confirmLabel = "Delete routine",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

/** An existing routine: on or off, its next time, failures in a row, and its pause note with "Switch it on again". */
@Composable
private fun OnOffCard(state: RoutineEditorUiState, actions: RoutineEditorActions) {
    AppCard(contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        if (state.pausedNote != null) {
            InfoBanner(title = "It switched itself off", text = state.pausedNote, tone = StatusTone.ATTENTION, modifier = Modifier.padding(vertical = Spacing.sm))
            Button(onClick = { actions.onEnabled(true) }, enabled = !state.isSaving, modifier = Modifier.padding(bottom = Spacing.sm).heightIn(min = 48.dp)) {
                Text("Switch it on again")
            }
        } else {
            SwitchRow(
                title = "Switched on",
                description = if (state.enabled) {
                    state.next ?: "It runs at its next time while routines are on in Settings."
                } else {
                    "Off: it does not run until you switch it on again."
                },
                checked = state.enabled,
                onCheckedChange = actions.onEnabled,
                enabled = !state.isSaving,
            )
            state.failures?.let { WarningLine(it, Modifier.padding(bottom = Spacing.sm)) }
        }
    }
}

@Composable
private fun WhatSection(state: RoutineEditorUiState, title: TextFieldState, objective: TextFieldState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("What it does")
        AppTextField(
            state = title,
            label = TITLE_LABEL,
            placeholder = "For example: Morning news",
            supportingText = "${formatCount(title.text.length.toLong())} / ${RoutineLimits.MAX_TITLE_CHARS}",
            errorText = state.titleError,
            inputTransformation = atMostChars(RoutineLimits.MAX_TITLE_CHARS),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        AppTextField(
            state = objective,
            label = OBJECTIVE_LABEL,
            placeholder = "For example: Summarise today's news about electric cars in five lines.",
            supportingText = "Write it as you would write a new objective. " +
                "${formatCount(objective.text.length.toLong())} / ${formatCount(RoutineLimits.MAX_OBJECTIVE_CHARS.toLong())}",
            errorText = state.objectiveError,
            singleLine = false,
            minLines = 4,
            maxLines = 10,
            inputTransformation = atMostChars(RoutineLimits.MAX_OBJECTIVE_CHARS),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Text(
            "Who works on it: your Director plans each run and hands parts to the agents of your team, as with an objective " +
                "you start yourself.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenSection(state: RoutineEditorUiState, actions: RoutineEditorActions) {
    Column {
        SectionHeader("When")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    ChoiceChip("Once a day", state.kind == ScheduleKind.DAILY) { actions.onKind(ScheduleKind.DAILY) }
                    ChoiceChip("Every few hours", state.kind == ScheduleKind.EVERY_HOURS) { actions.onKind(ScheduleKind.EVERY_HOURS) }
                }
                if (state.kind == ScheduleKind.EVERY_HOURS) {
                    // The label above its buttons, as the time below: "every 12 hours" is wide at large font sizes.
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        RowText("How often", "Runs start again each day from the first time.")
                        NumericStepper(
                            value = state.everyHours,
                            onValueChange = actions.onEveryHours,
                            range = RoutineLimits.MIN_HOURS_APART..RoutineLimits.MAX_HOURS_APART,
                            name = "How often",
                            valueText = if (state.everyHours == 1) "every hour" else "every ${state.everyHours} hours",
                        )
                    }
                }
                TimeRow(if (state.kind == ScheduleKind.DAILY) "At" else "First time each day", state.minuteOfDay, actions)
                RowDivider()
                RowText("On these days", null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    for (day in DayOfWeek.entries) {
                        val name = RoutineWords.dayName(day)
                        FilterChip(
                            selected = day in state.days,
                            onClick = { actions.onToggleDay(day) },
                            label = { Text(RoutineWords.shortDayName(day)) },
                            leadingIcon = checkIf(day in state.days),
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = name },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TextButton(onClick = { actions.onDays(DayOfWeek.entries.toSet()) }) { Text("Every day") }
                    TextButton(onClick = { actions.onDays(WEEKDAYS) }) { Text("Weekdays") }
                    TextButton(onClick = { actions.onDays(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) }) { Text("Weekends") }
                }
                state.daysError?.let { WarningLine(it) }
                RowDivider()
                Text(state.scheduleText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                state.timesText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                state.preview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.clockChange?.let { InfoBanner(title = "When the clocks change", text = it, icon = AppIcons.Schedule) }
                Text(RoutineWords.CLOCK_CHANGES, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The time as an hour and minutes, each with its own buttons (TalkBack: "Hour, 07"; "Increase Hour"), under its label so
 * neither is squeezed at large font sizes or on a narrow phone.
 */
@Composable
private fun TimeRow(title: String, minuteOfDay: Int, actions: RoutineEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            NumericStepper(value = minuteOfDay / 60, onValueChange = actions.onHour, range = 0..23, name = "Hour", valueText = "%02d".format(minuteOfDay / 60))
            Text(":", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            NumericStepper(
                value = minuteOfDay % 60,
                onValueChange = actions.onMinute,
                range = 0..55,
                step = 5,
                name = "Minutes",
                valueText = "%02d".format(minuteOfDay % 60),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhileItRunsSection(state: RoutineEditorUiState, actions: RoutineEditorActions) {
    Column {
        SectionHeader("While it runs")
        AppCard(contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)) {
            SwitchRow(
                title = "Have results reviewed",
                description = "Another agent checks important results against their completion criteria before they are used. " +
                    "Takes longer and uses more model calls.",
                checked = state.reviewResults,
                onCheckedChange = actions.onReviewResults,
            )
            RowDivider()
            SwitchRow(
                title = "May use other apps' screens",
                description = if (state.allowScreenActions) SCREENS_ON else SCREENS_OFF,
                checked = state.allowScreenActions,
                onCheckedChange = actions.onScreenActions,
            )
            if (state.allowScreenActions) WarningLine(SCREENS_WARNING, Modifier.padding(bottom = Spacing.sm))
            RowDivider()
            RowText(
                "How long its requests wait for you",
                "It may run while you are away. A request it makes waits this long for your answer; after that it is refused " +
                    "and the team goes on without it.",
                Modifier.padding(top = Spacing.sm),
            )
            FlowRow(
                modifier = Modifier.padding(vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                for (minutes in state.timeoutChoices) {
                    ChoiceChip(RoutineEditorViewModel.timeoutLabel(minutes), state.approvalTimeoutMinutes == minutes) { actions.onApprovalTimeout(minutes) }
                }
            }
            RowDivider()
            Text(
                "Every permission and every action that is hard to undo is still asked for, and today's limits apply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        }
    }
}

/** A chip of a group where one is chosen: read as a radio button. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = checkIf(selected),
        modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
    )
}

/** A check mark on a chosen chip: the choice is never shown by colour alone. */
private fun checkIf(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) ({ Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }) else null

@Composable
private fun SaveButton(state: RoutineEditorUiState, onSave: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (state.isNew) {
            Text(
                "It is switched on when you save it, and runs only while routines are on in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onSave, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (state.isSaving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(if (state.isNew) "Save routine" else "Save changes")
            }
        }
    }
}

/** The newest runs, each outcome in words with its icon, a started one with a link to its objective. */
@Composable
private fun HistorySection(state: RoutineEditorUiState, onOpenObjective: (TaskId) -> Unit) {
    Column {
        SectionHeader("Recent runs")
        AppCard {
            if (state.runs.isEmpty()) {
                Text("No runs yet. Each time it is due, what happened shows here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.runs.forEachIndexed { index, run ->
                if (index > 0) RowDivider(Modifier.padding(vertical = Spacing.sm))
                RunRow(run, onOpenObjective)
            }
        }
    }
}

@Composable
private fun RunRow(run: RoutineRunUi, onOpenObjective: (TaskId) -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(run.tone.icon, contentDescription = null, tint = appColors.status(run.tone), modifier = Modifier.padding(top = 2.dp).size(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(run.outcome, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(run.due, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                run.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        run.objectiveId?.let { id ->
            TextButton(onClick = { onOpenObjective(id) }, modifier = Modifier.padding(start = 22.dp)) { Text("Open the objective") }
        }
    }
}

/** The labels of the two text fields (tests type into them by these). */
const val TITLE_LABEL: String = "Name"
const val OBJECTIVE_LABEL: String = "What the team should do"

private val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

internal const val SCREENS_OFF: String =
    "Off: during this routine the team cannot read or tap other apps' screens, even when screen control is on."

internal const val SCREENS_ON: String =
    "On: during this routine agents may read and tap the screens of apps you authorised, also while you may not be looking."

internal const val SCREENS_WARNING: String =
    "Screen actions work only while the phone's screen is on and unlocked, and every tap that is hard to undo still asks you."

internal const val SCREENS_CONFIRM: String =
    "While it runs, agents may then read and tap other apps' screens, also while you may not be looking. Each app still needs " +
        "your permission, every tap that is hard to undo still asks you, and screen actions work only while the screen is on " +
        "and unlocked."
