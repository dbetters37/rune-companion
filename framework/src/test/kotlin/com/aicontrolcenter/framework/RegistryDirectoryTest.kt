package com.aicontrolcenter.framework

import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryDirectoryTest {
    private val world = TestWorld()

    @Test
    fun `an empty registry is an empty snapshot`() = runTest {
        val snapshot = world.directory.snapshot()
        assertTrue(snapshot.agents.isEmpty() && snapshot.providers.isEmpty() && snapshot.models.isEmpty() && snapshot.apps.isEmpty())
        assertNull(snapshot.director)
        assertEquals(0, snapshot.readyAgents)
    }

    @Test
    fun `the snapshot joins agents with models, providers, stats and the director setting`() = runTest {
        world.agentManager.ensureDefaults()
        val team = world.agents.getAll()
        world.providers.upsert(Samples.provider(id = "prv_cloud", locality = Locality.CLOUD, baseUrl = "https://api.example.com"))
        world.models.upsert(Samples.model(id = "mdl_cloud", providerId = "prv_cloud"))
        val researcher = team.first { it.name == "Researcher" }
        val writer = team.first { it.name == "Writer" }
        world.agentManager.assignModel(researcher.id, Samples.model(id = "mdl_cloud").id)
        world.agentManager.setEnabled(writer.id, false)
        world.agents.updateStats(researcher.id) { it.copy(tasksCompleted = 3) }
        world.apps.upsert(Samples.aiApp())

        val snapshot = world.directory.snapshot()
        assertEquals(team.map { it.name }, snapshot.agents.map { it.profile.name })
        assertEquals(1, snapshot.readyAgents)
        assertEquals(1, snapshot.disabledAgents)
        assertEquals(4, snapshot.unavailableAgents)
        assertEquals(1, snapshot.installedApps)
        assertEquals(AgentKind.DIRECTOR, snapshot.director?.profile?.kind)
        val entry = snapshot.agentsById.getValue(researcher.id)
        assertIs<AgentAvailability.Ready>(entry.availability)
        assertEquals(3, entry.stats.tasksCompleted)
        assertEquals("Home server", snapshot.provider(snapshot.models.single())?.name)
        val unassigned = assertIs<AgentAvailability.Unavailable>(snapshot.director!!.availability)
        assertEquals(UnavailableReason.NO_MODEL_ASSIGNED, unassigned.reason)
    }

    @Test
    fun `availability follows provider status and local-only mode live`() = runTest {
        world.providers.upsert(Samples.provider(id = "prv_cloud", locality = Locality.CLOUD))
        world.models.upsert(Samples.model(id = "mdl_cloud", providerId = "prv_cloud"))
        val agent = world.agentManager.create(com.aicontrolcenter.core.agents.AgentDraft(name = "A", modelId = Samples.model(id = "mdl_cloud").id))
        suspend fun availability() = world.directory.observe().first().agentsById.getValue(agent.id).availability

        assertTrue(availability().isReady)
        world.providers.upsert(Samples.provider(id = "prv_cloud", locality = Locality.CLOUD, status = ConnectionStatus.OFFLINE))
        assertEquals(UnavailableReason.PROVIDER_OFFLINE, assertIs<AgentAvailability.Unavailable>(availability()).reason)
        world.providers.upsert(Samples.provider(id = "prv_cloud", locality = Locality.CLOUD))
        world.settings.update { it.copy(localOnlyMode = true) }
        assertEquals(UnavailableReason.BLOCKED_BY_LOCAL_ONLY_MODE, assertIs<AgentAvailability.Unavailable>(availability()).reason)
        world.settings.update { it.copy(localOnlyMode = false) }
        world.models.delete(Samples.model(id = "mdl_cloud").id)
        assertEquals(UnavailableReason.MODEL_NOT_REGISTERED, assertIs<AgentAvailability.Unavailable>(availability()).reason)
    }
}
