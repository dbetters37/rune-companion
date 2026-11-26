package com.aicontrolcenter.app.ui.routines

import android.content.ActivityNotFoundException
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.routines.AndroidExactAlarmAccess
import com.aicontrolcenter.app.routines.ExactAlarmState
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.OpenInNew
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId

/** Where the Routines screens lead (supplied by the navigation shell). */
class RoutinesNavigation(
    val onBack: () -> Unit,
    val onNewRoutine: () -> Unit = {},
    val onOpenRoutine: (RoutineId) -> Unit = {},
    /** The runs waiting for the owner's tap ("Tap to start"). */
    val onOpenWaiting: () -> Unit = {},
    val onOpenObjective: (TaskId) -> Unit = {},
    /** Settings, where the master switch is. */
    val onOpenSettings: () -> Unit = {},
)

/** What the Routines list can do besides navigating. */
class RoutinesActions(
    /** Opens Android's "Alarms and reminders" page for this app. */
    val onAllowExactAlarms: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/**
 * The owner's routines (Q1, Appendix A, Tier B; WP10c): each with its schedule, next run, pause note and failures in a
 * row; the master switch's state (Settings › Routines and folder pick-up); a link to the runs waiting for a tap; that
 * Android may delay routines, and that "Alarms and reminders" makes them start on time (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`
 * on Android 12 and later, from this screen, never from a notification). Hidden when the build has no routines.
 */
@Composable
fun RoutinesRoute(navigation: RoutinesNavigation) {
    val graph = LocalAppGraph.current
    val routines = graph.ownerRoutines
    if (routines == null) {
        NotInThisVersion("Routines", navigation.onBack)
        return
    }
    val context = LocalContext.current
    // The application's context: the view model outlives this activity (rotation), and must not keep it.
    val access = remember(context) { AndroidExactAlarmAccess(context.applicationContext) }
    val work = LocalScreenWork.current
    val viewModel = viewModel { RoutinesViewModel(routines, graph.waitingRoutines, graph.settingsView, graph.clock, graph.zone, access, work) }
    // Coming back from Android's "Alarms and reminders" page: read it again.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshExactAlarms()
        onPauseOrDispose { }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RoutinesScreen(
        state = state,
        navigation = navigation,
        actions = RoutinesActions(
            onAllowExactAlarms = {
                val intent = access.settingsIntent()
                try {
                    if (intent != null) context.startActivity(intent) else viewModel.couldNotOpenAlarmSettings()
                } catch (_: ActivityNotFoundException) {
                    viewModel.couldNotOpenAlarmSettings()
                } catch (_: SecurityException) {
                    viewModel.couldNotOpenAlarmSettings()
                }
            },
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun RoutinesScreen(state: RoutinesUiState, navigation: RoutinesNavigation, actions: RoutinesActions = RoutinesActions()) {
    ScreenScaffold(title = "Routines", onBack = navigation.onBack, message = state.message, onMessageShown = actions.onMessageShown) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "intro") {
                Text(
                    "A routine gives your team an objective at set times, for example every morning at 07:00. It then starts " +
                        "by itself, also while you are not using the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.masterOn) {
                item(key = "master") { MasterOffNotice(navigation.onOpenSettings) }
            }
            state.waiting?.let { waiting ->
                item(key = "waiting") { WaitingNotice(waiting, navigation.onOpenWaiting) }
            }
            item(key = "header") { ListHeader(state, navigation.onNewRoutine) }
            if (state.routines.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = AppIcons.Schedule,
                        title = "No routines yet",
                        message = "For example: every weekday at 07:30, \"Summarise the news about electric cars in five lines.\"",
                    )
                }
            } else {
                items(state.routines, key = { it.id.value }) { row -> RoutineCard(row, onClick = { navigation.onOpenRoutine(row.id) }) }
            }
            item(key = "how") { HowRoutinesStart(state.exactAlarms, actions.onAllowExactAlarms) }
        }
    }
}

@Composable
private fun MasterOffNotice(onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        InfoBanner(
            title = "Routines are switched off",
            text = "No routine starts while routines are off. You switch them on in Settings, under Routines and folder pick-up.",
            tone = StatusTone.ATTENTION,
        )
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Settings") }
    }
}

@Composable
private fun WaitingNotice(text: String, onOpenWaiting: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        InfoBanner(title = "Waiting to start", text = "$text Nothing starts until you do.", tone = StatusTone.WAITING)
        Button(onClick = onOpenWaiting, modifier = Modifier.heightIn(min = 48.dp)) { Text("See what is waiting") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListHeader(state: RoutinesUiState, onNewRoutine: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionHeader("Your routines")
                Text(state.count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onNewRoutine, enabled = state.canAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("New routine", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (!state.canAdd) {
            Text(
                "You have the most routines there can be. Delete one to make another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One routine: the whole card opens it, and is read as one item. */
@Composable
internal fun RoutineCard(row: RoutineRowUi, onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        borderColor = if (row.pausedNote != null) appColors.status(StatusTone.ATTENTION).copy(alpha = 0.5f) else appColors.hairline,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(row.status, row.tone)
            }
            Text(row.schedule, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            row.next?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            row.pausedNote?.let {
                Text(
                    "$it Open it to see its recent runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = appColors.status(StatusTone.ATTENTION),
                )
            }
            row.failures?.let { WarningLine(it) }
            Text(row.screens, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A short warning with its icon, so it does not rest on colour. */
@Composable
internal fun WarningLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = appColors.status(StatusTone.ATTENTION), modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = appColors.status(StatusTone.ATTENTION))
    }
}

/** How routines start: "Alarms and reminders", late runs, safe start, the limits, the clock changes. */
@Composable
private fun HowRoutinesStart(exact: ExactAlarmState, onAllowExactAlarms: () -> Unit) {
    Column {
        SectionHeader("How routines start")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (exact) {
                    ExactAlarmState.NOT_ALLOWED -> {
                        InfoBanner(
                            title = "Routines may start late",
                            text = EXACT_NOT_ALLOWED,
                            tone = StatusTone.ATTENTION,
                        )
                        Button(onClick = onAllowExactAlarms, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Allow alarms and reminders", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    ExactAlarmState.ALLOWED -> InfoBanner(title = "Alarms and reminders: allowed", text = EXACT_ALLOWED, tone = StatusTone.READY)
                    ExactAlarmState.ALWAYS -> InfoBanner(text = EXACT_ALWAYS, tone = StatusTone.READY)
                }
                RowDivider(Modifier.padding(vertical = Spacing.xs))
                Fact(LATE)
                Fact(SAFE_START)
                Fact(RoutineWords.LIMITS)
                Fact(RoutineWords.CLOCK_CHANGES)
            }
        }
    }
}

@Composable
private fun Fact(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A screen of this package that the build does not include (its port is null): its entry points are hidden too. */
@Composable
internal fun NotInThisVersion(title: String, onBack: () -> Unit) {
    ScreenScaffold(title = title, onBack = onBack) { padding ->
        EmptyState(
            icon = AppIcons.Schedule,
            message = "This is not part of this version of the app.",
            modifier = Modifier.padding(padding).semantics { contentDescription = "$title is not part of this version of the app." },
        )
    }
}

/** "Alarms and reminders" is not allowed (Android 12 and later). */
internal const val EXACT_NOT_ALLOWED: String =
    "Android lets the app start a routine on time only when \"Alarms and reminders\" is allowed for it. Without it, Android " +
        "may hold a routine back, and nothing starts by itself: when its time comes you get a \"Tap to start\" notification, " +
        "and the routine starts only after you open the app and tap Start."

/** "Alarms and reminders" is allowed. */
internal const val EXACT_ALLOWED: String =
    "Routines start at their time, also while the phone sleeps. You can take this away again on Android's own page for the app."

/** Android 9 to 11: alarms are always exact. */
internal const val EXACT_ALWAYS: String = "On this phone Android lets routines start at their time, also while the phone sleeps."

/** Late runs (WP17: more than 30 minutes late waits for a tap; missed after 3 hours). */
internal const val LATE: String =
    "Android may still hold a routine back, for example to save battery or after the phone was off. A run that would start " +
        "more than 30 minutes late does not start by itself: it waits for you to tap Start, and after 3 hours it counts as missed."

/** Safe start (rule 10; WP8's decision: a routine due on time waits for the tap then). */
internal const val SAFE_START: String =
    "When the app has stopped unexpectedly several times in a row (safe start), routines wait for your tap too, until you " +
        "choose \"Back to normal\" on the Dashboard."
