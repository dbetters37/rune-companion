package com.aicontrolcenter.app.operator

import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenshotInfo
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.framework.operator.AuthorisedApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One agent operating one app's screen. [waitingForOwner] (Phase 6, D4): one of its screen actions
 * waits for the owner's answer ("<agent> waits for your answer to act in <app> · Stop").
 */
data class ScreenSession(val agentName: String, val packageName: String, val appLabel: String, val waitingForOwner: Boolean = false)

/**
 * The screen operator as agents get it. It passes every call through unchanged and also tells
 * the app who is operating which app right now ([sessions]), so the app can show the
 * "<agent> is operating <app> · Stop" notification next to the device's own banner.
 *
 * A session follows the device's rule: it starts with an agent's first call that gets past the
 * checks (service ready, not stopped, not protected, authorised, the app's own window in front)
 * and ends at [endSession], when the owner presses Stop, or [ScreenOperator.SESSION_IDLE_MS]
 * after the agent's last call. A call in progress keeps its session, and so does a screen action
 * that waits for the owner (Phase 6, D4, rule 12: [holdSession] until [releaseSession], per
 * request id, passed on to the device's operator): no [endSession] and no idle time-out ends a
 * held session; Stop ends every hold.
 *
 * It is a separate object from the device's operator, so nothing holding it can be cast to the
 * owner's Stop and Resume, even where one implementation serves both.
 */
class ScreenSessions(
    private val operator: ScreenOperator,
    private val apps: AuthorisedApps,
    private val scope: CoroutineScope,
) : ScreenOperator by operator {
    private val lock = Any()
    private val active = LinkedHashMap<String, ScreenSession>()
    private val inCall = HashMap<String, Int>()
    private val idleTimers = HashMap<String, Job>()

    /** The open holds: hold id → agent. */
    private val holds = HashMap<String, String>()

    /** Agents whose run ended ([endSession]) while one of their actions was held: their session ends with the last hold. */
    private val endingWhenReleased = HashSet<String>()
    private val shown = MutableStateFlow<List<ScreenSession>>(emptyList())

    /** Who operates which app now, in the order they started. Empty while nobody does. */
    val sessions: StateFlow<List<ScreenSession>> = shown.asStateFlow()

    init {
        // Stop ends every session at once, as on the device.
        scope.launch { operator.stopped.collect { stopped -> if (stopped) endAll() } }
    }

    override suspend fun read(agentName: String, packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> =
        tracked(agentName, packageName) { operator.read(agentName, packageName, maxElements) }

    override suspend fun tap(agentName: String, element: ScreenElement): ScreenResult<Unit> =
        tracked(agentName, element.packageName) { operator.tap(agentName, element) }

    override suspend fun type(agentName: String, element: ScreenElement, text: String, submit: Boolean): ScreenResult<Unit> =
        tracked(agentName, element.packageName) { operator.type(agentName, element, text, submit) }

    override suspend fun scroll(agentName: String, packageName: String, direction: ScrollDirection, element: ScreenElement?): ScreenResult<Unit> =
        tracked(agentName, packageName) { operator.scroll(agentName, packageName, direction, element) }

    override suspend fun globalAction(agentName: String, fromPackage: String, action: GlobalAction): ScreenResult<Unit> =
        tracked(agentName, fromPackage) { operator.globalAction(agentName, fromPackage, action) }

    override suspend fun waitFor(agentName: String, packageName: String, text: String, timeoutMs: Long): ScreenResult<ScreenElement> =
        tracked(agentName, packageName) { operator.waitFor(agentName, packageName, text, timeoutMs) }

    override suspend fun screenshot(agentName: String, packageName: String, target: WorkspaceFile): ScreenResult<ScreenshotInfo> =
        tracked(agentName, packageName) { operator.screenshot(agentName, packageName, target) }

    override fun endSession(agentName: String) {
        operator.endSession(agentName)
        synchronized(lock) {
            if (held(agentName)) {
                // Another run of the agent ended while one of its actions waits for the owner: the session stays (D4).
                endingWhenReleased += agentName
                return
            }
            idleTimers.remove(agentName)?.cancel()
            // A call still running ends the session when it returns, like the device does.
            if (inCall[agentName] == null) active.remove(agentName)
            publish()
        }
    }

    override fun holdSession(holdId: String, agentName: String, packageName: String) {
        operator.holdSession(holdId, agentName, packageName)
        if (operator.stopped.value) return
        var labelNeeded = false
        synchronized(lock) {
            if (holds.put(holdId, agentName) != null) return
            idleTimers.remove(agentName)?.cancel()
            val existing = active[agentName]
            labelNeeded = existing == null
            active[agentName] = (existing ?: ScreenSession(agentName, packageName, packageName)).copy(waitingForOwner = true)
            publish()
        }
        if (labelNeeded) {
            scope.launch {
                val label = labelOf(packageName)
                synchronized(lock) {
                    active[agentName]?.takeIf { it.appLabel == packageName && it.packageName == packageName }?.let { active[agentName] = it.copy(appLabel = label) }
                    publish()
                }
            }
        }
    }

    override fun releaseSession(holdId: String) {
        operator.releaseSession(holdId)
        synchronized(lock) {
            val agentName = holds.remove(holdId) ?: return
            if (held(agentName)) return
            active[agentName]?.let { active[agentName] = it.copy(waitingForOwner = false) }
            if (inCall[agentName] == null) {
                if (endingWhenReleased.remove(agentName)) active.remove(agentName)
                else if (agentName in active) idleTimers[agentName] = idleTimer(agentName)
            }
            publish()
        }
    }

    /** Called with [lock] held. */
    private fun held(agentName: String): Boolean = agentName in holds.values

    private suspend fun <T> tracked(agentName: String, packageName: String, call: suspend () -> ScreenResult<T>): ScreenResult<T> {
        synchronized(lock) {
            inCall[agentName] = (inCall[agentName] ?: 0) + 1
            idleTimers.remove(agentName)?.cancel()
        }
        // The label is the app's name as the owner authorised it; null when the call did not get past the checks.
        var label: String? = null
        try {
            val result = call()
            if (passedChecks(result)) label = labelOf(packageName)
            return result
        } finally {
            // Bookkeeping only, never suspending, so it also runs when the call is cancelled.
            synchronized(lock) {
                val left = (inCall[agentName] ?: 1) - 1
                if (left > 0) inCall[agentName] = left else inCall.remove(agentName)
                if (label != null && !operator.stopped.value) active[agentName] = ScreenSession(agentName, packageName, label!!, waitingForOwner = held(agentName))
                if (left == 0 && agentName in active && !held(agentName)) idleTimers[agentName] = idleTimer(agentName)
                publish()
            }
        }
    }

    private suspend fun labelOf(packageName: String): String = try {
        apps.get(packageName)?.label?.takeIf { it.isNotBlank() } ?: packageName
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        packageName
    }

    private fun idleTimer(agentName: String): Job = scope.launch {
        delay(ScreenOperator.SESSION_IDLE_MS)
        synchronized(lock) {
            if (inCall[agentName] == null && !held(agentName)) active.remove(agentName)
            idleTimers.remove(agentName)
            publish()
        }
    }

    private fun endAll() = synchronized(lock) {
        idleTimers.values.forEach { it.cancel() }
        idleTimers.clear()
        active.clear()
        // Stop ends every hold too, as on the device.
        holds.clear()
        endingWhenReleased.clear()
        publish()
    }

    /** Called with [lock] held. */
    private fun publish() {
        shown.value = active.values.toList()
    }

    private companion object {
        /** Whether a result came from past the device's checks, which is when a session starts. */
        fun passedChecks(result: ScreenResult<*>): Boolean = when (result) {
            is ScreenResult.Unavailable, ScreenResult.Stopped, is ScreenResult.WrongWindow,
            is ScreenResult.Protected, is ScreenResult.NotAuthorised -> false
            else -> true
        }
    }
}
