package com.aicontrolcenter.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.tone
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Egress
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/** The inspector for one event: every recorded field, the structured data, and any egress. */
@Composable
fun EventDetailContent(detail: EventDetailUi, modifier: Modifier = Modifier) {
    val event = detail.event
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                event.type.icon,
                contentDescription = null,
                tint = event.severity.tone?.let { appColors.status(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Text(event.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            event.severity.tone?.let { StatusChip(event.severity.label, it) }
            event.egress?.let {
                TintedChip("Left device · ${it.locality.label}", appColors.locality(it.locality), icon = AppIcons.Egress)
            }
        }
        if (event.detail.isNotBlank()) {
            Text(
                event.detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }

        SectionHeader("Event", modifier = Modifier.padding(top = Spacing.sm))
        detail.fields.forEach { KeyValueRow(it.label, it.value, mono = it.technical) }

        SectionHeader("Structured data", modifier = Modifier.padding(top = Spacing.sm))
        if (detail.data.isEmpty()) {
            Text("None recorded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            detail.data.forEach { KeyValueRow(it.label, it.value, mono = true) }
        }

        SectionHeader("Information that left the device", modifier = Modifier.padding(top = Spacing.sm))
        val egress = detail.egress
        if (egress == null) {
            InfoBanner("Nothing left the device because of this event.")
        } else {
            egress.forEach { KeyValueRow(it.label, it.value, mono = it.technical) }
        }
    }
}
