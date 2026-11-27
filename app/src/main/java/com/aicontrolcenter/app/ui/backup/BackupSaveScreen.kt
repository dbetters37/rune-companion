package com.aicontrolcenter.app.ui.backup

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SecretTextField
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.recovery.SecureWindow
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.backup.BackupFormat
import com.aicontrolcenter.data.backup.BackupTexts
import com.aicontrolcenter.data.backup.DetailedOwnerBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Save a backup (Tier B; WP10b). FLAG_SECURE while shown (rule 27); the file through Android's document picker
 * (rule 21: "The app you choose may send it off the phone"). Reached from Settings › Backup only (rule 23: no intent).
 */
@Composable
fun BackupSaveRoute(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val backup = graph.ownerBackup
    if (backup == null) {
        NoBackupScreen("Save a backup", onBack)
        return
    }
    // The key derivation is CPU work of seconds to minutes: always on the background pool, never on the main thread.
    val viewModel = viewModel { BackupSaveViewModel(backup, work = Dispatchers.Default) }
    val name = BackupSaveViewModel.suggestedName(graph.clock.now(), graph.zone())
    BackupSaveScreen(viewModel, suggestedName = name, onBack = onBack)
}

/** The screen itself; [suggestedName] is what the picker offers to call the file. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSaveScreen(viewModel: BackupSaveViewModel, suggestedName: String, onBack: () -> Unit) {
    SecureWindow()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var noPicker by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)) { uri ->
        if (uri != null) viewModel.save(chosen = chosenFileOf(context.contentResolver, uri), staging = File(context.cacheDir, STAGING_NAME))
    }
    // While the ready backup goes into the chosen file (moments) there is nothing to stop: Back waits.
    BackHandler(enabled = state.working) { viewModel.askToStop() }
    ScreenScaffold(title = "Save a backup", onBack = { if (state.working) viewModel.askToStop() else onBack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .readableWidth()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val saved = state.saved
            if (saved != null) {
                SavedCard(saved, onDone = onBack)
                return@Column
            }
            WhatIsInCard()
            Column {
                SectionHeader("Passphrase")
                AppCard {
                    if (state.useOwn) {
                        OwnPassphrase(viewModel, enabled = !state.working)
                    } else {
                        GeneratedPassphrase(state, viewModel)
                    }
                }
            }
            val step = state.step
            if (state.working && step != null) {
                BackupStepCard(step, keepOpen = BackupTexts.KEEP_OPEN, cancellable = state.cancellable, onCancel = viewModel::askToStop)
            } else {
                state.failure?.let { InfoBanner(it, tone = StatusTone.ATTENTION, title = "Not saved") }
                if (noPicker) InfoBanner(NO_PICKER, tone = StatusTone.ATTENTION)
                InfoBanner(BackupTexts.CHOOSE_PLACE + " " + BackupTexts.KEEP_OPEN)
                Button(
                    onClick = {
                        try {
                            picker.launch(suggestedName)
                            noPicker = false
                        } catch (_: ActivityNotFoundException) {
                            noPicker = true
                        }
                    },
                    enabled = viewModel.canSave(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Choose where to save it") }
                if (!viewModel.canSave()) {
                    Text(
                        if (state.useOwn) SAVE_NEEDS_OWN else SAVE_NEEDS_TICK,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    // The question lives in the view model: a backup that gets ready while it is open waits for the answer (SEC-W3-1).
    if (state.stopAsked && state.cancellable) {
        ConfirmDialog(
            title = "Stop saving?",
            message = STOP_MESSAGE,
            confirmLabel = "Stop",
            dismissLabel = "Keep saving",
            onConfirm = viewModel::cancel,
            onDismiss = viewModel::keepSaving,
        )
    }
}

@Composable
private fun WhatIsInCard() {
    Column {
        SectionHeader("What is in the file")
        AppCard {
            Text(BackupTexts.WHAT_IS_IN, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                BackupTexts.WHAT_IS_NOT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Text(
                BackupTexts.OBJECTIVES_NOT_IN,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/** The generated passphrase, in its five groups, shown once; the owner ticks that they wrote it down. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneratedPassphrase(state: BackupSaveUiState, viewModel: BackupSaveViewModel) {
    val generated = state.generated ?: return
    val groups = generated.split('-')
    Text(BackupTexts.WRITE_IT_DOWN, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    FlowRow(
        modifier = Modifier
            .padding(vertical = Spacing.md)
            .clearAndSetSemantics { contentDescription = spokenPassphrase(groups) },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        groups.forEach { group ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    group,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
    }
    Text(
        "It uses only digits and capital letters, without I, L, O or U, so no two look alike. " + BackupTexts.PASSPHRASE_ENTRY,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .heightIn(min = 48.dp)
            .toggleable(value = state.wroteItDown, enabled = !state.working, role = Role.Checkbox, onValueChange = viewModel::setWroteItDown),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = state.wroteItDown, onCheckedChange = null, enabled = !state.working)
        Text(
            "I have written down this passphrase",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = Spacing.sm),
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TextButton(onClick = viewModel::newPassphrase, enabled = !state.working, modifier = Modifier.heightIn(min = 48.dp)) { Text("Make a new one") }
        TextButton(onClick = { viewModel.useOwn(true) }, enabled = !state.working, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Use my own passphrase")
        }
    }
}

/** The owner's own passphrase, typed twice in fields the keyboard does not learn, with the rule's plain reasons. */
@Composable
private fun OwnPassphrase(viewModel: BackupSaveViewModel, enabled: Boolean) {
    Text(BackupTexts.OWN_PASSPHRASE_RULE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    Column(Modifier.padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SecretTextField(
            state = viewModel.ownPassphrase,
            label = "Your passphrase",
            supportingText = typedCount(viewModel.ownPassphrase.text.length),
            errorText = viewModel.ownProblem(),
            enabled = enabled,
        )
        SecretTextField(
            state = viewModel.ownPassphraseAgain,
            label = "The same passphrase again",
            supportingText = typedCount(viewModel.ownPassphraseAgain.text.length),
            errorText = viewModel.againProblem(),
            enabled = enabled,
        )
    }
    Text(
        BackupTexts.PASSPHRASE_ENTRY + " " + OWN_WARNING,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm),
    )
    TextButton(onClick = { viewModel.useOwn(false) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Use a passphrase the app makes instead")
    }
}

@Composable
private fun SavedCard(saved: SavedUi, onDone: () -> Unit) {
    InfoBanner(
        title = "Backup saved",
        text = "The file holds ${saved.what.removeSuffix(".").replaceFirstChar { it.lowercase() }} (${saved.size}). " +
            "Keep it, and the passphrase, somewhere safe. The passphrase is not shown again.",
        tone = StatusTone.READY,
    )
    if (saved.notes.isNotEmpty()) {
        AppCard { NoteLines(saved.notes) }
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
}

/** Shown when this build has no backup (the screen is then not reachable from Settings). */
@Composable
internal fun NoBackupScreen(title: String, onBack: () -> Unit) {
    ScreenScaffold(title = title, onBack = onBack) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().padding(Spacing.screen), contentAlignment = Alignment.TopCenter) {
            InfoBanner("This version of the app has no backup.")
        }
    }
}

/** "20 characters typed" under a hidden field, so the owner can tell a slip without seeing the text. */
private fun typedCount(length: Int): String = when (length) {
    0 -> "Nothing typed yet"
    1 -> "1 character typed"
    else -> "$length characters typed"
}

/** What a screen reader says for the generated passphrase: each group spelled out, character by character. */
internal fun spokenPassphrase(groups: List<String>): String =
    "Passphrase, ${groups.size} groups: " + groups.joinToString(". ") { group -> group.toCharArray().joinToString(" ") }

/** The file the picker handed back: a plain file (a file address, as in tests) or a document of another app. */
internal fun chosenFileOf(resolver: ContentResolver, uri: Uri): ChosenBackupFile {
    val path = uri.path
    return if (uri.scheme == ContentResolver.SCHEME_FILE && path != null) ChosenPlainFile(File(path)) else ChosenDocument(resolver, uri)
}

/**
 * A document of another app (Files, Drive, …). It cannot be renamed over, so it is written only once the whole backup is
 * ready, and checked afterwards by the size its app reports.
 */
internal class ChosenDocument(private val resolver: ContentResolver, private val uri: Uri) : ChosenBackupFile {
    /**
     * Asks the document's app on a background thread. A Stop or the save's time limit does not wait for an app that
     * hangs (a cloud drive offline): the question is called off and the answer, if it ever comes, is dropped.
     */
    override suspend fun size(): Long? {
        val signal = CancellationSignal()
        val asked = CoroutineScope(Dispatchers.IO).async {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null, signal)?.use { row ->
                if (row.moveToFirst() && !row.isNull(0)) row.getLong(0) else null
            }
        }
        return try {
            asked.await()
        } catch (stopped: CancellationException) {
            runCatching { signal.cancel() }
            asked.cancel()
            throw stopped
        }
    }

    override suspend fun replaceWith(ready: File) {
        withContext(Dispatchers.IO) {
            val out = try {
                resolver.openOutputStream(uri, "wt")
            } catch (_: Exception) {
                null
            } ?: throw ReplaceFailed(chosenUntouched = true)
            try {
                out.use { stream ->
                    ready.inputStream().use { it.copyTo(stream) }
                    stream.flush()
                }
            } catch (_: IOException) {
                throw ReplaceFailed(chosenUntouched = false)
            }
            val written = try {
                size()
            } catch (_: Exception) {
                null
            }
            if (written != null && written != ready.length()) throw ReplaceFailed(chosenUntouched = false)
        }
    }

    override suspend fun remove() {
        withContext(Dispatchers.IO) { DocumentsContract.deleteDocument(resolver, uri) }
    }
}

/**
 * A plain file: the ready backup is copied next to it under a hidden name and then renamed over it in one step, so the
 * file is either the earlier one or the whole new backup, never half of one.
 */
internal class ChosenPlainFile(private val file: File) : ChosenBackupFile {
    override suspend fun size(): Long? = withContext(Dispatchers.IO) { if (file.isFile) file.length() else null }

    override suspend fun replaceWith(ready: File) {
        withContext(Dispatchers.IO) {
            val part = File(file.absoluteFile.parentFile, ".${file.name}.part")
            try {
                ready.copyTo(part, overwrite = true)
                try {
                    Files.move(part.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: IOException) {
                part.delete()
                throw ReplaceFailed(chosenUntouched = true)
            }
        }
    }

    override suspend fun remove() {
        withContext(Dispatchers.IO) { file.delete() }
    }
}

/** The app's private file a backup is written into before it goes into the chosen file (removed after every save). */
internal const val STAGING_NAME: String = "backup-being-saved.part"

const val STOP_MESSAGE: String = "The backup is not finished. If you stop now, nothing is saved. A file you chose to replace stays as it was."
const val SAVE_NEEDS_TICK: String = "Tick that you wrote the passphrase down first."
const val SAVE_NEEDS_OWN: String = "Type a passphrase that meets the rule above, twice the same."
const val OWN_WARNING: String = "Nobody can open the file without it, including you."
const val NO_PICKER: String = "This phone has no app to choose where files go, so the backup cannot be saved."
