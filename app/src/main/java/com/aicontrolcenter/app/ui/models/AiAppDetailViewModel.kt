package com.aicontrolcenter.app.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.ControlChannel
import com.aicontrolcenter.core.registry.LaunchResult
import com.aicontrolcenter.core.registry.controlChannels
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AiAppDetailUiState(
    val isLoading: Boolean = true,
    /** Null after loading means the app is not in the registry any more. */
    val app: AiApp? = null,
    /** Exactly what the framework may do with the app right now. */
    val channels: Set<ControlChannel> = emptySet(),
    /**
     * True when the owner authorised accessibility operation for this app but it is not in
     * [channels], because the operator switches are off. Shown so the owner's decision and its
     * actual effect are never confused.
     */
    val accessibilityAuthorisedButInactive: Boolean = false,
    /** Set once after this screen removed the app; the route leaves the screen. */
    val removed: Boolean = false,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
) {
    val canOpen: Boolean get() = ControlChannel.LAUNCH in channels
    val canHandOffText: Boolean get() = ControlChannel.INTENT_HANDOFF in channels
}

/**
 * One registered AI application. Everything that touches the other app goes through
 * [AiAppLauncher], which enforces the app's control channels and writes the activity entry;
 * this class never starts an activity itself and never claims more than those channels allow.
 */
class AiAppDetailViewModel(
    private val appId: AiAppId,
    private val registry: AiAppRegistry,
    private val launcher: AiAppLauncher,
    settings: SettingsRepository,
) : ViewModel() {

    private data class Local(val removed: Boolean = false, val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<AiAppDetailUiState> = combine(
        registry.observeApps().map { apps -> apps.firstOrNull { it.id == appId } },
        settings.settings,
        local,
    ) { app, settings, local ->
        val channels = app?.controlChannels(settings).orEmpty()
        AiAppDetailUiState(
            isLoading = false,
            app = app,
            channels = channels,
            accessibilityAuthorisedButInactive = app?.accessibilityAuthorized == true && ControlChannel.ACCESSIBILITY !in channels,
            removed = local.removed,
            isBusy = local.isBusy,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiAppDetailUiState())

    fun setEnabled(enabled: Boolean) = run { registry.setEnabled(appId, enabled) }

    /** Records the owner's word that an app found only by its name really is an AI application. */
    fun confirm() = run {
        registry.confirm(appId)
        show("Confirmed as an AI application.")
    }

    /** Records the owner's explicit decision. The warning dialog is the screen's responsibility. */
    fun setAccessibilityAuthorized(authorized: Boolean) = run {
        registry.setAccessibilityAuthorized(appId, authorized)
        show(if (authorized) "Accessibility operation authorised for this app." else "Accessibility authorisation revoked.")
    }

    fun saveNotes(notes: String) = run {
        registry.updateNotes(appId, notes.trim())
        show("Notes saved.")
    }

    fun remove() = run {
        registry.remove(appId)
        local.update { it.copy(removed = true) }
    }

    fun openApp() = run {
        val app = currentApp() ?: return@run
        when (val result = launcher.launch(app)) {
            LaunchResult.Started -> Unit
            is LaunchResult.NotPossible -> show(result.reason)
        }
    }

    /** Hands [text] to the app's own UI. Whatever the app replies stays in that app. */
    fun handOffText(text: String) = run {
        val app = currentApp() ?: return@run
        if (text.isBlank()) {
            show("Enter some text to send.")
            return@run
        }
        when (val result = launcher.handOffText(app, text)) {
            LaunchResult.Started -> show("Handed to ${app.label}. Its reply stays in that app.")
            is LaunchResult.NotPossible -> show(result.reason)
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    /** Read from the registry rather than from [uiState], which is only current while the screen collects it. */
    private suspend fun currentApp(): AiApp? {
        val app = registry.observeApps().first().firstOrNull { it.id == appId }
        if (app == null) show("This app is no longer in the registry.")
        return app
    }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text, ++messageCounter)) }

    /** Runs one action at a time and turns a refusal or failure into a message for the owner. */
    private fun run(action: suspend () -> Unit) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalArgumentException) {
                show(refused.message ?: "That is not possible for this app.")
            } catch (failure: Exception) {
                show("Something went wrong: ${failure.message ?: "unknown error"}")
            } finally {
                local.update { it.copy(isBusy = false) }
            }
        }
    }
}
