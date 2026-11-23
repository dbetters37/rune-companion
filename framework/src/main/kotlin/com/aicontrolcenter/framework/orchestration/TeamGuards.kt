package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveLimits
import com.aicontrolcenter.core.tasks.Participant

/**
 * The loop guards of agent-to-agent work, as pure checks over stored state. Each returns null
 * when the exchange may go ahead, or a sentence saying why not, which becomes both the tool
 * result the calling model reads and the STATUS message recorded for the owner.
 *
 *  - Depth: a request made from a task [chain] levels below the plan travels hop chain+1, which
 *    may not exceed the objective's delegation depth. Delegating also respects every agent's own
 *    [com.aicontrolcenter.core.agents.DelegationPolicy.maxDepth]: the new task may not be more levels below
 *    an agent of the chain than that agent allows.
 *  - Cycles: no agent that is already in the chain (or is the caller) may be asked or handed work.
 *  - Size: the objective's task and message limits.
 *  - Repetition: the same sender asking the same recipient a near-identical question (or
 *    handing over a near-identical task) a third time is refused. Similarity is judged on the
 *    words, so rephrasing with the same content does not get round it.
 */
internal object TeamGuards {
    /** How many near-identical requests to the same agent are allowed before the next is refused. */
    const val MAX_REPEATS = 2
    private const val SIMILARITY = 0.8
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    fun depth(chain: List<AgentId>, limits: ObjectiveLimits): String? {
        val hop = chain.size + 1
        return if (hop > limits.maxDelegationDepth) {
            "this would be hop $hop of agent-to-agent work, and the limit for this objective is ${limits.maxDelegationDepth}. Finish the task with what you have."
        } else null
    }

    /** [newChain] is the chain of the task to be created, ending with the caller; [profiles] are the current profiles of its agents. */
    fun policyDepth(newChain: List<AgentId>, profiles: Map<AgentId, AgentProfile>): String? {
        newChain.forEachIndexed { index, id ->
            val levelsBelow = newChain.size - index
            val profile = profiles[id] ?: return@forEachIndexed
            if (levelsBelow > profile.delegation.maxDepth) {
                return "${profile.name} allows delegation only ${profile.delegation.maxDepth} level(s) below it, and this would be $levelsBelow."
            }
        }
        return null
    }

    fun cycle(callerChain: List<AgentId>, caller: AgentId, target: AgentProfile): String? = when (target.id) {
        caller -> "you cannot ask or delegate to yourself."
        in callerChain -> "${target.name} already handed work down to you; asking it back would go round in a circle. Finish the task with what you have."
        else -> null
    }

    fun taskLimit(tasksUnderObjective: Int, limits: ObjectiveLimits): String? =
        if (tasksUnderObjective + 1 > limits.maxTasks) "the objective already has $tasksUnderObjective tasks, the limit is ${limits.maxTasks}." else null

    fun messageLimit(messagesUnderObjective: Int, needed: Int, limits: ObjectiveLimits): String? =
        if (messagesUnderObjective + needed > limits.maxMessages) {
            "the objective already has $messagesUnderObjective messages, the limit is ${limits.maxMessages}."
        } else null

    fun repeated(history: List<AgentMessage>, from: AgentId, to: AgentId, type: MessageType, body: String, targetName: String): String? {
        val earlier = history.count {
            it.type == type && it.from == Participant.Agent(from) && it.to == Participant.Agent(to) && similar(it.body, body)
        }
        return if (earlier >= MAX_REPEATS) {
            "you already sent $targetName this request $earlier times. Use the answers you have instead of asking again."
        } else null
    }

    /** Near-identical: the same words, or word sets that overlap by at least 80 percent. */
    fun similar(a: String, b: String): Boolean {
        val left = words(a)
        val right = words(b)
        if (left.isEmpty() || right.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
        val shared = left.intersect(right).size.toDouble()
        return shared / (left.size + right.size - shared) >= SIMILARITY
    }

    private fun words(text: String): Set<String> = WORD.findAll(text.lowercase()).map { it.value }.toSet()
}
