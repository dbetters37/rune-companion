package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.FakeProviderAdapter
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient.Companion.call
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KnowledgeAndEgressTest {

    @Test
    fun `a task whose agent's model is off the device says so, and each call is logged as leaving the device`() = runTest {
        val team = TeamFixture(this).setUp()
        val cloud = team.world.provider(FakeProviderAdapter(client = team.script.client), baseUrl = "https://api.example.com/v1", name = "Example Cloud")
        val remote = team.world.model(cloud, modelName = "cloud-1", capabilities = ModelCapabilities(toolCalling = true))
        team.world.agents.upsert(team.researcher.copy(modelId = remote.id))
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        val started = team.world.events(ActivityType.TASK).first { it.title == "Task started: ${work.title}" }
        assertTrue(started.detail.contains("its prompts go to Example Cloud, off this device"), started.detail)
        assertTrue(team.world.log.all.any { it.egress != null && it.agentId == team.researcher.id && it.rootTaskId == objective.id })
        assertTrue(team.world.log.all.none { it.egress != null && it.agentId == team.director.id }, "the Director's model is on this device")
    }

    @Test
    fun `a run stopped by the per-task model call limit is reported as a limit reached`() = runTest {
        val team = TeamFixture(this) { copy(maxModelCallsPerTask = 2, maxTaskAttempts = 1) }.setUp()
        team.script.work = { ScriptStep.Calls(listOf(call("c${team.script.seen.size}", "clock_now"))) }
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.FAILED, work.status)
        assertTrue(team.limitMessages(objective.id).single().body.contains("Stopped after 2 model calls"), team.limitMessages(objective.id).toString())
    }

    @Test
    fun `knowledge goes to the Director's own memory when it may write no space, and refusals are logged, never fatal`() = runTest {
        val team = TeamFixture(this).setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Research it", listOf("research"))))) }
        val first = team.start(options = ObjectiveOptions(saveKnowledge = true))
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(first.id).status)
        val summary = team.world.memory.repository.list().single { it.kind == MemoryKind.SUMMARY }
        assertEquals(MemoryScope.Agent(team.director.id), summary.scope)
        assertTrue(summary.content.contains("All done."))

        team.world.agents.upsert(team.director.copy(memory = team.director.memory.copy(privateMemoryEnabled = false)))
        val second = team.start("Another objective", ObjectiveOptions(saveKnowledge = true))
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(second.id).status)
        assertEquals(1, team.world.memory.repository.list().count { it.kind == MemoryKind.SUMMARY })
        assertTrue(team.world.log.all.any { it.title == "Knowledge not saved" && it.rootTaskId == second.id && it.detail.contains("may not write") })

        val third = team.start("A third one")
        team.settle()
        assertTrue(team.world.log.all.none { it.title == "Knowledge not saved" && it.rootTaskId == third.id }, "nothing is saved unless the owner allowed it")
        assertEquals(2, team.world.memory.promotions.size, "task memory promoted for the two objectives that saved knowledge")
    }
}
