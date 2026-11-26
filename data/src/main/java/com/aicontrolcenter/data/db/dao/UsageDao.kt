package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.data.db.entity.AgentModelUsageRow
import com.aicontrolcenter.data.db.entity.AgentUsageRow
import com.aicontrolcenter.data.db.entity.ModelPriceEntity
import com.aicontrolcenter.data.db.entity.ModelUsageRow
import com.aicontrolcenter.data.db.entity.ProviderUsageRow
import com.aicontrolcenter.data.db.entity.UsageCounts
import com.aicontrolcenter.data.db.entity.UsageHourEntity
import com.aicontrolcenter.data.db.entity.WorkUsageEntity
import com.aicontrolcenter.data.db.mapper.addedTo
import com.aicontrolcenter.data.db.mapper.hourKey
import kotlinx.coroutines.flow.Flow

/**
 * Usage (`usage_hours`, `work_usage`) and the owner's prices (`model_prices`), schema 6.
 *
 * A call is recorded by [record]: its hour row and its work rows are each read, added to and
 * written inside one write transaction, so concurrent calls queue up and none is lost, and
 * whoever reads the tables sees a call in all of its rows or in none. Ranges are `[from, until)`
 * on `hour_start`, the start of the row's UTC hour (`UsageRange`), read from the primary key,
 * which starts with the hour.
 */
@Dao
internal interface UsageDao {
    @Query("SELECT * FROM usage_hours WHERE hour_start = :hourStart AND model_id = :modelId AND agent_id = :agentId AND source = :source")
    suspend fun hour(hourStart: Long, modelId: String, agentId: String, source: String): UsageHourEntity?

    @Upsert
    suspend fun upsertHour(row: UsageHourEntity)

    @Query("SELECT * FROM work_usage WHERE work_kind = :kind AND work_id = :id AND model_id = :modelId")
    suspend fun work(kind: String, id: String, modelId: String): WorkUsageEntity?

    @Upsert
    suspend fun upsertWork(row: WorkUsageEntity)

    /** Adds [sample] to its hour row and to its work rows, all in one write transaction. */
    @Transaction
    suspend fun record(sample: ModelCallSample) {
        val key = sample.hourKey
        upsertHour(sample.addedTo(hour(key.hourStart, key.modelId, key.agentId, key.source)))
        for (item in sample.workItems) {
            upsertWork(sample.addedTo(item, work(item.kind.name, item.id, sample.modelId.value)))
        }
    }

    // --- Reports ---

    @Query("SELECT $SUMS FROM usage_hours WHERE hour_start >= :from AND hour_start < :until")
    suspend fun sums(from: Long, until: Long): UsageCounts

    @Query("SELECT $SUMS FROM usage_hours WHERE hour_start >= :from AND hour_start < :until")
    fun observeSums(from: Long, until: Long): Flow<UsageCounts>

    @Query(BY_MODEL)
    suspend fun byModel(from: Long, until: Long): List<ModelUsageRow>

    @Query(BY_MODEL)
    fun observeByModel(from: Long, until: Long): Flow<List<ModelUsageRow>>

    @Query(
        "SELECT agent_id, $SUMS FROM usage_hours WHERE hour_start >= :from AND hour_start < :until " +
            "GROUP BY agent_id ORDER BY SUM(calls) DESC, agent_id = '${UsageHourEntity.NO_AGENT}' ASC, agent_id ASC",
    )
    suspend fun byAgent(from: Long, until: Long): List<AgentUsageRow>

    /** By agent and model together (wave 3): what a cost per agent needs, since prices are per model. */
    @Query(
        "SELECT agent_id, model_id, $SUMS FROM usage_hours WHERE hour_start >= :from AND hour_start < :until " +
            "GROUP BY agent_id, model_id ORDER BY SUM(calls) DESC, agent_id = '${UsageHourEntity.NO_AGENT}' ASC, agent_id ASC, model_id ASC",
    )
    suspend fun byAgentAndModel(from: Long, until: Long): List<AgentModelUsageRow>

    @Query(
        "SELECT provider_id, provider_label, locality, MAX(last_call_at) AS newest, $SUMS FROM usage_hours " +
            "WHERE hour_start >= :from AND hour_start < :until GROUP BY provider_id ORDER BY SUM(calls) DESC, provider_id ASC",
    )
    suspend fun byProvider(from: Long, until: Long): List<ProviderUsageRow>

    @Query(WORK)
    suspend fun workRows(kind: String, id: String): List<WorkUsageEntity>

    @Query(WORK)
    fun observeWorkRows(kind: String, id: String): Flow<List<WorkUsageEntity>>

    // --- Pruning ---

    @Query("DELETE FROM usage_hours WHERE hour_start < :before")
    suspend fun deleteHoursBefore(before: Long): Int

    @Query("DELETE FROM work_usage WHERE updated_at < :before")
    suspend fun deleteWorkUpdatedBefore(before: Long): Int

    /** "Clear usage history": hour rows that started before [before] and work rows last updated before it, together. */
    @Transaction
    suspend fun clearBefore(before: Long): Int {
        val hours = deleteHoursBefore(before)
        deleteWorkUpdatedBefore(before)
        return hours
    }

    /** Work rows whose task, objective or chat no longer exists. The stored kind names are pinned by `StoredNamesGoldenTest`. */
    @Query(
        "DELETE FROM work_usage WHERE (work_kind IN ('TASK', 'OBJECTIVE') AND NOT EXISTS (SELECT 1 FROM tasks WHERE tasks.id = work_usage.work_id)) " +
            "OR (work_kind = 'CHAT' AND NOT EXISTS (SELECT 1 FROM conversations WHERE conversations.id = work_usage.work_id))",
    )
    suspend fun deleteOrphanedWork(): Int

    // --- The owner's prices ---

    @Query("SELECT * FROM model_prices ORDER BY model_id ASC")
    fun observePrices(): Flow<List<ModelPriceEntity>>

    @Query("SELECT * FROM model_prices ORDER BY model_id ASC")
    suspend fun prices(): List<ModelPriceEntity>

    @Query("SELECT * FROM model_prices WHERE model_id = :modelId")
    suspend fun price(modelId: String): ModelPriceEntity?

    @Upsert
    suspend fun upsertPrice(price: ModelPriceEntity)

    @Query("DELETE FROM model_prices WHERE model_id = :modelId")
    suspend fun deletePrice(modelId: String): Int

    /** The report queries, as constants so a test can check that SQLite runs them from an index. */
    companion object {
        /**
         * Every counter of `usage_hours` summed, named as its column ([UsageCounts]). `COALESCE`
         * makes the sum over no rows 0, not null.
         */
        const val SUMS = "COALESCE(SUM(calls), 0) AS calls, COALESCE(SUM(ok), 0) AS ok, COALESCE(SUM(failed), 0) AS failed, " +
            "COALESCE(SUM(cancelled), 0) AS cancelled, COALESCE(SUM(blocked), 0) AS blocked, COALESCE(SUM(retries), 0) AS retries, " +
            "COALESCE(SUM(substituted), 0) AS substituted, COALESCE(SUM(truncated), 0) AS truncated, " +
            "COALESCE(SUM(no_usage), 0) AS no_usage, COALESCE(SUM(rate_limited_waits), 0) AS rate_limited_waits, " +
            "COALESCE(SUM(err_auth), 0) AS err_auth, COALESCE(SUM(err_rate_limited), 0) AS err_rate_limited, " +
            "COALESCE(SUM(err_overloaded), 0) AS err_overloaded, COALESCE(SUM(err_timeout), 0) AS err_timeout, " +
            "COALESCE(SUM(err_network), 0) AS err_network, COALESCE(SUM(err_server), 0) AS err_server, " +
            "COALESCE(SUM(err_invalid), 0) AS err_invalid, COALESCE(SUM(err_context), 0) AS err_context, " +
            "COALESCE(SUM(err_other), 0) AS err_other, COALESCE(SUM(input_tokens), 0) AS input_tokens, " +
            "COALESCE(SUM(output_tokens), 0) AS output_tokens, COALESCE(SUM(cached_input_tokens), 0) AS cached_input_tokens, " +
            "COALESCE(SUM(answer_ms_sum), 0) AS answer_ms_sum, COALESCE(SUM(ttft_ms_sum), 0) AS ttft_ms_sum, " +
            "COALESCE(SUM(ttft_count), 0) AS ttft_count, COALESCE(SUM(bytes_sent), 0) AS bytes_sent"

        /**
         * Per model, most calls first. The labels are those of the model's newest row: with
         * exactly one `MAX()` in an aggregate query, SQLite takes the other plain columns from the
         * row that holds the maximum (documented in "SELECT", "bare columns in an aggregate query").
         */
        const val BY_MODEL = "SELECT model_id, model_label, provider_id, provider_label, locality, MAX(last_call_at) AS newest, $SUMS " +
            "FROM usage_hours WHERE hour_start >= :from AND hour_start < :until GROUP BY model_id ORDER BY SUM(calls) DESC, model_id ASC"

        const val WORK = "SELECT * FROM work_usage WHERE work_kind = :kind AND work_id = :id ORDER BY model_id ASC"
    }
}
