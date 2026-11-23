package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.ReviewVerdict
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AgentSelectorTest {
    private val selector = AgentSelector()
    private val director = TaskSamples.entry("Director", AgentKind.DIRECTOR, setOf("planning", "research"), director = true)
    private val researcher = TaskSamples.entry("Researcher", skills = setOf("research", "fact-finding"))
    private val analyst = TaskSamples.entry("Analyst", skills = setOf("analysis", "comparison", "planning"), description = "Compares options and recommends.")
    private val reviewer = TaskSamples.entry("Reviewer", AgentKind.REVIEWER, setOf("review", "research"))
    private val team = listOf(director, researcher, analyst, reviewer)

    private fun Selection.agent(): AgentProfile = assertIs<Selection.Chosen>(this).agent

    @Test
    fun `work goes to the agent whose skills and words fit, never to the Director or a reviewer while others can run`() {
        assertEquals("Researcher", selector.forWork(TaskNeeds("Find sources", "Look up facts", listOf("research")), team, emptyMap()).agent().name)
        assertEquals("Analyst", selector.forWork(TaskNeeds("Compare the options", "Weigh each one"), team, emptyMap()).agent().name)
        assertEquals("Researcher", selector.forWork(TaskNeeds("Plan", "Plan it", listOf("planning")), listOf(director, researcher), emptyMap()).agent().name)
        assertEquals("Reviewer", selector.forWork(TaskNeeds("Research", "x", listOf("research")), listOf(director, reviewer), emptyMap()).agent().name)
        assertEquals("Director", selector.forWork(TaskNeeds("Research", "x", listOf("research")), listOf(director, researcher.copy(availability = TaskSamples.entry("x", ready = false).availability)), emptyMap()).agent().name)
    }

    @Test
    fun `a named assignee that can run wins, except the Director while another agent can run`() {
        assertEquals("Analyst", selector.forWork(TaskNeeds("Research", "x", listOf("research"), named = " analyst "), team, emptyMap()).agent().name)
        assertEquals("Researcher", selector.forWork(TaskNeeds("Research", "x", listOf("research"), named = "Director"), team, emptyMap()).agent().name)
        val offline = TaskSamples.entry("Analyst", skills = setOf("analysis"), ready = false)
        assertEquals("Researcher", selector.forWork(TaskNeeds("Research", "x", listOf("research"), named = "Analyst"), listOf(director, researcher, offline), emptyMap()).agent().name)
    }

    @Test
    fun `nobody who cannot run is chosen, and with nobody left the task gets a reason`() {
        val none = selector.forWork(TaskNeeds("x", "y"), listOf(TaskSamples.entry("A", ready = false)), emptyMap())
        assertEquals("no agent on the team can run right now", assertIs<Selection.NoneSuitable>(none).reason)
    }

    @Test
    fun `load, reliability and then the name break ties deterministically`() {
        val twins = listOf(TaskSamples.entry("Bea", skills = setOf("research")), TaskSamples.entry("Abe", skills = setOf("research")))
        assertEquals("Abe", selector.forWork(TaskNeeds("Research", "x", listOf("research")), twins, emptyMap()).agent().name)
        assertEquals("Bea", selector.forWork(TaskNeeds("Research", "x", listOf("research")), twins, mapOf(AgentId("agt_abe") to 1)).agent().name)
        val reliable = listOf(TaskSamples.entry("Bea", skills = setOf("research"), stats = AgentStats(tasksCompleted = 9)), TaskSamples.entry("Abe", skills = setOf("research"), stats = AgentStats(tasksFailed = 3)))
        assertEquals("Bea", selector.forWork(TaskNeeds("Research", "x", listOf("research")), reliable, emptyMap()).agent().name)
    }

    @Test
    fun `reviews go to a reviewer, never to the author, and to the Director only when nobody else can`() {
        assertEquals("Reviewer", selector.forReview(TaskNeeds("Research", "x"), researcher.profile.id, team, emptyMap()).agent().name)
        assertEquals("Analyst", selector.forReview(TaskNeeds("Research", "x"), researcher.profile.id, listOf(director, researcher, analyst), emptyMap()).agent().name)
        assertEquals("Director", selector.forReview(TaskNeeds("Research", "x"), researcher.profile.id, listOf(director, researcher), emptyMap()).agent().name)
        assertIs<Selection.NoneSuitable>(selector.forReview(TaskNeeds("Research", "x"), researcher.profile.id, listOf(researcher), emptyMap()))
    }

    @Test
    fun `the review policy revises within the rounds, then sends disagreements and exhausted reviews to the Director`() {
        val work = TaskSamples.work("Compare", result = "r")
        fun verdict(decision: ReviewDecision, vararg issues: String) = ReviewVerdict(AgentId("agt_r"), decision, "s", issues.toList(), 1)
        assertEquals(ReviewStep.Accept, ReviewPolicy.next(work, verdict(ReviewDecision.APPROVED), false, 2, 2))
        assertEquals(ReviewStep.Revise(1), ReviewPolicy.next(work, verdict(ReviewDecision.REVISE, "Add prices"), false, 2, 2))
        assertIs<ReviewStep.Arbitrate>(ReviewPolicy.next(work.copy(revisionRound = 2), verdict(ReviewDecision.REVISE, "Add prices"), false, 2, 2))
        val disagreed = work.copy(revisionRound = 1, reviews = listOf(verdict(ReviewDecision.REVISE, "Add the prices of each app")))
        assertIs<ReviewStep.Arbitrate>(ReviewPolicy.next(disagreed, verdict(ReviewDecision.REVISE, "add the prices of each app"), false, 5, 2))
        assertEquals(ReviewStep.Revise(2), ReviewPolicy.next(disagreed, verdict(ReviewDecision.REVISE, "Cite a source for every claim"), false, 5, 2))
        assertEquals(ReviewStep.NewAttempt(1), ReviewPolicy.next(work, verdict(ReviewDecision.REJECTED, "Off topic"), false, 2, 2))
        assertIs<ReviewStep.Arbitrate>(ReviewPolicy.next(work.copy(attempt = 1), verdict(ReviewDecision.REJECTED, "Off topic"), false, 2, 2))
        val unreadableOnce = work.copy(reviews = listOf(verdict(ReviewDecision.REVISE).copy(summary = "${ReviewPolicy.UNREADABLE_PREFIX}: no JSON.")))
        assertIs<ReviewStep.Arbitrate>(ReviewPolicy.next(unreadableOnce, verdict(ReviewDecision.REVISE), true, 2, 2))
    }
}
