package com.aicontrolcenter.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.destinationPhrase
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ExpandLess
import com.aicontrolcenter.app.ui.icons.ExpandMore
import com.aicontrolcenter.app.ui.icons.Forum
import com.aicontrolcenter.app.ui.icons.Lightbulb
import com.aicontrolcenter.app.ui.icons.Stop
import com.aicontrolcenter.app.ui.icons.Tools
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.ConversationId

class ChatActions(
    val onBack: () -> Unit,
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onRename: (String) -> Unit,
    val onDelete: () -> Unit,
    val onCheckProvider: () -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun ChatRoute(conversationId: ConversationId, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        ChatViewModel(conversationId, graph.registryDirectory, graph.agentChat, graph.toolRegistry, graph.providerManager)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    ChatScreen(
        state = state,
        actions = ChatActions(
            onBack = onBack,
            onDraftChange = viewModel::setDraft,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onCheckProvider = viewModel::checkProvider,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

@Composable
fun ChatScreen(state: ChatUiState, actions: ChatActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = state.title.ifBlank { "Conversation" },
        onBack = actions.onBack,
        actions = {
            if (!state.notFound && !state.isLoading) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(AppIcons.MoreVert, contentDescription = "Conversation options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                renaming = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete conversation") },
                            leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> EmptyState(
                icon = AppIcons.Forum,
                message = "This conversation no longer exists.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                Transcript(state, actions, Modifier.weight(1f))
                Composer(state, actions)
            }
        }
    }

    if (renaming) {
        RenameDialog(
            current = state.title,
            onRename = {
                renaming = false
                actions.onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this conversation?",
            message = "Every message in it, with its tool calls, is removed from this phone. ${state.agentName} itself is not affected.",
            confirmLabel = "Delete",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun Transcript(state: ChatUiState, actions: ChatActions, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val itemCount = state.items.size + (if (state.live != null) 1 else 0)
    // Follow the conversation as it grows, including while a reply streams in.
    LaunchedEffect(itemCount, state.live?.partialText?.length, state.live?.phase) {
        if (itemCount > 0) listState.scrollToItem(itemCount)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.readableWidth().fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (state.items.isEmpty() && state.live == null) {
            item(key = "intro") { Intro(state) }
        }
        items(state.items, key = { it.key }) { item ->
            when (item) {
                is TranscriptItem.Owner -> OwnerBubble(item.text)
                is TranscriptItem.Agent -> AgentTurn(state, item)
                is TranscriptItem.Notice -> NoticeRow(item, state, actions, isLast = item === state.items.last())
            }
        }
        state.live?.let { live -> item(key = "live") { LiveReply(state, live) } }
        item(key = "end") { Spacer(Modifier.size(1.dp)) }
    }
}

@Composable
private fun Intro(state: ChatUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AgentAvatar(state.agentName, state.agentColorIndex, size = 56.dp)
        Text("Talk to ${state.agentName}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            state.destination?.let { "Replies come from ${it.modelName}. Everything you write here is kept on this phone with the conversation." }
                ?: "Everything you write here is kept on this phone with the conversation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

@Composable
private fun OwnerBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp).padding(start = 48.dp).semantics(mergeDescendants = true) {
                contentDescription = "You: $text"
            },
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** The agent's side: avatar, reasoning, text, tool calls and the footer, aligned to the start. */
@Composable
private fun AgentTurn(state: ChatUiState, item: TranscriptItem.Agent) {
    AgentColumn(state) {
        item.reasoning?.let { ReasoningSummary(it, live = false) }
        if (item.text.isNotBlank()) AgentBubble(item.text)
        item.toolCalls.forEach { ToolCallRow(it) }
        item.footer?.let { ReplyFooter(it) }
    }
}

@Composable
private fun AgentColumn(state: ChatUiState, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(end = 24.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        AgentAvatar(state.agentName, state.agentColorIndex, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun AgentBubble(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ReasoningSummary(text: String, live: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = if (expanded) "Hide reasoning" else "Show reasoning") { expanded = !expanded }
                .heightIn(min = 40.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(AppIcons.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                if (live) "Thinking…" else "Reasoning summary",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        if (expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolCallRow(call: ToolCallUi) {
    var expanded by rememberSaveable(call.callId) { mutableStateOf(false) }
    val (statusText, tone) = when (call.outcome) {
        ToolOutcomeUi.Running -> "Running" to StatusTone.WORKING
        is ToolOutcomeUi.Succeeded -> "Done" to StatusTone.READY
        is ToolOutcomeUi.Failed -> "Failed" to StatusTone.ATTENTION
        ToolOutcomeUi.NoResult -> "Not run" to StatusTone.DISABLED
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            Modifier
                .clickable(onClickLabel = if (expanded) "Collapse tool call" else "Expand tool call") { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(AppIcons.Tools, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Text(
                    call.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (call.outcome == ToolOutcomeUi.Running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = appColors.working)
                } else {
                    Icon(tone.icon, contentDescription = null, tint = appColors.status(tone), modifier = Modifier.size(16.dp))
                }
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = appColors.status(tone))
                Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Text(call.argumentsSummary, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            val result = when (val outcome = call.outcome) {
                is ToolOutcomeUi.Succeeded -> outcome.content
                is ToolOutcomeUi.Failed -> outcome.content
                ToolOutcomeUi.NoResult -> "The turn ended before this tool ran."
                ToolOutcomeUi.Running -> null
            }
            if (result != null) {
                Text(
                    result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (call.outcome is ToolOutcomeUi.Failed) appColors.attention else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                Text("Arguments sent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text(call.argumentsJson.ifEmpty { "Not recorded" }, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ReplyFooter(footer: ReplyFooterUi) {
    val parts = listOfNotNull(footer.answeredBy?.let { "Answered by $it" }, footer.usage)
    if (parts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 4.dp)) {
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (footer.differs) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = appColors.attention, modifier = Modifier.size(14.dp))
                Text(
                    "Not the assigned model (${footer.assignedModel})",
                    style = MaterialTheme.typography.bodySmall,
                    color = appColors.attention,
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(item: TranscriptItem.Notice, state: ChatUiState, actions: ChatActions, isLast: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (item.kind) {
            NoticeKind.CUT_OFF -> InfoBanner(title = "Reply cut off", text = item.text, tone = StatusTone.ATTENTION)
            NoticeKind.FAILURE -> InfoBanner(title = "The turn failed", text = item.text, tone = StatusTone.UNAVAILABLE)
        }
        if (isLast && state.checkProviderId != null && state.blockedReason == null) {
            CheckProviderButton(state, actions)
        }
    }
}

@Composable
private fun CheckProviderButton(state: ChatUiState, actions: ChatActions) {
    FilledTonalButton(onClick = actions.onCheckProvider, enabled = !state.isCheckingProvider) {
        if (state.isCheckingProvider) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text(
            "Check ${state.checkProviderName ?: "provider"}",
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveReply(state: ChatUiState, live: LiveTurnUi) {
    AgentColumn(state) {
        if (live.partialReasoning.isNotEmpty()) ReasoningSummary(live.partialReasoning, live = true)
        if (live.partialText.isNotEmpty()) AgentBubble(live.partialText)
        Row(
            modifier = Modifier.heightIn(min = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = appColors.working)
            Text(live.phase, style = MaterialTheme.typography.labelLarge, color = appColors.working)
        }
    }
}

/** The bottom of the screen: where text goes, then the input (or why there is none). */
@Composable
private fun Composer(state: ChatUiState, actions: ChatActions) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = appColors.hairline)
            Column(
                modifier = Modifier.readableWidth().padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                DestinationLine(state.destination)
                val blocked = state.blockedReason
                if (blocked != null) {
                    InfoBanner(title = "Cannot send", text = blocked, tone = StatusTone.UNAVAILABLE)
                    if (state.checkProviderId != null) CheckProviderButton(state, actions)
                } else {
                    InputRow(state, actions)
                }
            }
        }
    }
}

/** Always visible: which model gets this conversation's text, through which provider, and whether it leaves the phone. */
@Composable
internal fun DestinationLine(destination: DestinationUi?) {
    if (destination == null) return
    val color = appColors.locality(destination.locality)
    val spoken = "Messages go to ${destination.modelName} through ${destination.providerName} and ${destination.locality.destinationPhrase}."
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = if (appColors.isDark) 0.10f else 0.07f).compositeOver(MaterialTheme.colorScheme.background), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(destination.locality.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                destination.locality.destinationPhrase.replaceFirstChar(Char::uppercase),
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
            Text(
                buildString {
                    append(destination.modelName).append(" · ").append(destination.providerName)
                    if (destination.usingFallback) append(" · fallback model")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (destination.isSimulator) TintedChip(SIMULATOR_LABEL, appColors.attention)
    }
}

@Composable
private fun InputRow(state: ChatUiState, actions: ChatActions) {
    // Typed text is held here and reported upward, so the cursor never jumps while the view model catches up.
    var text by remember { mutableStateOf(state.draft) }
    LaunchedEffect(state.draft) { if (state.draft != text) text = state.draft }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                actions.onDraftChange(it)
            },
            modifier = Modifier.weight(1f).semantics { contentDescription = "Message to ${state.agentName}" },
            placeholder = { Text(if (state.isRunning) "${state.agentName} is answering…" else "Message ${state.agentName}") },
            maxLines = 5,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (state.isRunning) {
            FilledIconButton(
                onClick = actions.onStop,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Icon(AppIcons.Stop, contentDescription = "Stop") }
        } else {
            FilledIconButton(onClick = actions.onSend, enabled = state.canSend, modifier = Modifier.size(56.dp)) {
                Icon(AppIcons.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename conversation") },
        text = { AppTextField(value = title, onValueChange = { title = it }, label = "Title") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onRename(title) }, enabled = title.isNotBlank()) { Text("Rename") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
