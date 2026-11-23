package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Label
import com.aicontrolcenter.app.ui.icons.Link
import com.aicontrolcenter.app.ui.icons.Schedule
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryTier
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryHeader(detail: MemoryDetailUi, isBusy: Boolean, actions: MemoryDetailActions, onShare: () -> Unit) {
    val record = detail.record
    AppCard {
        Text(record.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            ScopeChip(detail.scope)
            KindChip(record.kind)
            if (record.tier == MemoryTier.SHORT_TERM) NeutralChip("Short-term", icon = AppIcons.Schedule)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ImportanceMeter(record.importance)
                Text(importanceLabel(record.importance), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (record.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                record.tags.sorted().forEach { NeutralChip(it, icon = AppIcons.Label) }
            }
        }
        Text(
            detail.scope.description + ". " + whoSees(detail.scope),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedButton(onClick = actions.onEdit, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(AppIcons.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Edit", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(onClick = onShare, enabled = !isBusy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Share to space", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

private fun whoSees(scope: ScopeUi): String = when (scope.kind) {
    ScopeKind.PRIVATE -> "No other agent can read it."
    ScopeKind.SHARED -> "Agents allowed to read this space can recall it."
    ScopeKind.TASK -> "Working memory for one task; it expires unless it is kept."
}

@Composable
internal fun ContentSection(detail: MemoryDetailUi) {
    Column {
        SectionHeader("What it says")
        AppCard {
            SelectionContainer {
                Text(detail.record.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
internal fun ProvenanceSection(provenance: ProvenanceUi, actions: MemoryDetailActions) {
    Column {
        SectionHeader("Where it came from")
        AppCard {
            Text(provenance.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            RowDivider(Modifier.padding(vertical = Spacing.sm))
            provenance.rows.forEach { row ->
                val target = row.target
                if (target == null) {
                    KeyValueRow(row.label, row.value)
                } else {
                    val open = when (target) {
                        is ProvenanceTarget.Agent -> ({ actions.onOpenAgent(target.id) })
                        is ProvenanceTarget.Conversation -> ({ actions.onOpenConversation(target.id) })
                    }
                    LinkedValueRow(row.label, row.value, onClick = open, clickLabel = if (target is ProvenanceTarget.Agent) "Open agent" else "Open chat")
                }
            }
            if (provenance.derivedFrom.isNotEmpty()) {
                RowDivider(Modifier.padding(vertical = Spacing.sm))
                Text("Derived from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                provenance.derivedFrom.forEach { source -> MemoryRefRow(source, prefix = null, onOpen = actions.onOpenMemory) }
            }
            if (provenance.history.isNotEmpty()) {
                RowDivider(Modifier.padding(vertical = Spacing.sm))
                Text("Since then", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                provenance.history.forEach { event -> HistoryRow(event, actions.onOpenMemory) }
            }
        }
    }
}

/** One later change: what was done, when, and (for a share or promotion) the memory it came from. */
@Composable
private fun HistoryRow(event: HistoryRowUi, onOpen: (com.aicontrolcenter.core.MemoryId) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(event.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(event.at, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        event.related?.let { MemoryRefRow(it, prefix = null, onOpen = onOpen) }
    }
}

/** A labelled fact that opens what it names. */
@Composable
private fun LinkedValueRow(label: String, value: String, onClick: () -> Unit, clickLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).opens(clickLabel, onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Row(Modifier.weight(0.58f), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Another memory as a row: opens it when it still exists, says so when it does not. */
@Composable
private fun MemoryRefRow(ref: MemoryRefUi, prefix: String?, onOpen: (com.aicontrolcenter.core.MemoryId) -> Unit, trailing: @Composable () -> Unit = {}) {
    val title = ref.title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (title != null) Modifier.opens("Open memory") { onOpen(ref.id) } else Modifier)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (prefix != null) {
                Text(prefix, style = MaterialTheme.typography.labelMedium, color = relationColor(prefix))
            }
            Text(
                title ?: "A memory that has since been deleted",
                style = MaterialTheme.typography.bodyMedium,
                color = if (title != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ref.scope?.let { ScopeChip(it) }
        }
        trailing()
        if (title != null) Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** Contradictions are the one relation the owner must never miss, so they carry the warning colour. */
@Composable
private fun relationColor(phrase: String) =
    if (phrase == LinkRelation.CONTRADICTS.label || phrase == LinkRelation.CONTRADICTS.inverseLabel) appColors.unavailable else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun LinksSection(detail: MemoryDetailUi, candidates: List<LinkCandidateUi>, isBusy: Boolean, actions: MemoryDetailActions) {
    var adding by rememberSaveable { mutableStateOf(false) }
    Column {
        SectionHeader("Linked memories", actionLabel = if (adding) null else "Add link", onAction = { adding = true })
        AppCard {
            if (detail.links.isEmpty() && !adding) {
                Text(
                    "Not linked to any other memory. Links say how memories relate (one supports, contradicts or replaces " +
                        "another) and draw the graph.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            detail.links.forEachIndexed { index, link ->
                if (index > 0) RowDivider()
                MemoryRefRow(link.other, prefix = link.phrase, onOpen = actions.onOpenMemory) {
                    // A shared copy is made by sharing and kept as provenance; only links the owner can make are removable.
                    if (link.relation != LinkRelation.SHARED_COPY_OF) {
                        IconButton(onClick = { actions.onUnlink(link.id) }, enabled = !isBusy) {
                            Icon(AppIcons.Close, contentDescription = "Remove link: ${link.phrase} ${link.other.title ?: "deleted memory"}")
                        }
                    }
                }
            }
            if (adding) {
                if (detail.links.isNotEmpty()) RowDivider(Modifier.padding(vertical = Spacing.sm))
                LinkComposer(
                    candidates = candidates,
                    isBusy = isBusy,
                    onLink = { id, relation ->
                        adding = false
                        actions.onLink(id, relation)
                    },
                    onCancel = { adding = false },
                )
            }
        }
    }
}

/**
 * Picks another memory by title and a relation, then links this memory to it. Only the best few
 * title matches are listed, so the picker stays short however many memories there are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkComposer(
    candidates: List<LinkCandidateUi>,
    isBusy: Boolean,
    onLink: (com.aicontrolcenter.core.MemoryId, LinkRelation) -> Unit,
    onCancel: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("") }
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    var relation by rememberSaveable { mutableStateOf(LinkRelation.RELATES_TO) }
    val words = filter.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
    val matches = candidates.filter { c -> words.all { it in c.title.lowercase(Locale.ROOT) } }.take(MAX_CANDIDATES)
    val chosen = candidates.firstOrNull { it.id.value == chosenId }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Link to another memory", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text("How does this memory relate to it?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            ManualLinkRelations.forEach { option ->
                FilterChip(selected = relation == option, onClick = { relation = option }, label = { Text(option.label) })
            }
        }
        if (chosen == null) {
            AppTextField(value = filter, onValueChange = { filter = it }, label = "Find a memory by title")
            if (candidates.isEmpty()) {
                Text("There is no other memory to link to yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (matches.isEmpty()) {
                Text("No memory title contains that.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            matches.forEach { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).opens("Choose") { chosenId = candidate.id.value }.padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(AppIcons.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ScopeChip(candidate.scope)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    Text("This memory ${relation.label.lowercase(Locale.ROOT)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(chosen.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                TextButton(onClick = { chosenId = null }) { Text("Change") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { chosen?.let { onLink(it.id, relation) } }, enabled = chosen != null && !isBusy) { Text("Link") }
        }
    }
}

private const val MAX_CANDIDATES = 6

@Composable
internal fun UseSection(detail: MemoryDetailUi) {
    val record = detail.record
    Column {
        SectionHeader("Use and history")
        AppCard {
            KeyValueRow("Recalled", if (record.accessCount == 0) "Not yet" else if (record.accessCount == 1) "Once" else "${record.accessCount} times")
            KeyValueRow("Last recalled", detail.lastUsed ?: "Never")
            KeyValueRow("Importance", String.format(Locale.ROOT, "%.0f%%", record.importance * 100))
            KeyValueRow("Confidence", String.format(Locale.ROOT, "%.0f%%", record.confidence * 100))
            KeyValueRow("Kept", if (record.tier == MemoryTier.LONG_TERM) "Long-term, until deleted" else "Short-term")
            detail.expires?.let { KeyValueRow("Expires", it) }
            KeyValueRow("Created", detail.created)
            KeyValueRow("Last changed", if (record.revision > 1) "${detail.updated} (revision ${record.revision})" else detail.updated)
        }
    }
}
