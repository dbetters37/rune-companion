package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelSource
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

    // ---- C4: the reviewer ----

    private fun AgentEntry.on(model: String) = copy(availability = AgentAvailability.Ready(ModelId(model), usingFallback = false))

    private val reviewerA = TaskSamples.entry("Ada", AgentKind.REVIEWER, setOf("review"))
    private val reviewerB = TaskSamples.entry("Bo", AgentKind.REVIEWER, setOf("review"))

    @Test
    fun `C4 - an agent that helped the author does not review it, unless nobody else can`() {
        val needs = TaskNeeds("Research", "x")
        assertEquals("Ada", selector.forReview(needs, researcher.profile.id, listOf(researcher, reviewerA, reviewerB), emptyMap()).agent().name)
        assertEquals("Bo", selector.forReview(needs, researcher.profile.id, listOf(researcher, reviewerA, reviewerB), emptyMap(), contributors = setOf(reviewerA.profile.id)).agent().name)
        // Only helpers can run: one of them reviews rather than nobody.
        assertEquals("Ada", selector.forReview(needs, researcher.profile.id, listOf(researcher, reviewerA), emptyMap(), contributors = setOf(reviewerA.profile.id)).agent().name)
    }

    @Test
    fun `C4 - a later round keeps the earlier round's reviewer when it can run`() {
        val needs = TaskNeeds("Research", "x")
        assertEquals("Bo", selector.forReview(needs, researcher.profile.id, listOf(researcher, reviewerA, reviewerB), emptyMap(), previousReviewer = reviewerB.profile.id).agent().name)
        val boOff = reviewerB.copy(availability = TaskSamples.entry("x", ready = false).availability)
        assertEquals("Ada", selector.forReview(needs, researcher.profile.id, listOf(researcher, reviewerA, boOff), emptyMap(), previousReviewer = reviewerB.profile.id).agent().name)
        // A helper is not kept as the reviewer even if it reviewed before.
        assertEquals("Ada", selector.forReview(needs, researcher.profile.id, listOf(researcher, reviewerA, reviewerB), emptyMap(), contributors = setOf(reviewerB.profile.id), previousReviewer = reviewerB.profile.id).agent().name)
    }

    @Test
    fun `C4 - a reviewer on another model than the author's is preferred, and the choice says which model it runs on`() {
        val needs = TaskNeeds("Research", "x")
        val team = listOf(researcher.on("mdl_author"), reviewerA.on("mdl_author"), reviewerB.on("mdl_other"))
        val chosen = assertIs<Selection.Chosen>(selector.forReview(needs, researcher.profile.id, team, emptyMap(), authorModel = ModelId("mdl_author")))
        assertEquals("Bo", chosen.agent.name, "another model wins the tie")
        assertEquals(ModelId("mdl_other"), chosen.modelId)
        // Without a model to compare, the name decides as before.
        assertEquals("Ada", selector.forReview(needs, researcher.profile.id, team, emptyMap()).agent().name)
        // Everyone on the author's model: the same model is used, and the chosen model says so.
        val same = assertIs<Selection.Chosen>(selector.forReview(needs, researcher.profile.id, listOf(researcher.on("mdl_author"), reviewerA.on("mdl_author")), emptyMap(), authorModel = ModelId("mdl_author")))
        assertEquals(ModelId("mdl_author"), same.modelId)
    }

    // ---- D27: Reasoning and Coding count ----

    private fun model(id: String, reasoning: CapabilityTier = CapabilityTier.UNKNOWN, coding: CapabilityTier = CapabilityTier.UNKNOWN) = ModelDescriptor(
        id = ModelId(id), providerId = ProviderId("prv_1"), modelName = id, displayName = id,
        capabilities = ModelCapabilities(reasoning = reasoning, coding = coding), source = ModelSource.MANUAL, createdAt = 1, updatedAt = 1,
    )

    @Test
    fun `D27 - a task that calls for coding or reasoning goes to the agent whose model is rated higher for it`() {
        val abe = TaskSamples.entry("Abe", skills = setOf("research")).on("mdl_basic")
        val bea = TaskSamples.entry("Bea", skills = setOf("research")).on("mdl_strong")
        val models = listOf(
            model("mdl_basic", reasoning = CapabilityTier.BASIC, coding = CapabilityTier.BASIC),
            model("mdl_strong", reasoning = CapabilityTier.STRONG, coding = CapabilityTier.GOOD),
        ).associateBy { it.id }
        val pair = listOf(abe, bea)
        assertEquals("Bea", selector.forWork(TaskNeeds("Write a script", "Debug the code", listOf("research")), pair, emptyMap(), models = models).agent().name)
        assertEquals("Bea", selector.forWork(TaskNeeds("Compare the offers", "Decide which is best", listOf("research")), pair, emptyMap(), models = models).agent().name)
        // Words that call for neither: the ratings change nothing, and the name decides.
        assertEquals("Abe", selector.forWork(TaskNeeds("Find sources", "Look up facts", listOf("research")), pair, emptyMap(), models = models).agent().name)
        // Unknown ratings add nothing either.
        val unknown = listOf(model("mdl_basic"), model("mdl_strong")).associateBy { it.id }
        assertEquals("Abe", selector.forWork(TaskNeeds("Write a script", "Debug the code", listOf("research")), pair, emptyMap(), models = unknown).agent().name)
        // A step above Basic is worth 0.75: less than one shared skill (3), so skills still come first.
        val skilled = TaskSamples.entry("Cy", skills = setOf("research", "coding")).on("mdl_basic")
        assertEquals("Cy", selector.forWork(TaskNeeds("Write a script", "Debug the code", listOf("research", "coding")), listOf(skilled, bea), emptyMap(), models = models).agent().name)
        assertEquals(0.75, AgentSelector.TIER_STEP)
    }

    @Test
    fun `rule 25 - an assignee the plan named whose provider is only not answering keeps the task, any other reason does not`() {
        val offline = researcher.copy(availability = AgentAvailability.Unavailable(com.aicontrolcenter.core.agents.UnavailableReason.PROVIDER_OFFLINE, "Provider X is offline."))
        val named = TaskNeeds("Research", "x", listOf("research"), named = "Researcher")
        assertEquals("Researcher", selector.forWork(named, listOf(director, offline, analyst), emptyMap()).agent().name, "it waits for its provider")
        val disabled = researcher.copy(availability = AgentAvailability.Disabled)
        assertEquals("Analyst", selector.forWork(named, listOf(director, disabled, analyst), emptyMap()).agent().name)
        assertEquals("Analyst", selector.forWork(TaskNeeds("Research", "x", listOf("research")), listOf(director, offline, analyst), emptyMap()).agent().name, "unnamed: the best agent that can run")
    }
}
