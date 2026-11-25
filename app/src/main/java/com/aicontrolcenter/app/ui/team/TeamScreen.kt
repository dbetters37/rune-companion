@file:Phase6Stub("WP9")

package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.shell.StubScreen
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId

/** Where the Team view leads (supplied by the navigation shell). */
class TeamNavigation(
    val onBack: () -> Unit,
    val onOpenAgent: (AgentId) -> Unit = {},
    val onOpenTask: (TaskId) -> Unit = {},
    val onOpenObjective: (TaskId) -> Unit = {},
    val onOpenRequest: (RequestId) -> Unit = {},
    val onOpenQuestion: (RequestId) -> Unit = {},
    val onOpenSpace: (SpaceId) -> Unit = {},
)

/**
 * The Team view (goal 9; WP9): "You" at the top, the Director in the centre, agents on a ring, spaces along the bottom;
 * who works, who waits for whom, who waits for you; Picture and List (list first with TalkBack or large fonts); Calm and
 * `areAnimatorsEnabled()` switch motion off (rule 17). [objectiveId] opens it on one objective (from its header); null
 * shows the whole team (from the Dashboard).
 */
@Composable
fun TeamRoute(objectiveId: TaskId?, navigation: TeamNavigation) {
    val graph = LocalAppGraph.current
    @Suppress("UNUSED_VARIABLE")
    val viewModel = viewModel(key = "team-${objectiveId?.value}") {
        TeamViewModel(
            objectiveId = objectiveId,
            tasks = graph.tasks,
            messages = graph.messages,
            directory = graph.registryDirectory,
            pendingRequests = graph.ownerRequests.pending,
            monitor = graph.activityMonitor,
            memory = graph.memoryService,
            settings = graph.settingsView,
            clock = graph.clock,
        )
    }
    StubScreen("Team", navigation.onBack)
}
