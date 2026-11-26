package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Import
import com.aicontrolcenter.app.ui.icons.Save
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.data.backup.BackupTexts

/**
 * Settings › Backup (Tier B; WP10b): what a backup holds and does not, "Save a backup" ([onSaveBackup]) and "Restore from
 * a backup" ([onRestoreBackup]). Shown only when the build has a backup (`graph.ownerBackup != null`). A slot of
 * `SettingsScreen` (WP8 places it). The two screens are reached from here only (rule 23: no intent opens them).
 */
@Composable
fun BackupSection(
    onSaveBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("Backup")
        AppCard {
            Text(BACKUP_INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                BackupTexts.KEYS_AGAIN,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            OutlinedButton(
                onClick = onSaveBackup,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Save, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Save a backup", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = onRestoreBackup,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp),
            ) {
                Icon(AppIcons.Import, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Restore from a backup", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                BackupTexts.RESTORE_ONLY_FRESH,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/** The section's first line. */
const val BACKUP_INTRO: String =
    "Keep your agents, providers, models, prices, memories and chats in one file, locked with a passphrase, to bring " +
        "them back on a new phone."
