package com.aicontrolcenter.core.tools

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.RequestId
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
    /** The conversation the call happens in, so an owner request can be shown inline there. */
    val conversationId: ConversationId? = null,
)

/** What one specific call would do, described before it runs. */
data class ActionAssessment(
    val risk: RiskLevel,
    /** One sentence the owner can approve or refuse, e.g. "Delete 3 files in Documents/Reports". */
    val summary: String,
    val details: List<String> = emptyList(),
    /**
     * The folder or app this call is limited to. Must match [CapabilityInfo.constrainedBy] of the
     * tool's capability: grants are looked up with it, and a constrained capability with
     * [CapabilityConstraint.None] here is refused.
     */
    val constraint: CapabilityConstraint = CapabilityConstraint.None,
    /** Where information from this call goes if it leaves this app, in words ("example.com", "Gmail"). Null: it stays here. */
    val dataGoesTo: String? = null,
    /**
     * The exact action [summary] describes, resolved during [Tool.assess] (the file set, the
     * screen element, the folder state). [Tool.execute] acts on this, never on a fresh lookup,
     * so what runs is what was approved. Kept in memory only; never stored or logged.
     */
    val prepared: PreparedAction? = null,
    /**
     * The app this call hands control to, when that is not the constraint's app (the launcher
     * for a HOME action). The broker refuses the call unless that app is authorised too.
     */
    val leadsTo: String? = null,
)

/**
 * What [Tool.assess] resolved for one call, carried unchanged to [Tool.execute]. Implementations
 * hold the resolved targets plus what the assessment relied on (file count and sizes, element
 * identity, folder read-only state, the app in front).
 */
interface PreparedAction {
    /**
     * Re-checks, just before acting, everything the assessment relied on. Null when it all still
     * holds; otherwise a short phrase naming what changed ("the folder now holds 300 files"), and
     * the tool returns [ToolOutcome.changedSinceApproved] without acting.
     */
    suspend fun changedSince(): String?
}

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

        /** The refusal for a call whose [PreparedAction.changedSince] found a change: nothing was done. */
        fun changedSinceApproved(what: String) =
            failure("Nothing was done: $what since this action was checked and approved. Look again and ask anew if it is still needed.")
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

    /**
     * Runs the call as [assessment] described it; the framework always calls this one, with the
     * same assessment object it authorised. A tool whose assessment carries a
     * [ActionAssessment.prepared] action overrides this: it first calls
     * [PreparedAction.changedSince] and returns [ToolOutcome.changedSinceApproved] if anything
     * changed, then acts on the prepared targets only. Every MODIFYING or CONSEQUENTIAL operator
     * tool works this way. The default ignores the assessment.
     */
    suspend fun execute(arguments: JsonObject, context: ToolContext, assessment: ActionAssessment): ToolOutcome =
        execute(arguments, context)
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

    /** Allowed because the owner answered [requestIds] (permission, then approval), so the call's log can name them. */
    data class AllowedByOwner(val requestIds: List<RequestId>) : Authorization

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
    /** What every grant of this capability, and every call needing it, is narrowed to. */
    val constrainedBy: ConstraintKind = ConstraintKind.NONE,
)

/**
 * Capabilities known to the framework. Later subsystems add theirs through the capability
 * registry; the phone operator's are in `core.operator.OperatorCapabilities`.
 */
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
