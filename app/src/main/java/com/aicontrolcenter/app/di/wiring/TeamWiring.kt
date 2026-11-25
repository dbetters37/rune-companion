package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.OwnerConversation
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.framework.agents.AgentDeletionListener
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.orchestration.TeamTools
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import kotlinx.coroutines.CoroutineScope

/*
 * The Director and its team, and chat (Phase 6: WP7 owns this file in wave 2; orchestration, teamwork and chat).
 * Moved out of AppGraph unchanged in wave 1 (WP1d); AppGraph keeps its public members as delegates.
 */

/**
 * The orchestrator, the team tools and chat.
 *
 * @param ports the app's stores. Read here only, never kept.
 * @param settings read-only: the orchestrator and chat are agent-facing (rule 3).
 * @param registryDirectory the registry as agents see it.
 * @param runner the agent runner shared with chat.
 * @param toolRegistry where the team tools are registered, once, before any agent runs.
 * @param capabilities the capability registry the team tools register into.
 * @param monitor the activity monitor the runner writes into.
 * @param activity where every step is logged.
 * @param appScope objectives and chat turns run in it, so they outlive the screen that started them.
 * @param memory the one memory service of the process (the Director may save what an objective taught the team).
 * @param operatorHooks a task's permissions end with it; questions left open when the app stopped are withdrawn.
 * @param providerManager WP7: a provider that is only briefly unavailable is re-checked before the work waits (D28).
 * @param maintenanceGate WP7: starting an objective and sending a chat message are refused while a backup is restored.
 */
class TeamAssembly(
    ports: Ports,
    settings: SettingsView,
    registryDirectory: RegistryDirectory,
    runner: AgentRunner,
    toolRegistry: ToolRegistry,
    capabilities: CapabilityRegistry,
    monitor: DefaultAgentActivityMonitor,
    activity: ActivityRecorder,
    appScope: CoroutineScope,
    memory: MemoryService,
    operatorHooks: OperatorTaskHooks,
    @Suppress("UNUSED_PARAMETER") providerManager: ProviderManager,
    @Suppress("UNUSED_PARAMETER") maintenanceGate: MaintenanceGate,
) {
    /**
     * Runs the owner's objectives: the Director plans, agents work, reviewers check, the Director
     * writes the result. It shares the runner and the activity monitor with chat, so every limit,
     * permission check and "working now" indicator applies to orchestrated work exactly as to a
     * conversation. Objectives run in [appScope], so they outlive the screen that started them.
     */
    private val defaultOrchestrator = DefaultOrchestrator(
        tasks = ports.tasks,
        messages = ports.messages,
        conversations = ports.conversations,
        agents = ports.agents,
        directory = registryDirectory,
        settings = settings,
        runner = runner,
        toolRegistry = toolRegistry,
        monitor = monitor,
        activity = activity,
        ids = ports.idGenerator,
        clock = ports.clock,
        scope = appScope,
        // The Director may save what an objective taught the team, when the owner allows it.
        memory = memory,
        // A task's permissions end with it, and questions left open when the app stopped are withdrawn at start-up.
        operatorHooks = operatorHooks,
    ).also {
        // Once, before any agent runs: asking a teammate and delegating are tools like any other.
        TeamTools.registerAll(toolRegistry, capabilities, it)
    }

    val orchestrator: Orchestrator = defaultOrchestrator

    private val defaultAgentChat = DefaultAgentChat(
        conversations = ports.conversations,
        agents = ports.agents,
        models = ports.models,
        providers = ports.providers,
        settings = settings,
        runner = runner,
        activity = activity,
        ids = ports.idGenerator,
        clock = ports.clock,
        // Turns outlive the screen that started them.
        scope = appScope,
    )

    val agentChat: AgentChat = defaultAgentChat

    /** Deleting an agent stops its chat turns and removes its conversations (the agent manager calls it). */
    val chatDeletionListener: AgentDeletionListener = defaultAgentChat

    /**
     * Owner-only: the owner's notes to the team during an objective (C2, Tier B). Unavailable until WP7 builds
     * `OwnerNotes`, and whenever C2 is switched off.
     */
    val ownerConversation: OwnerConversation = OwnerConversation.Unavailable
}
