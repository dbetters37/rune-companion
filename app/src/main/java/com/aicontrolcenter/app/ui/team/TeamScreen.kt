package com.aicontrolcenter.app.ui.team

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Help
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import kotlinx.coroutines.flow.flowOf

/** Where the Team view leads (supplied by the navigation shell). */
class TeamNavigation(
    val onBack: () -> Unit,
    val onOpenAgent: (AgentId) -> Unit = {},
    val onOpenTask: (TaskId) -> Unit = {},
    val onOpenObjective: (TaskId) -> Unit = {},
    val onOpenRequest: (RequestId) -> Unit = {},
    val onOpenQuestion: (RequestId) -> Unit = {},
    val onOpenSpace: (SpaceId) -> Unit = {},
)

/** The two ways to see the team. */
enum class TeamMode { PICTURE, LIST }

/** What the Team view's controls do. */
class TeamActions(
    val navigation: TeamNavigation,
    val onShow: (TeamFilter) -> Unit = {},
    val onOpenNode: (String) -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onCloseSheet: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/**
 * The Team view (goal 9; WP9): "You" at the top, the Director in the centre, agents on a ring, spaces along the bottom;
 * who works, who waits for whom, who waits for you; Picture and List (list first with TalkBack or large fonts); Calm and
 * `areAnimatorsEnabled()` switch motion off (rule 17). [objectiveId] opens it on one objective (from its header); null
 * shows the whole team (from the Dashboard).
 */
@Composable
fun TeamRoute(objectiveId: TaskId?, navigation: TeamNavigation) {
    val graph = LocalAppGraph.current
    val work = LocalScreenWork.current
    val viewModel = viewModel(key = "team-${objectiveId?.value}") {
        TeamViewModel(
            objectiveId = objectiveId,
            tasks = graph.tasks,
            messages = graph.messages,
            directory = graph.registryDirectory,
            pendingRequests = graph.ownerRequests.pending,
            monitor = graph.activityMonitor,
            memory = graph.memoryService,
            clock = graph.clock,
            zone = graph.zone,
            routines = graph.ownerRoutines?.observeAll() ?: flowOf(emptyList()),
            capabilityInfo = graph.capabilities::info,
            computation = work,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val live by viewModel.liveSummary.collectAsStateWithLifecycle()
    TeamScreen(
        state = state,
        liveSummary = live,
        actions = TeamActions(
            navigation = navigation,
            onShow = viewModel::show,
            onOpenNode = viewModel::openNode,
            onOpenLink = viewModel::openLink,
            onCloseSheet = viewModel::closeSheet,
            onMessageShown = viewModel::messageShown,
        ),
        screenReaderOn = rememberScreenReaderOn(),
    )
}

/** TalkBack (or another screen reader that explores by touch) is on. */
@Composable
private fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    }
}

/** Width from which the controls sit beside the picture instead of above it. */
private val WideLayout = 700.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(state: TeamUiState, liveSummary: String, actions: TeamActions, screenReaderOn: Boolean = false) {
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val tooBig = state.graph.agentsLeftOut > 0
    var chosen by rememberSaveable { mutableStateOf<TeamMode?>(null) }
    val mode = chosen ?: if (screenReaderOn || largeText || tooBig) TeamMode.LIST else TeamMode.PICTURE
    var legendOpen by rememberSaveable { mutableStateOf(false) }
    val navigation = actions.navigation
    val picture = PictureActions(
        onOpenNode = actions.onOpenNode,
        onOpenLink = actions.onOpenLink,
        onOpenRequest = { id, question -> if (question) navigation.onOpenQuestion(id) else navigation.onOpenRequest(id) },
    )
    ScreenScaffold(
        title = TeamWords.TITLE,
        onBack = navigation.onBack,
        actions = {
            IconButton(onClick = { legendOpen = true }) { Icon(AppIcons.Help, contentDescription = "What the picture shows") }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= WideLayout
            val headerMax = maxHeight * 0.45f
            val controls: @Composable () -> Unit = {
                Controls(state, liveSummary, mode, { chosen = it }, actions, showHint = mode == TeamMode.PICTURE && (screenReaderOn || largeText), tooBig = tooBig)
            }
            when {
                mode == TeamMode.LIST -> LazyColumn(
                    modifier = Modifier.fillMaxSize().readableWidth().testTag(TEAM_LIST_TAG),
                    contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                ) {
                    item(key = "controls") { controls() }
                    teamListItems(state.graph, picture)
                }
                wide -> Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = Spacing.screen, end = Spacing.sm, bottom = Spacing.lg),
                    ) { controls() }
                    TeamPicture(state.graph, state.filter, picture, Modifier.weight(1f).fillMaxHeight())
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = headerMax).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen),
                    ) { controls() }
                    TeamPicture(state.graph, state.filter, picture, Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
    state.sheet?.let { sheet ->
        TeamSheet(
            sheet,
            SheetActions(
                onClose = actions.onCloseSheet,
                onOpenLink = actions.onOpenLink,
                onOpenAgent = navigation.onOpenAgent,
                onOpenTask = navigation.onOpenTask,
                onOpenObjective = navigation.onOpenObjective,
                onOpenRequest = picture.onOpenRequest,
                onOpenSpace = navigation.onOpenSpace,
            ),
        )
    }
    if (legendOpen) {
        ModalBottomSheet(
            onDismissRequest = { legendOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) { Legend() }
    }
}

/** Above (or beside) the picture: which objectives, the spoken summary, why something stopped, Picture or List. */
@Composable
private fun Controls(
    state: TeamUiState,
    liveSummary: String,
    mode: TeamMode,
    onMode: (TeamMode) -> Unit,
    actions: TeamActions,
    showHint: Boolean,
    tooBig: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.sm)) {
        ObjectiveChoices(state, actions.onShow)
        Text(
            text = liveSummary.ifEmpty { state.summary },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        state.card?.let { ObjectiveCard(it, actions.navigation.onOpenObjective) }
        state.notices.forEach { notice ->
            AppCard(onClick = { actions.onShow(TeamFilter.One(notice.objectiveId)) }, contentPadding = PaddingValues(Spacing.md)) {
                StatusChip(notice.title, notice.tone)
                Text(notice.objectiveTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(notice.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.emptyNote?.let { InfoBanner(it) }
        if (state.graph.agentsNotInObjective > 0) {
            Text(TeamWords.notInObjective(state.graph.agentsNotInObjective) + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tooBig) InfoBanner(TeamWords.TOO_MANY)
        TeamWords.leftOut(0, state.graph.spacesLeftOut)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            TeamMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = { onMode(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, TeamMode.entries.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (option == TeamMode.PICTURE) TeamWords.PICTURE else TeamWords.LIST) }
            }
        }
        if (showHint) {
            InfoBanner(TeamWords.LIST_IS_EASIER)
            TextButton(onClick = { onMode(TeamMode.LIST) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(TeamWords.SHOW_LIST) }
        }
        if (mode == TeamMode.PICTURE) {
            Text(TeamWords.TAP_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "All current" and every objective, as chips that scroll sideways. */
@Composable
private fun ObjectiveChoices(state: TeamUiState, onShow: (TeamFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        item(key = "all") {
            FilterChip(
                selected = state.filter == TeamFilter.AllCurrent,
                onClick = { onShow(TeamFilter.AllCurrent) },
                label = { Text("${TeamWords.ALL_CURRENT} (${state.currentCount})") },
            )
        }
        items(state.objectives, key = { it.id.value }) { chip ->
            FilterChip(
                selected = state.filter == TeamFilter.One(chip.id),
                onClick = { onShow(TeamFilter.One(chip.id)) },
                label = { Text(chip.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthCap()) },
                leadingIcon = { Icon(chip.status.tone.icon, contentDescription = chip.status.text, tint = appColors.status(chip.status.tone), modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** A long objective title is cut in its chip; the card and the objective's page show all of it. */
private fun Modifier.widthCap(): Modifier = this.then(Modifier.widthIn(max = 180.dp))

/** The one objective shown: what it is, how it stands, the routine that started it, why it does not move. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObjectiveCard(card: ObjectiveCardUi, onOpen: (TaskId) -> Unit) {
    AppCard(contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(card.status.text, card.status.tone)
                TextButton(onClick = { onOpen(card.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open objective") }
            }
            card.routine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            card.notice?.let { notice ->
                Text(notice.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(notice.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What every sign, line and glyph means: in words, so nothing depends on colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val calm = LocalCalmMotion.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen).padding(bottom = Spacing.xl).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text("What the picture shows", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            "You are at the top, the Director in the centre, the other agents around it and shared spaces along the bottom. " +
                "A line joins two that sent each other messages; an arrow shows which way.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LEGEND_KINDS.forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(kind.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${kind.title}: ${kind.style.legend}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("What each one is doing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.sm).semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            NodeState.entries.filter { it != NodeState.CANNOT_RUN }.forEach { StatusChip(it.word, it.tone) }
            StatusChip("Cannot run (it says why)", StatusTone.UNAVAILABLE)
        }
        Text(
            "A ring around an agent: it works, or (thicker) it waits for you. Faint: switched off or no longer in the team.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(if (calm) TeamWords.CALM_NOTE else TeamWords.DOTS_NOTE, style = MaterialTheme.typography.bodyMedium)
        Text(TeamWords.PAN_NOTE, style = MaterialTheme.typography.bodyMedium)
    }
}
