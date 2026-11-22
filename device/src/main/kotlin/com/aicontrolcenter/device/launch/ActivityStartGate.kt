package com.aicontrolcenter.device.launch

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo

/**
 * Answers whether Android would currently let this app bring another app's screen forward.
 *
 * Since Android 10 a start requested from the background is dropped silently: `startActivity`
 * returns normally and nothing opens. The launcher asks this gate first so that it never reports
 * a hand-off that did not happen.
 */
fun interface ActivityStartGate {
    fun mayStartActivities(): Boolean
}

/**
 * Allows starts only while this app's process is on screen, which is the one condition that
 * holds on every Android version. A later phase that gains another exemption (for example a
 * bound accessibility service) can supply its own gate.
 */
object VisibleProcessGate : ActivityStartGate {
    override fun mayStartActivities(): Boolean {
        val state = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        // Not a "<=" comparison: IMPORTANCE_FOREGROUND_SERVICE lies between these two values and
        // does not permit starting activities.
        return state.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            state.importance == RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}
