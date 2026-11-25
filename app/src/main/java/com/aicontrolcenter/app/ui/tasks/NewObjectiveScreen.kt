package com.aicontrolcenter.app.ui.tasks

import com.aicontrolcenter.app.Phase6Stub
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Cloud
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.TaskId

class NewObjectiveActions(
    val onBack: () -> Unit,
    val onReviewResultsChange: (Boolean) -> Unit,
    val onSaveKnowledgeChange: (Boolean) -> Unit,
    val onStart: () -> Unit,
    val onNotificationsExplained: () -> Unit,
    val onNotificationsAnswered: () -> Unit,
    val onMessageShown: () -> Unit,
    /** Asks Android for the notification permission; the answer goes to [onNotificationsAnswered]. */
    val onRequestPermission: () -> Unit,
)

/**
 * Wave 3 (WP11a): the daily-limit line ("Today: 37 of 200 answers", `graph.todayUsage`) links to [onOpenDailyLimits];
 * the background warning (A3) to [onOpenSettings] (Settings > Working in the background).
 */
@Phase6Stub("WP11a")
@Composable
fun NewObjectiveRoute(
    onBack: () -> Unit,
    onStarted: (TaskId) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenDailyLimits: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenSettings: () -> Unit = {},
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        NewObjectiveViewModel(graph.orchestrator, graph.registryDirectory, graph.memoryService, graph.notifications, awaitStartup = graph::awaitStartup)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.startedId) {
        state.startedId?.let {
            viewModel.startHandled()
            onStarted(it)
        }
    }
    val permission = graph.notifications.permission
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.notificationsAnswered() }
    NewObjectiveScreen(
        state,
        viewModel.objective,
        NewObjectiveActions(
            onBack = onBack,
            onReviewResultsChange = viewModel::setReviewResults,
            onSaveKnowledgeChange = viewModel::setSaveKnowledge,
            onStart = viewModel::start,
            onNotificationsExplained = viewModel::notificationsExplained,
            onNotificationsAnswered = viewModel::notificationsAnswered,
            onMessageShown = viewModel::messageShown,
            onRequestPermission = { if (permission != null) launcher.launch(permission) else viewModel.notificationsAnswered() },
        ),
    )
}

/**
 * Writing an objective for the Director, with its options and an honest note of where the work
 * goes. [objective] holds what is typed; the field, its counter and its error all read it.
 */
@Composable
fun NewObjectiveScreen(state: NewObjectiveUiState, objective: TextFieldState, actions: NewObjectiveActions) {
    ScreenScaffold(title = "New objective", onBack = actions.onBack, message = state.message, onMessageShown = actions.onMessageShown) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            DirectorLine(state.director)
            ObjectiveField(state, objective)
            Column {
                SectionHeader("Options")
                AppCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs)) {
                    SwitchRow(
                        title = "Have results reviewed",
                        description = "Another agent checks important results against their completion criteria before they are used, " +
                            "and the Director settles disagreements. Takes longer and uses more model calls.",
                        checked = state.reviewResults,
                        onCheckedChange = actions.onReviewResultsChange,
                    )
                    RowDivider()
                    SwitchRow(
                        title = "Save what was learned to memory",
                        description = state.knowledgeSpace?.let {
                            "The Director saves a summary of the result to the shared space “$it”, and each agent keeps what it learned on its tasks."
                        } ?: "Not available: the Director may not write to any shared space. Allow it in the Director's memory settings to use this.",
                        checked = state.saveKnowledge,
                        onCheckedChange = actions.onSaveKnowledgeChange,
                        enabled = state.knowledgeSpace != null,
                    )
                }
            }
            WhereTheWorkGoes(state.team)
            StartButton(state, actions.onStart)
        }
    }
    if (state.explainNotifications) {
        ConfirmDialog(
            title = "Show progress in a notification?",
            message = "While the team works, Android shows an ongoing notification with the objective's progress and a Pause " +
                "button, so the work can go on when you leave the app. Android asks for permission next. If you say no, the " +
                "work still runs and you can follow it here.",
            confirmLabel = "Continue",
            dismissLabel = "Not now",
            icon = AppIcons.Info,
            onConfirm = {
                actions.onNotificationsExplained()
                actions.onRequestPermission()
            },
            onDismiss = actions.onNotificationsAnswered,
        )
    }
}

@Composable
private fun DirectorLine(director: DirectorUi?) {
    if (director == null) {
        InfoBanner(
            title = "No Director",
            text = "Objectives go to the designated Director. Open Agents, choose an agent with the Director role and designate it.",
            tone = StatusTone.UNAVAILABLE,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        AgentAvatar(director.name, director.colorIndex, size = 40.dp, status = if (director.canRun) StatusTone.READY else StatusTone.UNAVAILABLE)
        Column(Modifier.weight(1f)) {
            Text(director.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Your Director. It plans the work, hands tasks to the team and writes the final result.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    director.problem?.let { InfoBanner(title = "The Director cannot start", text = it, tone = StatusTone.UNAVAILABLE) }
}

/** The objective, with an example as its hint (visible until the owner types), and a counter. */
@Composable
private fun ObjectiveField(state: NewObjectiveUiState, objective: TextFieldState) {
    val error = state.textError(objective.text)
    Column {
        SectionHeader("Your objective")
        OutlinedTextField(
            state = objective,
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).semantics { contentDescription = OBJECTIVE_FIELD },
            placeholder = {
                Text(
                    "For example: Research note-taking apps that work offline, compare the three best, and write a one-page " +
                        "recommendation with a plan to switch.",
                )
            },
            isError = error != null,
            supportingText = {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(error ?: "Say what you want and how the result should look.", modifier = Modifier.weight(1f))
                    Text("${formatCount(objective.text.length.toLong())} / ${formatCount(NewObjectiveViewModel.MAX_LENGTH.toLong())}")
                }
            },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 5, maxHeightInLines = 12),
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

/** What the objective field is called for screen readers (and tests). */
const val OBJECTIVE_FIELD = "Objective"

/** Which models the team uses and whether any of them is off this phone: said before anything is sent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhereTheWorkGoes(team: TeamDestinationsUi) {
    Column {
        SectionHeader("Where the work goes")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                val (tone, title, text) = when {
                    team.models.isEmpty() -> Triple(StatusTone.UNAVAILABLE, "No agent can run", "Give the team a model first: no agent has one it can use right now.")
                    team.localOnly -> Triple(
                        StatusTone.READY,
                        "Stays on this phone",
                        "Local-only mode is on: only models on this phone are used, and the tools that send something off this phone " +
                            "or hand it to another app are blocked.",
                    )
                    team.leavesDevice -> Triple(
                        StatusTone.ATTENTION,
                        "Some of it leaves this phone",
                        "The objective, results and messages between agents are sent to the models below as the team works. " +
                            "Every model call, and where it went, is in the activity log.",
                    )
                    else -> Triple(StatusTone.READY, "Stays on this phone", "Every model the team uses runs on this phone.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Icon(
                        if (team.leavesDevice && !team.localOnly) AppIcons.Cloud else AppIcons.OnDevice,
                        contentDescription = null,
                        tint = appColors.status(tone),
                        modifier = Modifier.size(22.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                team.models.forEach { model ->
                    RowDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text("${model.modelName} · ${model.providerName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            TintedChip(model.locality.label, appColors.locality(model.locality), icon = model.locality.icon)
                            if (model.isSimulator) TintedChip(SIMULATOR_LABEL, appColors.attention)
                        }
                        Text("Used by ${model.agents.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (team.agentsThatCannotRun.isNotEmpty()) {
                    RowDivider()
                    Text(
                        "Not taking part (cannot run now): ${team.agentsThatCannotRun.joinToString(", ")}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartButton(state: NewObjectiveUiState, onStart: () -> Unit) {
    Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (state.isStarting) {
            CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Icon(AppIcons.Play, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        }
        Text(if (state.isStarting) "Starting…" else "Start objective", modifier = Modifier.padding(start = Spacing.sm))
    }
}
