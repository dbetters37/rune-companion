package com.aicontrolcenter.app.ui.pickup

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Folder
import com.aicontrolcenter.app.ui.icons.Import
import com.aicontrolcenter.app.ui.routines.NotInThisVersion
import com.aicontrolcenter.app.ui.routines.WarningLine
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId

/** Where the Folder pick-up screens lead (supplied by the navigation shell). */
class PickUpNavigation(
    val onBack: () -> Unit,
    val onNewRule: (FolderId?) -> Unit = {},
    val onOpenRule: (PickUpRuleId) -> Unit = {},
    /** Tools › Folders, to share a folder or make one read-only. */
    val onOpenFolders: () -> Unit = {},
    val onOpenSpace: (SpaceId) -> Unit = {},
    /** Settings, where the master switch is. */
    val onOpenSettings: () -> Unit = {},
)

/** What the Folder pick-up list can do besides navigating. */
class PickUpRulesActions(
    val onSetEnabled: (PickUpRuleId, Boolean) -> Unit = { _, _ -> },
    val onMessageShown: () -> Unit = {},
)

/**
 * The owner's folder pick-up rules (Q1, Appendix A, Tier B; WP10c): each rule's folder and space, last run and its result
 * (sentences from `PickUpRunner`/`PickUpSafety`), the files it picked up or left out; the master switch's state; "files
 * already in the folder are left out, only text files arrive, at most 5 MB a day; nothing is sent to a model by picking
 * up". Hidden when the build has no pick-up.
 */
@Composable
fun PickUpRulesRoute(navigation: PickUpNavigation) {
    val ports = LocalAppGraph.current
    val rules = ports.ownerPickUpRules
    val imports = ports.pickUpImports
    if (rules == null || imports == null) {
        NotInThisVersion("Folder pick-up", navigation.onBack)
        return
    }
    val work = LocalScreenWork.current
    val viewModel = viewModel {
        PickUpRulesViewModel(rules, imports, ports.ownerFolders, ports.memoryService, ports.settingsView, ports.clock, ports.zone, work)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PickUpRulesScreen(state, navigation, PickUpRulesActions(viewModel::setEnabled, viewModel::messageShown))
}

@Composable
fun PickUpRulesScreen(state: PickUpRulesUiState, navigation: PickUpNavigation, actions: PickUpRulesActions = PickUpRulesActions()) {
    ScreenScaffold(title = "Folder pick-up", onBack = navigation.onBack, message = state.message, onMessageShown = actions.onMessageShown) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@ScreenScaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "intro") {
                Text(
                    "Folder pick-up saves new text files from a folder you shared into a memory space, by itself. Useful when " +
                        "another app exports notes into a folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!state.masterOn) {
                item(key = "master") {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        InfoBanner(
                            title = "Folder pick-up is switched off",
                            text = "Nothing is picked up while it is off. You switch it on in Settings, under Routines and folder pick-up.",
                            tone = StatusTone.ATTENTION,
                        )
                        OutlinedButton(onClick = navigation.onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Settings") }
                    }
                }
            }
            item(key = "header") { RulesHeader(state) { navigation.onNewRule(null) } }
            if (state.rules.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = AppIcons.Import,
                        title = "No rules yet",
                        message = "Share a folder in Tools, under Folders, make it read-only, then make a rule here for it.",
                    )
                }
            } else {
                items(state.rules, key = { it.id.value }) { rule -> RuleCard(rule, state.isBusy, navigation, actions) }
            }
            item(key = "facts") { PickUpFacts(navigation.onOpenFolders) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RulesHeader(state: PickUpRulesUiState, onNewRule: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionHeader("Your rules")
                Text(state.count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onNewRule, enabled = state.canAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("New rule", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (!state.canAdd) {
            Text(
                "You have the most rules there can be. Delete one to make another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One rule: where from, where to, how it last went, its newest files, and what can be done with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(rule: PickUpRuleUi, busy: Boolean, navigation: PickUpNavigation, actions: PickUpRulesActions) {
    AppCard(borderColor = if (rule.stoppedForSafety) appColors.status(StatusTone.ATTENTION).copy(alpha = 0.5f) else appColors.hairline) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(
                    "From “${rule.folder}” into “${rule.space}”",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(rule.status, rule.tone)
            }
            Text(rule.lastRun, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rule.lastResult?.let {
                if (rule.stoppedForSafety) {
                    WarningLine(it)
                } else {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (rule.files.isNotEmpty()) {
                RowDivider(Modifier.padding(vertical = Spacing.xs))
                Text("Newest files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                for (file in rule.files) FileRow(file)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OutlinedButton(onClick = { navigation.onOpenRule(rule.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
                if (!rule.enabled) {
                    Button(onClick = { actions.onSetEnabled(rule.id, true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (rule.stoppedForSafety) "Switch on again" else "Switch on")
                    }
                }
                TextButton(onClick = { navigation.onOpenSpace(rule.spaceId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open the space") }
            }
        }
    }
}

@Composable
private fun FileRow(file: PickedUpFileUi) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            if (file.leftOut) AppIcons.Info else AppIcons.Check,
            contentDescription = null,
            tint = if (file.leftOut) MaterialTheme.colorScheme.onSurfaceVariant else appColors.status(StatusTone.READY),
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(file.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What pick-up does and does not do, whatever the rule. */
@Composable
internal fun PickUpFacts(onOpenFolders: () -> Unit) {
    Column {
        SectionHeader("How pick-up works")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Fact(PickUpWords.READ_ONLY)
                Fact(PickUpWords.WHAT_ARRIVES)
                Fact(PickUpWords.WHEN)
                Fact(PickUpWords.NO_MODEL)
                OutlinedButton(onClick = onOpenFolders, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(AppIcons.Folder, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Open shared folders", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Fact(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
