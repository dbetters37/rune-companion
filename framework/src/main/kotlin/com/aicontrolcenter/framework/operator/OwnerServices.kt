package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.folderId
import com.aicontrolcenter.core.grantId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.FolderAccess
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.GrantStore
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The owner-only ports, over the stores. Only the app's owner-facing screens get these; nothing
 * reachable from the tool executor, the permission broker or a tool holds one (a :framework test
 * and the :app architecture test check it). Every change is logged.
 */

/**
 * How the owner sees and answers requests ([OwnerRequests]), over [OwnerRequestRepository]:
 * answering is one atomic [OwnerRequestRepository.decide], which writes a granted request's
 * grant in the same transaction, and is logged. The owner's note is trimmed, blank dropped and
 * cut at [MAX_NOTE] characters; it reaches the agent as quoted data.
 */
class DefaultOwnerRequests(
    private val repository: OwnerRequestRepository,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
) : OwnerRequests {
    /** Re-read when the stored list changes; a request that passes its expiry without changing is closed by its waiter at that moment. */
    override val pending: Flow<List<OwnerRequest>> =
        repository.observePending().map { all -> clock.now().let { now -> all.filter { !it.isExpiredAt(now) } } }

    override fun observeRecent(limit: Int): Flow<List<OwnerRequest>> = repository.observeRecent(limit)

    override fun observeForConversation(conversationId: ConversationId): Flow<List<OwnerRequest>> = repository.observeForConversation(conversationId)

    override suspend fun get(id: RequestId): OwnerRequest? = repository.get(id)

    override suspend fun grant(id: RequestId, scope: GrantScope, note: String?): DecideResult = decide(id, OwnerDecision.Grant(scope, clean(note)))

    override suspend fun approve(id: RequestId, note: String?): DecideResult = decide(id, OwnerDecision.Approve(clean(note)))

    override suspend fun deny(id: RequestId, note: String?): DecideResult = decide(id, OwnerDecision.Deny(clean(note)))

    private suspend fun decide(id: RequestId, decision: OwnerDecision): DecideResult {
        val result = repository.decide(id, decision, clock.now(), ids.grantId())
        when (result) {
            is DecideResult.Decided -> {
                val request = result.request
                val what = when (decision) {
                    is OwnerDecision.Grant -> "Owner gave permission ${scopeWords(decision.scope)}"
                    is OwnerDecision.Approve -> "Owner approved"
                    is OwnerDecision.Deny -> if (request.kind == OwnerRequestKind.APPROVAL) "Owner did not approve" else "Owner refused permission"
                }
                record(request, "$what: ${request.toolTitle ?: request.capability.id}", request.summary, Severity.INFO) {
                    put("status", request.status.name)
                    request.decisionScope?.let { put("scope", it.name) }
                    result.grant?.let { put("grantId", it.id.value) }
                }
            }
            is DecideResult.Expired -> record(result.request, "Answered too late: ${result.request.toolTitle ?: result.request.capability.id}", result.request.summary, Severity.WARNING) {
                put("status", result.request.status.name)
            }
            is DecideResult.AlreadyClosed, is DecideResult.NotAccepted, DecideResult.NotFound -> Unit
        }
        return result
    }

    private suspend fun record(request: OwnerRequest, title: String, detail: String, severity: Severity, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        logSafely {
            activity.record(
                if (request.kind == OwnerRequestKind.APPROVAL) ActivityType.APPROVAL else ActivityType.PERMISSION,
                title, detail.take(MAX_LOGGED), severity = severity, agentId = request.agentId, taskId = request.taskId, rootTaskId = request.rootId,
                data = buildJsonObject {
                    put("requestId", request.id.value)
                    put("kind", request.kind.name)
                    put("byOwner", true)
                    extra()
                },
            )
        }

    private fun clean(note: String?): String? = note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE)

    /** Limits. */
    companion object {
        const val MAX_NOTE = 500
    }
}

/**
 * The owner's view of grants, direct grants and revocation ([OwnerGrants]), over [GrantStore].
 * A direct grant is ALWAYS and only for what can be granted: a known capability that is not
 * granted by default, a constraint of the capability's kind, an existing folder, an authorised
 * app that is not protected. An identical grant in force is returned rather than doubled.
 */
class DefaultOwnerGrants(
    private val grants: GrantStore,
    private val agents: AgentRepository,
    private val capabilities: CapabilityRegistry,
    private val folders: SharedFolderRepository,
    private val apps: OperatorAppRepository,
    private val protectedPackages: () -> ProtectedPackages,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
) : OwnerGrants {
    /** Makes "look for an identical grant, else insert" one step for this process. */
    private val lock = Mutex()

    override fun observeForAgent(agentId: AgentId): Flow<List<PermissionGrant>> = grants.observeForAgent(agentId)

    override fun observeAll(): Flow<List<PermissionGrant>> = grants.observeAll()

    override suspend fun grant(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint): OwnerResult<PermissionGrant> = lock.withLock {
        val now = clock.now()
        val agent = agents.get(agentId) ?: return OwnerResult.Refused("That agent no longer exists.")
        val info = capabilities.info(capability) ?: return OwnerResult.Refused("\"${capability.id}\" is not a permission this app knows.")
        if (info.grantedByDefault) return OwnerResult.Refused("\"${info.title}\" is already allowed for every agent.")
        if (info.constrainedBy != constraint.kind) {
            return OwnerResult.Refused("\"${info.title}\" is given for one ${info.constrainedBy.name.lowercase()} at a time.")
        }
        when (constraint) {
            is CapabilityConstraint.Folder -> if (folders.get(constraint.folderId) == null) return OwnerResult.Refused("That folder was removed.")
            is CapabilityConstraint.App -> {
                if (protectedPackages().isProtected(constraint.packageName)) return OwnerResult.Refused("${constraint.packageName} is never operated by agents.")
                if (apps.get(constraint.packageName) == null) return OwnerResult.Refused("Authorise ${constraint.packageName} for screen control first.")
            }
            CapabilityConstraint.None -> Unit
        }
        grants.observeForAgent(agentId).first()
            .firstOrNull { it.scope == GrantScope.ALWAYS && it.capability == capability && it.constraint == constraint && it.isActiveAt(now) }
            ?.let { return OwnerResult.Done(it) }
        val grant = PermissionGrant(ids.grantId(), agentId, capability, constraint, GrantScope.ALWAYS, grantedAt = now)
        grants.insert(grant)
        logSafely {
            activity.record(
                ActivityType.PERMISSION, "Owner gave permission always: ${info.title}", "${agent.name}${targetOf(constraint)}",
                agentId = agentId, data = grantData(grant),
            )
        }
        OwnerResult.Done(grant)
    }

    override suspend fun revoke(id: GrantId): Boolean {
        val revoked = grants.revoke(id, clock.now()) ?: return false
        logSafely {
            activity.record(
                ActivityType.PERMISSION, "Owner revoked a permission: ${capabilities.info(revoked.capability)?.title ?: revoked.capability.id}",
                (agents.get(revoked.agentId)?.name ?: "A deleted agent") + targetOf(revoked.constraint),
                agentId = revoked.agentId, data = grantData(revoked),
            )
        }
        return true
    }

    override suspend fun revokeAll(agentId: AgentId): Int {
        val count = grants.revokeAllFor(agentId, clock.now())
        if (count > 0) {
            logSafely {
                activity.record(
                    ActivityType.PERMISSION, "Owner revoked every permission of ${agents.get(agentId)?.name ?: "a deleted agent"}", "$count permission(s) revoked.",
                    agentId = agentId, data = buildJsonObject { put("count", count) },
                )
            }
        }
        return count
    }

    private fun targetOf(constraint: CapabilityConstraint): String = when (constraint) {
        is CapabilityConstraint.Folder -> " · folder ${constraint.folderId.value}"
        is CapabilityConstraint.App -> " · app ${constraint.packageName}"
        CapabilityConstraint.None -> ""
    }

    private fun grantData(grant: PermissionGrant): JsonObject = buildJsonObject {
        put("grantId", grant.id.value)
        put("capability", grant.capability.id)
        put("constraint", grant.constraint.stored)
        put("scope", grant.scope.name)
    }
}

/**
 * The Folders screen ([OwnerFolders]), over [SharedFolders], [SharedFolderRepository] and
 * [GrantStore]. Removing narrows first: the folder's grants are revoked, then it is deleted,
 * then Android's access is released.
 */
class DefaultOwnerFolders(
    private val gateway: SharedFolders,
    private val folders: SharedFolderRepository,
    private val grants: GrantStore,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
) : OwnerFolders {
    private val lock = Mutex()

    override fun observeAll(): Flow<List<SharedFolder>> = folders.observeAll()

    override suspend fun add(treeUri: String): OwnerResult<SharedFolder> = lock.withLock {
        val name = when (val access = gateway.takeAccess(treeUri)) {
            is FolderAccess.Refused -> return OwnerResult.Refused(access.reason)
            is FolderAccess.Granted -> access.displayName
        }
        folders.getByTreeUri(treeUri)?.let { return OwnerResult.Done(it) }
        val folder = SharedFolder(ids.folderId(), treeUri, name, clock.now())
        folders.upsert(folder)
        log("Folder shared with agents: ${folder.displayName}", folder)
        OwnerResult.Done(folder)
    }

    override suspend fun remove(id: FolderId): Boolean = lock.withLock {
        val folder = folders.get(id) ?: return false
        val revoked = grants.revokeAllWithConstraint(CapabilityConstraint.Folder(id), clock.now())
        folders.delete(id)
        try {
            gateway.releaseAccess(folder)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            // The folder is gone from the app either way; Android drops an unused grant on its own.
        }
        log("Folder removed: ${folder.displayName}", folder, "$revoked permission(s) for it revoked.")
        true
    }

    override suspend fun setReadOnly(id: FolderId, readOnly: Boolean): SharedFolder? = lock.withLock {
        val updated = folders.get(id)?.copy(readOnly = readOnly) ?: return null
        folders.upsert(updated)
        log(if (readOnly) "Folder made read-only: ${updated.displayName}" else "Folder made writable: ${updated.displayName}", updated)
        updated
    }

    private suspend fun log(title: String, folder: SharedFolder, detail: String = "") = logSafely {
        activity.record(
            ActivityType.USER_ACTION, title, detail,
            data = buildJsonObject {
                put("folderId", folder.id.value)
                put("readOnly", folder.readOnly)
            },
        )
    }
}

/**
 * The authorised apps of the Screen control screen ([OwnerOperatorApps]), over
 * [OperatorAppRepository] and [GrantStore]. Authorising the same package with a different
 * signing certificate is authorising a different app: earlier grants for it are revoked.
 */
class DefaultOwnerOperatorApps(
    private val apps: OperatorAppRepository,
    private val grants: GrantStore,
    private val protectedPackages: () -> ProtectedPackages,
    private val activity: ActivityRecorder,
    private val clock: Clock,
) : OwnerOperatorApps {
    private val lock = Mutex()

    override fun observeAll(): Flow<List<OperatorApp>> = apps.observeAll()

    override suspend fun authorise(packageName: String, label: String, signingCertSha256: String?): OwnerResult<OperatorApp> = lock.withLock {
        if (!CapabilityConstraint.isValidPackageName(packageName)) return OwnerResult.Refused("\"${packageName.take(80)}\" is not a package name.")
        if (protectedPackages().isProtected(packageName)) return OwnerResult.Refused("$packageName is never operated by agents.")
        val now = clock.now()
        val existing = apps.get(packageName)
        var revoked = 0
        if (existing != null && existing.signingCertSha256 != signingCertSha256) {
            // A different app under the same name: what was granted for the old one does not carry over.
            revoked = grants.revokeAllWithConstraint(CapabilityConstraint.App(packageName), now)
        }
        val app = OperatorApp(packageName, label, existing?.takeIf { it.signingCertSha256 == signingCertSha256 }?.authorisedAt ?: now, signingCertSha256)
        apps.upsert(app)
        log(
            "App authorised for screen control: ${app.label}", app,
            if (revoked > 0) "Its signing certificate changed: $revoked earlier permission(s) for it revoked." else "",
        )
        OwnerResult.Done(app)
    }

    override suspend fun withdraw(packageName: String): Boolean = lock.withLock {
        val app = apps.get(packageName) ?: return false
        val revoked = grants.revokeAllWithConstraint(CapabilityConstraint.App(packageName), clock.now())
        apps.delete(packageName)
        log("App withdrawn from screen control: ${app.label}", app, "$revoked permission(s) for it revoked.")
        true
    }

    private suspend fun log(title: String, app: OperatorApp, detail: String) = logSafely {
        activity.record(ActivityType.USER_ACTION, title, detail, data = buildJsonObject { put("packageName", app.packageName) })
    }
}

internal fun scopeWords(scope: GrantScope?): String = when (scope) {
    GrantScope.ONCE -> "for one call"
    GrantScope.TASK -> "for this task"
    GrantScope.ALWAYS -> "always"
    null -> ""
}

/** The log is a record, not a dependency: a failure to write it never undoes or fails the change. */
internal suspend fun logSafely(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        rethrowIfCancelled(e)
    }
}

private const val MAX_LOGGED = 600
