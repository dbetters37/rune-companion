package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.tasks.ReviewVerdict
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.data.DataJson
import com.aicontrolcenter.data.StoredDataException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer

/** Serializers for the collection-valued JSON columns, declared once so every mapper agrees. */
internal object ColumnSerializers {
    val stringList: KSerializer<List<String>> = ListSerializer(String.serializer())
    val stringSet: KSerializer<Set<String>> = SetSerializer(String.serializer())
    val stringMap: KSerializer<Map<String, String>> = MapSerializer(String.serializer(), String.serializer())
    val modelIdList: KSerializer<List<ModelId>> = ListSerializer(ModelId.serializer())
    val toolIdSet: KSerializer<Set<ToolId>> = SetSerializer(ToolId.serializer())
    val appCapabilitySet: KSerializer<Set<AiAppCapability>> = SetSerializer(serializer<AiAppCapability>())
    val taskIdList: KSerializer<List<TaskId>> = ListSerializer(TaskId.serializer())
    val agentIdList: KSerializer<List<AgentId>> = ListSerializer(AgentId.serializer())
    val reviewList: KSerializer<List<ReviewVerdict>> = ListSerializer(ReviewVerdict.serializer())

    /** Polymorphic: `{"type":"owner"}` or `{"type":"agent","agentId":...}`. */
    val taskRequester: KSerializer<TaskRequester> = serializer<TaskRequester>()
}

internal fun <T> encodeColumn(serializer: SerializationStrategy<T>, value: T): String =
    DataJson.encodeToString(serializer, value)

/**
 * Identifies the row a mapper is reading, so that a value that cannot be decoded is reported
 * as "which column of which row" instead of a bare parser error.
 */
internal class RowRef(val table: String, val rowId: String) {

    fun <T> json(column: String, serializer: DeserializationStrategy<T>, text: String): T =
        try {
            DataJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw StoredDataException("Column $table.$column of row '$rowId' does not hold readable JSON.", e)
        }

    inline fun <reified E : Enum<E>> enum(column: String, name: String): E =
        enumValues<E>().firstOrNull { it.name == name }
            ?: throw StoredDataException("Column $table.$column of row '$rowId' holds the unknown value '$name'.")

    /** [value], which [reason] needs; a null there means the row is damaged. */
    fun <T : Any> present(column: String, value: T?, reason: String): T =
        value ?: throw StoredDataException("Column $table.$column of row '$rowId' is empty, but $reason.")
}
