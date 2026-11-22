package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.formatBytes
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Lightbulb
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.device.DeviceFeature
import com.aicontrolcenter.core.device.DeviceReport

/** What this phone can do: hardware, memory, storage, platform features and local-model guidance. */
@Composable
internal fun DeviceSection(state: DeviceReportState, onRefresh: () -> Unit) {
    Column {
        SectionHeader("Device", actionLabel = "Refresh", onAction = onRefresh)
        AppCard {
            when (state) {
                DeviceReportState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Reading this device…", style = MaterialTheme.typography.bodyMedium)
                }
                is DeviceReportState.Failed -> {
                    InfoBanner(title = "The device report is unavailable", text = state.reason, tone = StatusTone.ATTENTION)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
                is DeviceReportState.Loaded -> DeviceReportContent(state.report)
            }
        }
    }
}

@Composable
private fun DeviceReportContent(report: DeviceReport) {
    val guidance = report.localModelGuidance
    InfoBanner(
        icon = AppIcons.Lightbulb,
        title = "Local models: ${guidance.headline}",
        text = guidance.detail,
    )
    Subheading("Hardware")
    KeyValueRow("Device", "${report.manufacturer} ${report.model}")
    KeyValueRow("Android", "${report.androidRelease} (API ${report.sdkInt})")
    report.securityPatch?.let { KeyValueRow("Security patch", it) }
    listOfNotNull(report.socManufacturer, report.socModel).joinToString(" ").takeIf { it.isNotEmpty() }?.let {
        KeyValueRow("Chip", it)
    }
    if (report.cpuCores > 0) KeyValueRow("Processor cores", report.cpuCores.toString())
    if (report.supportedAbis.isNotEmpty()) KeyValueRow("Architectures", report.supportedAbis.joinToString(", "), mono = true)
    report.openGlEsVersion?.let { KeyValueRow("OpenGL ES", it) }

    Subheading("Memory")
    KeyValueRow("Installed memory", if (report.totalRamBytes > 0) formatBytes(report.totalRamBytes) else "Not reported")
    KeyValueRow("Free right now", if (report.totalRamBytes > 0) formatBytes(report.availableRamBytes) else "Not reported")
    if (report.isLowRamDevice) KeyValueRow("Low-memory device", "Yes")

    Subheading("Storage")
    KeyValueRow("Free space", if (report.storageTotalBytes > 0) formatBytes(report.storageFreeBytes) else "Not reported")
    KeyValueRow("Total space", if (report.storageTotalBytes > 0) formatBytes(report.storageTotalBytes) else "Not reported")

    if (report.features.isNotEmpty()) {
        Subheading("Features")
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.padding(top = Spacing.xs)) {
            report.features.forEach { FeatureRow(it) }
        }
    }
}

@Composable
private fun Subheading(text: String) {
    RowDivider(Modifier.padding(top = Spacing.md))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.xs),
    )
}

@Composable
private fun FeatureRow(feature: DeviceFeature) {
    val tone = if (feature.available) StatusTone.READY else StatusTone.DISABLED
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(
            tone.icon,
            contentDescription = if (feature.available) "Available" else "Not available",
            tint = appColors.status(tone),
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(feature.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (feature.detail.isNotBlank()) {
                Text(feature.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
