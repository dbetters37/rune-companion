package com.aicontrolcenter.app.work

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app may show notifications, and whether it should still ask the owner.
 *
 * Objectives can run for many minutes, so while one runs the app keeps a foreground service
 * with an ongoing notification (progress and a Pause button). On Android 13 and later that
 * notification needs the owner's permission. The app asks once, the first time an objective
 * is started, after explaining why; a refusal is respected and never asked again from here
 * (the Tasks screen then says notifications are off and offers the system settings).
 *
 * The phone operator needs the same permission for "Approval needed" (an agent waits for the
 * owner's answer) and for "<agent> is operating <app> · Stop". The app asks for it once more for
 * that, with its own explanation, when the owner turns the phone operator on (or at the first
 * start with it already on), whatever was answered for objectives ([shouldAskForOperator]).
 */
interface NotificationAccess {
    /** True when the app's notifications can be shown right now. Re-read by [refresh]. */
    val enabled: StateFlow<Boolean>

    /** The runtime permission to request, or null when this Android version has none. */
    val permission: String?

    /** True when the permission is missing and the owner has not been asked yet. */
    fun shouldAsk(): Boolean

    /** Records that the owner has been asked, whatever the answer, and re-reads [enabled]. */
    fun markAsked()

    /** True when the permission is missing and the owner has not been asked yet for the phone operator's notifications. */
    fun shouldAskForOperator(): Boolean

    /** Records that the owner has been asked for the phone operator's notifications, whatever the answer, and re-reads [enabled]. */
    fun markAskedForOperator()

    /** Re-reads [enabled]: the owner may have changed it in the system settings meanwhile. */
    fun refresh()
}

/**
 * A [NotificationAccess] held in memory: always allowed by default. Used where there is no
 * Android behind the graph (previews, tests), and by tests that script a refusal.
 */
class InMemoryNotificationAccess(enabled: Boolean = true, askable: Boolean = false) : NotificationAccess {
    /** Whether the owner can still be asked (Android 13 and later, not asked yet). */
    var askable: Boolean = askable

    /** Whether the owner can still be asked for the phone operator's notifications. */
    var askableForOperator: Boolean = false
    private val state = MutableStateFlow(enabled)
    override val enabled: StateFlow<Boolean> = state.asStateFlow()
    override val permission: String? get() = if (askable || askableForOperator) "android.permission.POST_NOTIFICATIONS" else null

    /** How many times the owner was recorded as asked. */
    var timesAsked: Int = 0
        private set

    override fun shouldAsk(): Boolean = askable && !state.value

    override fun markAsked() {
        timesAsked++
        askable = false
    }

    /** How many times the owner was recorded as asked for the phone operator. */
    var timesAskedForOperator: Int = 0
        private set

    override fun shouldAskForOperator(): Boolean = askableForOperator && !state.value

    override fun markAskedForOperator() {
        timesAskedForOperator++
        askableForOperator = false
    }

    override fun refresh() = Unit

    /** What the owner answered, or changed in the settings. */
    fun set(enabled: Boolean) {
        state.value = enabled
    }
}
