package com.aicontrolcenter.app.ui.recovery

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * While this is shown, the window is secure (`FLAG_SECURE`, rule 27): no screenshot, no screen recording, and the
 * recent-apps picture is blank, so a passphrase or the word that starts over never ends up in a picture. Used by the
 * Recovery screen and the two backup screens. The flag goes when the screen goes.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** The activity a composable is shown in, or null (a preview, a test host without one). */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
