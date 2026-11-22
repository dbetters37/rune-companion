package com.aicontrolcenter.core.tools

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A named permission. Tools declare the capability they need; agents are granted capabilities. */
@Serializable
@JvmInline
value class Capability(val id: String) {
    override fun toString(): String = id
}

/** How much damage one call could do. */
enum class RiskLevel {
    /** Reads or computes; changes nothing outside the conversation. */
    SAFE,

    /** Changes data in a way the owner can see and undo. */
    MODIFYING,

    /** Hard to undo, or reaches other people, money or installed software. Always confirmed by the owner. */
    CONSEQUENTIAL,
}

enum class ToolCategory { SYSTEM, MEMORY, COMMUNICATION, WORKSPACE, FILES, MEDIA, APPS, BROWSER, ACCESSIBILITY, SCRIPTS, WEB }

/** Everything the framework and the model need to know about a tool, without its implementation. */
@Serializable
data class ToolSpec(
    /** Stable id used in agent configuration, e.g. "memory.search". */
    val id: ToolId,
    /** The function name the model sees: letters, digits and underscores, at most 64 characters. */
    val functionName: String,
    /** Short name for the owner. */
    val title: String,
    /** What the tool does and when to use it, written for the model. */
    val description: String,
    /** JSON Schema of the arguments; the root is an object. */
    val parameters: JsonObject,
    val category: ToolCategory,
    val capability: Capability,
    /** The risk of a typical call. A specific call may be assessed higher by [Tool.assess]. */
    val risk: RiskLevel,
    /** True when calling it sends information off the device. Blocked in local-only mode. */
    val sendsDataOffDevice: Boolean = false,
)

/** Who is calling a tool, and as part of what. */
data class ToolContext(
    val agent: AgentProfile,
    val taskId: TaskId? = null,
    val rootTaskId: TaskId? = null,
    /** Agents that delegated down to this call, outermost first. */
    val delegationChain: List<AgentId> = emptyList(),
)

/** What one specific call would do, described before it runs. */
data class ActionAssessment(
    val risk: RiskLevel,
    /** One sentence the owner can approve or refuse, e.g. "Delete 3 files in Documents/Reports". */
    val summary: String,
    val details: List<String> = emptyList(),
)

@Serializable
data class ToolOutcome(
    val ok: Boolean,
    /** What the model reads back. On failure, an explanation it can act on. */
    val content: String,
    /** Structured result for the UI and logs. Never contains secrets. */
    val data: JsonElement? = null,
) {
    companion object {
        fun success(content: String, data: JsonElement? = null) = ToolOutcome(true, content, data)

        fun failure(content: String) = ToolOutcome(false, content)
    }
}

/**
 * A capability an agent can use. Implementations validate their own arguments and report bad
 * input as a failed [ToolOutcome] the model can correct, and throw only for programming errors.
 */
interface Tool {
    val spec: ToolSpec

    /** Describes this call without performing it. Must have no side effects. */
    suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment =
        ActionAssessment(spec.risk, spec.title)

    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome
}

/** All tools known to the framework. Subsystems register theirs at start-up. */
interface ToolRegistry {
    fun all(): List<Tool>

    fun byId(id: ToolId): Tool?

    fun byFunctionName(name: String): Tool?

    /** @throws IllegalArgumentException if the id or function name is already registered. */
    fun register(tool: Tool)
}

/** The outcome of asking whether an agent may make a specific tool call. */
sealed interface Authorization {
    data object Allowed : Authorization

    /** [reason] is returned to the model so it can explain or choose another way. */
    data class Denied(val reason: String) : Authorization
}

/**
 * Decides whether a tool call may run. It may suspend for as long as it takes the owner to
 * answer a permission or approval request.
 */
fun interface ToolAuthorizer {
    suspend fun authorize(tool: ToolSpec, assessment: ActionAssessment, context: ToolContext): Authorization
}

/** Describes a capability for the owner. */
data class CapabilityInfo(
    val capability: Capability,
    val title: String,
    val description: String,
    val category: ToolCategory,
    /** Usable by any agent that has a tool needing it switched on, without an explicit grant. */
    val grantedByDefault: Boolean,
)

/** Capabilities known to the framework. Later subsystems add theirs to [all] through the registry of tools. */
object Capabilities {
    /** Harmless facts about the framework and the clock. */
    val BASIC_INFO = Capability("basic.info")

    val builtIn: List<CapabilityInfo> = listOf(
        CapabilityInfo(
            BASIC_INFO, "Basic information",
            "Read the current date and time and the list of agents on the team.",
            ToolCategory.SYSTEM, grantedByDefault = true,
        ),
    )
}
