package com.aicontrolcenter.app.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.InstalledAppInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class RegisterAppUiState(
    val isLoading: Boolean = true,
    /** Installed, launchable apps not yet in the registry, after the search and system filter. */
    val apps: List<InstalledAppInfo> = emptyList(),
    /** How many unregistered apps exist before filtering, to tell "none" from "no match". */
    val totalCount: Int = 0,
    val query: String = "",
    val includeSystemApps: Boolean = false,
    val loadError: String? = null,
    /** Set once after a successful registration; the route opens the new entry. */
    val registeredId: AiAppId? = null,
    val isRegistering: Boolean = false,
    val message: UserMessage? = null,
)

/** Lets the owner add any installed app to the registry by hand. */
class RegisterAppViewModel(private val registry: AiAppRegistry) : ViewModel() {

    private val state = MutableStateFlow(RegisterAppUiState())
    val uiState: StateFlow<RegisterAppUiState> = state.asStateFlow()

    private var all: List<InstalledAppInfo> = emptyList()
    private var messageCounter = 0L

    init {
        load()
    }

    fun load() {
        state.update { it.copy(isLoading = true, loadError = null) }
        viewModelScope.launch {
            try {
                all = registry.unregisteredInstalledApps()
                state.update { it.copy(isLoading = false, totalCount = all.size).filtered() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state.update {
                    it.copy(isLoading = false, loadError = "The list of installed apps could not be read: ${failure.message ?: "unknown error"}")
                }
            }
        }
    }

    fun setQuery(query: String) = state.update { it.copy(query = query).filtered() }

    fun setIncludeSystemApps(include: Boolean) = state.update { it.copy(includeSystemApps = include).filtered() }

    fun register(packageName: String) {
        if (state.value.isRegistering) return
        state.update { it.copy(isRegistering = true) }
        viewModelScope.launch {
            try {
                val app = registry.registerManually(packageName)
                all = all.filterNot { it.packageName == packageName }
                state.update { it.copy(isRegistering = false, registeredId = app.id, totalCount = all.size).filtered() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalArgumentException) {
                show(refused.message ?: "That app cannot be registered.")
            } catch (failure: Exception) {
                show("Could not register the app: ${failure.message ?: "unknown error"}")
            }
        }
    }

    fun registeredHandled() = state.update { it.copy(registeredId = null) }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun show(text: String) =
        state.update { it.copy(isRegistering = false, message = UserMessage(text, ++messageCounter)) }

    private fun RegisterAppUiState.filtered(): RegisterAppUiState {
        val needle = query.trim()
        return copy(
            apps = all.filter { app ->
                (includeSystemApps || !app.isSystemApp) &&
                    (needle.isEmpty() || app.label.contains(needle, ignoreCase = true) || app.packageName.contains(needle, ignoreCase = true))
            },
        )
    }
}
