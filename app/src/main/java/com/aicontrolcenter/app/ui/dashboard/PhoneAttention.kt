package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader

/**
 * The phone's attention items: notifications off, battery saving on, background restricted, a rare standby bucket
 * (Settings › Working in the background: navigation.onOpenSettings), storage low or critical, objectives paused by
 * today's limit (navigation.onOpenObjective, navigation.onOpenDailyLimits) and work waiting to try again. Draws nothing
 * while the phone needs nothing. A slot of `DashboardScreen`, right after the registry's attention list.
 */
@Composable
fun PhoneAttention(
    extras: DashboardExtrasViewModel,
    navigation: DashboardNavigation,
    modifier: Modifier = Modifier,
) {
    val state by extras.uiState.collectAsStateWithLifecycle()
    PhoneAttentionContent(state.phone, navigation, modifier)
}

@Composable
internal fun PhoneAttentionContent(items: List<PhoneItemUi>, navigation: DashboardNavigation, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier = modifier) {
        SectionHeader("On this phone · ${items.size}")
        AppCard(contentPadding = PaddingValues(0.dp)) {
            items.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                val target = item.target
                AttentionRow(
                    tone = item.tone,
                    title = item.title,
                    detail = item.detail,
                    onClick = target?.let {
                        {
                            when (it) {
                                PhoneTarget.Settings -> navigation.onOpenSettings()
                                PhoneTarget.DailyLimits -> navigation.onOpenDailyLimits()
                                is PhoneTarget.Objective -> navigation.onOpenObjective(it.id)
                            }
                        }
                    },
                    clickLabel = when (target) {
                        is PhoneTarget.Objective -> "Open the objective"
                        null -> null
                        else -> "Fix"
                    },
                )
            }
        }
    }
}
