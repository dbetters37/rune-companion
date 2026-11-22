package com.aicontrolcenter.app.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.device.DeviceReport
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The device report is read on demand, so it has its own loading and failure states. */
sealed interface DeviceReportState {
    data object Loading : DeviceReportState
    data class Loaded(val report: DeviceReport) : DeviceReportState
    data class Failed(val reason: String) : DeviceReportState
}

@Immutable
data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: FrameworkSettings = FrameworkSettings(),
    val device: DeviceReportState = DeviceReportState.Loading,
    val appVersion: String = "",
    val message: UserMessage? = null,
)

/**
 * Framework and privacy settings. Every change is written straight to the repository; there is
 * no "save" step, so what the screen shows is always what is stored.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val activityLog: ActivityLog,
    private val activity: ActivityRecorder,
    private val deviceInspector: DeviceInspector,
    private val appVersion: String,
) : ViewModel() {

    private data class Local(val device: DeviceReportState = DeviceReportState.Loading, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<SettingsUiState> = combine(settings.settings, local) { settings, local ->
        SettingsUiState(
            isLoading = false,
            settings = settings,
            device = local.device,
            appVersion = appVersion,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appVersion = appVersion))

    init {
        refreshDeviceReport()
    }

    fun setLocalOnlyMode(enabled: Boolean) = change {
        if (settings.get().localOnlyMode == enabled) return@change
        settings.update { it.copy(localOnlyMode = enabled) }
        activity.record(
            ActivityType.USER_ACTION,
            if (enabled) "Local-only mode turned on" else "Local-only mode turned off",
            if (enabled) "Nothing that sends data off this device may be used." else "Providers and tools that leave the device are allowed again.",
        )
    }

    /** [days] of history to keep; 0 keeps everything. Negative values are treated as 0. */
    fun setActivityRetentionDays(days: Int) = change {
        settings.update { it.copy(activityRetentionDays = days.coerceAtLeast(0)) }
    }

    /** Sets one numeric limit. Values outside the limit's range are brought into it. */
    fun setLimit(limit: OrchestrationLimit, value: Int) = change {
        settings.update { limit.applyTo(it, value) }
    }

    fun setAutoResumeInterruptedTasks(enabled: Boolean) = change {
        settings.update { it.copy(autoResumeInterruptedTasks = enabled) }
    }

    /** Deletes every recorded event, then records that this happened so the log never silently restarts. */
    fun clearActivityLog() = change {
        activityLog.clear()
        activity.record(ActivityType.USER_ACTION, "Activity log cleared", "All earlier events were deleted at your request.")
        show("The activity log was cleared.")
    }

    fun refreshDeviceReport() {
        local.update { it.copy(device = DeviceReportState.Loading) }
        viewModelScope.launch {
            val state = try {
                DeviceReportState.Loaded(deviceInspector.inspect())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DeviceReportState.Failed(failure.message ?: "The device could not be inspected.")
            }
            local.update { it.copy(device = state) }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text, ++messageCounter)) }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                show("The change could not be saved: ${failure.message ?: "storage error"}")
            }
        }
    }
}
