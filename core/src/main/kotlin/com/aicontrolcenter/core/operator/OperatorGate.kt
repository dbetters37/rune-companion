package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.CapabilityInfo

/**
 * The permission broker's steps 1–3 (agent and tool switched on, the capability and its target
 * usable, master switches and local-only mode) as one pure function, shared by the broker and
 * [CapabilityRequester] so both refuse the same calls with the same words. Callers read every
 * fact fresh for each check, and again after any wait for the owner: the agent from its
 * repository (not the run's snapshot), the settings, the Stop flag, the folder and the
 * authorised apps.
 */
object OperatorGate {
    /** Everything [refusal] looks at. */
    data class Facts(
        /** The agent as stored now; null when it was deleted. */
        val agent: AgentProfile?,
        /** The tool being called, or null for an agent's own ask. */
        val toolId: ToolId?,
        /** The tool's title for messages, or null for an ask. */
        val toolTitle: String?,
        /** The capability needed. */
        val capability: Capability,
        /** How the capability is registered; null when it is unknown. */
        val capabilityInfo: CapabilityInfo?,
        val constraint: CapabilityConstraint,
        /** [com.aicontrolcenter.core.tools.ActionAssessment.leadsTo], if any. */
        val leadsTo: String? = null,
        val sendsDataOffDevice: Boolean,
        val settings: FrameworkSettings,
        val screenStopped: Boolean,
        /** The folder of a folder constraint, as stored now; null when it does not exist. */
        val folder: SharedFolder?,
        /** Package names authorised for screen operation now, with a verified certificate. */
        val authorisedApps: Set<String>,
        val protectedPackages: ProtectedPackages,
    )

    /**
     * The agents' own workspace, a folder inside this app. Using it never touches anything else on the phone, so it
     * does not need [FrameworkSettings.operatorEnabled]; changing it still needs the owner's grant like any tool.
     */
    val workspaceCapabilities: Set<Capability> = setOf(OperatorCapabilities.WORKSPACE_READ, OperatorCapabilities.WORKSPACE_WRITE)

    /** Capabilities that need [FrameworkSettings.operatorEnabled]: everything outside the agents' own workspace. */
    val operatorCapabilities: Set<Capability> = OperatorCapabilities.all.map { it.capability }.toSet() - workspaceCapabilities

    /** Why the call or ask must be refused before any request is raised, or null to go on to the grant check. */
    fun refusal(f: Facts): String? {
        val agent = f.agent ?: return "This agent no longer exists."
        if (!agent.enabled) return "${agent.name} is switched off, so it cannot use tools."
        if (f.toolId != null && f.toolId !in agent.toolIds) {
            return "The tool \"${f.toolTitle ?: f.toolId.value}\" is not switched on for ${agent.name}. The owner can switch it on in the agent's settings."
        }
        val info = f.capabilityInfo ?: return "\"${f.capability.id}\" is not a permission this app knows."
        val what = f.toolTitle?.let { "\"$it\"" } ?: "\"${info.title}\""
        if (info.constrainedBy != f.constraint.kind) {
            return "$what named the wrong kind of target for \"${info.title}\", so it was not run."
        }
        if (f.capability in operatorCapabilities && !f.settings.operatorEnabled) {
            return "Phone operation is switched off in Settings, so $what cannot be used."
        }
        when (val c = f.constraint) {
            is CapabilityConstraint.Folder -> if (f.folder?.id != c.folderId) return "That shared folder was removed or is not available."
            is CapabilityConstraint.App -> {
                if (!f.settings.accessibilityOperatorEnabled) return "Screen control is switched off in Settings."
                if (f.screenStopped) return "The owner pressed Stop on screen control. Screen actions are refused until the owner resumes."
                appRefusal(c.packageName, f)?.let { return it }
            }
            CapabilityConstraint.None -> Unit
        }
        f.leadsTo?.let { target -> appRefusal(target, f)?.let { return "This action would hand control to another app: $it" } }
        if (f.sendsDataOffDevice && f.settings.localOnlyMode) {
            return "Local-only mode is on, and $what would send information off this device."
        }
        return null
    }

    private fun appRefusal(packageName: String, f: Facts): String? = when {
        f.protectedPackages.isProtected(packageName) -> "$packageName is a protected app (system settings, installers, this app); agents never operate it."
        packageName !in f.authorisedApps -> "$packageName is not an app the owner authorised for screen control."
        else -> null
    }
}
