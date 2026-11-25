@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.routines

import androidx.compose.runtime.Composable
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.ui.shell.StubScreen
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId

/** Where the Routines screens lead (supplied by the navigation shell). */
class RoutinesNavigation(
    val onBack: () -> Unit,
    val onNewRoutine: () -> Unit = {},
    val onOpenRoutine: (RoutineId) -> Unit = {},
    /** The runs waiting for the owner's tap ("Tap to start"). */
    val onOpenWaiting: () -> Unit = {},
    val onOpenObjective: (TaskId) -> Unit = {},
    /** Settings, where the master switch is. */
    val onOpenSettings: () -> Unit = {},
)

/**
 * The owner's routines (Q1, Appendix A, Tier B; WP10c): each with its schedule, next run, pause note ("Switch it on
 * again") and failures in a row; the master switch's state (Settings › Routines); a link to the runs waiting for a tap;
 * Android may delay routines, and "Alarms and reminders" makes them start on time (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` on
 * Android 12 and later, from this screen, never from a notification). Hidden when the build has no routines.
 */
@Composable
fun RoutinesRoute(navigation: RoutinesNavigation) {
    StubScreen("Routines", navigation.onBack)
}
