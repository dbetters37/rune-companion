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
import kotlinx.coroutines.flow.flowOf
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.tools.ToolSpec

/** The device report is read on demand, so it has its own loading and failure states. */
sealed interface DeviceReportState {
    data object Loading : DeviceReportState
    data class Loaded(val report: DeviceReport) : DeviceReportState
    data class Failed(val reason: String) : DeviceReportState
}

/** What local-only mode blocks with the registry as it is now. */
@Immutable
data class LocalOnlyEffectUi(
    /** "Hosted API (cloud)", "Desk PC (local network)". */
    val providers: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    /** Agents whose every model is on a blocked provider, so they cannot run while the mode is on. */
    val agents: List<String> = emptyList(),
)

/** Facts about where memory lives and how it is searched, for the settings screen. */
@Immutable
data class MemoryInfoUi(
    /** How long a short-term memory lives, in whole days. */
    val shortTermDays: Int,
    /** True when semantic search computes its vectors on this phone. */
    val searchOnDevice: Boolean,
) {
    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun of(shortTermTtlMs: Long, embedderLocality: Locality) =
            MemoryInfoUi((shortTermTtlMs / DAY_MS).toInt().coerceAtLeast(1), embedderLocality == Locality.ON_DEVICE)
    }
}

@Immutable
data class SettingsUiState(
    val isLoading: Boolean = true,
    val settings: FrameworkSettings = FrameworkSettings(),
    val localOnlyEffect: LocalOnlyEffectUi = LocalOnlyEffectUi(),
    val device: DeviceReportState = DeviceReportState.Loading,
    val appVersion: String = "",
    val message: UserMessage? = null,
    val memory: MemoryInfoUi? = null,
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
    directory: RegistryDirectory? = null,
    private val tools: List<ToolSpec> = emptyList(),
    private val memoryInfo: MemoryInfoUi? = null,
) : ViewModel() {

    private data class Local(val device: DeviceReportState = DeviceReportState.Loading, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.settings,
        directory?.observe() ?: flowOf(null),
        local,
    ) { settings, snapshot, local ->
        SettingsUiState(
            isLoading = false,
            settings = settings,
            localOnlyEffect = snapshot?.localOnlyEffect() ?: LocalOnlyEffectUi(tools = blockedTools()),
            device = local.device,
            appVersion = appVersion,
            message = local.message,
            memory = memoryInfo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appVersion = appVersion, memory = memoryInfo))

    init {
        refreshDeviceReport()
    }

    /** The tools local-only mode blocks: those that send something off the phone or hand it to another app. */
    private fun blockedTools(): List<String> = tools.filter { it.sendsDataOffDevice }.map { it.title }

    /** Every enabled provider whose prompts leave the device, and the agents that would have no model left. */
    private fun RegistrySnapshot.localOnlyEffect(): LocalOnlyEffectUi {
        val blocked = providers.filter { it.enabled && it.effectiveLocality.leavesDevice }
        val blockedIds = blocked.map { it.id }.toSet()
        val blockedModels = models.filter { it.providerId in blockedIds }.map { it.id }.toSet()
        return LocalOnlyEffectUi(
            providers = blocked.map { "${it.name} (${if (it.effectiveLocality == Locality.CLOUD) "cloud" else "local network"})" },
            tools = blockedTools(),
            agents = agents.filter { entry ->
                val assigned = listOfNotNull(entry.profile.modelId) + entry.profile.fallbackModelIds
                assigned.isNotEmpty() && assigned.all { it in blockedModels }
            }.map { it.profile.name },
        )
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

    /** The phone operator's master switch: off, every phone tool is refused before anyone is asked. */
    fun setOperatorEnabled(enabled: Boolean) = change { operatorSwitch.set(enabled) }

    /** The same switch "Turn on" flips elsewhere: one path, one line in the activity log. */
    private val operatorSwitch = PhoneOperatorSwitch(settings, activity)

    /**
     * The second switch, for screen control (Android's accessibility service): off, no agent reads
     * or uses any app's screen, whatever else is allowed. It works only with [setOperatorEnabled] on.
     */
    fun setScreenControlEnabled(enabled: Boolean) = change {
        if (settings.get().accessibilityOperatorEnabled == enabled) return@change
        settings.update { it.copy(accessibilityOperatorEnabled = enabled) }
        activity.record(
            ActivityType.USER_ACTION,
            if (enabled) "Screen control turned on" else "Screen control turned off",
            if (enabled) {
                "Agents may see and use the screens of the apps you authorised, each with your permission."
            } else {
                "No agent can read or use any app's screen."
            },
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
        activity.record(ActivityType.USER_ACTION, LOG_CLEARED, "All earlier events were deleted at your request.")
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

    companion object {
        /** The log's first event after the owner cleared it; screens read it to know older records are gone (INT3-2). */
        const val LOG_CLEARED: String = "Activity log cleared"
    }
}
