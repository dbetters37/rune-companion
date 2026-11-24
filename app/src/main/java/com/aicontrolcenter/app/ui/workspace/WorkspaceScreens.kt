package com.aicontrolcenter.app.ui.workspace

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.InlineTextEntry
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Description
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.icons.History
import com.aicontrolcenter.app.ui.icons.Import
import com.aicontrolcenter.app.ui.icons.Link
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.operator.RelativePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the workspace screens lead. */
class WorkspaceNavigation(
    val onBack: () -> Unit,
    /** Opens a folder below the one shown. */
    val onOpenFolder: (RelativePath) -> Unit,
    /** Goes to a folder above, from the path at the top. */
    val onOpenCrumb: (RelativePath) -> Unit,
    val onOpenFile: (RelativePath) -> Unit,
    val onOpenTrash: () -> Unit,
)

/** Hands a [ShareRequest] to Android's share sheet once, through the app's [com.aicontrolcenter.app.operator.SystemScreens]. */
@Composable
private fun ShareEffect(share: ShareRequest?, onHandled: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    LaunchedEffect(share) {
        share ?: return@LaunchedEffect
        onHandled()
        try {
            context.startActivity(graph.systemScreens.shareFile(share.file, share.mimeType, share.title))
        } catch (_: ActivityNotFoundException) {
            // No app can take it; the share sheet itself always exists, so this does not happen in practice.
        } catch (_: IllegalArgumentException) {
            // The file is outside what the share provider serves; nothing is shared.
        }
    }
}

/** The name the owner's document goes by, as its provider reports it. */
private fun displayNameOf(context: Context, uri: Uri): String {
    val name = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
    return name ?: uri.lastPathSegment ?: "Imported file"
}

@Composable
fun WorkspaceRoute(path: String, navigation: WorkspaceNavigation) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val viewModel = viewModel(key = "workspace:$path") { WorkspaceViewModel(graph.workspace, path, graph.activityRecorder, graph.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ShareEffect(state.share, viewModel::shareHandled)
    // Android's document picker: the owner chooses one file anywhere they can reach; a copy is made here.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.import(displayNameOf(context, uri)) { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri) } }
        }
    }
    WorkspaceScreen(
        state = state,
        navigation = navigation,
        actions = WorkspaceActions(
            onImport = {
                try {
                    importer.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    // No document picker on this phone: nothing to import from.
                }
            },
            onMakeFolder = viewModel::makeFolder,
            onRename = viewModel::rename,
            onShare = viewModel::share,
            onTrash = viewModel::trash,
            onMessageShown = viewModel::messageShown,
        ),
    )
}

/** What the folder screen can ask for. */
class WorkspaceActions(
    val onImport: () -> Unit,
    val onMakeFolder: (String) -> Unit,
    val onRename: (RelativePath, String) -> Unit,
    val onShare: (RelativePath) -> Unit,
    val onTrash: (RelativePath) -> Unit,
    val onMessageShown: () -> Unit,
)

/** One workspace folder: where it is, its files and folders, and what the owner can do with them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceScreen(state: WorkspaceUiState, navigation: WorkspaceNavigation, actions: WorkspaceActions) {
    var creatingFolder by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenScaffold(
        title = state.title,
        onBack = navigation.onBack,
        actions = {
            IconButton(onClick = navigation.onOpenTrash) {
                // A neutral count: something in the trash is normal, not an alarm.
                BadgedBox(
                    badge = {
                        if (state.trashCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ) { Text(state.trashCount.toString()) }
                        }
                    },
                ) {
                    Icon(
                        AppIcons.Delete,
                        contentDescription = "Trash" + when {
                            state.trashCount > 0 -> ", ${plural(state.trashCount, "item")}"
                            // Nothing to restore, but not empty: leftovers of an interrupted change wait to be cleared.
                            state.trashHasLeftovers -> ", leftovers to clear"
                            else -> ", empty"
                        },
                    )
                }
            }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "where") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Crumbs(state.crumbs, navigation.onOpenCrumb)
                    if (state.path.isRoot) {
                        Text(
                            "The agents' own folder inside this app. Every agent may read it; changing it needs your permission. " +
                                "Nothing here leaves the phone unless you share it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        OutlinedButton(onClick = actions.onImport, enabled = !state.isBusy && state.problem == null) {
                            Icon(AppIcons.Import, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Import a file", modifier = Modifier.padding(start = Spacing.sm))
                        }
                        OutlinedButton(onClick = { creatingFolder = true }, enabled = !state.isBusy && state.problem == null) {
                            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("New folder", modifier = Modifier.padding(start = Spacing.sm))
                        }
                    }
                    if (creatingFolder) {
                        InlineTextEntry(
                            title = "New folder",
                            label = "Folder name",
                            confirmLabel = "Create",
                            onConfirm = { name ->
                                creatingFolder = false
                                actions.onMakeFolder(name)
                            },
                            onDismiss = { creatingFolder = false },
                        )
                    }
                }
            }
            when {
                state.problem != null -> item(key = "problem") {
                    EmptyState(icon = AppIcons.Folder, message = state.problem, actionLabel = "Back", onAction = navigation.onBack)
                }
                state.entries.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = AppIcons.Folder,
                        title = if (state.path.isRoot) "The workspace is empty" else "This folder is empty",
                        message = if (state.path.isRoot) {
                            "Agents with workspace tools keep their files here. You can import a file too."
                        } else {
                            "Import a file, or let an agent write here."
                        },
                    )
                }
                else -> items(state.entries, key = { it.path.value }) { entry ->
                    if (renaming == entry.path.value) {
                        InlineTextEntry(
                            title = "Rename “${entry.name}”",
                            label = "New name",
                            confirmLabel = "Rename",
                            initial = entry.name,
                            onConfirm = { name ->
                                renaming = null
                                actions.onRename(entry.path, name)
                            },
                            onDismiss = { renaming = null },
                        )
                    } else {
                        EntryRow(
                            entry = entry,
                            enabled = !state.isBusy,
                            onOpen = { if (entry.isFolder) navigation.onOpenFolder(entry.path) else navigation.onOpenFile(entry.path) },
                            onRename = { renaming = entry.path.value },
                            onShare = { actions.onShare(entry.path) },
                            onTrash = { actions.onTrash(entry.path) },
                        )
                    }
                }
            }
        }
    }
}

/** Where the folder is: "Workspace › reports › 2025", each level a way back up. */
@Composable
private fun Crumbs(crumbs: List<Crumb>, onOpen: (RelativePath) -> Unit) {
    if (crumbs.size <= 1) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) Text("›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (index == crumbs.lastIndex) {
                Text(crumb.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = Spacing.md))
            } else {
                TextButton(onClick = { onOpen(crumb.path) }) { Text(crumb.title, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: WorkspaceEntryUi, enabled: Boolean, onOpen: () -> Unit, onRename: () -> Unit, onShare: () -> Unit, onTrash: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    AppCard(onClick = if (entry.isLink) null else onOpen, contentPadding = PaddingValues(start = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                when {
                    entry.isLink -> AppIcons.Link
                    entry.isFolder -> AppIcons.Folder
                    else -> AppIcons.Description
                },
                contentDescription = null,
                tint = if (entry.isFolder) appColors.info else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(AppIcons.MoreVert, contentDescription = "More for ${entry.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!entry.isLink) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    }
                    if (!entry.isFolder && !entry.isLink) {
                        DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                    }
                    DropdownMenuItem(text = { Text("Move to trash") }, onClick = { menu = false; onTrash() })
                }
            }
        }
    }
}

@Composable
fun WorkspaceFileRoute(path: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel(key = "workspace-file:$path") { WorkspaceFileViewModel(graph.workspace, path, graph.activityRecorder, graph.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ShareEffect(state.share, viewModel::shareHandled)
    LaunchedEffect(state.trashed) { if (state.trashed) onBack() }
    WorkspaceFileScreen(state, onBack, onShare = viewModel::share, onTrash = viewModel::trash, onMessageShown = viewModel::messageShown)
}

/** One file, read-only: its facts and, for text, its start exactly as stored. */
@Composable
fun WorkspaceFileScreen(state: WorkspaceFileUiState, onBack: () -> Unit, onShare: () -> Unit, onTrash: () -> Unit, onMessageShown: () -> Unit) {
    // Share is the button below the preview; one way to share, not two.
    ScreenScaffold(
        title = state.name,
        onBack = onBack,
        message = state.message,
        onMessageShown = onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.problem != null -> EmptyState(
                icon = AppIcons.Description,
                message = state.problem,
                actionLabel = "Back",
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AppCard { state.facts.forEach { (label, value) -> KeyValueRow(label, value) } }
                Column {
                    SectionHeader("Preview")
                    val preview = state.preview
                    if (preview?.text == null) {
                        InfoBanner(preview?.problem ?: "No preview for this kind of file. Share it to open it in an app that can.")
                    } else {
                        preview.partial?.let { InfoBanner(it + ". Share the file to see all of it.", modifier = Modifier.padding(bottom = Spacing.sm)) }
                        AppCard {
                            SelectionContainer {
                                Text(preview.text.ifEmpty { "(empty file)" }, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
                Button(onClick = onShare, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Share", modifier = Modifier.padding(start = Spacing.sm))
                }
                OutlinedButton(
                    onClick = onTrash,
                    enabled = !state.isBusy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Move to trash", modifier = Modifier.padding(start = Spacing.sm))
                }
                Text(
                    "Shown as stored, read-only: nothing in it is run or opened. Sharing hands the file to the app you pick, and to nothing else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun WorkspaceTrashRoute(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { WorkspaceTrashViewModel(graph.workspace, graph.activityRecorder, graph.clock) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    WorkspaceTrashScreen(state, onBack, viewModel::restore, { shown -> viewModel.emptyTrash(shown) }, viewModel::messageShown)
}

/** The workspace trash: restore an item, or empty it after confirming. [onEmpty] gets what the confirmation was shown for. */
@Composable
fun WorkspaceTrashScreen(
    state: WorkspaceTrashUiState,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onEmpty: (WorkspaceTrashUiState) -> Unit,
    onMessageShown: () -> Unit,
) {
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(title = "Workspace trash", onBack = onBack, message = state.message, onMessageShown = onMessageShown) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        "What you or agents moved to the trash, newest first. Restoring puts an item back where it was. Emptying deletes " +
                            "everything here for good; an agent can only ask you to do that.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Offered whenever anything is in the trash, also leftovers of an interrupted change that are not listed.
                    if (state.canEmpty) {
                        OutlinedButton(
                            onClick = { confirmEmpty = true },
                            enabled = !state.isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Empty the trash", modifier = Modifier.padding(start = Spacing.sm))
                        }
                    }
                }
            }
            if (state.items.isEmpty()) {
                item(key = "empty") {
                    val leftovers = state.leftoverBytes
                    if (leftovers == null) {
                        EmptyState(icon = AppIcons.Delete, title = "The trash is empty", message = "Files moved to the trash wait here until it is emptied.")
                    } else {
                        EmptyState(
                            icon = AppIcons.Delete,
                            title = "Nothing here can be restored",
                            message = "The trash still holds leftovers of a change that was interrupted${sizeOfLeftovers(leftovers)}. " +
                                "They count against the workspace's size until you empty the trash.",
                        )
                    }
                }
            } else if (state.leftoverBytes != null) {
                item(key = "leftovers") {
                    Text(
                        "The trash also holds leftovers of a change that was interrupted${sizeOfLeftovers(state.leftoverBytes)}. " +
                            "They cannot be restored; emptying the trash deletes them too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.items, key = { it.id }) { item ->
                AppCard(contentPadding = PaddingValues(start = Spacing.lg, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Icon(if (item.isFolder) AppIcons.Folder else AppIcons.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { onRestore(item.id) },
                            enabled = !state.isBusy,
                            modifier = Modifier.semantics { contentDescription = "Restore ${item.name}" },
                        ) {
                            Icon(AppIcons.History, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                            Text("Restore", modifier = Modifier.padding(start = Spacing.xs))
                        }
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        ConfirmDialog(
            title = "Empty the trash?",
            message = state.emptyingDeletes,
            confirmLabel = "Empty trash",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmEmpty = false
                // What this dialog named, and nothing that arrived in the trash after it was drawn.
                onEmpty(state)
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}
