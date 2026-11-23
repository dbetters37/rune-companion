package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.registry.AgentEntry

/** What a task asks of its agent, for matching. */
internal data class TaskNeeds(
    val title: String,
    val instructions: String,
    val skills: List<String> = emptyList(),
    /** An agent's name the plan asked for. */
    val named: String? = null,
)

internal sealed interface Selection {
    data class Chosen(val agent: AgentProfile, val why: String) : Selection

    /** [reason] is a sentence the Director's synthesis can repeat. */
    data class NoneSuitable(val reason: String) : Selection
}

/**
 * Chooses the agent for a task. Deterministic: the same team, loads and task always give the
 * same agent.
 *
 * Rules, in order:
 *  - An agent that cannot run right now is never chosen: it would only fail the task.
 *  - A named assignee that exists and can run wins, except that the Director does not take
 *    work while another agent can run.
 *  - Otherwise agents are tried in tiers: specialists and operators first; reviewers only when
 *    no one else can run; the Director last. For a review the order is reviewers first, then
 *    anyone else, then the Director, and never the author.
 *  - Within a tier the score is: shared skill tags (3 each), words of the task found in the
 *    agent's skills, responsibilities and description (1 each, at most 5), its reliability
 *    (0..1, 0.5 without a track record), minus 0.75 per task it is already working on.
 *  - Ties go to the name in alphabetical order, then the id.
 */
internal class AgentSelector {

    fun forWork(needs: TaskNeeds, team: List<AgentEntry>, load: Map<AgentId, Int>, exclude: Set<AgentId> = emptySet()): Selection {
        val ready = team.filter { it.availability is AgentAvailability.Ready && it.profile.id !in exclude }
        if (ready.isEmpty()) return Selection.NoneSuitable(noOneCanRun(team))
        val nonDirector = ready.filter { !it.isDirector() }
        needs.named?.let { name ->
            val named = ready.firstOrNull { it.profile.name.trim().equals(name.trim(), ignoreCase = true) }
            if (named != null && (!named.isDirector() || nonDirector.isEmpty())) {
                return Selection.Chosen(named.profile, "the plan named ${named.profile.name}")
            }
        }
        val tiers = listOf(
            nonDirector.filter { it.profile.kind != AgentKind.REVIEWER },
            nonDirector.filter { it.profile.kind == AgentKind.REVIEWER },
            ready.filter { it.isDirector() },
        )
        return choose(tiers, needs, load)
    }

    fun forReview(needs: TaskNeeds, author: AgentId?, team: List<AgentEntry>, load: Map<AgentId, Int>): Selection {
        val ready = team.filter { it.availability is AgentAvailability.Ready && it.profile.id != author }
        if (ready.isEmpty()) return Selection.NoneSuitable("no agent other than the author can review right now")
        val reviewNeeds = needs.copy(skills = needs.skills + REVIEW_SKILLS)
        val tiers = listOf(
            ready.filter { !it.isDirector() && it.profile.kind == AgentKind.REVIEWER },
            ready.filter { !it.isDirector() && it.profile.kind != AgentKind.REVIEWER },
            ready.filter { it.isDirector() },
        )
        return choose(tiers, reviewNeeds, load)
    }

    private fun choose(tiers: List<List<AgentEntry>>, needs: TaskNeeds, load: Map<AgentId, Int>): Selection {
        val tier = tiers.firstOrNull { it.isNotEmpty() } ?: return Selection.NoneSuitable("no suitable agent can run right now")
        val taskSkills = needs.skills.map { it.lowercase() }.toSet()
        val taskWords = words("${needs.title} ${needs.instructions} ${taskSkills.joinToString(" ")}")
        val best = tier.map { entry -> entry to score(entry, taskSkills, taskWords, load[entry.profile.id] ?: 0) }
            .sortedWith(compareByDescending<Pair<AgentEntry, Double>> { it.second }.thenBy { it.first.profile.name.lowercase() }.thenBy { it.first.profile.id.value })
            .first()
        val shared = best.first.profile.skills.intersect(taskSkills)
        val why = if (shared.isEmpty()) "the best available fit" else "skills ${shared.sorted().joinToString(", ")}"
        return Selection.Chosen(best.first.profile, why)
    }

    private fun score(entry: AgentEntry, taskSkills: Set<String>, taskWords: Set<String>, load: Int): Double {
        val profile = entry.profile
        val skillMatches = profile.skills.count { it in taskSkills }
        val agentWords = words("${profile.skills.joinToString(" ")} ${profile.responsibilities.joinToString(" ")} ${profile.description}")
        val wordMatches = taskWords.count { it in agentWords }.coerceAtMost(MAX_WORD_MATCHES)
        val reliability = entry.stats.reliability ?: NO_RECORD_RELIABILITY
        return SKILL_WEIGHT * skillMatches + wordMatches + reliability - LOAD_PENALTY * load
    }

    private fun AgentEntry.isDirector() = isDesignatedDirector || profile.kind == AgentKind.DIRECTOR

    private fun noOneCanRun(team: List<AgentEntry>): String =
        if (team.isEmpty()) "there are no agents on the team" else "no agent on the team can run right now"

    private companion object {
        const val SKILL_WEIGHT = 3.0
        const val LOAD_PENALTY = 0.75
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
