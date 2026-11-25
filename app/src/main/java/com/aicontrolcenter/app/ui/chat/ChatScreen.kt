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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
import com.aicontrolcenter.app.ui.components.InlineTextEntry
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
import com.aicontrolcenter.app.ui.approvals.RequestCard
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.RequestId
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextAlign

class ChatActions(
    val onBack: () -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onRename: (String) -> Unit,
    val onDelete: () -> Unit,
    val onCheckProvider: () -> Unit,
    val onOpenProvider: (ProviderId) -> Unit,
    val onMessageShown: () -> Unit,
    /** Opens the Approval screen of a request raised in this chat. */
    val onOpenRequest: (RequestId) -> Unit = {},
)

@Composable
fun ChatRoute(conversationId: ConversationId, onBack: () -> Unit, onOpenProvider: (ProviderId) -> Unit, onOpenRequest: (RequestId) -> Unit = {}) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        ChatViewModel(
            conversationId, graph.registryDirectory, graph.agentChat, graph.toolRegistry, graph.providerManager,
            requests = graph.ownerRequests.observeForConversation(conversationId),
            capabilityInfo = graph.capabilities::info,
            clock = graph.clock,
            awaitStartup = graph::awaitStartup,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    ChatScreen(
        state = state,
        draft = viewModel.draft,
        actions = ChatActions(
            onBack = onBack,
            onSend = viewModel::send,
            onStop = viewModel::stop,
            onRename = viewModel::rename,
            onDelete = viewModel::delete,
            onCheckProvider = viewModel::checkProvider,
            onOpenProvider = onOpenProvider,
            onMessageShown = viewModel::messageShown,
            onOpenRequest = onOpenRequest,
        ),
    )
}

@Composable
fun ChatScreen(state: ChatUiState, draft: TextFieldState, actions: ChatActions) {
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
                if (renaming) {
                    InlineTextEntry(
                        title = "Rename conversation",
                        label = "Title",
                        confirmLabel = "Rename",
                        initial = state.title,
                        onConfirm = {
                            renaming = false
                            actions.onRename(it)
                        },
                        onDismiss = { renaming = false },
                        modifier = Modifier.readableWidth().padding(horizontal = Spacing.screen, vertical = Spacing.sm),
                    )
                }
                Transcript(state, actions, Modifier.weight(1f))
                Composer(state, draft, actions)
            }
        }
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
        itemsIndexed(state.items, key = { _, item -> item.key }) { index, item ->
            when (item) {
                is TranscriptItem.Owner -> OwnerBubble(item.text)
                // One avatar per run of agent messages, so a turn with tool calls reads as one speaker.
                is TranscriptItem.Agent -> AgentTurn(state.agentName, state.agentColorIndex, item, showAvatar = state.items.getOrNull(index - 1) !is TranscriptItem.Agent)
                is TranscriptItem.Notice -> NoticeRow(item, state, actions, isLast = item === state.items.last())
                // Answered on its Approval screen only, never in the chat.
                is TranscriptItem.Request -> RequestCard(item.request, onOpen = { actions.onOpenRequest(item.request.id) })
            }
        }
        state.live?.let { live ->
            item(key = "live") { LiveReply(state, live, showAvatar = state.items.lastOrNull() !is TranscriptItem.Agent) }
        }
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
            buildString {
                state.destination?.let { append("Replies come from ${it.modelName}. ") }
                append("The conversation is stored on this phone; where each message is sent is shown above the input.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

@Composable
private fun NoticeRow(item: TranscriptItem.Notice, state: ChatUiState, actions: ChatActions, isLast: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TranscriptNotice(item)
        if (isLast && state.checkProviderId != null && state.blockedReason == null) {
            ProviderActions(state, actions)
        }
    }
}

/** Check the provider again (a stale or passing problem), or open it (a key to replace, an address to fix). */
@Composable
private fun ProviderActions(state: ChatUiState, actions: ChatActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CheckProviderButton(state, actions, Modifier.weight(1f, fill = false))
        state.checkProviderId?.let { id ->
            androidx.compose.material3.TextButton(onClick = { actions.onOpenProvider(id) }) { Text("Open provider") }
        }
    }
}

@Composable
private fun CheckProviderButton(state: ChatUiState, actions: ChatActions, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = actions.onCheckProvider, enabled = !state.isCheckingProvider, modifier = modifier) {
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
private fun LiveReply(state: ChatUiState, live: LiveTurnUi, showAvatar: Boolean) {
    AgentColumn(state.agentName, state.agentColorIndex, showAvatar) {
        if (live.partialReasoning.isNotEmpty()) ReasoningSummary(live.partialReasoning, live = true, answering = live.partialText.isNotEmpty())
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
private fun Composer(state: ChatUiState, draft: TextFieldState, actions: ChatActions) {
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
                    if (state.checkProviderId != null) ProviderActions(state, actions)
                } else {
                    InputRow(state, draft, actions)
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
        // Everything stacks in one column, so the line stays readable on narrow (360 dp) phones.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (destination.isSimulator) TintedChip(SIMULATOR_LABEL, appColors.attention)
        }
    }
}

@Composable
private fun InputRow(state: ChatUiState, draft: TextFieldState, actions: ChatActions) {
    // The field reads and writes the view model's text directly, so no key waits for the transcript to update.
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            state = draft,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Message to ${state.agentName}" },
            placeholder = { Text(if (state.isRunning) "${state.agentName} is answering…" else "Message ${state.agentName}") },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 5),
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
            FilledIconButton(onClick = actions.onSend, enabled = state.canSend(draft.text), modifier = Modifier.size(56.dp)) {
                Icon(AppIcons.Send, contentDescription = "Send")
            }
        }
    }
}
