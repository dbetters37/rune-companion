package com.aicontrolcenter.device.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The screen operator's accessibility service. The system binds it once the owner switches it on
 * in Android's accessibility settings; it cannot be switched on from code. It stays a thin
 * executor: it hands itself to the operator ([ServiceLink]) when connected, ticks a counter on
 * every event (no event content is kept) and removes its banner when unbound. Every decision
 * (which app, which window, which element) is the operator's
 * ([com.aicontrolcenter.core.operator.ScreenOperator]).
 */
class OperatorAccessibilityService : AccessibilityService() {
    private var host: ServiceScreenHost? = null

    public override fun onServiceConnected() {
        super.onServiceConnected()
        // A second connection without an unbind replaces the first, banner included.
        disconnect()
        val connected = ServiceScreenHost(this)
        host = connected
        ServiceLink.process.connected(connected)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        ServiceLink.process.changed()
    }

    override fun onInterrupt() {
        // No spoken or haptic feedback to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        val connected = host ?: return
        host = null
        connected.indicator.hide()
        ServiceLink.process.disconnected(connected)
    }

    /** Names this service for Android's settings. */
    companion object {
        /** The component Android lists in `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` when the owner enables it. */
        fun componentName(context: Context): ComponentName = ComponentName(context.packageName, OperatorAccessibilityService::class.java.name)
    }
}

/** [ScreenHost] over the running service. */
internal class ServiceScreenHost(private val service: AccessibilityService) : ScreenHost {
    override val indicator: AccessibilityOverlayIndicator = AccessibilityOverlayIndicator(service)

    override fun windows(): List<AccessibilityWindowInfo> =
        try {
            service.windows.orEmpty()
        } catch (_: RuntimeException) {
            // The connection to the system is gone.
            emptyList()
        }

    override fun performGlobalAction(action: Int): Boolean = service.performGlobalAction(action)

    override suspend fun captureWindow(windowId: Int): ScreenCapture =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34.captureWindow(service, windowId)
        } else {
            ScreenCapture.Error(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        }

    override suspend fun captureDisplay(): ScreenCapture =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30.captureDisplay(service)
        } else {
            // ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR; the constant itself is API 30.
            ScreenCapture.Error(1)
        }

    /** Keeps the API 30 classes out of the verifier's way on Android 9 and 10. */
    @RequiresApi(Build.VERSION_CODES.R)
    private object Api30 {
        suspend fun captureDisplay(service: AccessibilityService): ScreenCapture = suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback { continuation.resume(it) })
        }

        fun callback(done: (ScreenCapture) -> Unit) = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
                done(if (bitmap != null) ScreenCapture.Image(bitmap) else ScreenCapture.Error(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) = done(ScreenCapture.Error(errorCode))
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        suspend fun captureWindow(service: AccessibilityService, windowId: Int): ScreenCapture = suspendCancellableCoroutine { continuation ->
            service.takeScreenshotOfWindow(windowId, service.mainExecutor, Api30.callback { continuation.resume(it) })
        }
    }
}
