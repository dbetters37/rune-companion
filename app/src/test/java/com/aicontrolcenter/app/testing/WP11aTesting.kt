package com.aicontrolcenter.app.testing

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.aicontrolcenter.app.ui.shell.AiControlCenterRoot
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.RiskLevel
import kotlin.coroutines.CoroutineContext

/*
 * WP11a's test helpers: agents' questions as the question tool raises them, and the app shown with a chosen place for
 * the screens' state building.
 */

/** The question the samples ask: a plain question with two suggested answers. */
const val SAMPLE_QUESTION = "Which city should the report cover?"

/**
 * A QUESTION request as `team.ask_owner` raises it for [agentName]'s task: SAFE, no folder or app, the question and
 * its suggestions as the agent wrote them. [minutes] is long by default: the test clock moves 37 seconds a reading.
 */
fun sampleQuestion(
    agentId: AgentId,
    agentName: String = "Researcher",
    id: String = "req_question",
    createdAt: Long = TestApp.START_TIME,
    minutes: Int = 240,
    taskId: TaskId? = TaskId("tsk_compare"),
    rootId: TaskId? = TaskId("tsk_root"),
    question: String = SAMPLE_QUESTION,
    options: List<String> = listOf("Berlin", "Paris, in “France”"),
    reason: String? = null,
) = OwnerRequest(
    id = RequestId(id),
    kind = OwnerRequestKind.QUESTION,
    agentId = agentId,
    toolId = ToolId("team.ask_owner"),
    capability = Capability("team.ask_owner"),
    risk = RiskLevel.SAFE,
    summary = "A question from $agentName",
    reason = reason,
    taskId = taskId,
    rootId = rootId,
    createdAt = createdAt,
    expiresAt = createdAt + minutes * 60_000L,
    agentName = agentName,
    toolTitle = "Ask the owner",
    question = question,
    options = options,
)

/** Shows the whole app, as [AppRobot.launch] does, with the screens' state built on [work]. */
fun AppRobot.launchWithWork(compose: ComposeContentTestRule, work: CoroutineContext, darkTheme: Boolean = true) = apply {
    compose.setContent {
        CompositionLocalProvider(LocalScreenWork provides work) { AiControlCenterRoot(app.graph, darkTheme) }
    }
    compose.waitForIdle()
}
