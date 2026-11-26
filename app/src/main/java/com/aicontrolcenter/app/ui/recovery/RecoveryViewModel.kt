package com.aicontrolcenter.app.ui.recovery

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.RecoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Recovery screen shows. */
@Immutable
data class RecoveryUiState(
    val isLoading: Boolean = false,
    /** Why the data cannot be opened (`DatabaseState.ownerText`). */
    val reason: String = "",
    /** What the owner can try first, for this reason. */
    val advice: String = "",
    /** True when this build can start over (an `OwnerRecovery` exists). */
    val canStartOver: Boolean = false,
    /** True while Start over runs. */
    val startingOver: Boolean = false,
    /** Why Start over changed nothing, as it said it. */
    val refusal: String? = null,
    /** The owner tapped Try again and the data still does not open. */
    val stillNotOpening: Boolean = false,
    /** Start over is done: the app starts again now (rule 30). */
    val restartNow: Boolean = false,
)

/**
 * The Recovery screen (R5, rules 9, 27, 30; WP10b): why the data cannot be opened (`DatabaseState.ownerText`), what to
 * try first, Start over with the typed word ([confirmWord], `OwnerRecovery.confirms`; what is set aside and what is reset,
 * in words; nothing is deleted), "Try again", "Close and try later". It holds the owner's recovery port only and never
 * anything of the app's agents: the Recovery path is reached before any of that is built (rule 9).
 */
class RecoveryViewModel(
    private val state: DatabaseState,
    private val recovery: OwnerRecovery?,
) : ViewModel() {
    /** "START OVER", typed in a field the keyboard does not learn (`SecretTextField(state = …)`, rule 27). */
    val confirmWord: TextFieldState = TextFieldState()

    private val current = MutableStateFlow(
        RecoveryUiState(reason = state.ownerText, advice = adviceFor(state), canStartOver = recovery != null),
    )

    val uiState: StateFlow<RecoveryUiState> = current.asStateFlow()

    /** True when the word typed is the confirming word (case and spaces at the ends do not matter). Reads the field now. */
    fun wordTyped(): Boolean = OwnerRecovery.confirms(confirmWord.text)

    /** The owner tapped Try again; if the screen comes back, the data still did not open. */
    fun triedAgain() {
        current.update { it.copy(stillNotOpening = true, refusal = null) }
    }

    /** Start over (rule 9), only once the word is typed. Done: the screen restarts the app; refused: its sentence. */
    fun startOver() {
        val way = recovery ?: return
        if (!wordTyped() || current.value.startingOver || current.value.restartNow) return
        current.update { it.copy(startingOver = true, refusal = null) }
        viewModelScope.launch {
            val result = try {
                way.startOver()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RecoveryResult.Refused(DID_NOT_WORK)
            }
            when (result) {
                is RecoveryResult.Done -> {
                    clearWord()
                    current.update { it.copy(startingOver = false, restartNow = true) }
                }
                is RecoveryResult.Refused -> current.update { it.copy(startingOver = false, refusal = result.reason) }
            }
        }
    }

    override fun onCleared() {
        clearWord()
    }

    private fun clearWord() {
        confirmWord.clearText()
        confirmWord.undoState.clearHistory()
    }

    companion object {
        const val TITLE: String = "The app cannot open your data"
        const val DID_NOT_WORK: String = "Start over did not work, so nothing was set aside. Restart the phone, then try again."

        /** What Start over does, in words: what is set aside, what is reset, what stays, and that nothing is deleted. */
        val WHAT_START_OVER_DOES: List<String> = listOf(
            "Your current data, the key that opens it and your saved provider keys are moved into a folder of their own in " +
                "the app's private storage. Nothing is deleted. The phone's Files app cannot see that folder, and " +
                "uninstalling the app removes it.",
            "The app then starts fresh, as on the first day, with the starter team. Your agents, chats, memories and " +
                "objectives stay in the set-aside copy, not in the fresh app. Enter your provider keys again.",
            "Set back to the start: the starter team and Getting started (set up again), the choice of Director, and the " +
                "Phone operator and Screen control switches (off). Folders you shared with the app are let go; share them " +
                "again if you want.",
            "Kept: your other settings, such as local-only mode and the daily limits.",
            "If you have a backup file, you can restore it into the fresh app from Settings › Backup.",
        )

        /** What to try first, for each reason. */
        fun adviceFor(state: DatabaseState): String = when (state) {
            DatabaseState.OK -> "Tap Try again to open the app."
            DatabaseState.KEY_MISSING, DatabaseState.KEY_DAMAGED ->
                "Without its key the app cannot read your data, and the key cannot be made again. Try again once; if it " +
                    "still does not open, Start over gives you a fresh app and keeps the old data aside."
            DatabaseState.KEYSTORE_UNAVAILABLE ->
                "This is often brief. Close the app, restart the phone, then open the app again. Start over only if it keeps happening."
            DatabaseState.NOT_A_DATABASE ->
                "The app cannot read the file as it is. Start over gives you a fresh app; the damaged file is kept aside, not deleted."
            DatabaseState.MIGRATION_FAILED ->
                "A newer version of the app may fix this: look for an update, then try again. Start over only if it keeps happening."
            DatabaseState.OTHER ->
                "Try again first, or restart the phone. Start over only if it keeps happening."
        }
    }
}
