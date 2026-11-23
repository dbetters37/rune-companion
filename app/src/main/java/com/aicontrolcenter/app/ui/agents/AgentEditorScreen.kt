package com.aicontrolcenter.app.ui.agents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.Agent
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentDraft

/** Everything the editor screen can ask for. Bundled so the screen's signature stays readable. */
class AgentEditorActions(
    val onEdit: ((AgentDraft) -> AgentDraft) -> Unit,
    val onAddResponsibility: (String) -> Unit,
    val onRemoveResponsibility: (String) -> Unit,
    val onAddSkill: (String) -> Unit,
    val onRemoveSkill: (String) -> Unit,
    val onSetModel: (ModelId?) -> Unit,
    val onAddFallback: (ModelId) -> Unit,
    val onRemoveFallback: (ModelId) -> Unit,
    val onSetTool: (ToolId, Boolean) -> Unit,
    val onSetTemperature: (Double?) -> Unit,
    val onSetMaxOutputTokens: (String) -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onClose: () -> Unit,
    val onMessageShown: () -> Unit,
    val onSetSpaceReadable: (SpaceId, Boolean) -> Unit = { _, _ -> },
    val onSetSpaceWritable: (SpaceId, Boolean) -> Unit = { _, _ -> },
    /** Opens Tools › Permissions for the agent being edited; null for an agent not created yet. */
    val onOpenPermissions: (() -> Unit)? = null,
)

private class SavedStateDraftStore(private val handle: SavedStateHandle) : DraftStore {
    override fun read(): String? = handle[KEY]
    override fun write(value: String?) {
        handle[KEY] = value
    }

    private companion object {
        const val KEY = "agent_draft"
    }
}

@Composable
fun AgentEditorRoute(
    agentId: AgentId?,
    templateId: String?,
    onClose: () -> Unit,
    onSaved: (AgentId, isNew: Boolean) -> Unit,
    onOpenPermissions: (AgentId) -> Unit = {},
) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        AgentEditorViewModel(
            agentManager = graph.agentManager,
            directory = graph.registryDirectory,
            editingId = agentId,
            templateId = templateId,
            draftStore = SavedStateDraftStore(createSavedStateHandle()),
            tools = graph.toolRegistry.all().map { it.spec },
            spaces = graph.memoryService.observeSpaces(),
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.savedAgentId) {
        state.savedAgentId?.let { saved ->
            viewModel.savedHandled()
            onSaved(saved, state.isNew)
        }
    }
    val actions = AgentEditorActions(
        onEdit = viewModel::edit,
        onAddResponsibility = viewModel::addResponsibility,
        onRemoveResponsibility = viewModel::removeResponsibility,
        onAddSkill = viewModel::addSkill,
        onRemoveSkill = viewModel::removeSkill,
        onSetModel = viewModel::setModel,
        onAddFallback = viewModel::addFallback,
        onRemoveFallback = viewModel::removeFallback,
        onSetTool = viewModel::setTool,
        onSetTemperature = viewModel::setTemperature,
        onSetMaxOutputTokens = viewModel::setMaxOutputTokensText,
        onSave = viewModel::save,
        onDiscard = viewModel::discard,
        onClose = onClose,
        onMessageShown = viewModel::messageShown,
        onSetSpaceReadable = viewModel::setSpaceReadable,
        onSetSpaceWritable = viewModel::setSpaceWritable,
        onOpenPermissions = agentId?.let { id -> { onOpenPermissions(id) } },
    )
    AgentEditorScreen(state, actions)
}

@Composable
fun AgentEditorScreen(state: AgentEditorUiState, actions: AgentEditorActions) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (state.isDirty) confirmDiscard = true else actions.onClose() }
    BackHandler(enabled = state.isDirty) { confirmDiscard = true }

    ScreenScaffold(
        title = if (state.isNew) "New agent" else "Edit agent",
        onBack = requestClose,
        actions = {
            if (!state.isLoading && !state.notFound) {
                TextButton(onClick = actions.onSave, enabled = !state.isSaving) { Text("Save") }
            }
        },
        message = state.message,
        onMessageShown = actions.onMessageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.notFound -> EmptyState(
                icon = AppIcons.Agent,
                message = "This agent no longer exists, so there is nothing to edit.",
                actionLabel = "Go back",
                onAction = actions.onClose,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .imePadding()
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                IdentityEditor(state, actions)
                InstructionsEditor(state, actions)
                ResponsibilitiesAndSkillsEditor(state, actions)
                ModelEditor(state, actions)
                ToolsEditor(state, actions)
                GenerationEditor(state, actions)
                MemoryEditor(state, actions)
                DelegationEditor(state, actions)
                Button(
                    onClick = actions.onSave,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (state.isNew) "Create agent" else "Save changes")
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = if (state.isNew) {
                "This agent has not been created yet. Leaving now discards what you entered."
            } else {
                "Your edits to this agent have not been saved."
            },
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                actions.onDiscard()
                actions.onClose()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}
