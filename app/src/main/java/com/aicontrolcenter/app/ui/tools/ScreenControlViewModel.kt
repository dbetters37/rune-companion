package com.aicontrolcenter.app.ui.tools

import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
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

/** The accessibility service's state for the setup steps: whether Android has it on, and what to do while it is not. */
@Immutable
data class ServiceStatusUi(
    val state: ScreenServiceState,
    /**
     * Switched on in Android's accessibility settings (connected or still starting). On Android 13 and later that
     * proves restricted settings were allowed: Android does not let the owner switch it on before.
     */
    val enabled: Boolean,
    /** One line for step 4 while the service is not connected; null once it is. */
    val hint: String?,
)

/** The five steps of setting up phone control, in the order the owner does them. */
enum class SetupStep(val title: String) {
    PHONE_OPERATOR("Turn on Phone operator"),
    SCREEN_CONTROL("Turn on Screen control"),
    RESTRICTED_SETTINGS("Allow restricted settings (Android blocks this for apps installed from a file)"),
    ACCESSIBILITY("Turn on AI Control Center in Accessibility"),
    APPS("Choose the apps it may use"),
    ;

    val number: Int get() = ordinal + 1
}

/** Whether a setup step is done, in the owner's words. */
enum class StepStatus(val label: String) {
    DONE("Done"),
    NOT_DONE("Not done"),

    /** Only "Allow restricted settings", below Android 13, where Android has no such block. */
    NOT_NEEDED("Not needed on this phone"),
}

/** One row of the setup checklist. */
@Immutable
data class SetupStepUi(
    val step: SetupStep,
    val status: StepStatus,
    /** What to do there, while the step is not done; null otherwise. */
    val hint: String?,
) {
    /**
     * What TalkBack reads for the row: "Step 1, Turn on Phone operator, Done". While it is not done the hint follows,
     * as a sighted owner reads it under the title.
     */
    val spoken: String get() = "Step ${step.number}, ${step.title}, ${status.label}" + (hint?.let { ". $it" } ?: "")
}

/** "Set up phone control" on top of Tools › Screen control: the five steps and how far the owner is. */
@Immutable
data class SetupChecklistUi(val steps: List<SetupStepUi>, val summary: String) {
    /** Every step is done, or not needed on this phone. */
    val allDone: Boolean get() = steps.none { it.status == StepStatus.NOT_DONE }

    fun of(step: SetupStep): SetupStepUi = steps.first { it.step == step }
}

/** The first Android version (13, API 33) that blocks accessibility for apps installed from a file until restricted settings are allowed. */
internal const val RESTRICTED_SETTINGS_FROM_SDK: Int = 33

/** Above the steps once every one is done. */
const val SETUP_ALL_SET: String = "All set. Ask the Operator agent to use an app."

/** Above the steps once every one is done, while the owner has pressed Stop. */
const val SETUP_ALL_SET_STOPPED: String = "All set, but screen control is stopped. Resume it below to use it."

/** The hint of step 3, "Allow restricted settings", on this app's App info page. */
const val RESTRICTED_SETTINGS_HINT: String = "Tap ⋮ at the top right, then Allow restricted settings."

/**
 * The setup checklist from what the screen already knows: the two switches in Settings, Android's accessibility
 * service ([service], from [serviceStatusOf] with its restricted-settings guess), the authorised apps and the Android
 * version ([sdkInt]). Android offers no way to read whether restricted settings were allowed, so step 3 counts as done
 * once the service is switched on, which Android allows only after it. [switchedOffNotice] (D7) says on step 4 why
 * Android switched the service off.
 */
fun setupChecklistOf(
    operatorEnabled: Boolean,
    screenControlEnabled: Boolean,
    service: ServiceStatusUi,
    apps: List<OperatorAppUi>,
    sdkInt: Int,
    stopped: Boolean = false,
    switchedOffNotice: String? = null,
): SetupChecklistUi {
    fun step(step: SetupStep, done: Boolean, hint: String?) =
        SetupStepUi(step, if (done) StepStatus.DONE else StepStatus.NOT_DONE, hint.takeUnless { done })

    val serviceOn = service.state == ScreenServiceState.READY
    val steps = listOf(
        step(SetupStep.PHONE_OPERATOR, operatorEnabled, "In Settings, switch on Phone operator."),
        step(
            SetupStep.SCREEN_CONTROL, screenControlEnabled,
            if (operatorEnabled) "In Settings, switch on Screen control." else "Do step 1 first, then switch on Screen control.",
        ),
        if (sdkInt < RESTRICTED_SETTINGS_FROM_SDK) {
            SetupStepUi(SetupStep.RESTRICTED_SETTINGS, StepStatus.NOT_NEEDED, hint = null)
        } else {
            step(SetupStep.RESTRICTED_SETTINGS, service.enabled, RESTRICTED_SETTINGS_HINT)
        },
        step(SetupStep.ACCESSIBILITY, serviceOn, switchedOffNotice?.takeUnless { serviceOn } ?: service.hint),
        step(
            SetupStep.APPS, apps.any { it.inEffect },
            if (apps.isEmpty()) "Pick at least one app agents may see and use." else "Authorise your app again below, so it is checked.",
        ),
    )
    val needed = steps.count { it.status != StepStatus.NOT_NEEDED }
    val done = steps.count { it.status == StepStatus.DONE }
    val summary = when {
        done < needed -> "$done of $needed done"
        stopped -> SETUP_ALL_SET_STOPPED
        else -> SETUP_ALL_SET
    }
    return SetupChecklistUi(steps, summary)
}

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
    /** "Set up phone control": the five steps, each done or not, on top of the tab. */
    val checklist: SetupChecklistUi = setupChecklistOf(false, false, service, apps, RESTRICTED_SETTINGS_FROM_SDK),
    val picker: AppPickerUi? = null,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
    /**
     * D7: Android switched screen control off (the app was stopped or force-stopped while the service was on), in the
     * device layer's words; null otherwise. Android's own rule: only the owner can switch it on again, in Android's
     * accessibility settings. Shown as the hint of the checklist's step 4, which opens them.
     */
    val switchedOffNotice: String? = null,
) {
    /** Everything needed for an agent to operate an authorised app, apart from its permission. */
    val ready: Boolean get() = service.state == ScreenServiceState.READY && operatorEnabled && screenControlEnabled && !stopped
}

/**
 * The generic D7 sentence, when the device layer gave none: the device's own wording for "stopped"
 * (`ScreenSwitchedOffSentences.STOPPED`; `SwitchedOffWordsTest` keeps the two in step), which cannot tell a force stop
 * from a swipe from Recents before Android 15.
 */
internal const val SWITCHED_OFF: String =
    "Android switched screen control off after the app was stopped. Turn it on again in Android's accessibility settings."

/**
 * The Screen control tab: the five steps of setting it up, each done or not ([SetupChecklistUi]), the Stop state with
 * Resume, the apps the owner authorised (each with its recorded signing certificate and which agents may see or use it),
 * and adding an installed app. Authorising records the app's signing certificate, so another app installed later under
 * the same name is not trusted.
 *
 * Only the owner reaches this screen: agents never operate this app, so they cannot press Resume.
 *
 * @param sdkInt the Android version (injected for tests of other versions): below 13, restricted settings do not exist.
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
    private val sdkInt: Int = Build.VERSION.SDK_INT,
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
        val service = serviceStatusOf(state)
        // Once the service is on again the notice is out of date (the device clears its flag then too, D3A-18c).
        val notice = switchedOff.takeIf { state != ScreenServiceState.READY }
        val shown = list.map { app ->
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
            }
        ScreenControlUiState(
            isLoading = false,
            service = service,
            operatorEnabled = settings.operatorEnabled,
            screenControlEnabled = settings.accessibilityOperatorEnabled,
            stopped = stopped,
            apps = shown,
            checklist = setupChecklistOf(
                operatorEnabled = settings.operatorEnabled,
                screenControlEnabled = settings.accessibilityOperatorEnabled,
                service = service,
                apps = shown,
                sdkInt = sdkInt,
                stopped = stopped,
                switchedOffNotice = notice,
            ),
            picker = local.picker?.let { picker ->
                // Already-authorised marks follow the list as it changes.
                picker.copy(apps = picker.apps.map { item -> if (list.any { it.packageName == item.packageName }) item.copy(note = "Authorised", selectable = false) else item })
            },
            isBusy = local.isBusy,
            message = local.message,
            switchedOffNotice = notice,
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

/**
 * The service's [state] for the setup steps: whether it is switched on in Android's settings, and step 4's hint while it
 * is not connected. [ScreenServiceState.RESTRICTED] is the device layer's guess that Android's restricted settings block
 * it (Android does not say for sure), so the hint sends the owner to step 3 first.
 */
fun serviceStatusOf(state: ScreenServiceState): ServiceStatusUi = when (state) {
    ScreenServiceState.CHECKING -> ServiceStatusUi(state, enabled = false, hint = "Checking Android's settings. This takes a moment.")
    ScreenServiceState.READY -> ServiceStatusUi(state, enabled = true, hint = null)
    ScreenServiceState.STARTING -> ServiceStatusUi(
        state, enabled = true,
        hint = "On in Android, but not connected yet. Switch “AI Control Center operator” off and on again.",
    )
    ScreenServiceState.NOT_ENABLED -> ServiceStatusUi(
        state, enabled = false,
        hint = "Find “AI Control Center operator” (often under Downloaded apps) and switch it on.",
    )
    ScreenServiceState.RESTRICTED -> ServiceStatusUi(
        state, enabled = false,
        hint = "Probably blocked by restricted settings: do step 3 first, then switch on “AI Control Center operator”.",
    )
    ScreenServiceState.ADVANCED_PROTECTION -> ServiceStatusUi(
        state, enabled = false,
        hint = "Android's Advanced Protection is on and keeps this off. Screen control cannot be used while it is on.",
    )
}
