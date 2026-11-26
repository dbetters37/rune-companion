package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.RowText
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.theme.Spacing

/**
 * Settings › Questions from agents and their time-out (Q3, Tier B; WP10b). A slot of `SettingsScreen` (WP8 places it).
 * The switch says what a question is and is not (rule 13: never a permission); the time-out says what happens when the
 * owner does not answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuestionTimeoutSection(
    viewModel: QuestionSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.isLoading) return
    Column(modifier = modifier) {
        SectionHeader("Questions from agents")
        AppCard {
            SwitchRow(
                title = "Agents may ask me questions",
                description = QUESTIONS_SWITCH,
                checked = state.questionsOn,
                onCheckedChange = viewModel::setQuestionsOn,
            )
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            RowText(title = "Time to answer", description = TIME_TO_ANSWER)
            FlowRow(
                modifier = Modifier.padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                state.choices.forEach { choice ->
                    FilterChip(
                        selected = state.timeoutMinutes == choice.minutes,
                        onClick = { viewModel.setTimeoutMinutes(choice.minutes) },
                        label = { Text(choice.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            state.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Under the switch: what a question is, and what switching it off does. */
const val QUESTIONS_SWITCH: String =
    "An agent may stop and ask you a short question while it works on an objective; it waits in Waiting for you. " +
        "A question is never a permission: your answer is only text for the agent. Off: agents decide by themselves, " +
        "and a question already waiting can still be answered."

/** Under "Time to answer". */
const val TIME_TO_ANSWER: String =
    "If you have not answered by then, the agent is told to make the most reasonable choice and go on. " +
        "Waiting for you does not count against the task's time limit."
