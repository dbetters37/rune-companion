package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.aicontrolcenter.app.ui.components.textChanges
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.agents.summariseNames
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Groups
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.SpaceId

/** What the Spaces tab can ask for. */
class SpacesActions(
    val onStartCreate: () -> Unit,
    val onCreate: (name: String, description: String) -> Unit,
    val onCancelCreate: () -> Unit,
    val onClearCreateError: () -> Unit,
    val onOpenSpace: (SpaceId) -> Unit,
)

/** Shared knowledge spaces: how many memories each holds and which agents may use it. */
@Composable
internal fun SpacesTab(state: SpacesUiState, isCreating: Boolean, actions: SpacesActions) {
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item(key = "intro") {
            Text(
                "A space holds knowledge agents may share. Each agent reads or writes a space only when you allow it, " +
                    "and reading and writing are allowed separately. Private memories never appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isCreating) {
            item(key = "new-space") {
                NewSpaceForm(state.isCreating, state.createError, actions)
            }
        }
        if (state.spaces.isEmpty() && !isCreating) {
            item(key = "empty") {
                EmptyState(
                    icon = AppIcons.Groups,
                    title = "No shared spaces",
                    message = "Without a space every memory stays private to the agent that saved it. Create one to let " +
                        "chosen agents share what they know.",
                    actionLabel = "New space",
                    onAction = actions.onStartCreate,
                )
            }
        }
        items(state.spaces, key = { it.id.value }) { space -> SpaceCard(space, onClick = { actions.onOpenSpace(space.id) }) }
    }
}

@Composable
private fun SpaceCard(space: SpaceRowUi, onClick: () -> Unit) {
    val accent = appColors.agentAccent(space.colorIndex)
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(40.dp).background(accent.copy(alpha = 0.16f), MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Groups, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        space.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        plural(space.memoryCount, "memory", "memories"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (space.description.isNotBlank()) {
                    Text(
                        space.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AccessLine("Read", space.readers, emptyText = "No agent can read it yet")
                AccessLine("Write", space.writers, emptyText = "No agent can add to it")
            }
        }
    }
}

@Composable
private fun AccessLine(label: String, names: List<String>, emptyText: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
        Text(
            if (names.isEmpty()) emptyText else summariseNames(names),
            style = MaterialTheme.typography.bodySmall,
            color = if (names.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Asks for a new space's name and description in place, as a card at the top of the list
 * (the same pattern as other short entries in the app: the keyboard pushes the page up).
 */
@Composable
private fun NewSpaceForm(isSaving: Boolean, error: String?, actions: SpacesActions) {
    // Kept across rotation; the fields read and write these directly, so no key is lost.
    val name = rememberTextFieldState()
    val description = rememberTextFieldState()
    // A complaint about the name goes as soon as the name is changed.
    val currentError by rememberUpdatedState(error)
    val clearError by rememberUpdatedState(actions.onClearCreateError)
    LaunchedEffect(name) {
        name.textChanges().drop(1).collect { if (currentError != null) clearError() }
    }
    AppCard(borderColor = appColors.hairlineStrong) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text("New shared space", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            AppTextField(
                state = name,
                label = "Space name",
                placeholder = "Research notes",
                errorText = error,
            )
            AppTextField(
                state = description,
                label = "What belongs in it (optional)",
                singleLine = false,
                maxLines = 4,
            )
            Text(
                "No agent can use it until you allow it. You choose who reads and who writes next.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                TextButton(onClick = actions.onCancelCreate) { Text("Cancel") }
                Button(
                    onClick = { actions.onCreate(name.text.toString(), description.text.toString()) },
                    enabled = name.text.isNotBlank() && !isSaving,
                ) { Text("Create space") }
            }
        }
    }
}
