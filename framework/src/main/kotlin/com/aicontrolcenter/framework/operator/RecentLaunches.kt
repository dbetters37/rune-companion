package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId

/**
 * The app each agent opened last, and when (D5). An app that was just opened can take a few
 * seconds to come to the front; a screen tool called by the agent that opened it waits a bounded
 * time for it ([OperatorLimits.screenOpenWaitMs], for launches less than
 * [OperatorLimits.launchRecentMs] old) instead of being refused at once.
 *
 * The tools that open another app record here: the package for an app opened by name, null for
 * a link or a web page, where Android picks the app. Only each agent's latest launch is kept, for
 * at most [maxAgents] agents. In memory only: after the app restarts, nothing is remembered and
 * nothing waits.
 */
class RecentLaunches(private val maxAgents: Int = 200) {
    init {
        require(maxAgents > 0) { "maxAgents must be positive" }
    }

    /** One launch: [packageName] null when Android picked the app (a link, a web page). */
    data class Launch(val packageName: String?, val at: Long)

    /** Each agent's latest launch, the agent that launched least recently first. */
    private val latest = object : LinkedHashMap<AgentId, Launch>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AgentId, Launch>): Boolean = size > maxAgents
    }

    /** Notes that [agent] opened [packageName] (null: an app Android picked) at [at]. */
    @Synchronized
    fun record(agent: AgentId, packageName: String?, at: Long) {
        // Re-inserted, so that the agent that launched last is the last to be dropped.
        latest.remove(agent)
        latest[agent] = Launch(packageName, at)
    }

    /** [agent]'s latest launch when it was made less than [withinMs] before [now] (and not after it); null otherwise. */
    @Synchronized
    fun latest(agent: AgentId, withinMs: Long, now: Long): Launch? = latest[agent]?.takeIf { now - it.at in 0 until withinMs }

    /**
     * Whether [agent]'s latest launch, less than [withinMs] before [now], opened [packageName];
     * for null, whether it opened an app Android picked.
     */
    fun launchedWithin(agent: AgentId, packageName: String?, withinMs: Long, now: Long): Boolean =
        latest(agent, withinMs, now)?.let { it.packageName == packageName } == true
}
