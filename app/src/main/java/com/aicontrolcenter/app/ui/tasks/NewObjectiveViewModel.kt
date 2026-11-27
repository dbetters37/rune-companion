package com.aicontrolcenter.app.ui.tasks

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.LIMIT_PAUSES_OBJECTIVES
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.work.NotificationAccess
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.app.ui.components.OperatorOffTexts
import com.aicontrolcenter.app.ui.components.PhoneTools
import com.aicontrolcenter.app.ui.settings.PhoneOperatorSwitch
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.app.usage.TodayUse
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StorageLevel
import com.aicontrolcenter.core.health.StorageWatch
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.app.ui.activity.moneyWords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Today's use against the daily limits, as the New objective form shows it. */
@Immutable
data class TodayLineUi(val text: String, val reached: Boolean, val note: String)

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
    /** "Today: 37 of 200 answers", with the other limits; null when no limit is set. */
    val today: TodayLineUi? = null,
    /** D27: the Director's model is rated Basic at reasoning, so planning may go wrong. */
    val reasoningWarning: String? = null,
    /** A3: Android may stop the work in the background, and why; null when nothing stands in the way. */
    val backgroundWarning: String? = null,
    /** R8: the phone is low on room (a warning) or almost full (no objective starts). */
    val storageWarning: String? = null,
    /** The phone is almost full: Start refuses. */
    val storageFull: Boolean = false,
    /**
     * Phone operator is off and an agent of the team has phone tools: said before work starts, with "Turn on" (the owner's
     * own complaint: a task ran for minutes and only at its end said so).
     */
    val operatorOff: Boolean = false,
    /** "Turn on" can switch it on here (the owner's switch is at hand). */
    val canTurnOnOperator: Boolean = false,
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
    /** Today's use as the daily limits count it (read only): "Today: 37 of 200 answers". */
    today: Flow<TodayUse> = flowOf(TodayUse.NONE),
    /** The phone's free room (R8): measured again at Start, which refuses when it is almost full. */
    private val storage: StorageWatch = StorageWatch.None,
    /** Whether Android lets the work go on in the background (A3). */
    background: Flow<BackgroundState> = flowOf(BackgroundState.UNKNOWN),
    /** The owner's Phone operator switch, for "Turn on"; owner UI only. Null: only the way to Settings is offered. */
    private val operatorSwitch: PhoneOperatorSwitch? = null,
    /** Which tools need Phone operator on (the registered tools' specs in the app). */
    private val isPhoneTool: (ToolId) -> Boolean = PhoneTools.CATALOGUE,
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

    private data class Phone(val today: TodayUse, val storage: StorageLevel, val background: BackgroundState)

    val uiState: StateFlow<NewObjectiveUiState> = combine(
        directory.observe(),
        memory?.observeSpaces() ?: flowOf(emptyList()),
        local,
        combine(today, storage.level, background, ::Phone),
    ) { snapshot, spaces, local, phone ->
        val director = directorOf(snapshot)
        val space = knowledgeSpaceOf(snapshot, spaces)
        NewObjectiveUiState(
            today = todayLineOf(phone.today),
            reasoningWarning = reasoningWarningOf(snapshot),
            backgroundWarning = backgroundWarningOf(phone.background),
            storageWarning = when (phone.storage) {
                // Under the banner's title "The phone is almost full": the body does not say it again (D3A-12a).
                StorageLevel.CRITICAL -> STORAGE_FULL_BANNER
                StorageLevel.LOW -> "The phone has little free room left. Objectives pause by themselves if it gets almost full."
                else -> null
            },
            storageFull = phone.storage == StorageLevel.CRITICAL,
            operatorOff = !snapshot.settings.operatorEnabled &&
                snapshot.agents.any { it.profile.enabled && it.profile.toolIds.any(isPhoneTool) },
            canTurnOnOperator = operatorSwitch != null,
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

    init {
        // R8: measure the phone's free room when the form opens (off the main thread in the device layer), so the
        // banner says how things are now rather than at the last check of the background service.
        viewModelScope.launch {
            try {
                storage.check()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Not measured: Start measures again and refuses only on an answer.
            }
        }
    }

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

    /** "Turn on" on the Phone operator card: the owner's switch, as in Settings (its line in the activity log). */
    fun turnOnOperator() {
        val switch = operatorSwitch ?: return
        viewModelScope.launch {
            try {
                switch.set(true)
                say(OperatorOffTexts.TURNED_ON)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                say(OPERATOR_NOT_TURNED_ON)
            }
        }
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
                // R8: measured now, not as last seen: an objective started on an almost full phone would only pause at once.
                if (storage.check() == StorageLevel.CRITICAL) {
                    fail(STORAGE_FULL)
                    return@launch
                }
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
                fail(if (refused.message == MaintenanceGate.RESTORING) RESTORING else refused.message ?: NO_DIRECTOR)
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

    /** D27: a Director whose model is rated Basic at reasoning plans less well. */
    private fun reasoningWarningOf(snapshot: RegistrySnapshot): String? {
        val entry = snapshot.director ?: return null
        val modelId = (entry.availability as? AgentAvailability.Ready)?.modelId ?: return null
        val model = snapshot.modelsById[modelId] ?: return null
        if (model.capabilities.reasoning != CapabilityTier.BASIC) return null
        return "${entry.profile.name} plans the work with ${model.displayName}, which is rated Basic at reasoning. Plans may miss steps " +
            "or go wrong. A model rated Good or Strong at reasoning plans better; you can change the rating or the model in Models."
    }

    companion object {
        const val MAX_LENGTH = 4_000

        /** Said when "Turn on" could not save the switch. */
        const val OPERATOR_NOT_TURNED_ON = "Phone operator could not be turned on. Try again, or turn it on in Settings."

        /** R8: what Start says, and the form shows, when the phone is almost full. */
        const val STORAGE_FULL = "The phone is almost full. Free some room before starting an objective."

        /** The form's banner under its title "The phone is almost full" (R8). */
        const val STORAGE_FULL_BANNER = "Free some room on the phone before starting an objective."

        /** What Start says while a backup is being restored. */
        const val RESTORING = "The app is restoring a backup. Start the objective once the restore has finished."

        /** "Today: 37 of 200 answers · 12,400 of 500,000 tokens · US$0.12 of US$1.00", or null without limits. */
        fun todayLineOf(today: TodayUse): TodayLineUi? {
            val limits = today.limits
            val use = today.use
            val parts = listOfNotNull(
                limits.maxCalls?.let { "${formatCount(use.calls)} of ${formatCount(it.toLong())} answers" },
                limits.maxTokens?.let { "${formatCount(use.tokens)} of ${formatCount(it)} tokens" },
                // The money limit is said even before any price exists, in the app's one currency, US$ (D3A-8).
                limits.maxSpendMicros?.let {
                    if (use.priced) "${moneyWords(use.spendMicros, ModelPrice.DEFAULT_CURRENCY)} of ${DailyLimits.money(it, ModelPrice.DEFAULT_CURRENCY)}"
                    else "${DailyLimits.money(it, ModelPrice.DEFAULT_CURRENCY)} (counted once you enter prices)"
                },
            )
            if (parts.isEmpty()) return null
            return TodayLineUi(
                text = "Today: " + parts.joinToString(" · "),
                reached = today.reached,
                note = if (today.reached) {
                    "Today's limit is reached: a new objective pauses at its first step. After midnight, or once you raise the " +
                        "limit, open it and tap Resume."
                } else {
                    "Answers and tokens (the pieces of text a model reads and writes) are counted for models off this phone. " +
                        LIMIT_PAUSES_OBJECTIVES
                },
            )
        }

        /** A3: what may stop the work while the owner is away, in plain words; null when nothing does. */
        fun backgroundWarningOf(state: BackgroundState): String? {
            if (state.allClear) return null
            val reasons = listOfNotNull(
                "notifications are off".takeIf { !state.notificationsAllowed },
                "battery saving applies to this app".takeIf { state.batteryOptimised == true },
                "background use is restricted for this app".takeIf { state.backgroundRestricted == true },
                "Android rarely lets this app run in the background".takeIf { state.standbyWarning },
            )
            return "When you leave the app, Android may stop the work: " + reasons.joinToString(", ") +
                ". The work goes on while the app is open. Settings › Working in the background shows how to fix each one."
        }
        private const val NO_DIRECTOR = "No Director is designated. Open Agents, choose an agent with the Director role and designate it."

        /** Why [text] cannot be given as an objective, or null when it can. */
        fun validate(text: String): String? = when {
            text.isBlank() -> "Describe what the team should achieve."
            text.trim().length > MAX_LENGTH -> "Keep it to ${formatCount(MAX_LENGTH.toLong())} characters or fewer (now ${formatCount(text.trim().length.toLong())})."
            else -> null
        }
    }
}
