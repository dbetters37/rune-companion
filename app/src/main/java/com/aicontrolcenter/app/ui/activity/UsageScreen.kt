package com.aicontrolcenter.app.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatTile
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.icons.Speed
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.usage.CostEstimate

/** What the Usage tab's numbers mean; also the cost line's last words (rule 29). */
private const val COST_DISCLAIMER = CostEstimate.DISCLAIMER

/**
 * Activity › "Usage and cost" (goals 1-2, rules 7 and 29; WP10a): Today / 7 days / 30 days / this month; answers, tokens
 * and estimated cost by model, answers and tokens by agent; reliability in words; average time to the first words and to
 * the whole answer (models with 5 or more answers); how long requests waited for the owner; every row opens the log
 * filtered ([onOpenLog]); "Estimated from the prices you entered; your provider's bill is what counts".
 */
@Composable
fun UsageTab(
    viewModel: UsageViewModel,
    navigation: ActivityNavigation,
    onOpenLog: (agentId: AgentId?, text: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    UsageContent(state, viewModel::selectPeriod, navigation, onOpenLog, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UsageContent(
    state: UsageUiState,
    onSelectPeriod: (UsagePeriod) -> Unit,
    navigation: ActivityNavigation,
    onOpenLog: (agentId: AgentId?, text: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.readableWidth(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "periods") {
            // Four short chips fit a 360 dp phone; at large font sizes they wrap to a second line.
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                UsagePeriod.entries.forEach { period ->
                    FilterChip(
                        selected = period == state.period,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(period.title) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        when {
            state.isLoading -> item(key = "loading") {
                Column(Modifier.fillMaxWidth().padding(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                }
            }
            state.summary == null -> item(key = "empty") {
                EmptyState(
                    icon = AppIcons.Insights,
                    title = "Nothing used ${state.period.phrase}",
                    message = "When agents ask models for answers, how many answers and tokens they used, and what that " +
                        "cost at the prices you entered, shows here. $TOKENS_IN_PLAIN_WORDS",
                )
            }
            else -> {
                item(key = "summary") { SummaryCard(state.summary, state.period, navigation) }
                if (state.models.isNotEmpty()) {
                    item(key = "models-header") { SectionHeader("By model · ${state.models.size}") }
                    items(state.models, key = { "model:${it.modelId.value}" }) { row ->
                        ModelRow(row, onOpenLog = { onOpenLog(null, row.logText) }, onAddPrice = { navigation.onOpenModel(row.modelId) })
                    }
                }
                if (state.agents.isNotEmpty()) {
                    item(key = "agents-header") {
                        Column {
                            SectionHeader("By agent · ${state.agents.size}")
                            Text(
                                "The cost is shown for each model above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "agents") { AgentsCard(state.agents, onOpenLog) }
                }
            }
        }
        state.waiting?.let { waiting ->
            item(key = "waiting") { WaitingCard(waiting, onOpenLog = { onOpenLog(null, "Owner") }) }
        }
        if (!state.isLoading && state.summary != null) {
            item(key = "disclaimer") {
                Text(
                    "$COST_DISCLAIMER Usage numbers stay on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: UsageSummaryUi, period: UsagePeriod, navigation: ActivityNavigation) {
    AppCard {
        UsageStatTiles(
            answers = UsageStat(formatCount(summary.answers), if (summary.answers == 1L) "Answer" else "Answers"),
            tokens = UsageStat(formatCount(summary.tokens), if (summary.tokens == 1L) "Token" else "Tokens"),
            cost = UsageStat(summary.cost ?: "No price", "Estimated cost"),
        )
        Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            summary.lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                "$ANSWERS_IN_PLAIN_WORDS $TOKENS_IN_PLAIN_WORDS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        summary.unpriced.forEach { model ->
            TextButton(onClick = { navigation.onOpenModel(model.modelId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Add a price for ${model.name}")
            }
        }
        if (period == UsagePeriod.TODAY) {
            TextButton(onClick = navigation.onOpenDailyLimits, modifier = Modifier.heightIn(min = 48.dp)) { Text("Daily limits") }
        }
    }
}

/** One headline number: its value, what it is, and an optional qualifier under it. [onClick] makes the tile a link. */
@Immutable
internal data class UsageStat(
    val value: String,
    val label: String,
    val caption: String? = null,
    val captionColor: Color? = null,
)

/**
 * Answers, tokens and the estimated cost as tiles: answers and tokens side by side with the cost under them (money needs
 * the width), or all three stacked on a narrow window or with large fonts. Each tile reads as one phrase.
 */
@Composable
internal fun UsageStatTiles(answers: UsageStat, tokens: UsageStat, cost: UsageStat, onClick: (() -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 260.dp || LocalDensity.current.fontScale >= 1.5f
        @Composable
        fun Tile(stat: UsageStat, icon: ImageVector, modifier: Modifier) = StatTile(
            value = stat.value,
            label = stat.label,
            icon = icon,
            modifier = modifier,
            onClick = onClick,
            caption = stat.caption,
            captionColor = stat.captionColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (stacked) {
                Tile(answers, AppIcons.Insights, Modifier.fillMaxWidth())
                Tile(tokens, AppIcons.Speed, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Tile(answers, AppIcons.Insights, Modifier.weight(1f))
                    Tile(tokens, AppIcons.Speed, Modifier.weight(1f))
                }
            }
            Tile(cost, AppIcons.Schedule, Modifier.fillMaxWidth())
        }
    }
}

/** One model: what it used, its cost, reliability and speed. The row opens the log on this model's calls. */
@Composable
private fun ModelRow(row: ModelUsageUi, onOpenLog: () -> Unit, onAddPrice: () -> Unit) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Show its calls in the log", onClick = onOpenLog)
                .heightIn(min = 64.dp)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(row.where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${answers(row.answers)} · ${tokens(row.tokens)} · ${row.cost}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                row.reliability.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                row.speed?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (row.needsPrice) {
            RowDivider()
            TextButton(onClick = onAddPrice, modifier = Modifier.padding(horizontal = Spacing.sm).heightIn(min = 48.dp)) {
                Text("Add a price")
            }
        }
    }
}

@Composable
private fun AgentsCard(agents: List<AgentUsageUi>, onOpenLog: (AgentId?, String?) -> Unit) {
    AppCard(contentPadding = PaddingValues(0.dp)) {
        agents.forEachIndexed { index, agent ->
            if (index > 0) RowDivider()
            val summary = "${answers(agent.answers)} · ${tokens(agent.tokens)}"
            val id = agent.agentId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (id != null) Modifier.clickable(onClickLabel = "Show what ${agent.name} did in the log") { onOpenLog(id, null) } else Modifier)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                    .clearAndSetSemantics { contentDescription = "${agent.name}: $summary" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(agent.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (id != null) Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WaitingCard(waiting: WaitingTimesUi, onOpenLog: () -> Unit) {
    Column {
        SectionHeader("Waiting for you")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                waiting.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                if (waiting.waitingNow > 0) {
                    Text(
                        if (waiting.waitingNow == 1) "1 request waits for your answer now." else "${waiting.waitingNow} requests wait for your answer now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "While a request waits for you, the agent's work waits too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                waiting.partial?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TextButton(onClick = onOpenLog, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show your answers in the log") }
        }
    }
}
