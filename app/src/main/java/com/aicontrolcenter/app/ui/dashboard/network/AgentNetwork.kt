package com.aicontrolcenter.app.ui.dashboard.network

import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentActivity
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
    /**
     * Waiting for the owner or for a teammate (N3): its run is under way but not working itself, so it is never shown as
     * working; [status] says for whom it waits.
     */
    val waiting: Boolean = false,
)

enum class EdgeKind {
    /** [NetworkEdge.fromId] reports to the Director. */
    REPORTS,

    /** Two agents that exchanged messages in a running objective, outside the reporting lines. */
    EXCHANGE,
}

/** A connection between two agents. */
data class NetworkEdge(
    val fromId: String,
    val toId: String,
    /** True when both ends can run right now, so messages could actually flow. */
    val live: Boolean,
    val kind: EdgeKind = EdgeKind.REPORTS,
)

/**
 * A message an agent sent to another agent in a running objective, which can travel along its edge as a dot from sender
 * to recipient. The card sends a dot only once per message, when the message is new to it (N2: never the newest ones
 * looping); with Calm or Android's animations off it sends none.
 */
data class NetworkPulse(
    val fromId: String,
    val toId: String,
    val colorIndex: Int,
    val messageId: String,
)

/** One agent-to-agent message of a running objective: what the network animates. */
data class AgentTraffic(val fromId: AgentId, val toId: AgentId, val messageId: String)

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
 * Director. Without a designated Director there is no structure, so there are no reporting edges.
 *
 * [traffic] (agent-to-agent messages of running objectives, newest first) adds what is actually
 * happening: an edge between two agents that exchanged messages outside the reporting lines, and
 * the newest [MESSAGE_CANDIDATES] messages as pulses in sending order, of which the card sends a dot
 * only for those it has not seen. Agents in [working] are shown as working when they work, and as
 * waiting for you or for a teammate when that is what their run does (N3).
 */
fun RegistrySnapshot.toAgentNetwork(
    working: Map<AgentId, AgentRuntimeState> = emptyMap(),
    traffic: List<AgentTraffic> = emptyList(),
): AgentNetwork {
    val nodes = agents.map { entry ->
        val busy = working[entry.profile.id]
        val status = when (busy?.activity) {
            AgentActivity.WORKING -> StatusLabel("Working", StatusTone.WORKING, busy.detail)
            AgentActivity.WAITING_FOR_APPROVAL -> StatusLabel("Waiting for you", StatusTone.ATTENTION, busy.detail)
            AgentActivity.WAITING_FOR_AGENT -> StatusLabel("Waiting for a teammate", StatusTone.WAITING, busy.detail)
            else -> entry.availability.toStatusLabel()
        }
        NetworkNode(
            id = entry.profile.id.value,
            name = entry.profile.name,
            role = entry.profile.kind,
            colorIndex = entry.profile.colorIndex,
            status = status,
            isHub = entry.isDesignatedDirector,
            working = busy?.activity == AgentActivity.WORKING,
            waiting = busy?.activity == AgentActivity.WAITING_FOR_APPROVAL || busy?.activity == AgentActivity.WAITING_FOR_AGENT,
        )
    }
    val known = agentsById.keys
    val shown = traffic.filter { it.fromId != it.toId && it.fromId in known && it.toId in known }
    val director = director
    val reports = if (director == null) emptyList() else agents.filterNot { it.isDesignatedDirector }.map { entry ->
        NetworkEdge(
            fromId = entry.profile.id.value,
            toId = director.profile.id.value,
            live = entry.availability.isReady && director.availability.isReady,
        )
    }
    // Pairs already joined by a reporting line need no second edge.
    val exchanges = shown.map { setOf(it.fromId, it.toId) }.distinct()
        .filter { pair -> director == null || director.profile.id !in pair }
        .map { pair ->
            val (a, b) = pair.sortedBy { it.value }
            NetworkEdge(a.value, b.value, live = true, kind = EdgeKind.EXCHANGE)
        }
    val pulses = shown.take(MESSAGE_CANDIDATES).asReversed().map { message ->
        NetworkPulse(
            fromId = message.fromId.value,
            toId = message.toId.value,
            colorIndex = agentsById[message.fromId]?.profile?.colorIndex ?: 0,
            messageId = message.messageId,
        )
    }
    return AgentNetwork(nodes = nodes, edges = reports + exchanges, pulses = pulses)
}

/** How many of the newest messages the card looks at for new dots; only new ones travel, once each. */
const val MESSAGE_CANDIDATES = 12
