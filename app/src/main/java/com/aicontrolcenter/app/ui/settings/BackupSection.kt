@file:Phase6Stub("WP10b")

package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aicontrolcenter.app.Phase6Stub

/** Settings › Backup (Tier B; WP10b): what a backup holds and does not, "Save a backup" ([onSaveBackup]) and "Restore from a backup" ([onRestoreBackup]). Shown only when the build has a backup (`graph.ownerBackup != null`). A slot of `SettingsScreen` (WP8 places it). Draws nothing until WP10b builds it. */
@Composable
fun BackupSection(
    @Suppress("UNUSED_PARAMETER") onSaveBackup: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onRestoreBackup: () -> Unit,
    @Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier,
) {
}
