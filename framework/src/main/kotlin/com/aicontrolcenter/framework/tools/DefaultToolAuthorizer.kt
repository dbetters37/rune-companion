package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolAuthorizer
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolSpec

/**
 * Decides tool calls without asking anyone: the rules that need no owner in the loop.
 *
 * A call is allowed only when the agent is switched on and has the tool switched on, its
 * capability is one that is granted by default, local-only mode does not forbid it, and the
 * action is not consequential. The risk of a call is the higher of the tool's declared risk and
 * what [com.aicontrolcenter.core.tools.Tool.assess] said about this call: an assessment can
 * raise the risk, never lower it. Everything else is denied with a reason the model can pass on, because this
 * class cannot raise a permission or approval request. The permission broker that can (with
 * per-agent grants and owner prompts) implements the same [ToolAuthorizer] and takes this
 * class's place in [ToolExecutor]; nothing else changes.
 */
class DefaultToolAuthorizer(
    private val settings: SettingsRepository,
    private val capabilities: CapabilityRegistry,
) : ToolAuthorizer {

    override suspend fun authorize(tool: ToolSpec, assessment: ActionAssessment, context: ToolContext): Authorization {
        val agent = context.agent.name
        if (!context.agent.enabled) return Authorization.Denied(agentSwitchedOff(context.agent))
        if (tool.id !in context.agent.toolIds) return Authorization.Denied(toolNotSwitchedOn(tool, context.agent))
        val capability = capabilities.info(tool.capability)
        if (capability == null || !capability.grantedByDefault) {
            val name = capability?.title ?: tool.capability.id
            return Authorization.Denied("\"${tool.title}\" needs the \"$name\" permission, which $agent has not been granted.")
        }
        if (tool.sendsDataOffDevice && settings.get().localOnlyMode) {
            return Authorization.Denied("Local-only mode is on, and \"${tool.title}\" would send information off this device.")
        }
        val risk = maxOf(tool.risk, assessment.risk)
        if (risk == RiskLevel.CONSEQUENTIAL) {
            return Authorization.Denied("This action needs the owner's approval, and it cannot be asked for here: ${assessment.summary}")
        }
        return Authorization.Allowed
    }
}

/** Why a tool the agent does not have was not run. The same words whoever refuses it, so the refusal reveals nothing else. */
internal fun toolNotSwitchedOn(tool: ToolSpec, agent: AgentProfile): String =
    "The tool \"${tool.title}\" is not switched on for ${agent.name}. The owner can switch it on in the agent's settings."

internal fun agentSwitchedOff(agent: AgentProfile): String = "${agent.name} is switched off, so it cannot use tools."
