package com.aicontrolcenter.device.screen

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aicontrolcenter.device.R

/** The on-screen "<agent> is operating <app> · Stop" banner. Both methods run on the main thread. */
internal interface OperatorIndicator {
    /** Shows the banner with [text], or updates it if it is showing; [onStop] runs when the owner taps Stop. */
    fun show(text: String, onStop: () -> Unit)

    /** Removes the banner; nothing happens if it is not showing. */
    fun hide()
}

/**
 * The banner as a `TYPE_ACCESSIBILITY_OVERLAY` window of the accessibility service: no
 * permission beyond the enabled service, drawn above every app, and gone with the service.
 * Only the pill itself is touchable, so the app underneath keeps working. Agents cannot tap it:
 * the operator only acts in `TYPE_APPLICATION` windows of other, authorised apps.
 *
 * @param service the accessibility service's own context; only its `WindowManager` may add
 *   accessibility overlays.
 */
internal class AccessibilityOverlayIndicator(private val service: Context) : OperatorIndicator {
    private val windowManager: WindowManager? = service.getSystemService(WindowManager::class.java)
    private var banner: View? = null
    private var label: TextView? = null
    private var stopButton: Button? = null

    /** The banner while it is showing (for tests). */
    val view: View? get() = banner

    override fun show(text: String, onStop: () -> Unit) {
        val shown = banner
        if (shown != null) {
            label?.text = text
            stopButton?.setOnClickListener { onStop() }
            return
        }
        val manager = windowManager ?: return
        val textView = TextView(service).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
        }
        val button = Button(service).apply {
            setText(R.string.operator_overlay_stop)
            contentDescription = service.getString(R.string.operator_overlay_stop_description)
            setOnClickListener { onStop() }
        }
        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padding = dp(12)
            setPadding(padding, dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(235, 32, 33, 36))
            }
            addView(textView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(36)
            title = "AI Control Center operator banner"
        }
        try {
            manager.addView(pill, params)
        } catch (_: RuntimeException) {
            // The service is going away (BadToken) or Android refused the window: nothing to show on.
            return
        }
        banner = pill
        label = textView
        stopButton = button
    }

    override fun hide() {
        val shown = banner ?: return
        banner = null
        label = null
        stopButton = null
        try {
            windowManager?.removeView(shown)
        } catch (_: RuntimeException) {
            // Already gone with the service.
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), service.resources.displayMetrics).toInt()
}
