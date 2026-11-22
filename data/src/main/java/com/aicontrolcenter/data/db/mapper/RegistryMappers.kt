package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.data.db.entity.AiAppEntity
import com.aicontrolcenter.data.db.entity.ModelEntity
import com.aicontrolcenter.data.db.entity.ProviderEntity

internal fun ModelProvider.toEntity(): ProviderEntity = ProviderEntity(
    id = id.value,
    name = name,
    kind = kind.name,
    baseUrl = baseUrl,
    apiKeyAlias = apiKeyAlias,
    extraHeadersJson = encodeColumn(ColumnSerializers.stringMap, extraHeaders),
    locality = locality.name,
    enabled = enabled,
    status = status.name,
    statusMessage = statusMessage,
    lastCheckedAt = lastCheckedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ProviderEntity.toDomain(): ModelProvider {
    val row = RowRef(ProviderEntity.TABLE, id)
    return ModelProvider(
        id = ProviderId(id),
        name = name,
        kind = row.enum("kind", kind),
        baseUrl = baseUrl,
        apiKeyAlias = apiKeyAlias,
        extraHeaders = row.json("extra_headers_json", ColumnSerializers.stringMap, extraHeadersJson),
        locality = row.enum("locality", locality),
        enabled = enabled,
        status = row.enum("status", status),
        statusMessage = statusMessage,
        lastCheckedAt = lastCheckedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal fun ModelDescriptor.toEntity(): ModelEntity = ModelEntity(
    id = id.value,
    providerId = providerId.value,
    modelName = modelName,
    displayName = displayName,
    capabilitiesJson = encodeColumn(ModelCapabilities.serializer(), capabilities),
    source = source.name,
    localPath = localPath,
    sizeBytes = sizeBytes,
    enabled = enabled,
    status = status.name,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ModelEntity.toDomain(): ModelDescriptor {
    val row = RowRef(ModelEntity.TABLE, id)
    return ModelDescriptor(
        id = ModelId(id),
        providerId = ProviderId(providerId),
        modelName = modelName,
        displayName = displayName,
        capabilities = row.json("capabilities_json", ModelCapabilities.serializer(), capabilitiesJson),
        source = row.enum("source", source),
        localPath = localPath,
        sizeBytes = sizeBytes,
        enabled = enabled,
        status = row.enum("status", status),
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

internal fun AiApp.toEntity(): AiAppEntity = AiAppEntity(
    id = id.value,
    packageName = packageName,
    label = label,
    vendor = vendor,
    versionName = versionName,
    source = source.name,
    capabilitiesJson = encodeColumn(ColumnSerializers.appCapabilitySet, capabilities),
    status = status.name,
    enabled = enabled,
    accessibilityAuthorized = accessibilityAuthorized,
    linkedProviderId = linkedProviderId?.value,
    promptDeepLink = promptDeepLink,
    notes = notes,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
)

internal fun AiAppEntity.toDomain(): AiApp {
    val row = RowRef(AiAppEntity.TABLE, id)
    return AiApp(
        id = AiAppId(id),
        packageName = packageName,
        label = label,
        vendor = vendor,
        versionName = versionName,
        source = row.enum("source", source),
        capabilities = row.json("capabilities_json", ColumnSerializers.appCapabilitySet, capabilitiesJson),
        status = row.enum("status", status),
        enabled = enabled,
        accessibilityAuthorized = accessibilityAuthorized,
        linkedProviderId = linkedProviderId?.let(::ProviderId),
        promptDeepLink = promptDeepLink,
        notes = notes,
        firstSeenAt = firstSeenAt,
        lastSeenAt = lastSeenAt,
    )
}
