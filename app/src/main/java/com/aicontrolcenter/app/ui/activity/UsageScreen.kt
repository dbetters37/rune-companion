@file:Phase6Stub("WP10a")

package com.aicontrolcenter.app.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub

/**
 * Activity › "Usage and cost" (goals 1-2, rules 7 and 29; WP10a): Today / 7 days / 30 days / this month; answers, tokens
 * and estimated cost by agent and by model; reliability in words; average time to first words and to the full answer
 * (models with 5 or more answers); how long requests waited for the owner; every row links to the log filtered; "estimated
 * from the prices you entered; your provider's bill is what counts" (`CostEstimate.DISCLAIMER`).
 */
@Composable
fun UsageTab(
    @Suppress("UNUSED_PARAMETER") viewModel: UsageViewModel,
    @Suppress("UNUSED_PARAMETER") navigation: ActivityNavigation,
    @Suppress("UNUSED_PARAMETER") onOpenLog: (agentId: com.aicontrolcenter.core.AgentId?, text: String?) -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
