package com.aicontrolcenter.core.agents

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.ToolId
import kotlinx.serialization.Serializable

/**
 * A configured actor inside the framework. An agent has an identity, a persona, a job, an
 * assigned model, a set of enabled tools, a memory policy and delegation rights.
 *
 * The profile is pure configuration: swapping [modelId] changes which model does the thinking
 * without touching anything else about the agent. Runtime state lives in [AgentRuntimeState],
 * track record in [AgentStats], and security grants in the permission store.
 */
@Serializable
data class AgentProfile(
    val id: AgentId,
    val name: String,
    val kind: AgentKind,
    /** One-line summary of what this agent is for; shown in lists and used by the Director. */
    val description: String = "",
    /** The agent's standing instructions (system prompt body). */
    val systemInstructions: String = "",
    /** Tone and character, kept separate from instructions so either can change independently. */
    val personality: String = "",
    /** Specialised jobs this agent is responsible for. */
    val responsibilities: List<String> = emptyList(),
    /** Lower-case tags the Director matches work against, e.g. "research", "coding", "review". */
    val skills: Set<String> = emptySet(),
    /** The model that powers this agent. Null means "not assigned yet" and the agent cannot run. */
    val modelId: ModelId? = null,
    /** Tried in order when the primary model is unavailable. */
    val fallbackModelIds: List<ModelId> = emptyList(),
    val generation: GenerationSettings = GenerationSettings(),
    /** Tools switched on for this agent. Using one still requires the matching permission grant. */
    val toolIds: Set<ToolId> = emptySet(),
    val memory: MemoryPolicy = MemoryPolicy(),
    val delegation: DelegationPolicy = DelegationPolicy(),
    val enabled: Boolean = true,
    /**
     * Chooses the agent's accent colour. Consumers pick the colour with
     * `Math.floorMod(colorIndex, paletteSize)`, so any value is safe.
     */
    val colorIndex: Int = 0,
    /** Id of the template this agent was created from, for reference only. */
    val templateId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The protocol role an agent plays. Responsibilities and skills refine it. */
enum class AgentKind {
    /** Receives the user's objectives, plans, assigns, collects, arbitrates and finalises. */
    DIRECTOR,

    /** Does assigned work in its speciality. */
    SPECIALIST,

    /** Reviews other agents' results. */
    REVIEWER,

    /** May be granted phone-operation tools (files, apps, accessibility, scripts). */
    OPERATOR,
}

/** Display name of the role. */
val AgentKind.label: String
    get() = when (this) {
        AgentKind.DIRECTOR -> "Director"
        AgentKind.SPECIALIST -> "Specialist"
        AgentKind.REVIEWER -> "Reviewer"
        AgentKind.OPERATOR -> "Operator"
    }

@Serializable
data class GenerationSettings(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

/**
 * What an agent may remember and see. Every agent owns a private memory namespace. Shared
 * knowledge is opt-in per space and separately for reading and writing, so agents are never
 * forced to share everything.
 */
@Serializable
data class MemoryPolicy(
    val privateMemoryEnabled: Boolean = true,
    val readableSpaces: Set<SpaceId> = emptySet(),
    val writableSpaces: Set<SpaceId> = emptySet(),
    /** Retrieve relevant memories automatically before each task. */
    val autoRecall: Boolean = true,
    /** Maximum number of memories injected into a prompt by auto-recall. */
    val recallLimit: Int = 6,
)

@Serializable
data class DelegationPolicy(
    /** May hand subtasks to other agents. */
    val canDelegate: Boolean = false,
    /** May ask other agents for help without handing over the task. */
    val canRequestAssistance: Boolean = true,
    /** How many delegation levels below this agent are allowed. */
    val maxDepth: Int = 1,
)

/** Track record of an agent, updated by the runtime after every task and review. */
@Serializable
data class AgentStats(
    val tasksAssigned: Int = 0,
    val tasksCompleted: Int = 0,
    val tasksFailed: Int = 0,
    val tasksTimedOut: Int = 0,
    val tasksCancelled: Int = 0,
    val reviewsPassed: Int = 0,
    val revisionsRequested: Int = 0,
    val reviewsRejected: Int = 0,
    val modelCalls: Int = 0,
    val modelErrors: Int = 0,
    val toolCalls: Int = 0,
    val toolErrors: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTaskDurationMs: Long = 0,
    val lastActiveAt: Long? = null,
) {
    val tasksFinished: Int get() = tasksCompleted + tasksFailed + tasksTimedOut

    val averageTaskDurationMs: Long?
        get() = if (tasksFinished == 0) null else totalTaskDurationMs / tasksFinished

    /**
     * Reliability in 0..1. Completions count for the agent; failures, time-outs and rejected
     * reviews count against it; a requested revision counts half against it. Laplace smoothing
     * keeps a new agent at 0.5 rather than at an extreme, and null means "no evidence yet".
     */
    val reliability: Double?
        get() {
            val good = tasksCompleted + reviewsPassed
            val bad = tasksFailed + tasksTimedOut + reviewsRejected + revisionsRequested * 0.5
            val total = good + bad
            if (total == 0.0) return null
            return (good + 1.0) / (total + 2.0)
        }
}

/** What an agent is doing right now. Held in memory by the runtime; never persisted. */
@Serializable
data class AgentRuntimeState(
    val agentId: AgentId,
    val activity: AgentActivity = AgentActivity.IDLE,
    /** Human-readable description of the current step, e.g. "Calling tool: workspace_write_file". */
    val detail: String? = null,
    val currentTaskId: com.aicontrolcenter.core.TaskId? = null,
    val since: Long = 0,
)

enum class AgentActivity {
    IDLE,
    WORKING,

    /** Blocked on the user approving a permission or a consequential action. */
    WAITING_FOR_APPROVAL,

    /** Blocked on another agent it asked for help or delegated to. */
    WAITING_FOR_AGENT,

    /** Its last task failed or it asked a question; the user should look. */
    NEEDS_ATTENTION,
}
