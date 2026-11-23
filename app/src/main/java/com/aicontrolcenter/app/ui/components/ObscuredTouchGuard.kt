package com.aicontrolcenter.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Refuses touches that arrive while another app's window covers this one, for as long as the
 * caller is shown, so an overlay cannot trick the owner into a tap they cannot see. Called inside
 * a dialog, it guards the dialog's own window (a dialog has its own view).
 */
@Composable
fun ObscuredTouchGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = before }
    }
}
