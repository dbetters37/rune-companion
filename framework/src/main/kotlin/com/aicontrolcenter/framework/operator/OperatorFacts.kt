package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.registry.AgentDirectory
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import kotlinx.coroutines.flow.StateFlow

/**
 * Reads what [OperatorGate.refusal] decides on, fresh from the stores on every call: the agent
 * as stored (not a run's snapshot of it), the settings, the owner's Stop, the folder of a folder
 * constraint, the authorised apps and the protected packages. Shared by [PermissionBroker] and
 * [DefaultCapabilityRequester], so both refuse the same calls with the same words.
 *
 * It only reads: it gets read-only lookups of the folders and the authorised apps, never their
 * stores, and nothing here reaches an owner-only port.
 */
class OperatorFacts(
    private val agents: AgentDirectory,
    private val settings: SettingsView,
    private val capabilities: CapabilityRegistry,
    private val folders: FolderDirectory,
    private val apps: AuthorisedApps,
    /** The owner's Stop, read-only: [com.aicontrolcenter.core.operator.ScreenOperator.stopped]. */
    private val screenStopped: StateFlow<Boolean>,
    /** The protected packages as resolved at start-up (`:device`); read on every check. */
    private val protectedPackages: () -> ProtectedPackages,
) {
    fun capabilityInfo(capability: Capability): CapabilityInfo? = capabilities.info(capability)

    suspend fun settings(): FrameworkSettings = settings.get()

    suspend fun agent(id: AgentId): AgentProfile? = agents.get(id)

    /**
     * The facts about one tool call by [agentId], with [extraToolIds] (the tools the framework
     * offered this run on top of the agent's own, such as the team tools inside a task) counted
     * as switched on, like the agent runner counts them.
     */
    suspend fun forCall(agentId: AgentId, spec: ToolSpec, assessment: ActionAssessment, extraToolIds: Set<ToolId> = emptySet()): OperatorGate.Facts {
        val stored = agents.get(agentId)
        val agent = if (stored != null && extraToolIds.isNotEmpty()) stored.copy(toolIds = stored.toolIds + extraToolIds) else stored
        return facts(agent, spec.id, spec.title, spec.capability, assessment.constraint, assessment.leadsTo, spec.sendsDataOffDevice)
    }

    /**
     * The facts about an agent asking for [capability] itself. The ask counts as sending data off
     * the device when any tool needing the capability does, so local-only mode refuses it.
     */
    suspend fun forAsk(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint): OperatorGate.Facts =
        facts(
            agents.get(agentId), null, null, capability, constraint, null,
            OperatorTools.all.any { it.capability == capability && it.sendsDataOffDevice },
        )

    /** What the owner calls the target of [constraint] now: the folder's name or the app's label; null without one. */
    suspend fun targetLabel(constraint: CapabilityConstraint): String? = when (constraint) {
        is CapabilityConstraint.Folder -> folders.get(constraint.folderId)?.displayName
        is CapabilityConstraint.App -> apps.get(constraint.packageName)?.label ?: constraint.packageName
        CapabilityConstraint.None -> null
    }

    /**
     * Why access to [constraint] cannot be given now: the folder was removed, the app is
     * protected or no longer authorised, or the owner pressed Stop on screen control. Null when
     * it can, or without a constraint. The owner's answers check it, as direct grants do.
     */
    suspend fun targetRefusal(constraint: CapabilityConstraint): String? = when (constraint) {
        is CapabilityConstraint.Folder -> if (folders.get(constraint.folderId) == null) "The folder was removed, so this was not allowed." else null
        is CapabilityConstraint.App -> {
            val name = constraint.packageName
            when {
                protectedPackages().isProtected(name) -> "$name is never operated by agents, so this was not allowed."
                apps.get(name) == null -> "$name is no longer authorised for screen control, so this was not allowed."
                screenStopped.value -> "You pressed Stop on screen control, so this was not allowed."
                else -> null
            }
        }
        CapabilityConstraint.None -> null
    }

    private suspend fun facts(
        agent: AgentProfile?,
        toolId: ToolId?,
        toolTitle: String?,
        capability: Capability,
        constraint: CapabilityConstraint,
        leadsTo: String?,
        sendsDataOffDevice: Boolean,
    ): OperatorGate.Facts {
        // The gate asks only about the call's own app and the app it leads to.
        val packages = listOfNotNull((constraint as? CapabilityConstraint.App)?.packageName, leadsTo)
        return OperatorGate.Facts(
            agent = agent, toolId = toolId, toolTitle = toolTitle, capability = capability,
            capabilityInfo = capabilities.info(capability), constraint = constraint, leadsTo = leadsTo,
            sendsDataOffDevice = sendsDataOffDevice, settings = settings.get(), screenStopped = screenStopped.value,
            folder = (constraint as? CapabilityConstraint.Folder)?.let { folders.get(it.folderId) },
            authorisedApps = packages.filterTo(HashSet()) { apps.get(it) != null },
            protectedPackages = protectedPackages(),
        )
    }
}
