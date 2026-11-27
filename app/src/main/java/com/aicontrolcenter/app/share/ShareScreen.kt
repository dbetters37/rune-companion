package com.aicontrolcenter.app.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicontrolcenter.app.ui.approvals.previewAnnotated
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.ObscuredTouchGuard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.atMostChars
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.icons.Layers
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.icons.Privacy
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.icons.Shield
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/** What the owner can do on the share screen. */
class ShareActions(
    val onSelect: (ShareTarget) -> Unit,
    val onSave: () -> Unit,
    val onStop: () -> Unit,
    /** Closes the screen: "Don't save", Back, Close, Done. */
    val onClose: () -> Unit,
)

/** What the title field is called for screen readers (and tests). */
const val SHARE_TITLE_FIELD = "Title of what was shared"

/**
 * "Save to memory" (rule 34, Tier B; WP16): shown by [ShareReceiverActivity] outside navigation; [onDone] closes it.
 * Nothing is stored until the owner picks a place and taps Save; Back and "Don't save" close it with nothing stored.
 */
@Composable
fun ShareScreen(viewModel: ShareViewModel, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ShareContent(
        state,
        viewModel.title,
        ShareActions(onSelect = viewModel::select, onSave = viewModel::save, onStop = viewModel::stopSaving, onClose = onDone),
    )
}

/**
 * The share screen itself: what was shared (its size and how it starts, as quoted data), what was left out, the title,
 * where to save it (the spaces, then the workspace), the cost and privacy line, and Save. Touches are refused while
 * another app's window covers it, and Save is marked sensitive, as on the Approval screen: only the owner's own tap saves.
 */
@Composable
fun ShareContent(state: ShareUiState, title: TextFieldState, actions: ShareActions) {
    // While saving, Back stops the save (what it stored is taken back); otherwise Back closes with nothing stored.
    BackHandler(enabled = state.saving != null) { actions.onStop() }
    ScreenScaffold(
        title = ShareTexts.TITLE,
        onBack = if (state.saving != null) actions.onStop else actions.onClose,
    ) { padding ->
        ObscuredTouchGuard()
        when {
            state.isLoading -> Working(ShareTexts.READING, Modifier.padding(padding), onCancel = actions.onClose)
            state.done != null -> Saved(state.done, Modifier.padding(padding), onDone = actions.onClose)
            state.nothingToSave -> EmptyState(
                icon = AppIcons.Info,
                title = ShareTexts.NOTHING_TITLE,
                message = state.leftOut.joinToString("\n\n").ifEmpty { ShareIntake.NOTHING },
                actionLabel = ShareTexts.CLOSE,
                onAction = actions.onClose,
                modifier = Modifier.padding(padding),
            )
            else -> Form(state, title, actions, Modifier.padding(padding))
        }
    }
}

/** The screen while the share is read: a moving circle (a still clock under Calm) and Cancel. */
@Composable
internal fun Working(text: String, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        if (LocalCalmMotion.current) {
            Icon(AppIcons.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        } else {
            CircularProgressIndicator()
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = Spacing.touchTarget)) { Text(ShareTexts.DONT_SAVE) }
    }
}

@Composable
private fun Saved(done: ShareDone, modifier: Modifier, onDone: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).readableWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
    ) {
        Icon(AppIcons.StatusReady, contentDescription = null, tint = appColors.status(StatusTone.READY), modifier = Modifier.size(48.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(done.headline, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(done.detail, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) { Text(ShareTexts.DONE) }
    }
}

@Composable
private fun Form(state: ShareUiState, title: TextFieldState, actions: ShareActions, modifier: Modifier) {
    val busy = state.saving != null
    // At usual font sizes Save stays in view at the bottom; with large fonts the screen is one list, Save at its end, so
    // the pinned bar never takes half the screen (lists first when fonts are large).
    val pinned = LocalDensity.current.fontScale < LARGE_FONT
    Column(modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .readableWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (state.leftOut.isNotEmpty()) {
                InfoBanner(
                    title = if (state.leftOut.size == 1) "One part was left out" else "Some parts were left out",
                    text = state.leftOut.joinToString("\n"),
                    tone = StatusTone.ATTENTION,
                    icon = AppIcons.StatusAttention,
                )
            }
            WhatWasShared(state)
            InfoBanner(text = ShareTexts.AS_DATA, icon = AppIcons.Shield)
            if (state.asksTitle) {
                AppTextField(
                    state = title,
                    label = ShareTexts.TITLE_FIELD,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = SHARE_TITLE_FIELD },
                    supportingText = "What it is called in your memory. You can change it later.",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    inputTransformation = remember { atMostChars(ShareSaving.MAX_TYPED_TITLE_CHARS) },
                    enabled = !busy,
                )
            }
            WhereToSave(state, actions.onSelect, enabled = !busy)
            PrivacyAndCost(state.localOnly)
            if (!pinned) SaveArea(state, actions, pinned = false)
        }
        if (pinned) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column {
                    RowDivider()
                    Box(Modifier.readableWidth().padding(horizontal = Spacing.screen, vertical = Spacing.md)) {
                        SaveArea(state, actions, pinned = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatWasShared(state: ShareUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader("What was shared")
        // With one item its own size says it all; with several, the whole share's size comes first and each shows less.
        if (state.items.size > 1) Text(state.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Only the cards the screen shows are composed: never more than one share may bring in (SEC-W3-2).
        state.items.take(ShareIntake.MAX_ITEMS).forEach { item -> SharedItemCard(item, maxLines = if (state.items.size > 1) 4 else 8) }
        val more = state.items.size - ShareIntake.MAX_ITEMS
        if (more > 0) {
            Text("And ${"%,d".format(java.util.Locale.ENGLISH, more)} more.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One shared item: its name and size, and how it starts, quoted on a quiet ground (it is another app's text). */
@Composable
private fun SharedItemCard(item: SharedItemUi, maxLines: Int) {
    val markColor = appColors.attention
    val shown = remember(item.preview, markColor) { previewAnnotated("“" + item.preview + if (item.previewIsStart) "…”" else "”", markColor) }
    var cut by remember(item.preview, maxLines) { mutableStateOf(false) }
    AppCard(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(item.size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, appColors.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    // Always left to right: shared text that starts with right-to-left letters cannot reorder the line.
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> cut = layout.hasVisualOverflow },
                    )
                    Text(
                        if (item.previewIsStart || cut) "The start of it, as the other app wrote it." else "As the other app wrote it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WhereToSave(state: ShareUiState, onSelect: (ShareTarget) -> Unit, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(ShareTexts.WHERE)
        if (state.noSpaces) InfoBanner(text = ShareTexts.NO_SPACES)
        AppCard(contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.xs), modifier = Modifier.selectableGroup()) {
            state.targets.forEachIndexed { index, target ->
                if (index > 0) RowDivider()
                TargetRow(target, selected = target.target == state.selected, enabled = enabled, onSelect = { onSelect(target.target) })
            }
        }
    }
}

@Composable
private fun TargetRow(target: ShareTargetUi, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val isSpace = target.target is ShareTarget.Space
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Icon(
            if (isSpace) AppIcons.Layers else AppIcons.Folder,
            contentDescription = null,
            tint = if (isSpace) appColors.agentAccent(target.colorIndex) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (isSpace) "Memory space “${target.name}”" else target.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (target.detail.isNotEmpty()) {
                Text(target.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The plan's line on cost and privacy, and, when local-only mode is on, that recalled text stays on the phone. */
@Composable
private fun PrivacyAndCost(localOnly: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        InfoBanner(text = ShareTexts.STORING, icon = AppIcons.Privacy)
        if (localOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(AppIcons.OnDevice, contentDescription = null, tint = appColors.onDevice, modifier = Modifier.size(20.dp))
                Text(ShareTexts.LOCAL_ONLY_ON, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * What Save will do, why the last Save did not, and Save and "Don't save" (side by side when [pinned] at the bottom,
 * one under the other at the end of the list); while saving, the progress and "Stop saving".
 */
@Composable
private fun SaveArea(state: ShareUiState, actions: ShareActions, pinned: Boolean) {
    // Marked sensitive: on Android 14 and later only accessibility tools that help the owner can press it.
    val sensitive = Modifier.heightIn(min = Spacing.touchTarget).semantics { isSensitiveData = true }
    Column(verticalArrangement = Arrangement.spacedBy(if (pinned) Spacing.sm else Spacing.md)) {
        Text(
            state.saveLine ?: ShareTexts.CHOOSE_FIRST,
            style = if (pinned) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        state.problem?.let { InfoBanner(title = "Not saved", text = it, tone = StatusTone.ATTENTION, icon = AppIcons.StatusAttention) }
        val saving = state.saving
        if (saving != null) {
            if (saving.total > 0 || LocalCalmMotion.current) {
                LinearProgressIndicator(progress = { saving.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                (if (saving.total > 0) "Saving ${saving.done} of ${saving.total}…" else "Getting it ready…") +
                    " Stopping takes back what was saved so far.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(onClick = actions.onStop, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) { Text(ShareTexts.STOP) }
        } else if (pinned) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = actions.onClose, modifier = Modifier.weight(1f).heightIn(min = Spacing.touchTarget)) {
                    Text(ShareTexts.DONT_SAVE, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SaveButton(state.canSave, actions.onSave, sensitive.weight(1f))
            }
        } else {
            SaveButton(state.canSave, actions.onSave, sensitive.fillMaxWidth())
            OutlinedButton(onClick = actions.onClose, modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget)) {
                Text(ShareTexts.DONT_SAVE)
            }
            Text(
                "Nothing is saved until you tap ${ShareTexts.SAVE}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveButton(enabled: Boolean, onSave: () -> Unit, modifier: Modifier) {
    Button(onClick = onSave, enabled = enabled, modifier = modifier) {
        Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(ShareTexts.SAVE, modifier = Modifier.padding(start = Spacing.sm))
    }
}

/** From this font scale on, the screen is one list with Save at its end. */
private const val LARGE_FONT = 1.5f
