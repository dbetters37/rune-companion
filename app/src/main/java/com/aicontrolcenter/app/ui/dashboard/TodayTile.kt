package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.activity.UsageStat
import com.aicontrolcenter.app.ui.activity.UsageStatTiles
import com.aicontrolcenter.app.ui.activity.TOKENS_IN_PLAIN_WORDS
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/** Under the tile's numbers (rule 29). */
private const val COST_DISCLAIMER = "Costs are estimated from the prices you entered; your provider's bill is what counts."

/**
 * The Today tile: answers, tokens and estimated cost today against today's limits, and this month. The cost is what the
 * money limit counts (the models with a price), as Activity › Usage and cost and Settings › Daily limits show it; the
 * models without a price are named under it (D3A-1). Opens Activity › Usage and cost
 * (navigation.onOpenUsage) and Settings › Daily limits (navigation.onOpenDailyLimits). A slot of `DashboardScreen`.
 */
@Composable
fun TodayTile(
    extras: DashboardExtrasViewModel,
    navigation: DashboardNavigation,
    modifier: Modifier = Modifier,
) {
    val state by extras.uiState.collectAsStateWithLifecycle()
    val today = state.today ?: return
    TodayTileContent(today, navigation, modifier)
}

@Composable
internal fun TodayTileContent(today: TodayTileUi, navigation: DashboardNavigation, modifier: Modifier = Modifier) {
    val limits = today.limits
    val maxCalls = limits.maxCalls
    val maxTokens = limits.maxTokens
    Column(modifier = modifier) {
        SectionHeader("Today", actionLabel = "Usage and cost", onAction = navigation.onOpenUsage)
        AppCard {
            val attention = appColors.status(StatusTone.ATTENTION)
            val callsReached = maxCalls != null && today.answers >= maxCalls
            val tokensReached = maxTokens != null && today.tokens >= maxTokens
            UsageStatTiles(
                answers = UsageStat(
                    formatCount(today.answers), "Answers",
                    listOfNotNull(maxCalls?.let { "of ${formatCount(it.toLong())}" }, "reached".takeIf { callsReached }).joinToString(", ").ifEmpty { null },
                    attention.takeIf { callsReached },
                ),
                tokens = UsageStat(
                    formatCount(today.tokens), "Tokens",
                    listOfNotNull(maxTokens?.let { "of ${formatCount(it)}" }, "reached".takeIf { tokensReached }).joinToString(", ").ifEmpty { null },
                    attention.takeIf { tokensReached },
                ),
                cost = UsageStat(today.cost, "Estimated cost", today.costCaption, attention.takeIf { today.costReached }),
                onClick = navigation.onOpenUsage,
            )
            Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                today.reached?.let { InfoBanner(it, tone = StatusTone.ATTENTION, title = "Today's limit is reached") }
                if (!today.used && today.phoneNote == null) {
                    Text(
                        "Nothing used yet today. Models on this phone are free and not counted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                today.costNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                today.phoneNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                today.month?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                // The owner's rule: no "token" without plain words near it (D3A-7).
                Text(
                    if (today.used || today.month != null) "$TOKENS_IN_PLAIN_WORDS $COST_DISCLAIMER" else TOKENS_IN_PLAIN_WORDS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (today.unpriced.isNotEmpty()) {
                    // One model: straight to its page, where its price is entered; several: the Models list.
                    val single = today.unpriced.singleOrNull()
                    TextButton(
                        onClick = { if (single != null) navigation.onOpenModel(single.modelId) else navigation.onOpenModels() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (today.unpriced.size == 1) "Add its price" else "Add their prices")
                    }
                }
                TextButton(onClick = navigation.onOpenDailyLimits, modifier = Modifier.heightIn(min = 48.dp)) { Text("Daily limits") }
            }
        }
    }
}

