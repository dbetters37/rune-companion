package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.AgentModelUsage
import com.aicontrolcenter.core.usage.AgentUsage
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelUsage
import com.aicontrolcenter.core.usage.ProviderUsage
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.core.usage.WorkItem
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.core.usage.WorkUsage
import com.aicontrolcenter.data.db.entity.AgentModelUsageRow
import com.aicontrolcenter.data.db.entity.AgentUsageRow
import com.aicontrolcenter.data.db.entity.ModelPriceEntity
import com.aicontrolcenter.data.db.entity.ModelUsageRow
import com.aicontrolcenter.data.db.entity.ProviderUsageRow
import com.aicontrolcenter.data.db.entity.UsageCounts
import com.aicontrolcenter.data.db.entity.UsageHourEntity
import com.aicontrolcenter.data.db.entity.WorkUsageEntity

/*
 * Usage rows and the domain's usage types (schema 6; Phase 6, K11). Numbers, ids and labels
 * only (rule 7).
 */

internal fun UsageCounts.toTotals(): UsageTotals = UsageTotals(
    calls = calls, ok = ok, failed = failed, cancelled = cancelled, blocked = blocked, retries = retries,
    substituted = substituted, truncated = truncated, noUsage = noUsage, rateLimitedWaits = rateLimitedWaits,
    errors = buildMap {
        for (error in ErrorClass.entries) {
            val count = errorCount(error)
            if (count > 0) put(error, count)
        }
    },
    inputTokens = inputTokens, outputTokens = outputTokens, cachedInputTokens = cachedInputTokens,
    answerMsSum = answerMsSum, ttftMsSum = ttftMsSum, ttftCount = ttftCount, bytesSent = bytesSent,
)

/** The `err_*` column of [error]. */
internal fun UsageCounts.errorCount(error: ErrorClass): Long = when (error) {
    ErrorClass.AUTH -> errAuth
    ErrorClass.RATE_LIMITED -> errRateLimited
    ErrorClass.OVERLOADED -> errOverloaded
    ErrorClass.TIMEOUT -> errTimeout
    ErrorClass.NETWORK -> errNetwork
    ErrorClass.SERVER -> errServer
    ErrorClass.INVALID -> errInvalid
    ErrorClass.CONTEXT -> errContext
    ErrorClass.OTHER -> errOther
}

internal fun UsageTotals.toCounts(): UsageCounts {
    fun error(error: ErrorClass) = errors[error] ?: 0
    return UsageCounts(
        calls = calls, ok = ok, failed = failed, cancelled = cancelled, blocked = blocked, retries = retries,
        substituted = substituted, truncated = truncated, noUsage = noUsage, rateLimitedWaits = rateLimitedWaits,
        errAuth = error(ErrorClass.AUTH), errRateLimited = error(ErrorClass.RATE_LIMITED), errOverloaded = error(ErrorClass.OVERLOADED),
        errTimeout = error(ErrorClass.TIMEOUT), errNetwork = error(ErrorClass.NETWORK), errServer = error(ErrorClass.SERVER),
        errInvalid = error(ErrorClass.INVALID), errContext = error(ErrorClass.CONTEXT), errOther = error(ErrorClass.OTHER),
        inputTokens = inputTokens, outputTokens = outputTokens, cachedInputTokens = cachedInputTokens,
        answerMsSum = answerMsSum, ttftMsSum = ttftMsSum, ttftCount = ttftCount, bytesSent = bytesSent,
    )
}

/** The key of the hour row [this] sample is summed into. */
internal data class UsageHourKey(val hourStart: Long, val modelId: String, val agentId: String, val source: String)

internal val ModelCallSample.hourKey: UsageHourKey
    get() = UsageHourKey(hourStart, modelId.value, agentId?.value ?: UsageHourEntity.NO_AGENT, source.name)

/**
 * [stored] (the row of this sample's hour, model, agent and source, or null for a new one) with
 * this sample added: counts summed, labels and locality the sample's, [UsageHourEntity.lastCallAt]
 * the newer of the two.
 */
internal fun ModelCallSample.addedTo(stored: UsageHourEntity?): UsageHourEntity {
    val key = hourKey
    val before = stored?.counts?.toTotals() ?: UsageTotals()
    return UsageHourEntity(
        hourStart = key.hourStart, modelId = key.modelId, agentId = key.agentId, source = key.source,
        modelLabel = modelLabel, providerId = providerId.value, providerLabel = providerLabel, locality = locality.name,
        counts = (before + UsageTotals.of(this)).toCounts(),
        lastCallAt = maxOf(stored?.lastCallAt ?: at, at),
    )
}

/**
 * [stored] (the row of [item] and this sample's model, or null for a new one) with this sample's tokens added. Its calls
 * grow only by a call that counts as a model answer ([UsageTotals.countedCalls], as today's limits count): never by a
 * call the daily limit refused before sending, nor by one a "slow down" answer ended (INT3-1).
 */
internal fun ModelCallSample.addedTo(item: WorkItem, stored: WorkUsageEntity?): WorkUsageEntity = WorkUsageEntity(
    workKind = item.kind.name,
    workId = item.id,
    modelId = modelId.value,
    calls = (stored?.calls ?: 0) + UsageTotals.of(this).countedCalls,
    inputTokens = (stored?.inputTokens ?: 0) + (inputTokens ?: 0),
    outputTokens = (stored?.outputTokens ?: 0) + (outputTokens ?: 0),
    cachedInputTokens = (stored?.cachedInputTokens ?: 0) + (cachedInputTokens ?: 0),
    updatedAt = maxOf(stored?.updatedAt ?: at, at),
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun ModelUsageRow.toDomain(): ModelUsage = ModelUsage(
    modelId = ModelId(modelId),
    modelLabel = modelLabel,
    providerId = ProviderId(providerId),
    providerLabel = providerLabel,
    locality = RowRef(UsageHourEntity.TABLE, modelId).enum<Locality>("locality", locality),
    totals = counts.toTotals(),
)

internal fun AgentUsageRow.toDomain(): AgentUsage =
    AgentUsage(agentId = agentId.takeUnless { it == UsageHourEntity.NO_AGENT }?.let(::AgentId), totals = counts.toTotals())

internal fun AgentModelUsageRow.toDomain(): AgentModelUsage = AgentModelUsage(
    agentId = agentId.takeUnless { it == UsageHourEntity.NO_AGENT }?.let(::AgentId),
    modelId = ModelId(modelId),
    totals = counts.toTotals(),
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun ProviderUsageRow.toDomain(): ProviderUsage = ProviderUsage(
    providerId = ProviderId(providerId),
    providerLabel = providerLabel,
    locality = RowRef(UsageHourEntity.TABLE, providerId).enum<Locality>("locality", locality),
    totals = counts.toTotals(),
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun WorkUsageEntity.toDomain(): WorkUsage = WorkUsage(
    kind = RowRef(WorkUsageEntity.TABLE, "$workKind/$workId/$modelId").enum<WorkKind>("work_kind", workKind),
    workId = workId,
    modelId = ModelId(modelId),
    calls = calls,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedInputTokens = cachedInputTokens,
    updatedAt = updatedAt,
)

internal fun ModelPrice.toEntity(): ModelPriceEntity = ModelPriceEntity(
    modelId = modelId.value,
    inputPerMTokMicros = inputPerMTokMicros,
    outputPerMTokMicros = outputPerMTokMicros,
    cachedInputPerMTokMicros = cachedInputPerMTokMicros,
    currencyLabel = currencyLabel,
    updatedAt = updatedAt,
)

/** @throws com.aicontrolcenter.data.StoredDataException if the stored values are not a valid price. */
internal fun ModelPriceEntity.toDomain(): ModelPrice = RowRef(ModelPriceEntity.TABLE, modelId).consistent {
    ModelPrice(
        modelId = ModelId(modelId),
        inputPerMTokMicros = inputPerMTokMicros,
        outputPerMTokMicros = outputPerMTokMicros,
        cachedInputPerMTokMicros = cachedInputPerMTokMicros,
        currencyLabel = currencyLabel,
        updatedAt = updatedAt,
    )
}
