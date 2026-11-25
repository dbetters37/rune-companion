package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.activity.moneyWords
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatTile
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.icons.Speed
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * The Today tile: answers, tokens and estimated cost today against today's limits, and this month; cost only when every
 * model used today has a price, otherwise which models have none. Opens Activity › Usage and cost
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
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 330.dp || LocalDensity.current.fontScale >= 1.5f
                val answers = StatValue(
                    formatCount(today.answers), "Answers",
                    maxCalls?.let { "of ${formatCount(it.toLong())}" },
                    maxCalls != null && today.answers >= maxCalls,
                )
                val tokens = StatValue(
                    formatCount(today.tokens), "Tokens",
                    maxTokens?.let { "of ${formatCount(it)}" },
                    maxTokens != null && today.tokens >= maxTokens,
                )
                val cost = StatValue(
                    today.cost ?: if (today.used) "No cost" else moneyWords(0, today.currency), "Estimated cost",
                    if (today.cost != null || !today.used) limits.maxSpendMicros?.let { "of ${moneyWords(it, today.currency)}" } else "a price is missing",
                    false,
                )
                val tiles = listOf(answers, tokens, cost)
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        tiles.forEach { TodayStat(it, Modifier.fillMaxWidth(), navigation.onOpenUsage) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        tiles.forEach { TodayStat(it, Modifier.weight(1f), navigation.onOpenUsage) }
                    }
                }
            }
            Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                today.reached?.let { InfoBanner(it, tone = StatusTone.ATTENTION, title = "Today's limit is reached") }
                if (!today.used) {
                    Text(
                        "Nothing used yet today. Models on this phone are free and not counted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                today.costNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                today.month?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                if (today.cost != null || today.month != null) {
                    Text(
                        "Costs are estimated from the prices you entered; your provider's bill is what counts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (today.unpriced.isNotEmpty()) {
                    TextButton(onClick = navigation.onOpenModels, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (today.unpriced.size == 1) "Add its price" else "Add their prices")
                    }
                }
                TextButton(onClick = navigation.onOpenDailyLimits, modifier = Modifier.heightIn(min = 48.dp)) { Text("Daily limits") }
            }
        }
    }
}

private data class StatValue(val value: String, val label: String, val caption: String?, val reached: Boolean)

@Composable
private fun TodayStat(stat: StatValue, modifier: Modifier, onClick: () -> Unit) {
    StatTile(
        value = stat.value,
        label = stat.label,
        icon = when (stat.label) {
            "Answers" -> AppIcons.Insights
            "Tokens" -> AppIcons.Speed
            else -> AppIcons.Schedule
        },
        modifier = modifier,
        onClick = onClick,
        caption = if (stat.reached) listOfNotNull(stat.caption, "reached").joinToString(", ") else stat.caption,
        captionColor = if (stat.reached) appColors.status(StatusTone.ATTENTION) else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
