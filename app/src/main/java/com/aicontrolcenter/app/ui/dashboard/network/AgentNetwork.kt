package com.aicontrolcenter.app.ui.dashboard.network

import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentRuntimeState
import com.aicontrolcenter.core.registry.RegistrySnapshot

/*
 * The data model of the agent network visualisation: nodes, edges and (optional) pulses.
 * It is plain immutable data with no Compose or Android types, so the mapping from the
 * registry, the layout and the hit geometry are all unit-tested on the JVM. The view only
 * draws what this model says.
 */

/** One agent in the network. */
data class NetworkNode(
    val id: String,
    val name: String,
    val role: AgentKind,
    val colorIndex: Int,
    val status: StatusLabel,
    /** The designated Director, drawn at the centre. At most one node is the hub. */
    val isHub: Boolean,
    /** Running a turn right now; [status] then says what it is doing. */
    val working: Boolean = false,
)

/** A standing connection: [fromId] reports to [toId]. */
data class NetworkEdge(
    val fromId: String,
    val toId: String,
    /** True when both ends can run right now, so messages could actually flow. */
    val live: Boolean,
)

/**
 * Activity along an edge, [progress] 0 at the sender and 1 at the recipient. While an agent
 * works, its edge carries a pulse that the view keeps moving (from [progress] onwards), so
 * working agents stand out at a glance. The message bus of a later phase animates real
 * traffic through the same type.
 */
data class NetworkPulse(
    val fromId: String,
    val toId: String,
    val progress: Float,
    val colorIndex: Int,
)

data class AgentNetwork(
    val nodes: List<NetworkNode> = emptyList(),
    val edges: List<NetworkEdge> = emptyList(),
    val pulses: List<NetworkPulse> = emptyList(),
) {
    val hub: NetworkNode? get() = nodes.firstOrNull { it.isHub }

    fun node(id: String?): NetworkNode? = if (id == null) null else nodes.firstOrNull { it.id == id }
}

/**
 * The reporting structure of the registry: every other agent reports to the designated
 * Director. Without a designated Director there is no structure, so there are no edges.
 * Agents in [working] are shown as working, and their edge carries a pulse.
 */
fun RegistrySnapshot.toAgentNetwork(working: Map<AgentId, AgentRuntimeState> = emptyMap()): AgentNetwork {
    val nodes = agents.map { entry ->
        val busy = working[entry.profile.id]
        NetworkNode(
            id = entry.profile.id.value,
            name = entry.profile.name,
            role = entry.profile.kind,
            colorIndex = entry.profile.colorIndex,
            status = if (busy != null) {
                StatusLabel("Working", StatusTone.WORKING, busy.detail)
            } else {
                entry.availability.toStatusLabel()
            },
            isHub = entry.isDesignatedDirector,
            working = busy != null,
        )
    }
    val director = director ?: return AgentNetwork(nodes = nodes)
    val edges = agents.filterNot { it.isDesignatedDirector }.map { entry ->
        NetworkEdge(
            fromId = entry.profile.id.value,
            toId = director.profile.id.value,
            live = entry.availability.isReady && director.availability.isReady,
        )
    }
    val pulses = agents.filterNot { it.isDesignatedDirector }.filter { it.profile.id in working }.map { entry ->
        NetworkPulse(entry.profile.id.value, director.profile.id.value, progress = 0.55f, colorIndex = entry.profile.colorIndex)
    }
    return AgentNetwork(nodes = nodes, edges = edges, pulses = pulses)
}
