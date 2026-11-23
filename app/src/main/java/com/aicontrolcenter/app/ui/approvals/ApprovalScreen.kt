package com.aicontrolcenter.app.ui.approvals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.ObscuredTouchGuard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Approval
import com.aicontrolcenter.app.ui.icons.Cloud
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus

/** Where the Approval screen leads. */
class ApprovalActions(
    val onBack: () -> Unit,
    val onChoose: (RequestChoice) -> Unit,
    val onNoteChange: (String) -> Unit,
    val onOpenTask: (TaskId) -> Unit,
    val onOpenObjective: (TaskId) -> Unit,
    val onOpenConversation: (ConversationId) -> Unit,
    val onMessageShown: () -> Unit,
)

@Composable
fun ApprovalRoute(
    requestId: RequestId,
    onBack: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    onOpenObjective: (TaskId) -> Unit,
    onOpenConversation: (ConversationId) -> Unit,
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel(key = "approval-${requestId.value}") {
        ApprovalViewModel(requestId, graph.ownerRequests, graph.registryDirectory, graph.capabilities::info, graph.clock)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ApprovalScreen(
        state,
        ApprovalActions(onBack, viewModel::choose, viewModel::setNote, onOpenTask, onOpenObjective, onOpenConversation, viewModel::messageShown),
    )
}

/**
 * One question to the owner, answered here and only here: who asks and through whom, the tool,
 * the exact action with every detail, the target, where data would go, the risk, the agent's
 * own words, the time left, and the answers on offer. A permission can be given once, for the
 * task or always, or refused; a consequential action can only be approved or denied, every time.
 * Each answer says what it leads to. For an action that is hard to undo, Deny leads and is drawn
 * as the safe choice, and Approve names the action, is drawn as a warning and asks once more.
 * Touches are refused while another app's window covers this screen or its dialogs.
 */
@Composable
fun ApprovalScreen(state: ApprovalUiState, actions: ApprovalActions) {
    val request = state.request
    ScreenScaffold(
        title = request?.title ?: "Request",
        onBack = actions.onBack,
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            request == null -> EmptyState(
                icon = AppIcons.Approval,
                message = "This request no longer exists. It may have been cleared with the agent that asked.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> {
                ObscuredTouchGuard()
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .readableWidth()
                        .padding(horizontal = Spacing.screen)
                        .padding(bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    WhoAsks(request)
                    WhatItDoes(request)
                    Facts(request, actions)
                    request.reason?.let { Reason(it, request.agent.name) }
                    if (request.isOpen) {
                        Answer(request, state, actions)
                    } else {
                        Outcome(request)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoAsks(request: RequestUi) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusChip(request.status.text, request.status.tone, icon = if (request.isOpen) request.kind.icon else request.status.tone.icon)
                RiskChip(request.riskLevel)
                request.dataGoesToHost?.let { TintedChip("Sends data to $it", appColors.cloud, icon = AppIcons.Cloud) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AgentAvatar(request.agent.name, request.agent.colorIndex, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(request.agent.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        when {
                            request.toolTitle == null -> "Asks you for a permission"
                            request.kind == OwnerRequestKind.APPROVAL -> "Wants to use “${request.toolTitle}”"
                            else -> "Needs a permission to use “${request.toolTitle}”"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (request.chain.isNotEmpty()) {
                Text(
                    "Asked through: " + (request.chain + request.agent.name).joinToString(" → "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WhatItDoes(request: RequestUi) {
    Column {
        SectionHeader(if (request.kind == OwnerRequestKind.APPROVAL) "Exactly what would happen" else "What it is for")
        request.hiddenWarning?.let {
            InfoBanner(
                title = "Hidden characters",
                text = it,
                tone = StatusTone.UNAVAILABLE,
                icon = AppIcons.StatusAttention,
                modifier = Modifier.padding(bottom = Spacing.sm),
            )
        }
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PreviewTextView(request.summary, MaterialTheme.typography.titleMedium, MaterialTheme.colorScheme.onSurface)
                if (request.details.isNotEmpty()) {
                    RowDivider()
                    request.details.forEach { line ->
                        PreviewTextView(line, MonoTextStyle, MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text(
                    "Shown exactly as the agent gave it. Highlighted marks stand for line breaks, tabs, repeated characters, quote marks inside " +
                        "a quoted value (⟨\"⟩) and hidden characters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Facts(request: RequestUi, actions: ApprovalActions) {
    Column {
        SectionHeader("Details")
        AppCard {
            Column {
                KeyValueRow("Permission", request.permission)
                request.permissionDescription?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Spacing.xs),
                    )
                }
                request.target?.let { KeyValueRow(request.targetKind ?: "For", it) }
                KeyValueRow("Where data goes", request.dataGoesTo)
                KeyValueRow("Risk", listOfNotNull(request.risk.text, request.risk.detail).joinToString(". "))
                KeyValueRow("Asked", request.raisedAt)
                if (request.isOpen) KeyValueRow("Time left", request.timing.removeSuffix(" left")) else KeyValueRow("Status", request.timing)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    request.taskId?.let { id -> TextButton(onClick = { actions.onOpenTask(id) }) { Text("Open the task") } }
                    if (request.taskId == null) request.rootId?.let { id -> TextButton(onClick = { actions.onOpenObjective(id) }) { Text("Open the objective") } }
                    request.conversationId?.let { id -> TextButton(onClick = { actions.onOpenConversation(id) }) { Text("Open the chat") } }
                }
            }
        }
    }
}

@Composable
private fun Reason(reason: String, agentName: String) {
    Column {
        SectionHeader("$agentName's reason")
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, appColors.hairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PreviewTextView("“$reason”", MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.onSurface)
                Text(
                    "In the agent's own words. It is not a fact the app checked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Answer(request: RequestUi, state: ApprovalUiState, actions: ApprovalActions) {
    var confirmAlways by rememberSaveable { mutableStateOf(false) }
    var confirmApprove by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionHeader("Your answer")
        request.hardToUndo?.let { InfoBanner(title = request.risk.text, text = it, tone = StatusTone.UNAVAILABLE, icon = AppIcons.StatusAttention) }
        AppTextField(
            value = state.note,
            onValueChange = actions.onNoteChange,
            label = "Note for the agent (optional)",
            modifier = Modifier.fillMaxWidth(),
        )
        // Each answer is marked sensitive: on Android 14 and later only accessibility tools that help
        // the owner use the phone can read or press it, so no automation service can answer for them.
        val answerModifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).semantics { isSensitiveData = true }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // A permission: the narrowest answer is the most prominent; "Always" is the least, and asks again.
            // An approval: Deny leads; Approve names the action and asks again when it is hard to undo.
            request.choices.forEach { choice ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    AnswerButton(choice, request, enabled = !state.isBusy, modifier = answerModifier) {
                        when {
                            choice == RequestChoice.ALLOW_ALWAYS -> confirmAlways = true
                            choice == RequestChoice.APPROVE && request.isConsequential -> confirmApprove = true
                            else -> actions.onChoose(choice)
                        }
                    }
                    request.consequences[choice]?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(
            "If you do not answer within the time left, the agent is refused.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmAlways) {
        ConfirmDialog(
            title = request.always.title,
            message = request.always.message,
            confirmLabel = request.always.confirm,
            dismissLabel = "Back",
            icon = AppIcons.Approval,
            cautionary = true,
            widens = true,
            guarded = true,
            onConfirm = {
                confirmAlways = false
                actions.onChoose(RequestChoice.ALLOW_ALWAYS)
            },
            onDismiss = { confirmAlways = false },
        )
    }
    if (confirmApprove) {
        ApproveDialog(
            request,
            onApprove = {
                confirmApprove = false
                actions.onChoose(RequestChoice.APPROVE)
            },
            onBack = { confirmApprove = false },
        )
    }
}

/**
 * One answer as a button. Deny is never drawn as a danger: for an action that is hard to undo it
 * is the filled, neutral choice that leads, and Approve is the outlined one in the error colour.
 */
@Composable
private fun AnswerButton(choice: RequestChoice, request: RequestUi, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = if (choice == RequestChoice.APPROVE) request.approveLabel else choice.label
    when (choice) {
        RequestChoice.ALLOW_ONCE -> Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
        RequestChoice.ALLOW_FOR_TASK -> FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
        RequestChoice.ALLOW_ALWAYS -> OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
        RequestChoice.DENY ->
            if (request.kind == OwnerRequestKind.APPROVAL) {
                FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
            } else {
                OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
            }
        RequestChoice.APPROVE ->
            if (request.isConsequential) {
                val error = MaterialTheme.colorScheme.error
                OutlinedButton(
                    onClick = onClick,
                    enabled = enabled,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
                    border = BorderStroke(1.dp, if (enabled) error else error.copy(alpha = 0.38f)),
                    modifier = modifier,
                ) { Text(label) }
            } else {
                Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
            }
    }
}

/** The second step before an action that is hard to undo: it names the action and shows it again in full. */
@Composable
private fun ApproveDialog(request: RequestUi, onApprove: () -> Unit, onBack: () -> Unit) {
    val sensitive = Modifier.semantics { isSensitiveData = true }
    AlertDialog(
        onDismissRequest = onBack,
        icon = { Icon(AppIcons.StatusAttention, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(request.action?.let { "$it?" } ?: "Approve this action?") },
        text = {
            ObscuredTouchGuard()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PreviewTextView(request.summary, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface)
                Text(
                    "${request.agent.name} does exactly this, this one time. " + (request.hardToUndo ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onApprove,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = sensitive,
            ) { Text(request.approveLabel) }
        },
        dismissButton = { TextButton(onClick = onBack, modifier = sensitive) { Text("Back") } },
    )
}

@Composable
private fun Outcome(request: RequestUi) {
    val (text, tone) = when (request.storedStatus) {
        OwnerRequestStatus.APPROVED -> (if (request.kind == OwnerRequestKind.APPROVAL) "You approved it." else "You allowed it.") to StatusTone.READY
        OwnerRequestStatus.DENIED -> "You refused. The agent was told." to StatusTone.UNAVAILABLE
        OwnerRequestStatus.CANCELLED ->
            "The agent stopped waiting before you answered: its chat or task was stopped, or the app restarted. Nothing was allowed." to null
        OwnerRequestStatus.PENDING, OwnerRequestStatus.EXPIRED -> "Nobody answered in time, so the agent was refused. Nothing was allowed." to null
    }
    InfoBanner(title = request.status.text, text = listOfNotNull(text, request.note?.let { "Note: $it" }).joinToString(" "), tone = tone)
}
