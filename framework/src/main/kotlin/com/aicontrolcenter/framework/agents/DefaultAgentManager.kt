package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agentId
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentField
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.agents.AgentOperationException
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentTemplate
import com.aicontrolcenter.core.agents.AgentValidationException
import com.aicontrolcenter.core.agents.label
import com.aicontrolcenter.core.agents.ValidationIssue
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.agents.toProfile
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.framework.memory.MemoryTools
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Called after an agent is deleted so other subsystems can clean up what belonged to it. */
fun interface AgentDeletionListener {
    suspend fun onAgentDeleted(agent: AgentProfile)
}

class DefaultAgentManager(
    private val agents: AgentRepository,
    private val settings: SettingsRepository,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    override val templates: List<AgentTemplate> = AgentTemplates.all,
    private val starterTeam: List<String> = AgentTemplates.starterTeam,
    private val deletionListeners: List<AgentDeletionListener> = emptyList(),
) : AgentManager {

    /** Serialises mutations so that the name-uniqueness check and the write are one step. */
    private val mutex = Mutex()

    override suspend fun validate(draft: AgentDraft, editing: AgentId?): List<ValidationIssue> =
        validate(draft, editing, agents.getAll(), settings.get().directorAgentId)

    private fun validate(
        draft: AgentDraft,
        editing: AgentId?,
        existing: List<AgentProfile>,
        directorId: AgentId?,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val name = draft.name.trim()
        when {
            name.isEmpty() -> issues += ValidationIssue(AgentField.NAME, "Give the agent a name.")
            name.length > MAX_NAME_LENGTH ->
                issues += ValidationIssue(AgentField.NAME, "Keep the name to $MAX_NAME_LENGTH characters or fewer.")
            existing.any { it.id != editing && nameKey(it.name) == nameKey(name) } ->
                issues += ValidationIssue(AgentField.NAME, "Another agent is already called \"$name\".")
        }
        if (editing != null && editing == directorId && draft.kind != AgentKind.DIRECTOR) {
            issues += ValidationIssue(
                AgentField.KIND,
                "This agent is the designated Director. Designate another Director before changing its role.",
            )
        }
        draft.generation.temperature?.let {
            if (it.isNaN() || it < 0.0 || it > 2.0) {
                issues += ValidationIssue(AgentField.GENERATION, "Temperature must be between 0 and 2.")
            }
        }
        draft.generation.maxOutputTokens?.let {
            if (it !in 1..MAX_OUTPUT_TOKENS) {
                issues += ValidationIssue(AgentField.GENERATION, "Maximum output tokens must be between 1 and $MAX_OUTPUT_TOKENS.")
            }
        }
        if (draft.memory.recallLimit !in 0..MAX_RECALL_LIMIT) {
            issues += ValidationIssue(AgentField.MEMORY, "Recalled memories per task must be between 0 and $MAX_RECALL_LIMIT.")
        }
        if (draft.delegation.maxDepth !in 0..MAX_DELEGATION_DEPTH) {
            issues += ValidationIssue(AgentField.DELEGATION, "Delegation depth must be between 0 and $MAX_DELEGATION_DEPTH.")
        }
        return issues
    }

    override suspend fun create(draft: AgentDraft): AgentProfile = mutex.withLock { createLocked(draft) }

    private suspend fun createLocked(draft: AgentDraft): AgentProfile {
        val issues = validate(draft, null, agents.getAll(), settings.get().directorAgentId)
        if (issues.isNotEmpty()) throw AgentValidationException(issues)
        // Strictly after every existing agent, so creation order is the listing order even when
        // several agents are created within one clock tick.
        val now = maxOf(clock.now(), (agents.getAll().maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE) + 1)
        val agent = draft.toProfile(ids.agentId(), createdAt = now, updatedAt = now)
        agents.upsert(agent)
        activity.record(ActivityType.REGISTRY, "Agent created", "${agent.name} (${agent.kind.label})", agentId = agent.id)
        return agent
    }

    override suspend fun createFromTemplate(templateId: String, name: String?): AgentProfile = mutex.withLock {
        val template = templates.firstOrNull { it.id == templateId }
            ?: throw AgentOperationException("Unknown agent template \"$templateId\".")
        val existing = agents.getAll()
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: template.draft.name
        createLocked(template.draft.copy(name = uniqueName(wanted, existing)))
    }

    override suspend fun update(id: AgentId, draft: AgentDraft): AgentProfile = mutex.withLock {
        val current = agents.get(id) ?: throw AgentOperationException("That agent no longer exists.")
        val issues = validate(draft, id, agents.getAll(), settings.get().directorAgentId)
        if (issues.isNotEmpty()) throw AgentValidationException(issues)
        val updated = draft.toProfile(id, createdAt = current.createdAt, updatedAt = clock.now())
        agents.upsert(updated)
        activity.record(ActivityType.REGISTRY, "Agent updated", describeChanges(current, updated), agentId = id)
        updated
    }

    override suspend fun duplicate(id: AgentId, newName: String?): AgentProfile = mutex.withLock {
        val source = agents.get(id) ?: throw AgentOperationException("That agent no longer exists.")
        val existing = agents.getAll()
        val wanted = newName?.trim()?.takeIf { it.isNotEmpty() }
            ?: (source.name.take(MAX_NAME_LENGTH - COPY_SUFFIX.length).trimEnd() + COPY_SUFFIX)
        val nextColor = if (source.colorIndex in 0 until Int.MAX_VALUE) source.colorIndex + 1 else 0
        val draft = source.toDraft().copy(name = uniqueName(wanted, existing), colorIndex = nextColor)
        val copy = createLocked(draft)
        activity.record(ActivityType.REGISTRY, "Agent duplicated", "${source.name} → ${copy.name}", agentId = copy.id)
        copy
    }

    override suspend fun setEnabled(id: AgentId, enabled: Boolean): AgentProfile = mutex.withLock {
        val current = agents.get(id) ?: throw AgentOperationException("That agent no longer exists.")
        if (current.enabled == enabled) return@withLock current
        val updated = current.copy(enabled = enabled, updatedAt = clock.now())
        agents.upsert(updated)
        activity.record(ActivityType.REGISTRY, if (enabled) "Agent enabled" else "Agent disabled", current.name, agentId = id)
        updated
    }

    override suspend fun assignModel(id: AgentId, modelId: ModelId?): AgentProfile = mutex.withLock {
        val current = agents.get(id) ?: throw AgentOperationException("That agent no longer exists.")
        if (current.modelId == modelId) return@withLock current
        val updated = current.copy(
            modelId = modelId,
            fallbackModelIds = current.fallbackModelIds.filter { it != modelId },
            updatedAt = clock.now(),
        )
        agents.upsert(updated)
        activity.record(
            ActivityType.REGISTRY,
            if (modelId == null) "Model unassigned" else "Model changed",
            current.name,
            agentId = id,
        )
        updated
    }

    override suspend fun designateDirector(id: AgentId) = mutex.withLock {
        val agent = agents.get(id) ?: throw AgentOperationException("That agent no longer exists.")
        if (agent.kind != AgentKind.DIRECTOR) {
            throw AgentOperationException("Only an agent with the Director role can be designated as Director.")
        }
        if (settings.get().directorAgentId == id) return@withLock
        settings.update { it.copy(directorAgentId = id) }
        activity.record(ActivityType.REGISTRY, "Director designated", agent.name, agentId = id)
        Unit
    }

    override suspend fun delete(id: AgentId) = mutex.withLock {
        val agent = agents.get(id) ?: return@withLock
        if (settings.get().directorAgentId == id) {
            throw AgentOperationException("${agent.name} is the designated Director. Designate another Director before deleting it.")
        }
        // Once the agent is gone, clean-up and the audit entry must all run, even if the caller
        // is cancelled or one listener fails. Listeners must not call back into this manager.
        withContext(NonCancellable) {
            agents.delete(id)
            for (listener in deletionListeners) {
                try {
                    listener.onAgentDeleted(agent)
                } catch (e: Exception) {
                    activity.record(
                        ActivityType.ERROR, "Clean-up after deleting an agent failed",
                        "${agent.name}: ${e.message ?: e::class.simpleName}", severity = Severity.ERROR,
                    )
                }
            }
            activity.record(ActivityType.REGISTRY, "Agent deleted", agent.name)
        }
        Unit
    }

    override suspend fun ensureDefaults() = mutex.withLock {
        val before = settings.get().seedVersion
        if (before >= SEED_VERSION) return@withLock
        // The starter team is created on the first run only. Resumable: an interrupted first run
        // continues with the templates that are still missing. An owner who already has agents of
        // their own is left alone, and a later version never brings back agents the owner deleted.
        val existing = agents.getAll()
        val seedable = before == 0 && (existing.isEmpty() || existing.all { it.templateId in starterTeam })
        if (seedable) {
            withContext(NonCancellable) {
                var created = 0
                for (templateId in starterTeam) {
                    if (agents.getAll().any { it.templateId == templateId }) continue
                    val template = templates.firstOrNull { it.id == templateId } ?: continue
                    createLocked(template.draft.copy(name = uniqueName(template.draft.name, agents.getAll())))
                    created++
                }
                if (created > 0) {
                    activity.record(
                        ActivityType.SYSTEM, "Starter team created",
                        "$created agents were set up. Each needs a model before it can work.",
                    )
                }
            }
        }
        if (before in 1 until TOOLS_SEED_VERSION) giveTemplateToolsToEarlierAgents()
        if (before in 1 until MEMORY_TOOLS_SEED_VERSION) giveMemoryToolsToTemplateAgents()
        if (before in 1 until OPERATOR_TOOLS_SEED_VERSION) giveOperatorToolsToTemplateAgents()
        val current = settings.get()
        val director = current.directorAgentId?.let { agents.get(it) }
            ?: agents.getAll().firstOrNull { it.kind == AgentKind.DIRECTOR }
        settings.update { it.copy(seedVersion = SEED_VERSION, directorAgentId = director?.id) }
        Unit
    }

    /**
     * Version 2 of the starter content added the built-in information tools to the templates.
     * Agents created from a template before then get the template's tools too, unless the owner
     * has already chosen tools for them. Those tools only read information (the date and time,
     * the list of agents); the owner can switch them off per agent. Tools the templates gained
     * in later versions are left to the steps of those versions, which run after this one.
     */
    private suspend fun giveTemplateToolsToEarlierAgents() = withContext(NonCancellable) {
        var updated = 0
        for (agent in agents.getAll()) {
            if (agent.toolIds.isNotEmpty()) continue
            val tools = templates.firstOrNull { it.id == agent.templateId }?.draft?.toolIds.orEmpty() - MemoryTools.defaultToolIds -
                AgentTemplates.operatorToolIds - AgentTemplates.askToolIds
            if (tools.isEmpty()) continue
            agents.upsert(agent.copy(toolIds = tools, updatedAt = clock.now()))
            updated++
        }
        if (updated > 0) {
            val count = if (updated == 1) "1 agent" else "$updated agents"
            activity.record(
                ActivityType.SYSTEM, "Information tools switched on",
                "$count from the starter templates can now read the date and time and the list of agents. " +
                    "These tools only read information; switch them off in each agent's settings.",
            )
        }
    }

    /**
     * Version 3 of the starter content added memory search and saving to the templates. Every
     * agent made from a template that has them gets exactly those two tools added, and keeps
     * every other tool choice of the owner, including tools the owner switched off. It runs
     * once, so an owner who later switches a memory tool off keeps it off. What each agent may
     * read or write in memory is still limited by its memory policy.
     */
    private suspend fun giveMemoryToolsToTemplateAgents() = withContext(NonCancellable) {
        var updated = 0
        for (agent in agents.getAll()) {
            val template = templates.firstOrNull { it.id == agent.templateId } ?: continue
            val added = template.draft.toolIds.intersect(MemoryTools.defaultToolIds) - agent.toolIds
            if (added.isEmpty()) continue
            agents.upsert(agent.copy(toolIds = agent.toolIds + added, updatedAt = clock.now()))
            updated++
        }
        if (updated > 0) {
            val count = if (updated == 1) "1 agent" else "$updated agents"
            activity.record(
                ActivityType.SYSTEM, "Memory tools switched on",
                "$count from the starter templates can now search and save memories. Each agent's memory settings decide what it may " +
                    "read and write; switch the tools off in each agent's settings.",
            )
        }
    }

    /**
     * Version 4 of the starter content added the phone operator (Phase 5). The Operator gets the
     * operator tools its template now has, and every agent made from a template gets
     * `permissions.request`, so it can ask the owner for what it lacks. Exactly those tools are
     * added; every other tool choice of the owner is kept. It runs once, so a tool the owner
     * later switches off stays off. Switching a tool on gives no permission: each operator tool
     * still needs the owner's grant (reading the workspace aside), and every consequential action
     * asks the owner every time.
     */
    private suspend fun giveOperatorToolsToTemplateAgents() = withContext(NonCancellable) {
        val added = AgentTemplates.operatorToolIds + AgentTemplates.askToolIds
        var operators = 0
        var updated = 0
        for (agent in agents.getAll()) {
            val template = templates.firstOrNull { it.id == agent.templateId } ?: continue
            val missing = template.draft.toolIds.intersect(added) - agent.toolIds
            if (missing.isEmpty()) continue
            agents.upsert(agent.copy(toolIds = agent.toolIds + missing, updatedAt = clock.now()))
            updated++
            if (missing.any { it in AgentTemplates.operatorToolIds }) operators++
        }
        if (updated > 0) {
            val count = if (updated == 1) "1 agent" else "$updated agents"
            val operatorPart = if (operators > 0) " The Operator also has the phone tools switched on." else ""
            activity.record(
                ActivityType.SYSTEM, "Phone operator tools switched on",
                "$count from the starter templates can now ask you for a permission.$operatorPart Switching a tool on gives no " +
                    "permission: you grant each one, and every consequential action asks you first.",
            )
        }
    }

    /** One key for "the same name": trimmed and case-folded the same way everywhere. */
    private fun nameKey(name: String): String = name.trim().uppercase().lowercase()

    private fun uniqueName(wanted: String, existing: List<AgentProfile>): String {
        val taken = existing.mapTo(HashSet()) { nameKey(it.name) }
        val base = wanted.trim().take(MAX_NAME_LENGTH).trimEnd()
        if (nameKey(base) !in taken) return base
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = base.take(MAX_NAME_LENGTH - suffix.length).trimEnd() + suffix
            if (nameKey(candidate) !in taken) return candidate
            n++
        }
    }

    private fun describeChanges(before: AgentProfile, after: AgentProfile): String {
        val changed = buildList {
            if (before.name != after.name) add("name")
            if (before.kind != after.kind) add("role")
            if (before.description != after.description) add("description")
            if (before.systemInstructions != after.systemInstructions) add("instructions")
            if (before.personality != after.personality) add("personality")
            if (before.responsibilities != after.responsibilities) add("responsibilities")
            if (before.skills != after.skills) add("skills")
            if (before.modelId != after.modelId || before.fallbackModelIds != after.fallbackModelIds) add("model")
            if (before.generation != after.generation) add("generation settings")
            if (before.toolIds != after.toolIds) add("tools")
            if (before.memory != after.memory) add("memory policy")
            if (before.delegation != after.delegation) add("delegation")
            if (before.enabled != after.enabled) add(if (after.enabled) "enabled" else "disabled")
        }
        return if (changed.isEmpty()) after.name else "${after.name}: ${changed.joinToString(", ")}"
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        private const val COPY_SUFFIX = " copy"
        const val MAX_OUTPUT_TOKENS = 200_000
        const val MAX_RECALL_LIMIT = 50
        const val MAX_DELEGATION_DEPTH = 10

        /** Bump when the built-in starter content changes in a way existing installs should receive. */
        const val SEED_VERSION = 4

        /** The starter-content version that gave template agents the built-in information tools. */
        private const val TOOLS_SEED_VERSION = 2

        /** The starter-content version that gave template agents memory search and saving. */
        private const val MEMORY_TOOLS_SEED_VERSION = 3

        /** The starter-content version that gave the Operator its phone tools and every template agent `permissions.request`. */
        private const val OPERATOR_TOOLS_SEED_VERSION = 4
    }
}
