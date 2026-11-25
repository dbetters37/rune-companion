@file:Phase6Stub("WP10c")

package com.aicontrolcenter.app.ui.routines

import androidx.compose.runtime.Composable
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.ui.shell.StubScreen
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.TaskId

/** A new routine ([routineId] null) or one to change (WP10c); [onSaved] gets the saved routine's id. */
@Composable
fun RoutineEditorRoute(
    routineId: RoutineId?,
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSaved: (RoutineId) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenObjective: (TaskId) -> Unit,
) {
    StubScreen(if (routineId == null) "New routine" else "Routine", onClose)
}
