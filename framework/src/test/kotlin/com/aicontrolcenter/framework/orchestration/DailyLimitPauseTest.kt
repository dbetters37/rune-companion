package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP7 item 1 (rule 6, Q2): a step whose model call is refused because today's limit of model answers, tokens or money
 * is reached pauses its objective ("Paused: today's limit is reached…"), never fails it, and uses no attempt; when the
 * owner raised the limit (or after midnight) and resumes, the objective goes on and finishes. Whatever step meets the
 * limit (planning, work, a review, the final result), and with several tasks at once.
 */
class DailyLimitPauseTest {

    private class Limit(var reached: Boolean = true) {
        fun or(step: () -> ScriptStep): ScriptStep = if (reached) ScriptStep.Fail(LlmException.DailyLimitReached(SENTENCE)) else step()
    }

    /** Runs the objective until the limit pauses it, checks the pause, raises the limit, resumes and checks it finishes. */
    private suspend fun TestScope.pausesThenFinishes(
        options: ObjectiveOptions = ObjectiveOptions(),
        script: TeamFixture.(Limit) -> Unit,
    ): Pair<TeamFixture, com.aicontrolcenter.core.TaskId> {
        val team = TeamFixture(this).setUp()
        val limit = Limit()
        team.script(limit)
        val objective = team.start("Research note apps", options)
        team.settle()

        val paused = team.objective(objective.id)
        assertEquals(TaskStatus.PAUSED, paused.status, paused.statusDetail)
        assertEquals(DailyLimits.PAUSED_TEXT, paused.statusDetail)
        assertTrue(team.tree(objective.id).none { it.status == TaskStatus.FAILED || it.status == TaskStatus.TIMED_OUT }, "nothing failed")
        assertTrue(team.tree(objective.id).filter { it.kind != TaskKind.OBJECTIVE }.all { it.attempt == 0 }, "no attempt was used")
        assertTrue(team.orchestrator.running.value.isEmpty())
        assertEquals(options, paused.options, "the owner's options are kept as they were")
        val said = team.messagesOf(objective.id, MessageType.STATUS).filter { it.from == Participant.System && it.to == Participant.Owner }.map { it.body }
        assertEquals(1, said.count { it == DailyLimits.PAUSED_TEXT }, "the owner is told once")
        assertEquals(1, team.world.log.all.count { it.title == "Daily limit reached" && it.detail == SENTENCE && it.rootTaskId == objective.id })

        // The owner raises the limit (or midnight passes) and resumes: the work goes on from where it stood.
        limit.reached = false
        team.orchestrator.resume(objective.id)
        team.settle()
        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status, done.statusDetail)
        assertEquals(options, done.options)
        return team to objective.id
    }

    @Test
    fun `a limit met by a task pauses the objective, uses no attempt, and resuming finishes it`() = runTest {
        val (team, id) = pausesThenFinishes(ObjectiveOptions(screenActionsAllowed = false, approvalTimeoutMinutes = 90)) { limit ->
            script.work = { limit.or { ScriptStep.Reply("Apps found") } }
        }
        val work = team.work(id).single()
        assertEquals("Apps found", work.result)
        assertEquals(0, work.attempt)
    }

    @Test
    fun `a limit met while the Director plans pauses the objective and planning starts again on resume`() = runTest {
        pausesThenFinishes { limit ->
            script.plan = { _, _ -> limit.or { ScriptStep.Reply(planJson(listOf(task("t1", "Research the subject", skills = listOf("research"))))) } }
        }
    }

    @Test
    fun `a limit met by a review pauses the objective, and the review runs on resume`() = runTest {
        pausesThenFinishes { limit ->
            script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t1", "Research the subject", skills = listOf("research"), needsReview = true)))) }
            script.review = { limit.or { ScriptStep.Reply(TeamScript.verdict("approved", "Meets the criteria.")) } }
        }
    }

    @Test
    fun `a limit met while the final result is written pauses the objective, and the Director writes it on resume`() = runTest {
        val (team, id) = pausesThenFinishes { limit ->
            script.synthesis = { limit.or { ScriptStep.Reply("# Final\nAll done.") } }
        }
        assertEquals("# Final\nAll done.", team.objective(id).result)
    }

    @Test
    fun `two tasks at work when the limit is met are both paused, and neither fails`() = runTest {
        pausesThenFinishes { limit ->
            script.plan = { _, _ ->
                ScriptStep.Reply(planJson(listOf(task("a", "Find apps", skills = listOf("research")), task("b", "Compare prices", skills = listOf("analysis")))))
            }
            script.work = { s -> if (s.agent == "Analyst") limit.or { ScriptStep.Reply("Prices compared") } else ScriptStep.Reply("Apps found") }
        }
    }

    private companion object {
        const val SENTENCE = "Today's limit of 200 model answers is reached. It starts again at midnight, or you can raise it in Settings › Daily limits."
    }
}
