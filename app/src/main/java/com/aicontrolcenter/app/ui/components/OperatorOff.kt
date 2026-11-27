package com.aicontrolcenter.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.tools.ToolSpec

/**
 * Saying that Phone operator is off where it matters, before work starts (the owner's own complaint: a task ran for
 * minutes and only at its end said so). The card shows on New objective and in a chat with an agent that has phone
 * tools; the line shows under a phone tool that was refused for this reason. "Turn on" goes through the owner's own
 * switch (Settings' path, with its line in the activity log), held by owner screens only.
 */
object OperatorOffTexts {
    const val CARD: String = "Phone operator is off. Agents can use their own workspace, but not your apps, folders or web pages."
    const val REFUSED: String = "Refused because Phone operator is off. Turn it on, then ask again."
    const val TURN_ON: String = "Turn on"
    const val OPEN_SETTINGS: String = "Open Settings"
    const val TURNED_ON: String = "Phone operator is on. Agents still ask your permission for each phone tool."

    /** True when [text] is a tool's refusal because Phone operator is off, in the gate's own words. */
    fun isRefusal(text: String): Boolean = OperatorGate.OPERATOR_OFF in text
}

/** Which tools need Phone operator on: every phone tool, not the agents' own workspace. */
object PhoneTools {
    /** From the catalogue of operator tools (tests and screens without the registry). */
    val CATALOGUE: (ToolId) -> Boolean = { id -> OperatorTools.info(id)?.capability?.let { it in OperatorGate.operatorCapabilities } == true }

    /** From the registered tools' specs: what the gate checks. */
    fun of(specs: List<ToolSpec>): (ToolId) -> Boolean {
        val phone = specs.filter { it.capability in OperatorGate.operatorCapabilities }.mapTo(HashSet()) { it.id }
        return { id -> id in phone || CATALOGUE(id) }
    }
}

/** The card: Phone operator is off, what that means, "Turn on" and the way to Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperatorOffCard(onTurnOn: (() -> Unit)?, onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
                Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = appColors.status(StatusTone.ATTENTION), modifier = Modifier.size(20.dp))
                Text(OperatorOffTexts.CARD, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (onTurnOn != null) {
                    Button(onClick = onTurnOn, modifier = Modifier.heightIn(min = 48.dp)) { Text(OperatorOffTexts.TURN_ON) }
                }
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(OperatorOffTexts.OPEN_SETTINGS) }
                }
            }
        }
    }
}

/** The line under a phone tool refused because Phone operator is off, with the same "Turn on". */
@Composable
fun OperatorRefusedLine(onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(StatusTone.ATTENTION.icon, contentDescription = null, tint = appColors.status(StatusTone.ATTENTION), modifier = Modifier.size(18.dp))
        Text(
            OperatorOffTexts.REFUSED,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onTurnOn, modifier = Modifier.heightIn(min = 48.dp).padding(start = 0.dp)) { Text(OperatorOffTexts.TURN_ON) }
    }
}
