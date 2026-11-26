package com.aicontrolcenter.app.ui.questions

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.approvals.PreviewTextView
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ObscuredTouchGuard
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.atMostChars
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Cloud
import com.aicontrolcenter.app.ui.icons.Help
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.QuestionRules

/** Where the question screen leads, and the owner's answers. */
class QuestionActions(
    val onBack: () -> Unit,
    val onSend: () -> Unit,
    val onSendAnyway: () -> Unit,
    val onDecideYourself: () -> Unit,
    /** Copies suggested answer number n into the answer; sends nothing. */
    val onUseSuggestion: (Int) -> Unit,
    val onOpenTask: (TaskId) -> Unit,
    val onOpenObjective: (TaskId) -> Unit,
    val onMessageShown: () -> Unit,
)

/**
 * One question an agent asked the owner (rule 13, Tier B; WP11a): "A question from <agent>", the line "This is not a
 * permission. Your answer is only text for the agent.", the question as quoted agent text, suggested answers as a quoted
 * list with "Use this answer", the owner's answer, Send, "Decide yourself" and "Send it anyway" (rule 32). Never the
 * Approval screen, and never beside one. Texts and API: scratchpad/phase6/handoff-wp5.md section 3 (WP11a).
 *
 * @param onOpenTask the task that asked; [onOpenObjective] its objective.
 */
@Composable
fun QuestionRoute(requestId: RequestId, onBack: () -> Unit, onOpenTask: (TaskId) -> Unit, onOpenObjective: (TaskId) -> Unit) {
    val graph = LocalAppGraph.current
    val work = LocalScreenWork.current
    val viewModel = viewModel(key = "question-${requestId.value}") {
        QuestionViewModel(requestId, graph.ownerQuestions, graph.registryDirectory, graph.clock, graph.zone, computation = work)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    QuestionScreen(
        state,
        viewModel.answer,
        QuestionActions(
            onBack = onBack,
            onSend = viewModel::send,
            onSendAnyway = viewModel::sendAnyway,
            onDecideYourself = viewModel::decideYourself,
            onUseSuggestion = viewModel::useSuggestion,
            onOpenTask = onOpenTask,
            onOpenObjective = onOpenObjective,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

/** What the answer field is called for screen readers (and tests). */
const val ANSWER_FIELD = "Your answer"

/**
 * The question screen: who asks and that this is not a permission, the question in the agent's own words, its
 * suggestions, where the answer goes, the answer field and the three ways to answer. Touches are refused while another
 * app's window covers it, and its answers are marked sensitive, as on the Approval screen.
 */
@Composable
fun QuestionScreen(state: QuestionUiState, answer: TextFieldState, actions: QuestionActions) {
    val question = state.question
    ScreenScaffold(
        title = question?.title ?: "A question from an agent",
        onBack = actions.onBack,
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            question == null -> EmptyState(
                icon = AppIcons.Help,
                message = "This question no longer exists. It may have been cleared with the agent that asked.",
                actionLabel = "Go back",
                onAction = actions.onBack,
                modifier = Modifier.padding(padding),
            )
            else -> {
                ObscuredTouchGuard()
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .imePadding()
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .readableWidth()
                        .padding(horizontal = Spacing.screen)
                        .padding(bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    WhoAsks(question, actions)
                    InfoBanner(text = QuestionTexts.NOT_A_PERMISSION + " " + QuestionTexts.ALLOWS_NOTHING, icon = AppIcons.Help)
                    TheQuestion(question)
                    if (state.canAnswer) {
                        if (question.options.isNotEmpty()) Suggestions(question, enabled = !state.isBusy, onUse = actions.onUseSuggestion)
                        Answer(question, state, answer, actions)
                    } else {
                        Outcome(question, state.refusal)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhoAsks(question: QuestionUi, actions: QuestionActions) {
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(question.status.text, question.status.tone, icon = if (question.isOpen) AppIcons.Help else question.status.tone.icon)
                Text(question.timing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AgentAvatar(question.agent.name, question.agent.colorIndex, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(question.agent.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Asked ${question.askedAt.replaceFirstChar(Char::lowercaseChar)} while working on a task",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                question.taskId?.let { id -> TextButton(onClick = { actions.onOpenTask(id) }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Open the task") } }
                question.rootId?.let { id -> TextButton(onClick = { actions.onOpenObjective(id) }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text("Open the objective") } }
            }
        }
    }
}

/** The question, quoted, in the agent's own words; any hidden character is warned about first. */
@Composable
private fun TheQuestion(question: QuestionUi) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("${question.agent.name} asks")
        question.hiddenWarning?.let {
            InfoBanner(title = "Hidden characters", text = it, tone = StatusTone.UNAVAILABLE, icon = AppIcons.StatusAttention)
        }
        Quoted(question.question, "In ${question.agent.name}'s own words. It is not a fact the app checked.", large = true)
        question.reason?.let { Quoted(it, "${question.agent.name}'s reason for asking, in its own words.", large = false) }
    }
}

/** Agent text, quoted on a quiet ground, with the line that says whose words they are. */
@Composable
private fun Quoted(text: String, note: String, large: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            PreviewTextView(
                "“$text”",
                if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                MaterialTheme.colorScheme.onSurface,
            )
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The agent's suggestions as a quoted list, each with "Use this answer", which only fills the answer below: never a
 * primary button, never a button that answers (rule 13).
 */
@Composable
private fun Suggestions(question: QuestionUi, enabled: Boolean, onUse: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("Answers ${question.agent.name} suggests")
        Text(
            "Suggested by ${question.agent.name}. “${QuestionTexts.USE_THIS_ANSWER}” only puts it in your answer below; nothing is sent until you tap ${QuestionTexts.SEND}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppCard(contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            question.options.forEachIndexed { index, option ->
                if (index > 0) RowDivider()
                Column(Modifier.padding(vertical = Spacing.xs)) {
                    PreviewTextView("“$option”", MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface)
                    TextButton(
                        onClick = { onUse(index) },
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = Spacing.touchTarget).semantics {
                            isSensitiveData = true
                            contentDescription = "${QuestionTexts.USE_THIS_ANSWER}: $option"
                        },
                    ) { Text(QuestionTexts.USE_THIS_ANSWER) }
                }
            }
        }
    }
}

/** The owner's answer: the field, what it looks like, where it goes, and Send, "Send it anyway" and "Decide yourself". */
@Composable
private fun Answer(question: QuestionUi, state: QuestionUiState, answer: TextFieldState, actions: QuestionActions) {
    // Marked sensitive: on Android 14 and later only accessibility tools that help the owner can press them.
    val sensitive = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).semantics { isSensitiveData = true }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionHeader("Your answer")
        AppTextField(
            state = answer,
            label = ANSWER_FIELD,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = ANSWER_FIELD },
            placeholder = "Write in your own words",
            supportingText = state.hint?.takeIf { state.refusal == null }?.text ?: "At most ${"%,d".format(QuestionRules.MAX_ANSWER_CHARS)} characters.",
            errorText = state.hint?.takeIf { it == AnswerHint.KEY }?.text,
            singleLine = false,
            minLines = 3,
            maxLines = 10,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            inputTransformation = remember { atMostChars(QuestionRules.MAX_ANSWER_CHARS) },
            enabled = !state.isBusy,
        )
        state.refusal?.let { reason ->
            InfoBanner(
                title = if (state.sendAnywayOffered) "Is this a code?" else "Not sent",
                text = reason,
                tone = if (state.sendAnywayOffered) StatusTone.ATTENTION else StatusTone.UNAVAILABLE,
                icon = AppIcons.StatusAttention,
            )
        }
        WhereItGoes(question)
        Button(onClick = actions.onSend, enabled = !state.isBusy, modifier = sensitive) {
            Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(QuestionTexts.SEND, modifier = Modifier.padding(start = Spacing.sm))
        }
        if (state.sendAnywayOffered) {
            // The second tap for a code: outlined in the warning colour, never the leading choice.
            val error = MaterialTheme.colorScheme.error
            OutlinedButton(
                onClick = actions.onSendAnyway,
                enabled = !state.isBusy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
                border = BorderStroke(1.dp, if (!state.isBusy) error else error.copy(alpha = 0.38f)),
                modifier = sensitive,
            ) { Text(QuestionTexts.SEND_ANYWAY) }
            Text(
                "Only if you are sure ${question.agent.name} should have this code. It is sent as you typed it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = actions.onDecideYourself, enabled = !state.isBusy, modifier = sensitive) { Text(QuestionTexts.letDecide(question.agent.name)) }
        Text(
            "${question.agent.name} makes the most reasonable choice and says which one in its result.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        question.waitLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Where the answer goes, said before anything is sent, with the after-answer rule (rule 32). */
@Composable
private fun WhereItGoes(question: QuestionUi) {
    val color = if (question.answerLeavesPhone) appColors.cloud else appColors.onDevice
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(if (question.answerLeavesPhone) AppIcons.Cloud else AppIcons.OnDevice, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(question.whereAnswerGoes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text(QuestionTexts.AFTER_ANSWER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A closed question: how it ended, the owner's answer if they gave one, and why the last answer did not go. */
@Composable
private fun Outcome(question: QuestionUi, refusal: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        refusal?.takeIf { it != question.outcome }?.let { InfoBanner(title = "Not sent", text = it, tone = StatusTone.ATTENTION) }
        InfoBanner(title = question.status.text, text = question.outcome.orEmpty(), tone = question.status.tone.takeIf { it == StatusTone.READY })
        question.answer?.let { Quoted(it, "Your answer, as it was sent.", large = false) }
    }
}
