package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.memory.AutoRecall
import com.aicontrolcenter.framework.tools.ToolExecutor
import kotlin.coroutines.CoroutineContext

/*
 * Agents at work: the activity monitor and the runner every chat turn and task goes through (Phase 6: WP15 owns this
 * file in wave 2; lean requests). Moved out of AppGraph unchanged in wave 1 (WP1d); AppGraph keeps its public members
 * as delegates.
 */

/**
 * The activity monitor and the agent runner.
 *
 * @param ports the app's stores. Read here only, never kept.
 * @param settings read-only: the runner is agent-facing (rule 3).
 * @param gateway every model call goes through it.
 * @param tools every tool agents can be given.
 * @param executor runs every tool call through the permission broker.
 * @param memory the one memory service of the process, for automatic recall.
 * @param activity where recall is logged.
 * @param backgroundContext where memory recall runs.
 * @param screen the agents' screen view: the "is operating" indicator ends with the run that operated the screen.
 */
class AgentsAssembly(
    ports: Ports,
    settings: SettingsView,
    gateway: ModelGateway,
    tools: ToolRegistry,
    executor: ToolExecutor,
    memory: MemoryService,
    activity: ActivityRecorder,
    backgroundContext: CoroutineContext,
    screen: ScreenOperator,
) {
    /** The monitor itself: the runner and the orchestrator write into it. */
    internal val monitor = DefaultAgentActivityMonitor(ports.clock)

    /** What every agent is doing right now; agents that are not in it are idle. */
    val activityMonitor: AgentActivityMonitor = monitor

    /** Runs one agent turn or task: prompt, model calls, tool calls. Shared by chat and the orchestrator. */
    internal val agentRunner = AgentRunner(
        gateway = gateway,
        tools = tools,
        executor = executor,
        agents = ports.agents,
        settings = settings,
        monitor = monitor,
        clock = ports.clock,
        // Relevant memories are added to the prompt before each turn, per the agent's memory policy.
        recall = AutoRecall(memory, activity, lookupContext = backgroundContext),
        // The "is operating" indicator ends with the run that operated the screen.
        screen = screen,
    )
}
