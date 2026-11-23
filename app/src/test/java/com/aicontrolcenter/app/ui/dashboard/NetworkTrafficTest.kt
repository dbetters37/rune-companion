package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.ui.dashboard.network.AgentTraffic
import com.aicontrolcenter.app.ui.dashboard.network.EdgeKind
import com.aicontrolcenter.app.ui.dashboard.network.MAX_MESSAGE_PULSES
import com.aicontrolcenter.app.ui.dashboard.network.toAgentNetwork
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentRuntimeState
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.testkit.Samples
import org.junit.Test
import kotlin.test.assertEquals

/** Messages of running objectives in the agent network: edges between who talked, and pulses from sender to recipient. */
class NetworkTrafficTest {
    private val director = AgentId("agt_d")
    private val researcher = AgentId("agt_r")
    private val analyst = AgentId("agt_a")
    private val writer = AgentId("agt_w")

    private fun entry(id: AgentId, name: String, colorIndex: Int, kind: AgentKind = AgentKind.SPECIALIST) =
        AgentEntry(Samples.agent(id = id.value, name = name, kind = kind).copy(colorIndex = colorIndex), AgentAvailability.Disabled, AgentStats(), isDesignatedDirector = id == director)

    private val snapshot = RegistrySnapshot(
        agents = listOf(entry(director, "Director", 0, AgentKind.DIRECTOR), entry(researcher, "Researcher", 1), entry(analyst, "Analyst", 2), entry(writer, "Writer", 3)),
    )

    @Test
    fun `agents that talked outside the reporting lines are joined, and the newest messages travel`() {
        val traffic = listOf(
            AgentTraffic(analyst, researcher, "m5"),
            AgentTraffic(researcher, analyst, "m4"),
            AgentTraffic(director, analyst, "m3"),
            AgentTraffic(researcher, director, "m2"),
            AgentTraffic(director, researcher, "m1"),
            AgentTraffic(AgentId("agt_gone"), writer, "m0"),
        )
        val network = snapshot.toAgentNetwork(traffic = traffic)
        assertEquals(3, network.edges.count { it.kind == EdgeKind.REPORTS })
        val exchanges = network.edges.filter { it.kind == EdgeKind.EXCHANGE }
        assertEquals(listOf(setOf(analyst.value, researcher.value)), exchanges.map { setOf(it.fromId, it.toId) }, "one edge per pair, none to the Director")
        assertEquals(listOf("m5", "m4", "m3", "m2"), network.pulses.map { it.messageId }, "the newest $MAX_MESSAGE_PULSES messages")
        assertEquals(analyst.value to researcher.value, network.pulses.first().let { it.fromId to it.toId })
        assertEquals(2, network.pulses.first().colorIndex, "a pulse has its sender's colour")
    }

    @Test
    fun `an agent working in a chat pulses towards the Director unless it is already sending a message`() {
        val working = mapOf(
            researcher to AgentRuntimeState(researcher, AgentActivity.WORKING, "Waiting for the model"),
            writer to AgentRuntimeState(writer, AgentActivity.WORKING, "Writing"),
        )
        val network = snapshot.toAgentNetwork(working, listOf(AgentTraffic(researcher, analyst, "m1")))
        assertEquals(listOf("m1" to researcher.value, null to writer.value), network.pulses.map { it.messageId to it.fromId })
    }

    @Test
    fun `without a Director, messages still draw their edges`() {
        val network = snapshot.copy(agents = snapshot.agents.map { it.copy(isDesignatedDirector = false) })
            .toAgentNetwork(traffic = listOf(AgentTraffic(director, writer, "m1")))
        assertEquals(listOf(EdgeKind.EXCHANGE), network.edges.map { it.kind })
    }
}
