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
 * Activity along an edge, [progress] 0 at the sender and 1 at the recipient; the view keeps it
 * moving from [progress] onwards. A pulse with a [messageId] is a message an agent sent in a
 * running objective, travelling from sender to recipient; one without is an agent at work in a
 * chat, travelling towards the Director.
 */
data class NetworkPulse(
    val fromId: String,
    val toId: String,
    val progress: Float,
    val colorIndex: Int,
    val messageId: String? = null,
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
 * happening: an edge between two agents that exchanged messages outside the reporting lines,
 * and a pulse for each of the newest [MAX_MESSAGE_PULSES] messages, from sender to recipient.
 * Agents in [working] are shown as working; one that is not already sending a message gets a
 * pulse towards the Director.
 */
fun RegistrySnapshot.toAgentNetwork(
    working: Map<AgentId, AgentRuntimeState> = emptyMap(),
    traffic: List<AgentTraffic> = emptyList(),
): AgentNetwork {
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
    val messagePulses = shown.take(MAX_MESSAGE_PULSES).mapIndexed { index, message ->
        NetworkPulse(
            fromId = message.fromId.value,
            toId = message.toId.value,
            progress = (0.15f + index * 0.22f) % 1f,
            colorIndex = agentsById[message.fromId]?.profile?.colorIndex ?: 0,
            messageId = message.messageId,
        )
    }
    val sending = messagePulses.map { it.fromId }.toSet()
    val workPulses = if (director == null) emptyList() else agents.filterNot { it.isDesignatedDirector }
        .filter { it.profile.id in working && it.profile.id.value !in sending }
        .map { entry -> NetworkPulse(entry.profile.id.value, director.profile.id.value, progress = 0.55f, colorIndex = entry.profile.colorIndex) }
    return AgentNetwork(nodes = nodes, edges = reports + exchanges, pulses = messagePulses + workPulses)
}

/** How many of the newest messages travel through the network at once; more would be noise. */
const val MAX_MESSAGE_PULSES = 4
