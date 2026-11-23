package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.agents.AgentTemplate
import com.aicontrolcenter.core.agents.label
import com.aicontrolcenter.framework.agents.AgentTemplates

/** Exposes the framework's built-in templates. They are static, so there is nothing to observe. */
class AgentTemplatesViewModel(agentManager: AgentManager) : ViewModel() {
    val templates: List<AgentTemplate> = agentManager.templates
}

@Composable
fun AgentTemplatesRoute(onBack: () -> Unit, onPickTemplate: (templateId: String) -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel { AgentTemplatesViewModel(graph.agentManager) }
    AgentTemplatesScreen(viewModel.templates, onBack, onPickTemplate)
}

/** First step of creating an agent: choose a starting point. Nothing is created until the editor saves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentTemplatesScreen(
    templates: List<AgentTemplate>,
    onBack: () -> Unit,
    onPickTemplate: (templateId: String) -> Unit,
) {
    ScreenScaffold(title = "New agent", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                Text(
                    text = "Choose a starting point. A template only pre-fills the editor; you can change everything before saving.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.xs),
                )
            }
            items(templates, key = { it.id }) { template ->
                AppCard(modifier = Modifier.fillMaxWidth(), onClick = { onPickTemplate(template.id) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar(template.title, template.draft.colorIndex)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(
                                // Its phone tools (files, documents, apps, the workspace) do not exist yet.
                                template.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                template.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                modifier = Modifier.padding(top = Spacing.xs),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                NeutralChip(template.draft.kind.label)
                                template.draft.skills.take(3).forEach { NeutralChip(it) }
                            }
                        }
                        Icon(
                            AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
