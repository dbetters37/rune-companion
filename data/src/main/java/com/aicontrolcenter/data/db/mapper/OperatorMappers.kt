package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.data.db.entity.OperatorAppEntity
import com.aicontrolcenter.data.db.entity.OwnerRequestEntity
import com.aicontrolcenter.data.db.entity.PermissionGrantEntity
import com.aicontrolcenter.data.db.entity.SharedFolderEntity

internal fun PermissionGrant.toEntity(): PermissionGrantEntity = PermissionGrantEntity(
    id = id.value,
    agentId = agentId.value,
    capability = capability.id,
    constraintKey = constraint.stored,
    scope = scope.name,
    taskId = taskId?.value,
    requestId = requestId?.value,
    grantedAt = grantedAt,
    expiresAt = expiresAt,
    revokedAt = revokedAt,
    consumedAt = consumedAt,
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun PermissionGrantEntity.toDomain(): PermissionGrant {
    val row = RowRef(PermissionGrantEntity.TABLE, id)
    val constraint = row.constraint("constraint_key", constraintKey)
    val scope = row.enum<GrantScope>("scope", scope)
    return row.consistent {
        PermissionGrant(
            id = GrantId(id),
            agentId = AgentId(agentId),
            capability = Capability(capability),
            constraint = constraint,
            scope = scope,
            taskId = taskId?.let(::TaskId),
            requestId = requestId?.let(::RequestId),
            grantedAt = grantedAt,
            expiresAt = expiresAt,
            revokedAt = revokedAt,
            consumedAt = consumedAt,
        )
    }
}

internal fun OwnerRequest.toEntity(): OwnerRequestEntity = OwnerRequestEntity(
    id = id.value,
    kind = kind.name,
    status = status.name,
    agentId = agentId.value,
    toolId = toolId?.value,
    capability = capability.id,
    constraintKey = constraint.stored,
    risk = risk.name,
    summary = summary,
    detailsJson = encodeColumn(ColumnSerializers.stringList, details),
    dataGoesTo = dataGoesTo,
    reason = reason,
    taskId = taskId?.value,
    rootId = rootId?.value,
    conversationId = conversationId?.value,
    createdAt = createdAt,
    expiresAt = expiresAt,
    decidedAt = decidedAt,
    decisionScope = decisionScope?.name,
    decisionNote = decisionNote,
    agentName = agentName,
    toolTitle = toolTitle,
    targetLabel = targetLabel,
    delegationChainJson = encodeColumn(ColumnSerializers.agentIdList, delegationChain),
    askedAgain = askedAgain,
    askAgainAt = askAgainAt,
    question = question,
    optionsJson = if (kind == OwnerRequestKind.QUESTION) encodeColumn(ColumnSerializers.stringList, options) else null,
    answer = answer,
)

/** @throws com.aicontrolcenter.data.StoredDataException naming the column, if the row cannot be read. */
internal fun OwnerRequestEntity.toDomain(): OwnerRequest {
    val row = RowRef(OwnerRequestEntity.TABLE, id)
    val kind = row.enum<OwnerRequestKind>("kind", kind)
    val status = row.enum<OwnerRequestStatus>("status", status)
    val risk = row.enum<RiskLevel>("risk", risk)
    val constraint = row.constraint("constraint_key", constraintKey)
    val details = row.json("details_json", ColumnSerializers.stringList, detailsJson)
    val delegationChain = row.json("delegation_chain_json", ColumnSerializers.agentIdList, delegationChainJson)
    val decisionScope = decisionScope?.let { row.enum<GrantScope>("decision_scope", it) }
    val options = optionsJson?.let { row.json("options_json", ColumnSerializers.stringList, it) } ?: emptyList()
    return row.consistent {
        OwnerRequest(
            id = RequestId(id),
            kind = kind,
            status = status,
            agentId = AgentId(agentId),
            toolId = toolId?.let(::ToolId),
            capability = Capability(capability),
            constraint = constraint,
            risk = risk,
            summary = summary,
            details = details,
            dataGoesTo = dataGoesTo,
            reason = reason,
            taskId = taskId?.let(::TaskId),
            rootId = rootId?.let(::TaskId),
            conversationId = conversationId?.let(::ConversationId),
            createdAt = createdAt,
            expiresAt = expiresAt,
            decidedAt = decidedAt,
            decisionScope = decisionScope,
            decisionNote = decisionNote,
            agentName = agentName,
            toolTitle = toolTitle,
            targetLabel = targetLabel,
            delegationChain = delegationChain,
            askedAgain = askedAgain,
            askAgainAt = askAgainAt,
            question = question,
            options = options,
            answer = answer,
        )
    }
}

/**
 * UTF-8 bytes of the text a request row holds, the measure for
 * `RoomOwnerRequestRepository.MAX_TEXT_BYTES`. Fixed-size columns are left out.
 */
internal fun OwnerRequestEntity.storedTextBytes(): Long =
    listOf(
        id, agentId, toolId, capability, constraintKey, summary, detailsJson, dataGoesTo, reason, taskId, rootId,
        conversationId, decisionNote, agentName, toolTitle, targetLabel, delegationChainJson, question, optionsJson, answer,
    ).sumOf { it?.utf8Length() ?: 0L }

internal fun SharedFolder.toEntity(): SharedFolderEntity = SharedFolderEntity(
    id = id.value,
    treeUri = treeUri,
    displayName = displayName,
    addedAt = addedAt,
    readOnly = readOnly,
    readOnlySince = readOnlySince,
)

internal fun SharedFolderEntity.toDomain(): SharedFolder =
    SharedFolder(id = FolderId(id), treeUri = treeUri, displayName = displayName, addedAt = addedAt, readOnly = readOnly, readOnlySince = readOnlySince)

internal fun OperatorApp.toEntity(): OperatorAppEntity = OperatorAppEntity(
    packageName = packageName,
    label = label,
    authorisedAt = authorisedAt,
    signingCertSha256 = signingCertSha256,
    actionPolicy = policy.name,
)

internal fun OperatorAppEntity.toDomain(): OperatorApp = OperatorApp(
    packageName = packageName,
    label = label,
    authorisedAt = authorisedAt,
    signingCertSha256 = signingCertSha256,
    // A name this version does not know falls back to the safe choice: ask before every action.
    policy = ScreenActionPolicy.entries.firstOrNull { it.name == actionPolicy } ?: ScreenActionPolicy.ASK_EVERY_ACTION,
)
