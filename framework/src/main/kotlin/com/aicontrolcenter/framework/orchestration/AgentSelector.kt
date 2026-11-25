package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelDescriptor

/** What a task asks of its agent, for matching. */
internal data class TaskNeeds(
    val title: String,
    val instructions: String,
    val skills: List<String> = emptyList(),
    /** An agent's name the plan asked for. */
    val named: String? = null,
)

internal sealed interface Selection {
    /** [modelId]: the model the agent would run on now. */
    data class Chosen(val agent: AgentProfile, val why: String, val modelId: ModelId? = null) : Selection

    /** [reason] is a sentence the Director's synthesis can repeat. */
    data class NoneSuitable(val reason: String) : Selection
}

/**
 * Chooses the agent for a task. Deterministic: the same team, loads and task always give the
 * same agent.
 *
 * Rules, in order:
 *  - An agent that cannot run right now is never chosen: it would only fail the task. One
 *    exception (rule 25): an assignee the plan named whose provider is only not answering keeps
 *    the task, which waits for it, rather than going to another agent that may be farther from
 *    the phone.
 *  - A named assignee that exists and can run wins, except that the Director does not take
 *    work while another agent can run.
 *  - Otherwise agents are tried in tiers: specialists and operators first; reviewers only when
 *    no one else can run; the Director last. For a review the order is reviewers first, then
 *    anyone else, then the Director, and never the author.
 *  - Within a tier the score is: shared skill tags (3 each), words of the task found in the
 *    agent's skills, responsibilities and description (1 each, at most 5), its reliability
 *    (0..1, 0.5 without a track record), minus 0.75 per task it is already working on.
 *  - D27: when the task's words call for coding or reasoning, 0.75 for each step its model is
 *    rated above Basic for it (Good 0.75, Strong 1.5); a model rated Unknown or Basic adds nothing.
 *  - C4, for a review: agents that helped the author (answered its questions, took delegated
 *    parts) are left out unless nobody else can run; a later round keeps the earlier round's
 *    reviewer when it can run; a reviewer on another model than the author's gets 1.0 more.
 *  - Ties go to the name in alphabetical order, then the id.
 */
internal class AgentSelector {

    /** [models]: the registered models, whose Coding and Reasoning ratings count when the task calls for them (D27). */
    fun forWork(
        needs: TaskNeeds,
        team: List<AgentEntry>,
        load: Map<AgentId, Int>,
        exclude: Set<AgentId> = emptySet(),
        models: Map<ModelId, ModelDescriptor> = emptyMap(),
    ): Selection {
        needs.named?.let { name ->
            val waiting = team.firstOrNull {
                it.profile.id !in exclude && !it.isDirector() && it.profile.name.trim().equals(name.trim(), ignoreCase = true) &&
                    (it.availability as? AgentAvailability.Unavailable)?.reason?.isTransient == true
            }
            if (waiting != null) return Selection.Chosen(waiting.profile, "the plan named ${waiting.profile.name}, which waits for its provider")
        }
        val ready = team.filter { it.availability is AgentAvailability.Ready && it.profile.id !in exclude }
        if (ready.isEmpty()) return Selection.NoneSuitable(noOneCanRun(team))
        val nonDirector = ready.filter { !it.isDirector() }
        needs.named?.let { name ->
            val named = ready.firstOrNull { it.profile.name.trim().equals(name.trim(), ignoreCase = true) }
            if (named != null && (!named.isDirector() || nonDirector.isEmpty())) {
                return Selection.Chosen(named.profile, "the plan named ${named.profile.name}", named.modelId())
            }
        }
        val tiers = listOf(
            nonDirector.filter { it.profile.kind != AgentKind.REVIEWER },
            nonDirector.filter { it.profile.kind == AgentKind.REVIEWER },
            ready.filter { it.isDirector() },
        )
        return choose(tiers, needs, load, models)
    }

    /**
     * The reviewer of a result by [author] (C4). [contributors] helped the author (answered its questions, took
     * delegated parts): left out unless nobody else can run. [previousReviewer] reviewed the earlier round: kept when it
     * can run, so the feedback stays consistent. [authorModel]: the model the author worked on; a reviewer on another
     * model scores [OTHER_MODEL_BONUS] more.
     */
    fun forReview(
        needs: TaskNeeds,
        author: AgentId?,
        team: List<AgentEntry>,
        load: Map<AgentId, Int>,
        contributors: Set<AgentId> = emptySet(),
        previousReviewer: AgentId? = null,
        authorModel: ModelId? = null,
        models: Map<ModelId, ModelDescriptor> = emptyMap(),
    ): Selection {
        val anyone = team.filter { it.availability is AgentAvailability.Ready && it.profile.id != author }
        if (anyone.isEmpty()) return Selection.NoneSuitable("no agent other than the author can review right now")
        val ready = anyone.filter { it.profile.id !in contributors }.ifEmpty { anyone }
        previousReviewer?.let { id -> ready.firstOrNull { it.profile.id == id } }?.let {
            return Selection.Chosen(it.profile, "it reviewed the earlier round", it.modelId())
        }
        val reviewNeeds = needs.copy(skills = needs.skills + REVIEW_SKILLS)
        val tiers = listOf(
            ready.filter { !it.isDirector() && it.profile.kind == AgentKind.REVIEWER },
            ready.filter { !it.isDirector() && it.profile.kind != AgentKind.REVIEWER },
            ready.filter { it.isDirector() },
        )
        return choose(tiers, reviewNeeds, load, models) { entry -> if (authorModel != null && entry.modelId().let { it != null && it != authorModel }) OTHER_MODEL_BONUS else 0.0 }
    }

    private fun choose(
        tiers: List<List<AgentEntry>>,
        needs: TaskNeeds,
        load: Map<AgentId, Int>,
        models: Map<ModelId, ModelDescriptor>,
        extra: (AgentEntry) -> Double = { 0.0 },
    ): Selection {
        val tier = tiers.firstOrNull { it.isNotEmpty() } ?: return Selection.NoneSuitable("no suitable agent can run right now")
        val taskSkills = needs.skills.map { it.lowercase() }.toSet()
        val taskWords = words("${needs.title} ${needs.instructions} ${taskSkills.joinToString(" ")}")
        val calls = Calls.of("${needs.title} ${needs.instructions} ${taskSkills.joinToString(" ")}")
        val best = tier.map { entry -> entry to score(entry, taskSkills, taskWords, load[entry.profile.id] ?: 0) + tierBonus(entry, models, calls) + extra(entry) }
            .sortedWith(compareByDescending<Pair<AgentEntry, Double>> { it.second }.thenBy { it.first.profile.name.lowercase() }.thenBy { it.first.profile.id.value })
            .first()
        val shared = best.first.profile.skills.intersect(taskSkills)
        val why = if (shared.isEmpty()) "the best available fit" else "skills ${shared.sorted().joinToString(", ")}"
        return Selection.Chosen(best.first.profile, why, best.first.modelId())
    }

    private fun score(entry: AgentEntry, taskSkills: Set<String>, taskWords: Set<String>, load: Int): Double {
        val profile = entry.profile
        val skillMatches = profile.skills.count { it in taskSkills }
        val agentWords = words("${profile.skills.joinToString(" ")} ${profile.responsibilities.joinToString(" ")} ${profile.description}")
        val wordMatches = taskWords.count { it in agentWords }.coerceAtMost(MAX_WORD_MATCHES)
        val reliability = entry.stats.reliability ?: NO_RECORD_RELIABILITY
        return SKILL_WEIGHT * skillMatches + wordMatches + reliability - LOAD_PENALTY * load
    }

    /** What the task's words call for (D27). */
    private class Calls(val coding: Boolean, val reasoning: Boolean) {
        companion object {
            fun of(text: String) = Calls(CODING.containsMatchIn(text), REASONING.containsMatchIn(text))
        }
    }

    /** D27: [TIER_STEP] per step above Basic of the agent's model, for coding and for reasoning, where the task calls for it. */
    private fun tierBonus(entry: AgentEntry, models: Map<ModelId, ModelDescriptor>, calls: Calls): Double {
        if (!calls.coding && !calls.reasoning) return 0.0
        val capabilities = entry.modelId()?.let { models[it] }?.capabilities ?: return 0.0
        val coding = if (calls.coding) stepsAboveBasic(capabilities.coding) else 0
        val reasoning = if (calls.reasoning) stepsAboveBasic(capabilities.reasoning) else 0
        return TIER_STEP * (coding + reasoning)
    }

    private fun stepsAboveBasic(tier: CapabilityTier): Int = when (tier) {
        CapabilityTier.UNKNOWN, CapabilityTier.BASIC -> 0
        CapabilityTier.GOOD -> 1
        CapabilityTier.STRONG -> 2
    }

    private fun AgentEntry.modelId(): ModelId? = (availability as? AgentAvailability.Ready)?.modelId

    private fun AgentEntry.isDirector() = isDesignatedDirector || profile.kind == AgentKind.DIRECTOR

    private fun noOneCanRun(team: List<AgentEntry>): String =
        if (team.isEmpty()) "there are no agents on the team" else "no agent on the team can run right now"

    internal companion object {
        const val SKILL_WEIGHT = 3.0
        const val LOAD_PENALTY = 0.75

        /** D27: what each step of a model's Coding or Reasoning rating above Basic adds, when the task calls for it. */
        const val TIER_STEP = 0.75

        /** C4: what a reviewer on another model than the author's gets. */
        const val OTHER_MODEL_BONUS = 1.0

        /** Words that call for a model good at coding (D27). */
        val CODING = Regex("\\b(code|coding|coder|script|scripts|scripting|program|programs|programming|debug|debugging|software|function|functions|algorithm|algorithms|bug|bugs)\\b", RegexOption.IGNORE_CASE)

        /** Words that call for a model good at reasoning (D27). */
        val REASONING = Regex(
            "\\b(analy[sz]e|analy[sz]es|analy[sz]ing|analysis|plan|plans|planning|compare|compares|comparing|comparison|calculate|calculating|calculation|" +
                "decide|deciding|decision|decisions|reason|reasoning|evaluate|evaluating|evaluation|logic|logical|math|maths|mathematics|prove|proof)\\b",
            RegexOption.IGNORE_CASE,
        )
        const val MAX_WORD_MATCHES = 5
        const val NO_RECORD_RELIABILITY = 0.5
        val REVIEW_SKILLS = listOf("review", "fact-checking", "quality-assurance", "critique")
        val STOP_WORDS = setOf("this", "that", "with", "from", "into", "your", "their", "about", "what", "which", "when", "will", "should", "each", "them", "have", "make", "task", "tasks", "and", "the")
        val WORD = Regex("[\\p{L}\\p{N}]+")

        /** Words of four or more letters, lower-case; skill tags split on hyphens so "fact-finding" also matches "finding". */
        fun words(text: String): Set<String> =
            WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= 4 && it !in STOP_WORDS }.map { stem(it) }.toSet()

        /** A crude stem, enough for "compare"/"comparing"/"comparison" and "research"/"researching" to meet. */
        fun stem(word: String): String = word.removeSuffix("ing").removeSuffix("ison").removeSuffix("es").removeSuffix("s").removeSuffix("e").take(7)
    }
}
