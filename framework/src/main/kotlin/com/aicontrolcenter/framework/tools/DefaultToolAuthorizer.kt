package com.aicontrolcenter.framework.tools

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
 * A call is allowed only when the tool is switched on for the agent, its capability is one
 * that is granted by default, local-only mode does not forbid it, and the action is not
 * consequential. Everything else is denied with a reason the model can pass on, because this
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
        if (tool.id !in context.agent.toolIds) {
            return Authorization.Denied("The tool \"${tool.title}\" is not switched on for $agent. The owner can switch it on in the agent's settings.")
        }
        val capability = capabilities.info(tool.capability)
        if (capability == null || !capability.grantedByDefault) {
            val name = capability?.title ?: tool.capability.id
            return Authorization.Denied("\"${tool.title}\" needs the \"$name\" permission, which $agent has not been granted.")
        }
        if (tool.sendsDataOffDevice && settings.get().localOnlyMode) {
            return Authorization.Denied("Local-only mode is on, and \"${tool.title}\" would send information off this device.")
        }
        if (assessment.risk == RiskLevel.CONSEQUENTIAL) {
            return Authorization.Denied("This action needs the owner's approval, and it cannot be asked for here: ${assessment.summary}")
        }
        return Authorization.Allowed
    }
}
