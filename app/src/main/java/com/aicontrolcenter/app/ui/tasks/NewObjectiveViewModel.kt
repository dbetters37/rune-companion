package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.work.NotificationAccess
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Orchestrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Director as the new-objective screen needs it: who it is, and whether it can take the objective. */
@Immutable
data class DirectorUi(val name: String, val colorIndex: Int, val canRun: Boolean, val problem: String?)

@Immutable
data class NewObjectiveUiState(
    val isLoading: Boolean = true,
    /** Problems with the text are shown once the owner has tried to start. */
    val showErrors: Boolean = false,
    val reviewResults: Boolean = true,
    val saveKnowledge: Boolean = false,
    /** Null when no Director is designated. */
    val director: DirectorUi? = null,
    /** The shared space the Director would save into; null when it may write none (the option is then off). */
    val knowledgeSpace: String? = null,
    val team: TeamDestinationsUi = TeamDestinationsUi(),
    val isStarting: Boolean = false,
    /** Set once the objective exists; the screen opens it. */
    val startedId: TaskId? = null,
    /** Explain the progress notification before Android asks for the permission. */
    val explainNotifications: Boolean = false,
    val message: UserMessage? = null,
) {
    val canStart: Boolean get() = !isLoading && !isStarting && director?.canRun == true

    /** What is wrong with [text] as an objective, once the owner has tried to start; null when nothing is (or not yet). */
    fun textError(text: CharSequence): String? = if (showErrors) NewObjectiveViewModel.validate(text.toString()) else null
}

/**
 * Gives the Director a new objective. Validation happens here; whether the Director can take it
 * is checked again by the orchestrator, whose refusal (in its own words) is shown as is.
 *
 * Before the first objective, Android 13 and later need permission for the progress
 * notification. The screen explains why first ([NewObjectiveUiState.explainNotifications]), asks,
 * and starts the objective whatever the answer.
 *
 * @param awaitStartup waits for start-up recovery, so a new objective is never mistaken for one
 *   the app was killed in the middle of.
 */
class NewObjectiveViewModel(
    private val orchestrator: Orchestrator,
    directory: RegistryDirectory,
    private val memory: MemoryService?,
    private val notifications: NotificationAccess,
    private val awaitStartup: suspend () -> Unit = {},
) : ViewModel() {

    private data class Local(
        val reviewResults: Boolean = true,
        val saveKnowledge: Boolean = false,
        val showErrors: Boolean = false,
        val isStarting: Boolean = false,
        val startedId: TaskId? = null,
        val explainNotifications: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /**
     * The objective being written. The field reads it and the keyboard writes it directly, so
     * every key lands in order however long the rest of this state takes to load. The counter
     * and the error read it too; nothing here ever writes it back while the owner types.
     */
    val objective = TextFieldState()

    val uiState: StateFlow<NewObjectiveUiState> = combine(
        directory.observe(),
        memory?.observeSpaces() ?: flowOf(emptyList()),
        local,
    ) { snapshot, spaces, local ->
        val director = directorOf(snapshot)
        val space = knowledgeSpaceOf(snapshot, spaces)
        NewObjectiveUiState(
            isLoading = false,
            showErrors = local.showErrors,
            reviewResults = local.reviewResults,
            saveKnowledge = local.saveKnowledge && space != null,
            director = director,
            knowledgeSpace = space,
            team = snapshot.teamDestinations(),
            isStarting = local.isStarting,
            startedId = local.startedId,
            explainNotifications = local.explainNotifications,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewObjectiveUiState())

    /** Replaces the objective from code (the field itself is typed into directly). */
    fun setText(value: String) = objective.putText(value)

    fun setReviewResults(value: Boolean) = local.update { it.copy(reviewResults = value) }

    fun setSaveKnowledge(value: Boolean) = local.update { it.copy(saveKnowledge = value) }

    /** The Start button: checks the text, then explains the notification if Android will ask, then starts. */
    fun start() {
        val current = local.value
        if (current.isStarting) return
        if (validate(objective.text.toString()) != null) {
            local.update { it.copy(showErrors = true) }
            return
        }
        val state = uiState.value
        val director = state.director
        if (director?.canRun != true) {
            say(director?.problem ?: NO_DIRECTOR)
            return
        }
        if (notifications.shouldAsk()) {
            local.update { it.copy(explainNotifications = true) }
            return
        }
        launchStart()
    }

    /** The owner read the explanation and Android is asking now; the start follows [notificationsAnswered]. */
    fun notificationsExplained() = local.update { it.copy(explainNotifications = false) }

    /** Android answered, or the owner chose "Not now": never asked again, and the objective starts either way. */
    fun notificationsAnswered() {
        notifications.markAsked()
        local.update { it.copy(explainNotifications = false) }
        launchStart()
    }

    fun startHandled() = local.update { it.copy(startedId = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun launchStart() {
        val current = local.value
        if (current.isStarting) return
        val text = objective.text.toString().trim()
        local.update { it.copy(isStarting = true) }
        val saveKnowledge = uiState.value.saveKnowledge
        viewModelScope.launch {
            try {
                awaitStartup()
                val started = orchestrator.startObjective(
                    text,
                    ObjectiveOptions(reviewResults = current.reviewResults, saveKnowledge = saveKnowledge),
                )
                objective.clearText()
                local.update { it.copy(isStarting = false, startedId = started.id, showErrors = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalStateException) {
                fail(refused.message ?: NO_DIRECTOR)
            } catch (invalid: IllegalArgumentException) {
                fail(invalid.message ?: "The objective is not valid.")
            } catch (failure: Exception) {
                fail("Could not start the objective: ${failure.message ?: "storage error"}")
            }
        }
    }

    private fun fail(text: String) {
        local.update { it.copy(isStarting = false, message = UserMessage(text, ++messageCounter)) }
    }

    private fun say(text: String) = local.update { it.copy(message = UserMessage(text, ++messageCounter)) }

    private fun directorOf(snapshot: RegistrySnapshot): DirectorUi? {
        val entry = snapshot.director ?: return null
        val name = entry.profile.name
        val problem = when (val availability = entry.availability) {
            is AgentAvailability.Ready -> null
            AgentAvailability.Disabled -> "The Director, $name, is switched off. Switch it on to give it an objective."
            is AgentAvailability.Unavailable -> "The Director, $name, cannot run right now. ${availability.detail}"
        }
        return DirectorUi(name, entry.profile.colorIndex, problem == null, problem)
    }

    /** The first shared space the Director's memory policy lets it write, which is where its summary would go. */
    private suspend fun knowledgeSpaceOf(snapshot: RegistrySnapshot, spaces: List<KnowledgeSpace>): String? {
        val service = memory ?: return null
        val profile = snapshot.director?.profile ?: return null
        val access = service.access(MemoryActor.Agent(profile))
        return spaces.firstOrNull { access.canWrite(MemoryScope.Shared(it.id)) }?.name
    }

    companion object {
        const val MAX_LENGTH = 4_000
        private const val NO_DIRECTOR = "No Director is designated. Open Agents, choose an agent with the Director role and designate it."

        /** Why [text] cannot be given as an objective, or null when it can. */
        fun validate(text: String): String? = when {
            text.isBlank() -> "Describe what the team should achieve."
            text.trim().length > MAX_LENGTH -> "Keep it to ${formatCount(MAX_LENGTH.toLong())} characters or fewer (now ${formatCount(text.trim().length.toLong())})."
            else -> null
        }
    }
}
