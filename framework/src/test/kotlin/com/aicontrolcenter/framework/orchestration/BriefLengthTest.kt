package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.framework.llm.TokenEstimator
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan item 12 (Tier B, shorter role protocols): the briefs of each kind, as the seeded starter team gets them on the
 * simulator, are measured in estimated tokens ([TokenEstimator]). The numbers before the change are pinned as the most
 * each may take: removing repeated sentences may only make them shorter. The test prints what it measured.
 */
class BriefLengthTest {

    @Test
    fun `the briefs of the seeded team take no more tokens than before the role protocols were shortened`() = runTest {
        val team = TeamFixture(this)
        val world = team.world
        world.providerManager.ensureBuiltIns()
        world.agentManager.ensureDefaults()
        world.memorySeeder.ensureDefaults()
        for (agent in world.agents.getAll()) world.agentManager.assignModel(agent.id, Simulator.MODEL_ID)
        val objective = team.start(OBJECTIVE, ObjectiveOptions(reviewResults = true, saveKnowledge = false))
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)

        val measured = LinkedHashMap<String, Int>()
        for (task in team.tree(objective.id)) {
            val first = world.conversations.getEntries(task.conversationId ?: continue).first().message.text
            val kind = when (OrchestrationProtocol.markerIn(first)) {
                OrchestrationProtocol.PLAN -> "planning"
                OrchestrationProtocol.TASK -> "work"
                OrchestrationProtocol.REVIEW -> "review"
                OrchestrationProtocol.SYNTHESIS -> "synthesis"
                else -> continue
            }
            measured[kind] = maxOf(measured[kind] ?: 0, TokenEstimator.tokens(first))
        }
        println("Brief lengths of the seeded team, estimated tokens (most per kind): $measured")
        assertEquals(setOf("planning", "work", "review", "synthesis"), measured.keys, "every kind of brief was measured")
        for ((kind, most) in BEFORE) {
            assertTrue(measured.getValue(kind) <= most, "the $kind brief takes ${measured[kind]} tokens, more than the $most it took before")
        }
        assertTrue(team.tree(objective.id).any { it.kind == TaskKind.REVIEW }, "a review brief was written")
    }

    private companion object {
        const val OBJECTIVE = "Research this subject, compare available solutions, develop a plan, and organize the results into a document"

        /** Measured on 90e1d70, before the change (most per kind, estimated tokens). */
        val BEFORE = mapOf("planning" to 667, "work" to 553, "review" to 602, "synthesis" to 881)
    }
}
