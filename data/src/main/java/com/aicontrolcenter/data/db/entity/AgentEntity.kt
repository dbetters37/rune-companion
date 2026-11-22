package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per agent profile. Everything that is filtered, ordered or joined on is a scalar
 * column; nested configuration objects and collections are JSON text (columns ending `_json`).
 *
 * There is deliberately no foreign key to `models`: an agent may point at a model that has been
 * removed, and the registry reports that as "model not registered" instead of losing the agent.
 */
@Entity(
    tableName = AgentEntity.TABLE,
    indices = [
        Index(value = ["created_at", "id"]),
        Index(value = ["model_id"]),
    ],
)
internal data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val description: String,
    @ColumnInfo(name = "system_instructions") val systemInstructions: String,
    val personality: String,
    @ColumnInfo(name = "responsibilities_json") val responsibilitiesJson: String,
    @ColumnInfo(name = "skills_json") val skillsJson: String,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "fallback_model_ids_json") val fallbackModelIdsJson: String,
    @ColumnInfo(name = "generation_json") val generationJson: String,
    @ColumnInfo(name = "tool_ids_json") val toolIdsJson: String,
    @ColumnInfo(name = "memory_json") val memoryJson: String,
    @ColumnInfo(name = "delegation_json") val delegationJson: String,
    val enabled: Boolean,
    @ColumnInfo(name = "color_index") val colorIndex: Int,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val TABLE = "agents"
    }
}
