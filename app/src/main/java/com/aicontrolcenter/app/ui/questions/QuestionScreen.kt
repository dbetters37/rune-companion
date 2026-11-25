@file:Phase6Stub("WP11a")

package com.aicontrolcenter.app.ui.questions

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.shell.StubScreen
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId

/**
 * One question an agent asked the owner (rule 13, Tier B; WP11a): "A question from <agent>", the line "This is not a
 * permission. Your answer is only text for the agent.", the question as quoted agent text, suggested answers as a quoted
 * list with "Use this answer", the owner's answer, Send, "Decide yourself" and "Send it anyway" (rule 32). Never the
 * Approval screen, and never beside one. Texts and API: scratchpad/phase6/handoff-wp5.md section 3 (WP11a).
 *
 * @param onOpenTask the task that asked; [onOpenObjective] its objective.
 */
@Composable
fun QuestionRoute(requestId: RequestId, onBack: () -> Unit, onOpenTask: (TaskId) -> Unit, onOpenObjective: (TaskId) -> Unit) {
    val graph = LocalAppGraph.current
    @Suppress("UNUSED_VARIABLE")
    val viewModel = viewModel(key = "question-${requestId.value}") {
        QuestionViewModel(requestId, graph.ownerQuestions, graph.registryDirectory, graph.clock)
    }
    StubScreen("A question from an agent", onBack)
}
