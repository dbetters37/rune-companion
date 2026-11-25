package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.testkit.FakeProviderAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave-2a merge: WP4's limiter in the gateway and WP7's pause meet. Until now each side was tested with the other one
 * scripted (WP7 with a model that fails with the limit's error). Here the team's agents work on a model off the phone,
 * the gateway's own limiter refuses the call that would be the third answer of the day, nothing is sent for it, and the
 * objective pauses instead of failing; once the owner raised the limit, Resume finishes it.
 */
class DailyLimitGatewayPauseTest {

    @Test
    fun `the gateway's own daily limit pauses an objective before anything more is sent, and raising it lets the objective finish`() = runTest {
        val team = TeamFixture(this) { copy(maxModelCallsPerDay = 2) }.setUp()
        // Every agent works on a model off the phone, which the daily limits count (models on the phone are not limited).
        val provider = team.world.provider(FakeProviderAdapter(client = team.script.client), baseUrl = "https://api.example.com/v1", name = "Cloud")
        val cloud = team.world.model(provider, capabilities = ModelCapabilities(toolCalling = true, jsonOutput = true))
        for (agent in listOf(team.director, team.researcher, team.analyst, team.writer, team.reviewer)) {
            team.world.agents.upsert(agent.copy(modelId = cloud.id))
        }

        val objective = team.start("Research note apps")
        team.settle()

        val paused = team.objective(objective.id)
        assertEquals(TaskStatus.PAUSED, paused.status, paused.statusDetail)
        assertEquals(DailyLimits.PAUSED_TEXT, paused.statusDetail)
        assertEquals(2, team.script.seen.size, "two answers were allowed, and nothing was sent for the refused call")
        assertTrue(team.tree(objective.id).none { it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT }, "nothing failed")
        assertTrue(team.tree(objective.id).filter { it.kind != TaskKind.OBJECTIVE }.all { it.attempt == 0 }, "no attempt was used")
        val logged = team.world.log.all.single { it.title == "Daily limit reached" && it.rootTaskId == objective.id }
        assertTrue("limit of 2 model answers" in logged.detail.orEmpty(), logged.detail)

        // The owner raises the limit in Settings and resumes: the work goes on and the objective finishes.
        team.world.settings.update { it.copy(maxModelCallsPerDay = 50) }
        team.orchestrator.resume(objective.id)
        team.settle()

        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status, done.statusDetail)
        assertTrue(team.script.seen.size > 2)
    }
}
