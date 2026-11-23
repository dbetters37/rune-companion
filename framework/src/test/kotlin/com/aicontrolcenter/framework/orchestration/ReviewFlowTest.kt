package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.verdict
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewFlowTest {

    /** One reviewed research task; the reviewer answers with [verdicts] in turn (the last one repeating). */
    private suspend fun TestScope.reviewed(vararg verdicts: String, settings: com.aicontrolcenter.core.settings.FrameworkSettings.() -> com.aicontrolcenter.core.settings.FrameworkSettings = { this }): TeamFixture {
        val team = TeamFixture(this, settings).setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Find apps", listOf("research"), needsReview = true)))) }
        var draft = 0
        team.script.work = { ScriptStep.Reply("Draft ${++draft}") }
        var round = 0
        team.script.review = { ScriptStep.Reply(verdicts[minOf(round++, verdicts.lastIndex)]) }
        return team
    }

    @Test
    fun `a requested revision goes back to the author with its previous result and the issues, then is approved`() = runTest {
        val team = reviewed(verdict("revise", "Close", "Add prices"), verdict("approved", "Good"))
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        assertEquals("Draft 2", work.result)
        assertEquals(1, work.revisionRound)
        assertEquals(listOf(ReviewDecision.REVISE, ReviewDecision.APPROVED), work.reviews.map { it.decision })
        val revision = team.script.seenBy(OrchestrationProtocol.TASK)[1].brief
        assertTrue(revision.contains(BriefWriter.H_REVISION), revision)
        assertTrue(revision.contains("label=\"Your previous result\">\n${DataBlock.QUOTE}Draft 1"), revision)
        assertTrue(revision.contains("label=\"Review by Reviewer\">") && revision.contains("${DataBlock.QUOTE}- Add prices"), revision)
        val reviews = team.reviews(objective.id)
        assertEquals(2, reviews.size)
        assertTrue(reviews.all { it.assignee == team.reviewer.id && it.status == TaskStatus.COMPLETED })
        assertTrue(team.script.seenBy(OrchestrationProtocol.REVIEW).all { it.agent == "Reviewer" && it.request.jsonOutput })
        assertEquals(2, team.messagesOf(objective.id, MessageType.REVIEW_RESULT).size)
        assertEquals(1, team.world.agents.getStats(team.researcher.id).revisionsRequested)
        assertEquals(1, team.world.agents.getStats(team.researcher.id).reviewsPassed)
    }

    @Test
    fun `when the revision rounds are used up the Director decides, and its decision is recorded`() = runTest {
        val team = reviewed(verdict("revise", "Close", "Add prices"), verdict("revise", "Still", "Cite every source"), settings = { copy(maxRevisionRounds = 1) })
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        assertEquals("Accepted by the Director: Good enough.", work.statusDetail)
        val arbitration = team.reviews(objective.id).single { it.title.startsWith(ReviewKind.ARBITRATION.prefix) }
        assertEquals(team.director.id, arbitration.assignee)
        assertEquals(TaskStatus.COMPLETED, arbitration.status)
        val brief = team.script.seenBy(OrchestrationProtocol.ARBITRATE).single().brief
        assertTrue(brief.contains("revised 1 times, the limit") && brief.contains("Cite every source"), brief)
        val decision = team.messagesOf(objective.id, MessageType.REVIEW_RESULT).last()
        assertEquals(Participant.Agent(team.director.id), decision.from)
        assertEquals(Participant.Agent(team.researcher.id), decision.to)
        assertTrue(decision.body.startsWith("Decision: accept."))
    }

    @Test
    fun `a reviewer repeating the same changes is a disagreement the Director settles, here by failing the task`() = runTest {
        val team = reviewed(verdict("revise", "Missing", "Add the price of each app"), settings = { copy(maxRevisionRounds = 5) })
        team.script.arbitrate = { ScriptStep.Reply("""{"decision": "fail", "reason": "Prices cannot be found."}""") }
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.FAILED, work.status)
        assertEquals("The Director decided not to use it: Prices cannot be found.", work.error)
        assertEquals(2, work.reviews.size, "arbitration came before the rounds ran out")
        assertTrue(team.script.seenBy(OrchestrationProtocol.ARBITRATE).single().brief.contains("still disagree"))
        assertEquals(TaskStatus.FAILED, team.objective(objective.id).status)
    }

    @Test
    fun `a rejected result is done again as a new attempt with the review's issues`() = runTest {
        val team = reviewed(verdict("rejected", "Off topic", "This is about cars, not note apps"), verdict("approved", "Good"))
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        assertEquals(1, work.attempt)
        assertEquals(0, work.revisionRound)
        val retry = team.script.seenBy(OrchestrationProtocol.TASK)[1].brief
        assertTrue(retry.contains(BriefWriter.H_NEW_ATTEMPT) && retry.contains("cars, not note apps"), retry)
        assertTrue(!retry.contains("Your previous result"), "a rejected result is not offered for revision")
    }

    @Test
    fun `an unreadable verdict counts as a revision once, and a second goes to the Director`() = runTest {
        val team = reviewed("Looks fine to me.")
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        assertEquals(1, work.revisionRound)
        assertEquals(2, work.reviews.size)
        assertTrue(work.reviews.all { it.summary.startsWith(ReviewPolicy.UNREADABLE_PREFIX) })
        assertEquals(1, team.script.seenBy(OrchestrationProtocol.ARBITRATE).size)
        assertTrue(work.statusDetail!!.startsWith("Accepted by the Director"))
    }

    @Test
    fun `with reviews switched off nothing is reviewed`() = runTest {
        val team = reviewed(verdict("revise", "x", "y"))
        val objective = team.start(options = ObjectiveOptions(reviewResults = false))
        team.settle()
        assertTrue(team.reviews(objective.id).isEmpty())
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
        assertTrue(!team.work(objective.id).single().needsReview)
    }

    @Test
    fun `the Director reviews only when nobody else can`() = runTest {
        val team = reviewed(verdict("approved", "Good"))
        for (agent in listOf(team.reviewer, team.analyst, team.writer)) team.world.agents.upsert(agent.copy(enabled = false))
        val objective = team.start()
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
        assertEquals(team.director.id, team.reviews(objective.id).single().assignee)
    }

    @Test
    fun `a result that cannot be reviewed within the task limit is used, says so, and the limit is reported`() = runTest {
        // The plan keeps a place for the first review; the revision's review would need a third.
        val team = reviewed(verdict("revise", "x", "y"), settings = { copy(maxTasksPerObjective = 2) })
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        assertTrue(work.statusDetail!!.contains("not reviewed: the objective has reached its limit of 2 tasks"), work.statusDetail)
        assertEquals(1, team.reviews(objective.id).size)
        assertTrue(team.limitMessages(objective.id).single().body.contains("used without review"))
        assertEquals(Participant.System, team.limitMessages(objective.id).single().from)
    }
}
