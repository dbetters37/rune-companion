package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.SelfLimitingTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The agent-to-agent tools: asking a teammate and delegating to one. They work only inside a
 * task of an objective (see [TeamDesk]); there, the orchestrator offers them to an agent as
 * its delegation policy allows ([com.aicontrolcenter.core.agents.DelegationPolicy]), whether
 * or not they are switched on in its tool list. Switched on, they show in a direct chat too,
 * where they explain that they need a task.
 *
 * Their capability is granted by default because it decides nothing on its own: the
 * delegation policy, the objective's limits and the loop guards do, on every call.
 */
object TeamTools {
    val ASK_ID = ToolId("team.ask_agent")
    val DELEGATE_ID = ToolId("team.delegate")

    /**
     * A question to the owner (Phase 6, Tier B). Not registered by [registerAll]; where it is
     * registered, a work task offers it while the owner allows agents' questions.
     */
    val ASK_OWNER_ID = ToolId("team.ask_owner")

    val COMMUNICATE = Capability("team.communicate")

    val capability = CapabilityInfo(
        COMMUNICATE, "Work with other agents",
        "Ask teammates questions and, where the agent's delegation settings allow, hand them parts of a task. Only inside the owner's objectives, within their limits.",
        ToolCategory.COMMUNICATION, grantedByDefault = true,
    )

    /**
     * Describes the capability (unless already described) and registers both tools. Call once
     * at start-up, after the orchestrator is built and before any objective runs.
     * @throws IllegalArgumentException if a team tool is already registered.
     */
    fun registerAll(registry: ToolRegistry, capabilities: CapabilityRegistry, orchestrator: DefaultOrchestrator) {
        if (capabilities.info(COMMUNICATE) == null) capabilities.register(capability)
        registry.register(AskAgentTool(orchestrator.desk))
        registry.register(DelegateTool(orchestrator.desk))
    }
}

internal class AskAgentTool(private val desk: TeamDesk) : SelfLimitingTool {
    override val spec = ToolSpec(
        id = TeamTools.ASK_ID,
        functionName = "team_ask_agent",
        title = "Ask a teammate",
        description = "Asks another agent on the team one short question and returns its answer. Use it when your task needs knowledge or a judgement " +
            "a teammate is better placed to give. Name the agent exactly as the team list shows it.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("agent") {
                    put("type", "string")
                    put("description", "The teammate's name.")
                }
                putJsonObject("question") {
                    put("type", "string")
                    put("description", "The question, self-contained: the teammate does not see your task.")
                }
            }
            putJsonArray("required") { add("agent"); add("question") }
            put("additionalProperties", false)
        },
        category = ToolCategory.COMMUNICATION,
        capability = TeamTools.COMMUNICATE,
        risk = RiskLevel.SAFE,
    )

    override suspend fun assess(arguments: JsonObject, context: ToolContext) =
        ActionAssessment(RiskLevel.SAFE, "Ask ${arguments.string("agent")?.take(60) ?: "a teammate"} a question")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val agent = arguments.string("agent") ?: return ToolOutcome.failure("\"agent\" is required.")
        val question = arguments.string("question") ?: return ToolOutcome.failure("\"question\" is required.")
        return desk.ask(context, agent, question)
    }
}

internal class DelegateTool(private val desk: TeamDesk) : SelfLimitingTool {
    override val spec = ToolSpec(
        id = TeamTools.DELEGATE_ID,
        functionName = "team_delegate",
        title = "Delegate to a teammate",
        description = "Hands a well-defined part of your task to another agent, waits for it to finish and returns its result. " +
            "Give self-contained instructions and the criteria that tell when the part is done.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("agent") {
                    put("type", "string")
                    put("description", "The teammate's name.")
                }
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "A short title for the delegated part.")
                }
                putJsonObject("instructions") {
                    put("type", "string")
                    put("description", "What to do, self-contained: the teammate does not see your task.")
                }
                putJsonObject("completion_criteria") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "How to tell the part is done, one criterion per entry.")
                }
            }
            putJsonArray("required") { add("agent"); add("instructions") }
            put("additionalProperties", false)
        },
        category = ToolCategory.COMMUNICATION,
        capability = TeamTools.COMMUNICATE,
        risk = RiskLevel.SAFE,
    )

    override suspend fun assess(arguments: JsonObject, context: ToolContext) =
        ActionAssessment(RiskLevel.SAFE, "Delegate \"${arguments.string("title")?.take(60) ?: "part of the task"}\" to ${arguments.string("agent")?.take(60) ?: "a teammate"}")

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
        val agent = arguments.string("agent") ?: return ToolOutcome.failure("\"agent\" is required.")
        val instructions = arguments.string("instructions") ?: return ToolOutcome.failure("\"instructions\" is required.")
        val criteria = (arguments["completion_criteria"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
        return desk.delegate(context, agent, arguments.string("title").orEmpty(), instructions, criteria)
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
