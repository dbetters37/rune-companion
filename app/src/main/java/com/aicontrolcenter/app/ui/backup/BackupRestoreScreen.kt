package com.aicontrolcenter.app.ui.backup

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.health.RecoveryActions
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SecretTextField
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.recovery.SecureWindow
import com.aicontrolcenter.app.ui.recovery.findActivity
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.data.backup.BackupTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/**
 * Restore a backup (Tier B; WP10b). FLAG_SECURE while shown (rule 27); the file through Android's document picker.
 * Reached from Settings › Backup only (rule 23: no intent). First the file is opened and checked with its passphrase
 * (nothing written), then a summary says what the backup holds, every setting that would change and what is kept back;
 * only then can it be restored, after one more confirmation. Restored, the app starts again (rule 30).
 */
@Composable
fun BackupRestoreRoute(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val backup = graph.ownerBackup
    if (backup == null) {
        NoBackupScreen("Restore a backup", onBack)
        return
    }
    val busy = remember(graph) {
        combine(graph.orchestrator.running, graph.agentChat.liveTurns) { running, turns ->
            BackupRestoreViewModel.busyText(objectiveRunning = running.isNotEmpty(), chatAnswering = turns.isNotEmpty())
        }.distinctUntilChanged()
    }
    // The key derivation is CPU work of seconds to minutes: always on the background pool, never on the main thread.
    val viewModel = viewModel {
        BackupRestoreViewModel(backup, graph.registryDirectory, work = Dispatchers.Default, busy = busy, zone = graph.zone)
    }
    BackupRestoreScreen(viewModel, onBack = onBack)
}

/**
 * The screen itself. [restart] starts the app again once the restore is done (rule 30); tests replace it.
 */
@Composable
fun BackupRestoreScreen(
    viewModel: BackupRestoreViewModel,
    onBack: () -> Unit,
    restart: (android.app.Activity) -> Unit = { RecoveryActions.restartApp(it) },
) {
    SecureWindow()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var noPicker by rememberSaveable { mutableStateOf(false) }
    // Once: the restart finishes the activity, which disposes this screen, which must not start a second restart.
    val restarted = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val restartNow = rememberUpdatedState { if (restarted.compareAndSet(false, true)) context.findActivity()?.let(restart) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.fileChosen(open = { openForReading(resolver, uri) }, name = { displayName(resolver, uri) })
    }
    // Once restored, leaving the screen any way at all starts the app again: nothing goes on with the old data's state.
    BackHandler(enabled = state.restored != null) { restartNow.value() }
    BackHandler(enabled = state.working && state.cancellable) { viewModel.cancel() }
    val restoredNow = state.restored != null
    DisposableEffect(restoredNow) {
        onDispose { if (restoredNow) restartNow.value() }
    }
    ScreenScaffold(
        title = "Restore a backup",
        onBack = when {
            state.restored != null -> ({ restartNow.value() })
            state.working && !state.cancellable -> null
            else -> onBack
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val restored = state.restored
            if (restored != null) {
                RestoredCard(restored, onRestart = { restartNow.value() })
                return@Column
            }
            InfoBanner(BackupTexts.RESTORE_ONLY_FRESH + " " + BackupTexts.STARTER_REPLACED)
            state.busy?.let { InfoBanner(it, tone = StatusTone.ATTENTION, title = "Not now") }
            Column {
                SectionHeader("1. The backup file")
                AppCard {
                    Text(
                        state.fileName ?: "No file chosen yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (noPicker) {
                        InfoBanner(NO_OPEN_PICKER, tone = StatusTone.ATTENTION, modifier = Modifier.padding(top = Spacing.sm))
                    }
                    OutlinedButton(
                        onClick = {
                            try {
                                picker.launch(arrayOf("*/*"))
                                noPicker = false
                            } catch (_: ActivityNotFoundException) {
                                noPicker = true
                            }
                        },
                        enabled = !state.working,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm).heightIn(min = 48.dp),
                    ) {
                        Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(if (state.fileName == null) "Choose the backup file" else "Choose another file", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            Column {
                SectionHeader("2. Its passphrase")
                AppCard {
                    SecretTextField(
                        state = viewModel.passphrase,
                        label = "Passphrase of the backup",
                        supportingText = BackupTexts.PASSPHRASE_ENTRY,
                        enabled = !state.working,
                    )
                    Button(
                        onClick = viewModel::openBackup,
                        enabled = viewModel.canOpen(),
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md).heightIn(min = 48.dp),
                    ) { Text("Open the backup") }
                    Text(
                        "Opening checks the whole file and changes nothing on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
            val step = state.step
            if (state.working && step != null) {
                BackupStepCard(step, keepOpen = KEEP_OPEN_RESTORE, cancellable = state.cancellable, onCancel = viewModel::cancel)
            }
            state.failure?.let { InfoBanner(it, tone = StatusTone.ATTENTION, title = "Nothing was restored") }
            state.summary?.let { summary ->
                SummaryCards(summary)
                summary.refusal?.let { InfoBanner(it, tone = StatusTone.ATTENTION, title = "This backup cannot be restored now") }
                if (summary.refusal == null) {
                    Button(
                        onClick = { confirmRestore = true },
                        enabled = state.canRestore,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Restore this backup") }
                    Text(
                        BackupTexts.RESTARTS,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirmRestore) {
        ConfirmDialog(
            title = "Restore this backup?",
            message = BackupTexts.STARTER_REPLACED + " " + BackupTexts.KEYS_AGAIN + " " + BackupTexts.RESTARTS + " " + KEEP_OPEN_RESTORE,
            confirmLabel = "Restore",
            cautionary = true,
            onConfirm = {
                confirmRestore = false
                viewModel.restore()
            },
            onDismiss = { confirmRestore = false },
        )
    }
}

/** What the backup holds, every setting that would change, what is kept back and what is never in a backup. */
@Composable
private fun SummaryCards(summary: RestoreSummaryUi) {
    Column {
        SectionHeader("What the backup holds")
        AppCard {
            Text(summary.what, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${summary.madeOn} The file is ${summary.size}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            if (summary.notes.isNotEmpty()) NoteLines(summary.notes, Modifier.padding(top = Spacing.sm))
        }
    }
    Column {
        SectionHeader("Settings that change on this phone")
        AppCard {
            if (summary.changes.isEmpty()) {
                Text("None of this phone's settings change.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            summary.changes.forEachIndexed { index, change ->
                if (index > 0) RowDivider(Modifier.padding(vertical = Spacing.xs))
                Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = change.spoken }) {
                    Text(change.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Now: ${change.from} · After the restore: ${change.to}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                summary.alwaysOff,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
    if (summary.keptBack.isNotEmpty()) {
        Column {
            SectionHeader("Kept back from the backup")
            AppCard {
                Text(KEPT_BACK_INTRO, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                summary.keptBack.forEach { kept ->
                    RowDivider(Modifier.padding(vertical = Spacing.xs))
                    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = kept.spoken }) {
                        Text(kept.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "In the backup: ${kept.inFile} · After the restore: ${kept.afterRestore}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    Column {
        SectionHeader("Never in a backup")
        AppCard {
            Text(BackupTexts.WHAT_IS_NOT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                BackupTexts.KEYS_AGAIN + " " + BackupTexts.OBJECTIVES_NOT_IN,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
private fun RestoredCard(restored: RestoredUi, onRestart: () -> Unit) {
    InfoBanner(
        title = "Backup restored",
        text = "Restored: ${restored.what.removeSuffix(".").replaceFirstChar { it.lowercase() }}. The app starts again now so that it uses them.",
        tone = StatusTone.READY,
    )
    if (restored.notes.isNotEmpty()) AppCard { NoteLines(restored.notes) }
    Button(onClick = onRestart, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start the app again") }
}

private suspend fun openForReading(resolver: ContentResolver, uri: Uri): InputStream = withContext(Dispatchers.IO) {
    resolver.openInputStream(uri) ?: throw IOException("No stream")
}

/** The name the file's app gives it (read off the main thread by the view model). */
private fun displayName(resolver: ContentResolver, uri: Uri): String? {
    val name = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    return name ?: uri.lastPathSegment
}

const val KEEP_OPEN_RESTORE: String = "Keep the app open until the restore is done."
const val KEPT_BACK_INTRO: String = "These values in the backup are not taken as they are, to keep this phone as safe as it is now."
const val NO_OPEN_PICKER: String = "This phone has no app to choose files with, so a backup cannot be opened."
