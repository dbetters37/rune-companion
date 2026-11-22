package com.aicontrolcenter.app.ui.dashboard.network

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * Pinch-to-zoom and drag-to-pan for the network view.
 *
 * It differs from the stock transform detector in one way that matters inside a scrolling page:
 * a one-finger drag is only claimed while the view [isZoomed]. Otherwise the events are left
 * unconsumed, so dragging across the network scrolls the dashboard as the owner expects.
 * Movement below the touch slop is never claimed, which keeps taps on nodes reliable.
 */
internal suspend fun PointerInputScope.detectNetworkTransform(
    isZoomed: () -> Boolean,
    onTransform: (zoom: Float, pan: Offset) -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val cancelled = event.changes.any { it.isConsumed }
            if (!cancelled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val multiTouch = event.changes.count { it.pressed } > 1
                    val zoomMotion = abs(1 - zoom) * event.calculateCentroidSize(useCurrent = false)
                    val panMotion = pan.getDistance()
                    pastTouchSlop = zoomMotion > touchSlop || (panMotion > touchSlop && (multiTouch || isZoomed()))
                }
                if (pastTouchSlop) {
                    if (zoomChange != 1f || panChange != Offset.Zero) onTransform(zoomChange, panChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!cancelled && event.changes.any { it.pressed })
    }
}
