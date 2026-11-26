package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tools.CapabilityConstraint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The accessibility service's state in plain words, with how to switch it on. */
@Immutable
data class ServiceStatusUi(
    val state: ScreenServiceState,
    val label: StatusLabel,
    /** What to do, in order; empty when there is nothing to do or nothing that can be done. */
    val steps: List<String>,
    /** Show the button to this app's App info page ("Allow restricted settings"). */
    val offerAppInfo: Boolean,
    /** Show the button to Android's accessibility settings. */
    val offerAccessibilitySettings: Boolean,
)

/** An app the owner authorised for screen control. */
@Immutable
data class OperatorAppUi(
    val packageName: String,
    val label: String,
    val authorised: String,
    /** Whether this exact app was checked (its signing certificate recorded), in words. */
    val certificate: String,
    /** The recorded certificate's fingerprint, for the owner who opens the details; null when none was recorded. */
    val fingerprint: String?,
    /** Agents can use it: its certificate was recorded. A row without one is not trusted by the operator. */
    val inEffect: Boolean,
    val agents: List<AgentAccessUi>,
    /** How often its screen actions ask the owner. */
    val policy: ScreenActionPolicy = ScreenActionPolicy.ASK_EVERY_ACTION,
)

/** The two ways an authorised app's screen actions can ask the owner, in the owner's words. */
object ScreenPolicyText {
    fun title(policy: ScreenActionPolicy): String = when (policy) {
        ScreenActionPolicy.ASK_EVERY_ACTION -> "Ask before every action"
        ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY -> "Ask only before buying, sending, deleting and similar"
    }

    fun description(policy: ScreenActionPolicy, app: String): String = when (policy) {
        ScreenActionPolicy.ASK_EVERY_ACTION ->
            "Every tap, typing, scroll, Back and Home in $app waits for your approval, and you see what $app shows at that " +
                "moment. The safest choice, and the one every app starts with."
        ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY ->
            "Other actions in $app run without asking. What asks is recognised by words on the control or its screen " +
                "(Buy, Send, Delete, OK under \"Delete 3 photos?\"…), a price, a code field or the keyboard's send key, in " +
                "English. A control worded some other way can be missed, so choose this only where a missed one does little harm."
    }
}

/** An installed app offered in the picker. */
@Immutable
data class InstalledAppUi(
    val packageName: String,
    val label: String,
    /** Why it cannot be picked, or that it is already authorised; null when it can be. */
    val note: String?,
    val selectable: Boolean,
)

/** The installed-app picker while it is open. */
@Immutable
data class AppPickerUi(val isLoading: Boolean = true, val apps: List<InstalledAppUi> = emptyList(), val failure: String? = null)

@Immutable
data class ScreenControlUiState(
    val isLoading: Boolean = true,
    val service: ServiceStatusUi = serviceStatusOf(ScreenServiceState.CHECKING),
    /** Settings › Phone operator. */
    val operatorEnabled: Boolean = false,
    /** Settings › Screen control. */
    val screenControlEnabled: Boolean = false,
    /** The owner pressed Stop; nothing is read or done until Resume. */
    val stopped: Boolean = false,
    val apps: List<OperatorAppUi> = emptyList(),
    val picker: AppPickerUi? = null,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
    /**
     * D7: Android switched screen control off (the app was stopped or force-stopped while the service was on), in the
     * device layer's words; null otherwise. Android's own rule: only the owner can switch it on again, in Android's
     * accessibility settings.
     */
    val switchedOffNotice: String? = null,
) {
    /** Everything needed for an agent to operate an authorised app, apart from its permission. */
    val ready: Boolean get() = service.state == ScreenServiceState.READY && operatorEnabled && screenControlEnabled && !stopped
}

/**
 * The generic D7 sentence, when the device layer gave none: the device's own wording for "stopped" (its watch is
 * internal to the device module), which cannot tell a force stop from a swipe from Recents before Android 15.
 */
internal const val SWITCHED_OFF: String =
    "Android switched screen control off after the app was stopped. Turn it on again in Android's accessibility settings."

/**
 * The Screen control tab: whether Android's accessibility service is on (and if not, the exact
 * steps to switch it on), the Stop state with Resume, the apps the owner authorised (each with
 * its recorded signing certificate and which agents may see or use it), and adding an installed
 * app. Authorising records the app's signing certificate, so another app installed later under
 * the same name is not trusted.
 *
 * Only the owner reaches this screen: agents never operate this app, so they cannot press Resume.
 */
class ScreenControlViewModel(
    serviceState: Flow<ScreenServiceState>,
    private val control: ScreenControl,
    settings: SettingsRepository,
    private val apps: OwnerOperatorApps,
    private val grants: OwnerGrants,
    directory: RegistryDirectory,
    private val installed: AiAppScanner,
    private val isProtected: (String) -> Boolean,
    private val familyOfTool: (ToolId) -> ToolFamily?,
    private val refreshService: () -> Unit,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * The AI app registry, whose own accessibility flag follows `operator_apps` for its channel
     * display only (nothing decides from it). Null where no registry is shown.
     */
    private val registry: AiAppRegistry? = null,
    /** Why Android switched screen control off, in the device layer's words (owner-only); null when it has none. */
    switchedOffSentence: StateFlow<String?> = MutableStateFlow(null),
) : ViewModel() {

    private data class Local(val picker: AppPickerUi? = null, val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val facts = combine(serviceState, control.stopped, settings.settings) { state, stopped, settings -> Triple(state, stopped, settings) }

    private val switchedOff = combine(control.switchedOffByAndroid, switchedOffSentence) { off, sentence ->
        if (off) sentence?.takeIf { it.isNotBlank() } ?: SWITCHED_OFF else null
    }

    val uiState: StateFlow<ScreenControlUiState> = combine(
        combine(facts, switchedOff, ::Pair), apps.observeAll(), grants.observeAll(), directory.observe(), local,
    ) { (facts, switchedOff), list, all, snapshot, local ->
        val (state, stopped, settings) = facts
        val now = clock.now()
        val agents = snapshot.agents.map { it.profile }
        ScreenControlUiState(
            isLoading = false,
            service = serviceStatusOf(state),
            operatorEnabled = settings.operatorEnabled,
            screenControlEnabled = settings.accessibilityOperatorEnabled,
            stopped = stopped,
            apps = list.map { app ->
                OperatorAppUi(
                    packageName = app.packageName,
                    label = app.label,
                    authorised = "Authorised ${formatCompactTimestamp(app.authorisedAt, now, zone)}",
                    certificate = if (app.signingCertSha256 != null) {
                        "Checked: this exact app. A look-alike app with the same name is not trusted."
                    } else {
                        "Not checked when it was authorised, so agents cannot use it. Authorise it again to check it."
                    },
                    fingerprint = app.signingCertSha256?.let { "Signing certificate fingerprint: ${fingerprint(it)}" },
                    inEffect = app.signingCertSha256 != null,
                    agents = agentAccessOf(agents, all, CapabilityConstraint.App(app.packageName), KIND, { familiesOf(it, familyOfTool) }, now),
                    policy = app.policy,
                )
            },
            picker = local.picker?.let { picker ->
                // Already-authorised marks follow the list as it changes.
                picker.copy(apps = picker.apps.map { item -> if (list.any { it.packageName == item.packageName }) item.copy(note = "Authorised", selectable = false) else item })
            },
            isBusy = local.isBusy,
            message = local.message,
            switchedOffNotice = switchedOff,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScreenControlUiState())

    init {
        refresh()
    }

    /** Reads Android's accessibility settings again (Android 9 to 12 send no signal when the owner switches the service on). */
    fun refresh() {
        try {
            refreshService()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The state shown stays what the service last reported.
        }
    }

    /** Stops every screen action at once, the one in progress included, until [resume]. */
    fun stop() = run {
        control.stop()
        "Stopped. No agent can read or use any app's screen until you resume."
    }

    /** Lets agents use authorised apps again, each still with its permission. */
    fun resume() = run {
        control.resume()
        "Resumed. Agents can use the authorised apps again, with your permission."
    }

    /** Opens the picker with the phone's launchable apps. */
    fun openPicker() {
        local.update { it.copy(picker = AppPickerUi()) }
        viewModelScope.launch {
            val picker = try {
                val list = installed.listInstalledApps()
                    .filter { it.packageName.isNotBlank() }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
                    .map { app ->
                        val protected = isProtected(app.packageName)
                        InstalledAppUi(
                            packageName = app.packageName,
                            label = app.label,
                            note = if (protected) "Never operated: it can change the phone's security" else null,
                            selectable = !protected,
                        )
                    }
                AppPickerUi(isLoading = false, apps = list)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppPickerUi(isLoading = false, failure = "The installed apps could not be read: ${failure.message ?: "unknown error"}")
            }
            local.update { if (it.picker == null) it else it.copy(picker = picker) }
        }
    }

    fun closePicker() = local.update { it.copy(picker = null) }

    /**
     * Authorises [packageName] with the signing certificate it has now. Refused when the
     * certificate cannot be read: without it, a different app installed later under the same
     * name could be trusted.
     */
    fun authorise(packageName: String, label: String) = run {
        local.update { it.copy(picker = null) }
        if (isProtected(packageName)) return@run "$label is never operated by agents: it can change the phone's security."
        val certificate = installed.inspect(packageName)?.signingCertSha256
            ?: return@run "$label could not be checked (its signing certificate could not be read), so it was not authorised. Is it still installed?"
        when (val result = apps.authorise(packageName, label, certificate)) {
            is OwnerResult.Done -> {
                syncRegistry(packageName, authorised = true)
                "$label is authorised and checked. Choose below which agents may see or use it."
            }
            is OwnerResult.Refused -> result.reason
        }
    }

    /** Sets how often [packageName]'s screen actions ask the owner. */
    fun setPolicy(packageName: String, policy: ScreenActionPolicy) = run {
        val app = apps.setPolicy(packageName, policy) ?: return@run "That app is not authorised any more."
        when (policy) {
            ScreenActionPolicy.ASK_EVERY_ACTION -> "Every action in ${app.label} asks you first now."
            ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY ->
                "Only actions recognised as buying, sending, deleting and similar ask you first in ${app.label} now."
        }
    }

    /** Removes every permission for the app, then takes it off the list. */
    fun withdraw(packageName: String) = run {
        if (!apps.withdraw(packageName)) return@run "That app was not authorised."
        syncRegistry(packageName, authorised = false)
        "Removed. Every permission for it was removed too, and no agent can operate it."
    }

    /** Keeps the registry's flag of an AI app with this package in step, for its display. A refusal there changes nothing here. */
    private suspend fun syncRegistry(packageName: String, authorised: Boolean) {
        val registry = registry ?: return
        for (app in registry.observeApps().first().filter { it.packageName == packageName && it.accessibilityAuthorized != authorised }) {
            try {
                registry.setAccessibilityAuthorized(app.id, authorised)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An app the registry no longer counts as installed keeps its flag; operator_apps decides.
            }
        }
    }

    fun setAccess(packageName: String, agentId: AgentId, level: AccessLevel) = run {
        applyAccess(grants, agentId, CapabilityConstraint.App(packageName), KIND, level) ?: when (level) {
            AccessLevel.NONE -> "No access any more. Its permissions for this app were removed."
            AccessLevel.READ -> "May now see this app's screen."
            AccessLevel.CHANGE -> "May now see and use this app's screen. Its actions still ask you as set for this app."
        }
    }

    /** Android could not open [what] (a settings page this phone does not have). */
    fun couldNotOpen(what: String) = local.update {
        it.copy(message = UserMessage("This phone could not open $what. Look for it in Android's Settings app.", ++messageCounter))
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun run(action: suspend () -> String) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val text = try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "The change could not be saved: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isBusy = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    companion object {
        val KIND = AccessKind(
            read = OperatorCapabilities.SCREEN_READ,
            change = OperatorCapabilities.SCREEN_OPERATE,
            family = ToolFamily.SCREEN,
            labels = listOf("No access", "See", "See and use"),
        )

        /** "AB:CD:EF:01 … 9A:BC", the start and end of a SHA-256 fingerprint, enough to compare by eye. */
        fun fingerprint(sha256: String): String {
            val pairs = sha256.uppercase().filter { it.isLetterOrDigit() }.chunked(2)
            return if (pairs.size <= 8) pairs.joinToString(":") else pairs.take(4).joinToString(":") + " … " + pairs.takeLast(2).joinToString(":")
        }
    }
}

/** The service's [state] for the owner, with the steps to switch it on. */
fun serviceStatusOf(state: ScreenServiceState): ServiceStatusUi = when (state) {
    ScreenServiceState.CHECKING -> ServiceStatusUi(
        state,
        StatusLabel("Checking", StatusTone.WAITING, "Looking up the service in Android's settings. This takes a moment."),
        steps = emptyList(), offerAppInfo = false, offerAccessibilitySettings = false,
    )
    ScreenServiceState.READY -> ServiceStatusUi(
        state,
        StatusLabel("Ready", StatusTone.READY, "The service is on. Agents can read and use only the apps listed below, each with your permission."),
        steps = emptyList(), offerAppInfo = false, offerAccessibilitySettings = false,
    )
    ScreenServiceState.STARTING -> ServiceStatusUi(
        state,
        StatusLabel(
            "Starting", StatusTone.WAITING,
            "The service is switched on in Android's settings but has not connected yet. If this does not change, switch it off and on again.",
        ),
        steps = listOf(
            "Tap “Open accessibility settings”.",
            "Open “AI Control Center operator”, switch it off, then on again.",
            "Come back here.",
        ),
        offerAppInfo = false, offerAccessibilitySettings = true,
    )
    ScreenServiceState.NOT_ENABLED -> ServiceStatusUi(
        state,
        StatusLabel("Off", StatusTone.INFO, "The service is off in Android's accessibility settings, so nothing can read or use any screen."),
        steps = listOf(
            "Tap “Open accessibility settings”.",
            "Find “AI Control Center operator” (often under “Downloaded apps” or “Installed apps”).",
            "Switch it on and confirm. Android warns that it can see and control the screen: here it does so only for the apps you authorise below.",
            "Come back here. The status changes to Ready.",
        ),
        offerAppInfo = false, offerAccessibilitySettings = true,
    )
    ScreenServiceState.RESTRICTED -> ServiceStatusUi(
        state,
        StatusLabel(
            "Blocked by restricted settings", StatusTone.ATTENTION,
            "Android blocks accessibility for apps installed from a file until you allow restricted settings for that app. This is a guess: Android does not say for sure.",
        ),
        steps = listOf(
            "Tap “Open accessibility settings”, find “AI Control Center operator” and try to switch it on. Android says the setting is restricted.",
            "Come back here and tap “Open App info”.",
            "Tap ⋮ at the top right, then “Allow restricted settings”, and confirm with your PIN, pattern or fingerprint.",
            "Open the accessibility settings again and switch “AI Control Center operator” on.",
            "Come back here. The status changes to Ready.",
        ),
        offerAppInfo = true, offerAccessibilitySettings = true,
    )
    ScreenServiceState.ADVANCED_PROTECTION -> ServiceStatusUi(
        state,
        StatusLabel(
            "Off: Advanced Protection", StatusTone.UNAVAILABLE,
            "Android's Advanced Protection is on, and it switches off services like this one. Screen control cannot be used while it is on. " +
                "Everything else in this app works as before.",
        ),
        steps = emptyList(), offerAppInfo = false, offerAccessibilitySettings = false,
    )
}
