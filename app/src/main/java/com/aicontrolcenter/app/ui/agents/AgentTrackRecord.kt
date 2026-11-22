package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.formatDuration
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.agents.AgentStats
import kotlin.math.roundToInt

/** The agent's history: reliability first, then the counts it is computed from. */
@Composable
internal fun TrackRecordSection(agent: AgentDetailUi) {
    Column {
        SectionHeader("Track record")
        AppCard {
            if (!agent.hasHistory) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Icon(
                        AppIcons.Insights,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("No history yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Tasks, reviews and reliability appear here once this agent has done work.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                TrackRecord(agent.stats)
            }
        }
    }
}

@Composable
private fun TrackRecord(stats: AgentStats) {
    val reliability = stats.reliability
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Reliability", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (reliability == null) {
            Text(
                "Not enough finished work to judge yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                "${(reliability * 100).roundToInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = { reliability.toFloat() },
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            )
            Text(
                "Completed tasks and passed reviews count for the agent; failures, time-outs and rejections count against it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    RowDivider(Modifier.padding(vertical = Spacing.md))
    KeyValueRow("Tasks assigned", stats.tasksAssigned.toString())
    KeyValueRow("Completed", stats.tasksCompleted.toString())
    KeyValueRow("Failed", stats.tasksFailed.toString())
    KeyValueRow("Timed out", stats.tasksTimedOut.toString())
    KeyValueRow("Cancelled", stats.tasksCancelled.toString())
    KeyValueRow("Average task time", stats.averageTaskDurationMs?.let(::formatDuration) ?: "No finished tasks")
    RowDivider(Modifier.padding(vertical = Spacing.sm))
    KeyValueRow("Reviews passed", stats.reviewsPassed.toString())
    KeyValueRow("Revisions requested", stats.revisionsRequested.toString())
    KeyValueRow("Reviews rejected", stats.reviewsRejected.toString())
    RowDivider(Modifier.padding(vertical = Spacing.sm))
    KeyValueRow("Model calls", "${stats.modelCalls} (${stats.modelErrors} failed)")
    KeyValueRow("Tool calls", "${stats.toolCalls} (${stats.toolErrors} failed)")
    KeyValueRow("Tokens in / out", "${stats.inputTokens} / ${stats.outputTokens}")
}
