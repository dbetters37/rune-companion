package com.aicontrolcenter.device.screen

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the screen operator needs from the bound accessibility service. The running
 * [OperatorAccessibilityService] provides it ([ServiceScreenHost]); tests provide node trees.
 * Kept this narrow so the operator never reaches for anything else (no gestures, no key events).
 */
internal interface ScreenHost {
    /** The interactive windows of the default display, as Android reports them. */
    fun windows(): List<AccessibilityWindowInfo>

    /** `AccessibilityService.performGlobalAction`. */
    fun performGlobalAction(action: Int): Boolean

    /** Android 14+: a picture of one window only ([AccessibilityWindowInfo.getId]), without overlays. */
    suspend fun captureWindow(windowId: Int): ScreenCapture

    /** Android 11+: a picture of the whole default display. */
    suspend fun captureDisplay(): ScreenCapture

    /** The "is operating" banner, drawn as this service's accessibility overlay. */
    val indicator: OperatorIndicator
}

/** The result of a screenshot request. */
internal sealed interface ScreenCapture {
    /** A software bitmap the caller owns. */
    class Image(val bitmap: Bitmap) : ScreenCapture

    /** Android's `ERROR_TAKE_SCREENSHOT_*` code. */
    data class Error(val code: Int) : ScreenCapture
}

/**
 * The link between the system-bound service and the screen operator: which [ScreenHost] is
 * connected now, and a counter that moves on every accessibility event (so waits wake up
 * without the events' content being kept anywhere).
 */
internal class ServiceLink {
    private val hostFlow = MutableStateFlow<ScreenHost?>(null)
    private val changesFlow = MutableStateFlow(0L)

    /** The connected service, or null when it is not running. */
    val host: StateFlow<ScreenHost?> = hostFlow.asStateFlow()

    /** Moves on every accessibility event. */
    val changes: StateFlow<Long> = changesFlow.asStateFlow()

    fun connected(host: ScreenHost) {
        hostFlow.value = host
    }

    /** Forgets [host] if it is still the connected one. */
    fun disconnected(host: ScreenHost) {
        hostFlow.compareAndSet(host, null)
    }

    fun changed() {
        changesFlow.update { it + 1 }
    }

    /** The process-wide link: the system creates the service, so it is found here. */
    companion object {
        val process: ServiceLink = ServiceLink()
    }
}
