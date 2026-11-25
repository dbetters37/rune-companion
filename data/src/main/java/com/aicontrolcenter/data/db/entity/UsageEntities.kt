package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Usage and prices (schema 6; Phase 6, K11, rules 6, 7, 29). Numbers, ids and the labels of
 * models and providers only: no message content, no keys, no addresses (rule 7, pinned by
 * `UsageColumnsTest`). None of these tables has a foreign key: a usage row outlives the model,
 * agent, task or chat it counts, and is removed by retention, by "Clear usage history", or with
 * the task tree or chat it belongs to.
 */

/**
 * The counters of one or more model calls: the numeric columns of `usage_hours`, also the shape
 * of every sum the reports read (`UsageDao.SUMS`). `err_*` count the failed calls by
 * `ErrorClass`.
 */
internal data class UsageCounts(
    val calls: Long = 0,
    val ok: Long = 0,
    val failed: Long = 0,
    val cancelled: Long = 0,
    val blocked: Long = 0,
    val retries: Long = 0,
    val substituted: Long = 0,
    val truncated: Long = 0,
    @ColumnInfo(name = "no_usage") val noUsage: Long = 0,
    @ColumnInfo(name = "rate_limited_waits") val rateLimitedWaits: Long = 0,
    @ColumnInfo(name = "err_auth") val errAuth: Long = 0,
    @ColumnInfo(name = "err_rate_limited") val errRateLimited: Long = 0,
    @ColumnInfo(name = "err_overloaded") val errOverloaded: Long = 0,
    @ColumnInfo(name = "err_timeout") val errTimeout: Long = 0,
    @ColumnInfo(name = "err_network") val errNetwork: Long = 0,
    @ColumnInfo(name = "err_server") val errServer: Long = 0,
    @ColumnInfo(name = "err_invalid") val errInvalid: Long = 0,
    @ColumnInfo(name = "err_context") val errContext: Long = 0,
    @ColumnInfo(name = "err_other") val errOther: Long = 0,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long = 0,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long = 0,
    @ColumnInfo(name = "cached_input_tokens") val cachedInputTokens: Long = 0,
    @ColumnInfo(name = "answer_ms_sum") val answerMsSum: Long = 0,
    @ColumnInfo(name = "ttft_ms_sum") val ttftMsSum: Long = 0,
    @ColumnInfo(name = "ttft_count") val ttftCount: Long = 0,
    @ColumnInfo(name = "bytes_sent") val bytesSent: Long = 0,
)

/**
 * One row per hour (UTC, [hourStart]), model, agent and source: every call of that hour summed
 * ([counts]). [agentId] is "" for calls made for no agent (a primary key column cannot be null);
 * [source] is a `UsageSource` name. The labels and the locality are those of the newest call
 * recorded into the row; [lastCallAt] is the time of the newest call in it, which always lies in
 * the row's hour, so the reports take the labels of a model or provider from the row with the
 * greatest [lastCallAt].
 *
 * Indexes: the primary key starts with the hour, which serves every range ("today", "this
 * month", retention); `(model_id, hour_start)` and `(agent_id, hour_start)` serve a model's or an
 * agent's history.
 */
@Entity(
    tableName = UsageHourEntity.TABLE,
    primaryKeys = ["hour_start", "model_id", "agent_id", "source"],
    indices = [
        Index(value = ["model_id", "hour_start"]),
        Index(value = ["agent_id", "hour_start"]),
    ],
)
internal data class UsageHourEntity(
    @ColumnInfo(name = "hour_start") val hourStart: Long,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    val source: String,
    @ColumnInfo(name = "model_label") val modelLabel: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_label") val providerLabel: String,
    val locality: String,
    @Embedded val counts: UsageCounts,
    @ColumnInfo(name = "last_call_at") val lastCallAt: Long,
) {
    companion object {
        const val TABLE = "usage_hours"

        /** The stored [agentId] of a call made for no agent. */
        const val NO_AGENT = ""
    }
}

/**
 * What one model's calls for one work item came to: [workKind] is a `WorkKind` name (TASK,
 * OBJECTIVE or CHAT) and [workId] the task's, objective's or conversation's id. [updatedAt] is
 * the time of the newest call recorded. Written in the same transaction as the call's
 * `usage_hours` row; deleted with the task tree or the conversation.
 */
@Entity(tableName = WorkUsageEntity.TABLE, primaryKeys = ["work_kind", "work_id", "model_id"])
internal data class WorkUsageEntity(
    @ColumnInfo(name = "work_kind") val workKind: String,
    @ColumnInfo(name = "work_id") val workId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    val calls: Long,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "cached_input_tokens") val cachedInputTokens: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val TABLE = "work_usage"
    }
}

/**
 * A price the owner typed in for a model, in millionths of [currencyLabel] per million tokens;
 * [cachedInputPerMTokMicros] null means cached input costs the input price. Separate from
 * `models` because refreshing a provider's model list rewrites those rows. Never fetched.
 */
@Entity(tableName = ModelPriceEntity.TABLE)
internal data class ModelPriceEntity(
    @PrimaryKey @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "input_per_mtok_micros") val inputPerMTokMicros: Long,
    @ColumnInfo(name = "output_per_mtok_micros") val outputPerMTokMicros: Long,
    @ColumnInfo(name = "cached_input_per_mtok_micros") val cachedInputPerMTokMicros: Long?,
    @ColumnInfo(name = "currency_label") val currencyLabel: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val TABLE = "model_prices"
    }
}
