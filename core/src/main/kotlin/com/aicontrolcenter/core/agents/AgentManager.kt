package com.aicontrolcenter.core.agents

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ToolId
import kotlinx.serialization.Serializable

/** Everything about an agent that the user edits; ids and timestamps are assigned by the manager. */
@Serializable
data class AgentDraft(
    val name: String,
    val kind: AgentKind = AgentKind.SPECIALIST,
    val description: String = "",
    val systemInstructions: String = "",
    val personality: String = "",
    val responsibilities: List<String> = emptyList(),
    val skills: Set<String> = emptySet(),
    val modelId: ModelId? = null,
    val fallbackModelIds: List<ModelId> = emptyList(),
    val generation: GenerationSettings = GenerationSettings(),
    val toolIds: Set<ToolId> = emptySet(),
    val memory: MemoryPolicy = MemoryPolicy(),
    val delegation: DelegationPolicy = DelegationPolicy(),
    val enabled: Boolean = true,
    val colorIndex: Int = 0,
    val templateId: String? = null,
)

fun AgentProfile.toDraft(): AgentDraft = AgentDraft(
    name = name, kind = kind, description = description, systemInstructions = systemInstructions,
    personality = personality, responsibilities = responsibilities, skills = skills, modelId = modelId,
    fallbackModelIds = fallbackModelIds, generation = generation, toolIds = toolIds, memory = memory,
    delegation = delegation, enabled = enabled, colorIndex = colorIndex, templateId = templateId,
)

fun AgentDraft.toProfile(id: AgentId, createdAt: Long, updatedAt: Long): AgentProfile = AgentProfile(
    id = id, name = name.trim(), kind = kind, description = description.trim(),
    systemInstructions = systemInstructions.trim(), personality = personality.trim(),
    responsibilities = responsibilities.map { it.trim() }.filter { it.isNotEmpty() },
    skills = skills.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
    modelId = modelId, fallbackModelIds = fallbackModelIds.filter { it != modelId }.distinct(),
    generation = generation, toolIds = toolIds, memory = memory, delegation = delegation,
    enabled = enabled, colorIndex = colorIndex, templateId = templateId,
    createdAt = createdAt, updatedAt = updatedAt,
)

/** A ready-made starting point for a new agent. */
@Serializable
data class AgentTemplate(
    val id: String,
    val title: String,
    val summary: String,
    val draft: AgentDraft,
)

@Serializable
data class ValidationIssue(val field: AgentField, val message: String)

enum class AgentField { NAME, KIND, INSTRUCTIONS, MODEL, GENERATION, MEMORY, DELEGATION }

class AgentValidationException(val issues: List<ValidationIssue>) :
    IllegalArgumentException(issues.joinToString("; ") { it.message })

/** An operation that is well-formed but not allowed in the current state. */
class AgentOperationException(message: String) : IllegalStateException(message)

/**
 * Creates, edits, duplicates and removes agents. Adding an agent is purely a data operation:
 * nothing else in the framework needs to be rebuilt or restarted.
 */
interface AgentManager {
    val templates: List<AgentTemplate>

    /** Problems that would prevent saving [draft]. Empty means valid. [editing] is the agent being edited, if any. */
    suspend fun validate(draft: AgentDraft, editing: AgentId? = null): List<ValidationIssue>

    /** @throws AgentValidationException when the draft is invalid. */
    suspend fun create(draft: AgentDraft): AgentProfile

    /** Creates an agent from a template, making the name unique if needed. */
    suspend fun createFromTemplate(templateId: String, name: String? = null): AgentProfile

    /**
     * Replaces the editable fields of an existing agent.
     * @throws AgentValidationException when the draft is invalid.
     * @throws AgentOperationException when the agent does not exist.
     */
    suspend fun update(id: AgentId, draft: AgentDraft): AgentProfile

    /**
     * Copies an agent's configuration into a new agent with a unique name. The copy starts with
     * no track record and no permission grants, and is never the designated Director.
     */
    suspend fun duplicate(id: AgentId, newName: String? = null): AgentProfile

    suspend fun setEnabled(id: AgentId, enabled: Boolean): AgentProfile

    /** Changes only the model. Passing null leaves the agent without a model. */
    suspend fun assignModel(id: AgentId, modelId: ModelId?): AgentProfile

    /**
     * Makes [id] the Director that receives the user's objectives.
     * @throws AgentOperationException unless the agent exists and has kind DIRECTOR.
     */
    suspend fun designateDirector(id: AgentId)

    /**
     * Deletes an agent.
     * @throws AgentOperationException for the designated Director; designate another one first.
     */
    suspend fun delete(id: AgentId)

    /**
     * First-run seeding: creates the Director and a small starter team once, and designates the
     * Director. Safe to call on every start; does nothing after the first successful run.
     */
    suspend fun ensureDefaults()
}
