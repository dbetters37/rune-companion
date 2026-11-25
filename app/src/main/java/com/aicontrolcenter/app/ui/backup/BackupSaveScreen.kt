@file:Phase6Stub("WP10b")

package com.aicontrolcenter.app.ui.backup

import androidx.compose.runtime.Composable
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.shell.StubScreen

/**
 * Save a backup (Tier B; WP10b). FLAG_SECURE while shown (rule 27); the file through Android's document picker
 * (rule 21: "The app you choose may send it off the phone"). Reached from Settings › Backup only (rule 23: no intent).
 */
@Composable
fun BackupSaveRoute(onBack: () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val backup = LocalAppGraph.current.ownerBackup
    StubScreen("Save a backup", onBack)
}
