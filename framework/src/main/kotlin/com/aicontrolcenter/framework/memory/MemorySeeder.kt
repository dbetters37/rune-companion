package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.agents.AgentOperationException
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentValidationException
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.framework.agents.AgentTemplates
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The built-in memory content, applied once per [MEMORY_SEED_VERSION]: a shared knowledge space
 * called [TEAM_SPACE_NAME], created by the owner, that every agent of the starter team may read
 * and only the Director may write. Agents keep their private memories to themselves; the
 * space is where the team's agreed knowledge goes.
 *
 * Rules:
 *  - Runs after the starter team exists. Call it after [AgentManager.ensureDefaults]; before the
 *    starter content has been applied it does nothing and waits for the next start.
 *  - Runs once. Once applied, a space the owner deletes or access the owner takes away is never
 *    put back, because the version is recorded in the settings.
 *  - Resumable. An interrupted run finds the space it already created by name instead of making
 *    a second one, and agents that already have the access are left as they are.
 *  - Only agents made from a starter template are touched; agents the owner made are left alone.
 *    "The Director" is the designated Director when it is a starter agent, otherwise the starter
 *    agent made from the Director template.
 *  - Access is given through [AgentManager.update], so it is validated, serialised with the
 *    owner's own edits and recorded like any other change to an agent.
 */
class MemorySeeder(
    private val memory: MemoryService,
    private val agentManager: AgentManager,
    private val agents: AgentRepository,
    private val settings: SettingsRepository,
    private val activity: ActivityRecorder,
    private val starterTeam: List<String> = AgentTemplates.starterTeam,
) {
    private val mutex = Mutex()

    /** Applies the memory starter content if this install has not had it yet. Safe to call on every start. */
    suspend fun ensureDefaults(): Unit = mutex.withLock {
        val current = settings.get()
        if (current.memorySeedVersion >= MEMORY_SEED_VERSION) return@withLock
        if (current.seedVersion == 0) return@withLock
        withContext(NonCancellable) {
            val space = existingTeamSpace() ?: memory.createSpace(MemoryActor.Owner, TEAM_SPACE_NAME, TEAM_SPACE_DESCRIPTION)
            val team = agents.getAll().filter { it.templateId in starterTeam }
            val director = team.firstOrNull { it.id == current.directorAgentId && it.kind == AgentKind.DIRECTOR }
                ?: team.firstOrNull { it.templateId == AgentTemplates.DIRECTOR && it.kind == AgentKind.DIRECTOR }
            var readers = 0
            var writerName: String? = null
            for (agent in team) {
                val writes = agent.id == director?.id
                if (!giveAccess(agent, space, writes)) continue
                readers++
                if (writes) writerName = agent.name
            }
            settings.update { it.copy(memorySeedVersion = MEMORY_SEED_VERSION) }
            activity.record(ActivityType.MEMORY, "Shared space created: ${space.name}", describe(readers, writerName))
        }
    }

    private suspend fun existingTeamSpace(): KnowledgeSpace? =
        memory.observeSpaces().first().firstOrNull { it.name.trim().equals(TEAM_SPACE_NAME, ignoreCase = true) }

    /** Gives [agent] read access to [space], and write access when [writes]. False when it could not be given. */
    private suspend fun giveAccess(agent: AgentProfile, space: KnowledgeSpace, writes: Boolean): Boolean {
        val policy = agent.memory
        val wanted = policy.copy(
            readableSpaces = policy.readableSpaces + space.id,
            writableSpaces = if (writes) policy.writableSpaces + space.id else policy.writableSpaces,
        )
        if (wanted == policy) return true
        return try {
            agentManager.update(agent.id, agent.toDraft().copy(memory = wanted))
            true
        } catch (e: AgentOperationException) {
            false // deleted meanwhile
        } catch (e: AgentValidationException) {
            false // stored in a state the editor would not accept; the owner can give access by hand
        }
    }

    private fun describe(readers: Int, writer: String?): String {
        val who = when (readers) {
            0 -> "No starter agents were found, so no agent can read it yet."
            1 -> "1 starter agent can read it."
            else -> "$readers starter agents can read it."
        }
        val write = if (writer == null) " No agent can add to it yet." else " Only $writer can add to it."
        return "$who$write Change who may read or write it in each agent's memory settings."
    }

    companion object {
        /** Bump when the built-in memory content changes in a way existing installs should receive. */
        const val MEMORY_SEED_VERSION = 1

        const val TEAM_SPACE_NAME = "Team knowledge"
        const val TEAM_SPACE_DESCRIPTION =
            "Knowledge the whole team can use: agreed facts, decisions and the owner's standing preferences. " +
                "Starter agents may read it; only the Director adds to it."
    }
}
