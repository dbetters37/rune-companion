package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.Immutable

/**
 * Zoom and pan of the Team view's picture, in pixels: the picture ([contentWidth] by [contentHeight], drawn from its top
 * left corner) is scaled by [scale] and moved by ([x], [y]). Kept so that the picture always covers the view where it can
 * and is centred where it is smaller, so nothing is ever lost off-screen. One finger always pans: the screen itself does
 * not scroll in Picture mode.
 */
@Immutable
data class TeamViewport(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val isZoomed: Boolean get() = scale > MIN_SCALE + EPSILON
    val canZoomIn: Boolean get() = scale < MAX_SCALE - EPSILON

    /** A pinch of [zoom] about ([cx], [cy]) in the view and a drag of ([dx], [dy]). */
    fun transformed(
        zoom: Float,
        dx: Float,
        dy: Float,
        cx: Float,
        cy: Float,
        contentWidth: Float,
        contentHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): TeamViewport {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val factor = newScale / scale
        // Keep the point under the fingers where it is while scaling.
        val nx = cx - (cx - x) * factor + dx
        val ny = cy - (cy - y) * factor + dy
        return TeamViewport(newScale, nx, ny).clamped(contentWidth, contentHeight, viewWidth, viewHeight)
    }

    fun zoomedBy(factor: Float, contentWidth: Float, contentHeight: Float, viewWidth: Float, viewHeight: Float): TeamViewport =
        transformed(factor, 0f, 0f, viewWidth / 2, viewHeight / 2, contentWidth, contentHeight, viewWidth, viewHeight)

    /** The same view, moved back inside its limits. */
    fun clamped(contentWidth: Float, contentHeight: Float, viewWidth: Float, viewHeight: Float): TeamViewport =
        copy(x = axis(x, contentWidth * scale, viewWidth), y = axis(y, contentHeight * scale, viewHeight))

    private fun axis(offset: Float, content: Float, view: Float): Float =
        if (content <= view) (view - content) / 2 else offset.coerceIn(view - content, 0f)

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 3f
        const val ZOOM_STEP = 1.35f
        private const val EPSILON = 0.001f
    }
}
