package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import kotlinx.coroutines.flow.first

/*
 * "Which agents may use it" for one shared folder or one authorised app. Each agent gets one of
 * three levels, kept as standing (ALWAYS) grants narrowed to that folder or app through
 * OwnerGrants, the only way the owner gives a permission directly. Lowering a level revokes every
 * grant it covered, whatever its scope, so "No access" really means none.
 */

/** How far an agent may go with one folder or app. */
enum class AccessLevel { NONE, READ, CHANGE }

/** The two permissions a folder or app is given with, and the tools that use them. */
data class AccessKind(
    /** Reading: "folders.read" or "screen.read". */
    val read: Capability,
    /** Changing: "folders.write" or "screen.operate". Given only together with [read]. */
    val change: Capability,
    /** The family of tools that use these permissions. */
    val family: ToolFamily,
    /** The owner's words for each level, in [AccessLevel] order. */
    val labels: List<String>,
)

/** One agent's access to one folder or app. */
@Immutable
data class AgentAccessUi(
    val agent: AgentFaceUi,
    val level: AccessLevel,
    /** The agent has at least one tool of the family switched on; without one, the access changes nothing yet. */
    val hasTools: Boolean,
    /** What the level does not show (a grant for one task, or changing without reading), or null. */
    val note: String?,
)

/** Each agent's access to [constraint], from the grants in force at [now]. Agents by name. */
fun agentAccessOf(
    agents: List<AgentProfile>,
    grants: List<PermissionGrant>,
    constraint: CapabilityConstraint,
    kind: AccessKind,
    familyOf: (AgentProfile) -> Set<ToolFamily>,
    now: Long,
): List<AgentAccessUi> = agents.sortedBy { it.name.lowercase() }.map { agent ->
    val own = grants.filter { it.agentId == agent.id && it.constraint == constraint && it.isActiveAt(now) }
    val standing = own.filter { it.scope == GrantScope.ALWAYS }.map { it.capability }.toSet()
    val canRead = kind.read in standing
    val canChange = kind.change in standing
    val forTask = own.any { it.scope != GrantScope.ALWAYS }
    AgentAccessUi(
        agent = AgentFaceUi(agent.id, agent.name, agent.colorIndex),
        level = when {
            canChange -> AccessLevel.CHANGE
            canRead -> AccessLevel.READ
            else -> AccessLevel.NONE
        },
        hasTools = kind.family in familyOf(agent),
        note = listOfNotNull(
            "Allowed to change, but not to read: choose a level to set both.".takeIf { canChange && !canRead },
            "Also allowed for a task in progress (see Allowed).".takeIf { forTask },
        ).joinToString(" ").ifEmpty { null },
    )
}

/**
 * Sets [agentId]'s access to [constraint] to [level] through [grants]: gives the standing grants
 * the level needs and revokes every grant (any scope) above it. Returns the owner's refusal, if
 * one step was refused (the folder was removed meanwhile, the app is protected), else null.
 */
suspend fun applyAccess(grants: OwnerGrants, agentId: AgentId, constraint: CapabilityConstraint, kind: AccessKind, level: AccessLevel): String? {
    val wanted = when (level) {
        AccessLevel.NONE -> emptyList()
        AccessLevel.READ -> listOf(kind.read)
        AccessLevel.CHANGE -> listOf(kind.read, kind.change)
    }
    // Narrow first, so a refusal below never leaves more access than asked for.
    val current = grants.observeAll().first().filter { it.agentId == agentId && it.constraint == constraint }
    for (grant in current) {
        if (grant.capability in listOf(kind.read, kind.change) && grant.capability !in wanted) grants.revoke(grant.id)
    }
    for (capability in wanted) {
        when (val result = grants.grant(agentId, capability, constraint)) {
            is OwnerResult.Refused -> return result.reason
            is OwnerResult.Done -> Unit
        }
    }
    return null
}

/** The families of the tools [agent] has switched on, from their ids' catalogue families. */
fun familiesOf(agent: AgentProfile, familyOfTool: (com.aicontrolcenter.core.ToolId) -> ToolFamily?): Set<ToolFamily> =
    agent.toolIds.mapNotNull(familyOfTool).toSet()
