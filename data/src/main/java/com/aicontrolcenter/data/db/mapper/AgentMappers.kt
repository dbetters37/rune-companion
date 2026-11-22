package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.data.db.entity.AgentEntity
import com.aicontrolcenter.data.db.entity.AgentStatsEntity

internal fun AgentProfile.toEntity(): AgentEntity = AgentEntity(
    id = id.value,
    name = name,
    kind = kind.name,
    description = description,
    systemInstructions = systemInstructions,
    personality = personality,
    responsibilitiesJson = encodeColumn(ColumnSerializers.stringList, responsibilities),
    skillsJson = encodeColumn(ColumnSerializers.stringSet, skills),
    modelId = modelId?.value,
    fallbackModelIdsJson = encodeColumn(ColumnSerializers.modelIdList, fallbackModelIds),
    generationJson = encodeColumn(GenerationSettings.serializer(), generation),
    toolIdsJson = encodeColumn(ColumnSerializers.toolIdSet, toolIds),
    memoryJson = encodeColumn(MemoryPolicy.serializer(), memory),
    delegationJson = encodeColumn(DelegationPolicy.serializer(), delegation),
    enabled = enabled,
    colorIndex = colorIndex,
    templateId = templateId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun AgentEntity.toDomain(): AgentProfile {
    val row = RowRef(AgentEntity.TABLE, id)
    return AgentProfile(
        id = AgentId(id),
        name = name,
        kind = row.enum("kind", kind),
        description = description,
        systemInstructions = systemInstructions,
        personality = personality,
        responsibilities = row.json("responsibilities_json", ColumnSerializers.stringList, responsibilitiesJson),
        skills = row.json("skills_json", ColumnSerializers.stringSet, skillsJson),
        modelId = modelId?.let(::ModelId),
        fallbackModelIds = row.json("fallback_model_ids_json", ColumnSerializers.modelIdList, fallbackModelIdsJson),
        generation = row.json("generation_json", GenerationSettings.serializer(), generationJson),
        toolIds = row.json("tool_ids_json", ColumnSerializers.toolIdSet, toolIdsJson),
        memory = row.json("memory_json", MemoryPolicy.serializer(), memoryJson),
        delegation = row.json("delegation_json", DelegationPolicy.serializer(), delegationJson),
        enabled = enabled,
        colorIndex = colorIndex,
        templateId = templateId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal fun AgentStats.toEntity(agentId: AgentId): AgentStatsEntity = AgentStatsEntity(
    agentId = agentId.value,
    tasksAssigned = tasksAssigned,
    tasksCompleted = tasksCompleted,
    tasksFailed = tasksFailed,
    tasksTimedOut = tasksTimedOut,
    tasksCancelled = tasksCancelled,
    reviewsPassed = reviewsPassed,
    revisionsRequested = revisionsRequested,
    reviewsRejected = reviewsRejected,
    modelCalls = modelCalls,
    modelErrors = modelErrors,
    toolCalls = toolCalls,
    toolErrors = toolErrors,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    totalTaskDurationMs = totalTaskDurationMs,
    lastActiveAt = lastActiveAt,
)

internal fun AgentStatsEntity.toDomain(): AgentStats = AgentStats(
    tasksAssigned = tasksAssigned,
    tasksCompleted = tasksCompleted,
    tasksFailed = tasksFailed,
    tasksTimedOut = tasksTimedOut,
    tasksCancelled = tasksCancelled,
    reviewsPassed = reviewsPassed,
    revisionsRequested = revisionsRequested,
    reviewsRejected = reviewsRejected,
    modelCalls = modelCalls,
    modelErrors = modelErrors,
    toolCalls = toolCalls,
    toolErrors = toolErrors,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    totalTaskDurationMs = totalTaskDurationMs,
    lastActiveAt = lastActiveAt,
)
