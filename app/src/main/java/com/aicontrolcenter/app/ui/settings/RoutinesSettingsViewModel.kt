package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One of the two master switches of Settings › Routines and folder pick-up. */
enum class MasterSwitch { ROUTINES, PICK_UP }

/** What Settings › Routines and folder pick-up shows. */
@Immutable
data class RoutinesSettingsUiState(
    val isLoading: Boolean = true,
    /** The build has routines (their port is not null): their switch and link show. */
    val routinesAvailable: Boolean = false,
    /** The build has folder pick-up: its switch and link show. */
    val pickUpAvailable: Boolean = false,
    val routinesOn: Boolean = false,
    val pickUpOn: Boolean = false,
    /** "No routines yet." / "2 routines, 1 of them switched on." */
    val routinesLine: String = "",
    /** "No folder pick-up rules yet." / "1 rule, switched on." */
    val pickUpLine: String = "",
    /** A switch the owner is switching on: asked once more, because work then runs while nobody watches. */
    val confirming: MasterSwitch? = null,
    val isSaving: Boolean = false,
    /** Why the last change was not saved. */
    val problem: String? = null,
)

/**
 * Settings › Routines and folder pick-up (Q1, Appendix A, Tier B; WP10c): the two master switches (`routinesEnabled`,
 * `folderPickUpEnabled`, both off after installing), how many routines and rules exist, and the links to their lists.
 * Switching either one on asks once more, because the app then acts while nobody watches (the owner's rule: confirmation
 * before consequential actions); switching off is at once.
 *
 * @param settings the owner's writer, for the two switches.
 * @param routines null when the build has none (then the routine part stays hidden); [pickUpRules] likewise.
 */
class RoutinesSettingsViewModel(
    private val settings: SettingsRepository,
    private val routines: OwnerRoutines?,
    private val pickUpRules: OwnerPickUpRules?,
) : ViewModel() {
    private data class Local(val confirming: MasterSwitch? = null, val isSaving: Boolean = false, val problem: String? = null)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<RoutinesSettingsUiState> = combine(
        settings.settings,
        routines?.observeAll() ?: flowOf(emptyList()),
        pickUpRules?.observeAll() ?: flowOf(emptyList()),
        local,
    ) { current, routineList, ruleList, local -> build(current, routineList, ruleList, local) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesSettingsUiState())

    private fun build(current: FrameworkSettings, routineList: List<Routine>, ruleList: List<PickUpRule>, local: Local) = RoutinesSettingsUiState(
        isLoading = false,
        routinesAvailable = routines != null,
        pickUpAvailable = pickUpRules != null,
        routinesOn = current.routinesEnabled,
        pickUpOn = current.folderPickUpEnabled,
        routinesLine = countLine(routineList.size, routineList.count { it.enabled }, "routine", "No routines yet."),
        pickUpLine = countLine(ruleList.size, ruleList.count { it.enabled }, "rule", "No folder pick-up rules yet."),
        confirming = local.confirming,
        isSaving = local.isSaving,
        problem = local.problem,
    )

    /** The owner flipped a master switch: on asks once more, off is saved at once. */
    fun request(which: MasterSwitch, on: Boolean) {
        if (on) local.update { it.copy(confirming = which, problem = null) } else store(which, false)
    }

    /** The owner confirmed switching [RoutinesSettingsUiState.confirming] on. */
    fun confirm() {
        val which = local.value.confirming ?: return
        local.update { it.copy(confirming = null) }
        store(which, true)
    }

    fun cancel() = local.update { it.copy(confirming = null) }

    private fun store(which: MasterSwitch, on: Boolean) {
        if (which == MasterSwitch.ROUTINES && routines == null) return
        if (which == MasterSwitch.PICK_UP && pickUpRules == null) return
        local.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            val problem = try {
                settings.update { if (which == MasterSwitch.ROUTINES) it.copy(routinesEnabled = on) else it.copy(folderPickUpEnabled = on) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "The change could not be saved. Try again."
            }
            local.update { it.copy(isSaving = false, problem = problem) }
        }
    }

    companion object {
        /** "No routines yet.", "1 routine, switched on.", "3 routines, 2 of them switched on." */
        fun countLine(count: Int, on: Int, noun: String, none: String): String = when {
            count == 0 -> none
            count == 1 -> if (on == 1) "1 $noun, switched on." else "1 $noun, switched off."
            on == count -> "$count ${noun}s, all switched on."
            on == 0 -> "$count ${noun}s, all switched off."
            else -> "$count ${noun}s, $on of them switched on."
        }

        /** What the routines switch does, in plain words. */
        const val ROUTINES_TEXT: String =
            "Off: no routine starts. On: each routine you made and switched on gives the team its objective at its time, " +
                "by itself, also while you are not using the phone. It uses your models and counts towards today's limits."

        /** What the folder pick-up switch does, in plain words. */
        const val PICK_UP_TEXT: String =
            "Off: nothing is picked up. On: about once a day, while the phone is charging and not in use, new text files in " +
                "the folders you chose are saved into a memory space, by themselves. Nothing is sent to a model by picking up."

        const val CONFIRM_ROUTINES: String =
            "Routines then start their objectives at their times by themselves, also while you are not looking at the phone. " +
                "They use your models and count towards today's limits. Every permission and every action that is hard to " +
                "undo is still asked for."

        const val CONFIRM_PICK_UP: String =
            "New text files in the folders you chose are then saved into memory by themselves, about once a day, while nobody " +
                "watches. Nothing is sent to a model by picking up, but agents that may read that memory space can recall " +
                "those files and send them to their model."
    }
}
