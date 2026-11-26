package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Lightbulb
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Getting started (Tier B, D47): what a model, a provider and an agent are (navigation.onOpenConcepts), adding a key
 * with plain notes on cheaper providers (navigation.onAddProvider), the daily limits (navigation.onOpenDailyLimits) and
 * the simulator (navigation.onTrySimulator: "Try with the simulator?" for the agents without a model; once every agent
 * has one, navigation.onOpenAgents). Hidden for good once the owner says done (`onboardingCompleted`). A slot of
 * `DashboardScreen`.
 */
@Composable
fun GettingStartedCard(
    extras: DashboardExtrasViewModel,
    navigation: DashboardNavigation,
    modifier: Modifier = Modifier,
) {
    val state by extras.uiState.collectAsStateWithLifecycle()
    val card = state.gettingStarted ?: return
    GettingStartedContent(card, navigation, onDone = extras::finishGettingStarted, modifier = modifier)
}

@Composable
internal fun GettingStartedContent(card: GettingStartedUi, navigation: DashboardNavigation, onDone: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier,
        containerColor = appColors.info.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface),
        borderColor = appColors.info.copy(alpha = 0.35f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Lightbulb, contentDescription = null, tint = appColors.info, modifier = Modifier.size(24.dp))
            Text(
                "Getting started",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            "Four things worth knowing before the agents work for you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        val trySimulator = navigation.onTrySimulator
        card.steps.forEachIndexed { index, step ->
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            // While some agent has no model, the simulator step asks right here, as the first-run notice does.
            val simulatorHere = step.target == GettingStartedTarget.AGENTS && trySimulator != null
            Step(index + 1, step, action = if (simulatorHere) SIMULATOR_ACTION else step.action) {
                when (step.target) {
                    GettingStartedTarget.CONCEPTS -> navigation.onOpenConcepts()
                    GettingStartedTarget.ADD_PROVIDER -> navigation.onAddProvider()
                    GettingStartedTarget.DAILY_LIMITS -> navigation.onOpenDailyLimits()
                    GettingStartedTarget.AGENTS -> if (trySimulator != null) trySimulator() else navigation.onOpenAgents()
                }
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp)) {
            Text("Done, hide this")
        }
    }
}

@Composable
private fun Step(number: Int, step: GettingStartedStepUi, action: String, onAction: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        // Done is said by the icon and in words, never by colour alone.
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(if (step.done) appColors.ready.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .semantics { contentDescription = if (step.done) "Step $number, done" else "Step $number" },
            contentAlignment = Alignment.Center,
        ) {
            if (step.done) {
                Icon(AppIcons.Check, contentDescription = null, tint = appColors.ready, modifier = Modifier.size(18.dp))
            } else {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                if (step.done) "${step.title} · done" else step.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(step.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

/** The simulator step's button while some agent has no model: it opens "Try with the simulator?". */
internal const val SIMULATOR_ACTION = "Try it now"
