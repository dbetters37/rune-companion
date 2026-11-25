package com.aicontrolcenter.testsupport

import com.aicontrolcenter.core.operator.ActiveWindow
import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.ScreenElement
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScreenTarget
import com.aicontrolcenter.core.operator.ScreenshotInfo
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.operator.WorkspaceFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [ScreenOperator] that implements only what an older implementation would: for checking the Phase 6 defaults. */
class MinimalScreen(private val front: ActiveWindow?) : ScreenOperator {
    override val state: StateFlow<ScreenServiceState> = MutableStateFlow(ScreenServiceState.READY)
    override val stopped: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun activeWindow(): ActiveWindow? = front
    override suspend fun launcherPackage(): String? = null
    override suspend fun read(agentName: String, packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> = error("unused")
    override suspend fun find(packageName: String, target: ScreenTarget): ScreenResult<ScreenElement> = error("unused")
    override suspend fun inspect(packageName: String, maxElements: Int): ScreenResult<ScreenSnapshot> = error("unused")
    override suspend fun tap(agentName: String, element: ScreenElement): ScreenResult<Unit> = error("unused")
    override suspend fun type(agentName: String, element: ScreenElement, text: String, submit: Boolean): ScreenResult<Unit> = error("unused")
    override suspend fun scroll(agentName: String, packageName: String, direction: ScrollDirection, element: ScreenElement?): ScreenResult<Unit> = error("unused")
    override suspend fun globalAction(agentName: String, fromPackage: String, action: GlobalAction): ScreenResult<Unit> = error("unused")
    override suspend fun waitFor(agentName: String, packageName: String, text: String, timeoutMs: Long): ScreenResult<ScreenElement> = error("unused")
    override suspend fun screenshot(agentName: String, packageName: String, target: WorkspaceFile): ScreenResult<ScreenshotInfo> = error("unused")
    override fun endSession(agentName: String) {}
}
