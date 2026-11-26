package com.aicontrolcenter.app.ui.settings

import android.content.Intent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.di.wiring.BackgroundScreens
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.health.BackgroundHealth
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.device.health.BackgroundSettingsIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of Android's screens a button opens. */
enum class AndroidScreen {
    /** Android's "let this app run in the background" request (battery optimisation). */
    BATTERY,

    /** The app's page in Android's settings (notifications, background use, everything else). */
    APP_PAGE,
}

/** One thing Android decides about the app's background work, in words, with the button that fixes it. */
@Immutable
data class BackgroundItemUi(
    val key: String,
    val title: String,
    /** "Allowed", "Battery saving applies to the app", … */
    val status: String,
    /** True: fine; false: may stop the work; null: Android did not say. Shown with an icon and words, never colour alone. */
    val ok: Boolean?,
    /** What it means for the owner's agents. */
    val detail: String,
    /** The button that opens Android's screen for it, when one helps. */
    val fix: AndroidScreen? = null,
    val fixLabel: String? = null,
) {
    val tone: StatusTone get() = when (ok) {
        true -> StatusTone.READY
        false -> StatusTone.ATTENTION
        null -> StatusTone.INFO
    }

    /** What a screen reader says for the row, as one phrase. */
    val spoken: String get() = "$title: $status. $detail"
}

/** What the Working in the background section shows. */
@Immutable
data class BackgroundSectionUiState(
    val isLoading: Boolean = true,
    /** True when every known item lets agents work with the screen off. */
    val allClear: Boolean = true,
    val summary: String = "",
    val items: List<BackgroundItemUi> = emptyList(),
    val keepAwake: Boolean = true,
    val notifyWhenWorkEnds: Boolean = true,
    /** True while Android is being asked again. */
    val checking: Boolean = false,
    /** A sentence after a tap (a screen Android does not have, a change not saved). */
    val message: String? = null,
    /** After a missing screen: the other button to offer. */
    val offer: AndroidScreen? = null,
)

/**
 * Settings › Working in the background (A3; WP10b): whether Android lets the app work in the background (notifications,
 * battery optimisation, background restriction, standby bucket), each with its button to Android's own screen (the
 * screen reads Android again when it comes back: [refresh]; [BackgroundSettingsIntents.NO_SUCH_SCREEN] when a screen is
 * missing), and the switches "Keep the phone awake while agents work" and "Tell me when work ends". Nothing here changes
 * what Android allows: the owner decides on Android's screens.
 *
 * The words match the Dashboard's phone items ("battery saving", "background use is restricted", "Android rarely lets
 * it run"), which lead here.
 *
 * @param settings the owner's writer, for the two switches.
 */
class BackgroundSectionViewModel(
    private val health: BackgroundHealth,
    private val screens: BackgroundScreens,
    private val settings: SettingsRepository,
) : ViewModel() {
    private data class Status(val checking: Boolean = false, val message: String? = null, val offer: AndroidScreen? = null)

    private val status = MutableStateFlow(Status())

    val uiState: StateFlow<BackgroundSectionUiState> = combine(health.state, settings.settings, status) { background, current, status ->
        stateOf(background, current, status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackgroundSectionUiState())

    /** Asks Android again (the section is shown again, the owner came back from one of Android's screens, "Check again"). */
    fun refresh() {
        if (status.value.checking) return
        status.update { it.copy(checking = true) }
        viewModelScope.launch {
            try {
                health.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The last known state stays; nothing to tell the owner beyond what is shown.
            } finally {
                status.update { it.copy(checking = false) }
            }
        }
    }

    /** The intent for [screen], or null (then the screen shows [screenMissing]'s sentence). The owner's tap starts it. */
    fun intentFor(screen: AndroidScreen): Intent? = try {
        when (screen) {
            AndroidScreen.BATTERY -> screens.batteryExemption()
            AndroidScreen.APP_PAGE -> screens.appDetails()
        }
    } catch (_: RuntimeException) {
        null
    }

    /** Android has no such screen on this phone (no intent, or `ActivityNotFoundException`): say so and offer the other one. */
    fun screenMissing(screen: AndroidScreen) {
        status.update {
            when (screen) {
                AndroidScreen.BATTERY -> it.copy(message = BackgroundSettingsIntents.NO_SUCH_SCREEN, offer = AndroidScreen.APP_PAGE)
                AndroidScreen.APP_PAGE -> it.copy(message = NO_APP_PAGE, offer = null)
            }
        }
    }

    /** The owner opened one of Android's screens: an earlier "no such screen" no longer applies. */
    fun screenOpened() {
        status.update { it.copy(message = null, offer = null) }
    }

    fun setKeepAwake(on: Boolean) = change { it.copy(keepAwakeWhileAgentsWork = on) }

    fun setNotifyWhenWorkEnds(on: Boolean) = change { it.copy(notifyWhenWorkEnds = on) }

    private fun change(edit: (FrameworkSettings) -> FrameworkSettings) {
        viewModelScope.launch {
            try {
                settings.update(edit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status.update { it.copy(message = QuestionSettingsViewModel.NOT_SAVED, offer = null) }
            }
        }
    }

    companion object {
        const val ALL_CLEAR: String = "Android lets the app work in the background, so agents keep working with the screen off."
        const val NOT_CLEAR: String = "Android may stop agents working in the background. Fix each item below with one tap: it opens Android's own screen."
        const val NOT_KNOWN: String = "Android did not say."
        const val NO_APP_PAGE: String =
            "This phone does not have that screen either. Open Android's Settings, then Apps, then AI Control Center."

        /** What the section shows for [background] and the owner's two switches. Pure. */
        fun itemsOf(background: BackgroundState): List<BackgroundItemUi> = listOf(
            if (background.notificationsAllowed) {
                BackgroundItemUi(
                    "notifications", "Notifications", "Allowed", ok = true,
                    "You hear when work ends and when an agent waits for you.",
                )
            } else {
                BackgroundItemUi(
                    "notifications", "Notifications", "Off", ok = false,
                    "You will not hear when work ends or when an agent waits for you. The work itself goes on.",
                    fix = AndroidScreen.APP_PAGE, fixLabel = "Turn on notifications",
                )
            },
            when (background.batteryOptimised) {
                true -> BackgroundItemUi(
                    "battery", "Battery saving", "Battery saving applies to the app", ok = false,
                    "With the screen off, Android may hold agents back until you use the phone again, and work that begins " +
                        "while you are away, such as a chat answer, may not keep going.",
                    fix = AndroidScreen.BATTERY, fixLabel = "Let the app run in the background",
                )
                false -> BackgroundItemUi(
                    "battery", "Battery saving", "Does not hold the app back", ok = true,
                    "Agents can keep working with the screen off.",
                )
                null -> BackgroundItemUi("battery", "Battery saving", NOT_KNOWN, ok = null, "The app could not read it on this phone.")
            },
            when (background.backgroundRestricted) {
                true -> BackgroundItemUi(
                    "restricted", "Background use", "Background use is restricted", ok = false,
                    "Android stops the app's work soon after you leave it. Allow background use on the app's page.",
                    fix = AndroidScreen.APP_PAGE, fixLabel = "Open the app's page in Android",
                )
                false -> BackgroundItemUi("restricted", "Background use", "Not restricted", ok = true, "Android does not stop the app when you leave it.")
                null -> BackgroundItemUi("restricted", "Background use", NOT_KNOWN, ok = null, "The app could not read it on this phone.")
            },
            when {
                background.standbyBucket == null -> BackgroundItemUi(
                    "standby", "How often Android lets it run", NOT_KNOWN, ok = null, "The app could not read it on this phone.",
                )
                background.standbyWarning -> BackgroundItemUi(
                    "standby", "How often Android lets it run", "Android rarely lets it run", ok = false,
                    "Android has seen the app little lately, so it lets it run only now and then. Using the app, or allowing " +
                        "it to run in the background, changes that.",
                    fix = AndroidScreen.BATTERY, fixLabel = "Let the app run in the background",
                )
                else -> BackgroundItemUi(
                    "standby", "How often Android lets it run", "Often enough", ok = true, "Android lets the app run when it needs to.",
                )
            },
        )

        private fun stateOf(background: BackgroundState, current: FrameworkSettings, status: Status): BackgroundSectionUiState =
            BackgroundSectionUiState(
                isLoading = false,
                allClear = background.allClear,
                summary = if (background.allClear) ALL_CLEAR else NOT_CLEAR,
                items = itemsOf(background),
                keepAwake = current.keepAwakeWhileAgentsWork,
                notifyWhenWorkEnds = current.notifyWhenWorkEnds,
                checking = status.checking,
                message = status.message,
                offer = status.offer,
            )
    }
}
