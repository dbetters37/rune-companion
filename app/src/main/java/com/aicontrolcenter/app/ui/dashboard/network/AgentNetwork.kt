package com.aicontrolcenter.app.ui.dashboard.network

import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.core.agents.AgentKind
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
)

/** A standing connection: [fromId] reports to [toId]. */
data class NetworkEdge(
    val fromId: String,
    val toId: String,
    /** True when both ends can run right now, so messages could actually flow. */
    val live: Boolean,
)

/**
 * A message in flight along an edge, [progress] 0 at the sender and 1 at the recipient.
 * Phase 1 has no agent messaging, so the registry mapping never produces pulses; the type
 * exists so the message bus of a later phase can animate real traffic through the same view.
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
 */
fun RegistrySnapshot.toAgentNetwork(): AgentNetwork {
    val nodes = agents.map { entry ->
        NetworkNode(
            id = entry.profile.id.value,
            name = entry.profile.name,
            role = entry.profile.kind,
            colorIndex = entry.profile.colorIndex,
            status = entry.availability.toStatusLabel(),
            isHub = entry.isDesignatedDirector,
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
    return AgentNetwork(nodes = nodes, edges = edges)
}
