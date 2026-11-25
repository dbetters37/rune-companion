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
import com.aicontrolcenter.core.llm.OwnerKeyCheck
import com.aicontrolcenter.core.operator.AnswerCheck
import com.aicontrolcenter.core.operator.AnswerRisk
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.FolderAccess
import com.aicontrolcenter.core.operator.FolderTrees
import com.aicontrolcenter.core.operator.GrantQuery
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
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.pickup.PickUpSafety
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 *
 * An answer is only taken for a request someone still waits for and whose target is still there:
 *  - a PENDING request no live call waits for ([RequestWaiters]: its waiter stopped with an
 *    earlier run of the app, or went away without closing it) is closed as CANCELLED instead,
 *    by [closeIfAbandoned] (which the Approval screen calls when it opens) or by any answer, and
 *    is left out of [pending];
 *  - a yes for a folder that was removed, an app no longer authorised (or protected), or the
 *    screen after the owner pressed Stop closes the request as CANCELLED instead, with the same
 *    checks as [DefaultOwnerGrants.grant], so an answer never gives back access just withdrawn.
 * Both are reported as [DecideResult.AlreadyClosed] with the closed request.
 *
 * A yes for the task or always also settles the other pending permission requests it covers
 * ([CoveredRequests]): their waiting calls go on.
 *
 * After a refused permission the owner can change their mind without waiting for the cool-down
 * ([letAskAgain]): the refusal then holds nothing back, and the agent's next call or ask for
 * that permission is put to the owner as usual. It gives nothing by itself.
 */
class DefaultOwnerRequests(
    private val repository: OwnerRequestRepository,
    private val waiters: RequestWaiters,
    /** The folders, authorised apps, protected apps and Stop, read now; read-only. */
    private val facts: OperatorFacts,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    /**
     * Whether an answer holds one of the provider keys in the vault (rule 32). Owner-only: only this
     * port holds it (security review of wave 1, F3), never the broker or the question tool.
     */
    private val keyCheck: OwnerKeyCheck = OwnerKeyCheck.None,
) : OwnerRequests {
    private val withdrawals = RequestWithdrawals(repository, activity, clock)
    private val covered = CoveredRequests(repository, waiters, withdrawals, ids, clock)

    /** Re-read when the stored list or the live waiters change; a request that passes its expiry without changing is closed by its waiter at that moment. */
    override val pending: Flow<List<OwnerRequest>> =
        combine(repository.observePending(), waiters.waiting) { all, live -> clock.now().let { now -> all.filter { !it.isExpiredAt(now) && it.id in live } } }

    override fun observeRecent(limit: Int): Flow<List<OwnerRequest>> = repository.observeRecent(limit)

    override fun observeForConversation(conversationId: ConversationId): Flow<List<OwnerRequest>> = repository.observeForConversation(conversationId)

    override suspend fun get(id: RequestId): OwnerRequest? = repository.get(id)

    override suspend fun closeIfAbandoned(id: RequestId): OwnerRequest? {
        val request = repository.get(id) ?: return null
        if (!request.status.isPending || waiters.isWaiting(id)) return request
        return closeInstead(request, NOT_WAITING_NOTE)
    }

    override suspend fun grant(id: RequestId, scope: GrantScope, note: String?): DecideResult = decide(id, OwnerDecision.Grant(scope, clean(note)))

    override suspend fun approve(id: RequestId, note: String?): DecideResult = decide(id, OwnerDecision.Approve(clean(note)))

    override suspend fun deny(id: RequestId, note: String?): DecideResult = decide(id, OwnerDecision.Deny(clean(note)))

    override suspend fun letAskAgain(id: RequestId): OwnerResult<OwnerRequest> {
        val current = repository.get(id) ?: return OwnerResult.Refused("This request no longer exists.")
        if (current.kind != OwnerRequestKind.PERMISSION || current.status != OwnerRequestStatus.DENIED) {
            return OwnerResult.Refused("Only a permission you refused keeps an agent from asking again.")
        }
        // Whether any refusal of that agent, permission and target still holds: only then does this change anything.
        val held = repository.lastRefusal(current.agentId, current.capability, current.constraint, since = Long.MIN_VALUE) != null
        val opened = repository.letAskAgain(id, clock.now()) ?: return OwnerResult.Refused("This request no longer exists.")
        if (held) {
            val permission = facts.capabilityInfo(opened.capability)?.title ?: opened.capability.id
            record(
                opened, "Owner let the agent ask again: $permission",
                "${opened.agentName ?: "The agent"} may ask for \"$permission\"" + (opened.targetLabel?.let { " ($it)" } ?: "") +
                    " again at once. Nothing was allowed: its next call or request for it is put to the owner.",
                Severity.INFO,
            ) {
                put("status", opened.status.name)
                opened.askAgainAt?.let { put("askAgainAt", it) }
            }
        }
        return OwnerResult.Done(opened)
    }

    /**
     * The owner answers an agent's question (Phase 6, C1, Tier B; rules 2, 32, 33): [text] in their
     * own words, or null for "decide yourself". Refused with a sentence for the owner when the
     * question is gone, answered, expired or closed, or nothing waits for it any more (it is then
     * closed, as any answer to such a request closes it); when the text is blank or too long; always
     * when it looks like a key ([AnswerCheck], [AnswerRisk.KEY]) or holds a provider key stored in
     * the vault ([OwnerKeyCheck]); and when it looks like a code ([AnswerRisk.CODE]) unless
     * [sendAnyway] (the owner's second tap). It goes through the request store's compare-and-set,
     * writes no grant, approves nothing and leaves every other request as it is; it is logged
     * without its text.
     */
    override suspend fun answer(id: RequestId, text: String?, sendAnyway: Boolean): OwnerResult<Unit> {
        val current = repository.get(id) ?: return OwnerResult.Refused(QUESTION_GONE)
        if (current.kind != OwnerRequestKind.QUESTION) return OwnerResult.Refused("Only an agent's question is answered with text.")
        if (!current.status.isPending) return OwnerResult.Refused(closedText(current))
        if (!waiters.isWaiting(id)) {
            closeInstead(current, NOT_WAITING_NOTE)
            return OwnerResult.Refused(NOT_WAITING_NOTE)
        }
        val words = text?.trim()
        if (words != null) {
            current.problemWith(OwnerDecision.Answer(words))?.let { return OwnerResult.Refused(it) }
            when (AnswerCheck.classify(current.question.orEmpty(), words)) {
                AnswerRisk.KEY -> return OwnerResult.Refused(KEY_REFUSED)
                AnswerRisk.CODE -> if (!sendAnyway) return OwnerResult.Refused(CODE_ASK_AGAIN)
                AnswerRisk.NONE -> Unit
            }
            // Constant time, against every key in the vault; checked even after "Send it anyway".
            if (keyCheck.matchesStoredKey(words)) return OwnerResult.Refused(KEY_REFUSED)
        }
        return when (val result = repository.decide(id, OwnerDecision.Answer(words), clock.now(), ids.grantId())) {
            is DecideResult.Decided -> {
                record(result.request, if (words == null) "You let ${result.request.agentName ?: "the agent"} decide itself" else "You answered a question", result.request.summary, Severity.INFO) {
                    put("status", result.request.status.name)
                    put("answered", words != null)
                }
                OwnerResult.Done(Unit)
            }
            is DecideResult.Expired -> {
                record(result.request, "Answered too late: ${whatItAsked(result.request)}", result.request.summary, Severity.WARNING) {
                    put("status", result.request.status.name)
                }
                OwnerResult.Refused(closedText(result.request))
            }
            is DecideResult.AlreadyClosed -> OwnerResult.Refused(closedText(result.request))
            is DecideResult.NotAccepted -> OwnerResult.Refused(result.problem)
            DecideResult.NotFound -> OwnerResult.Refused(QUESTION_GONE)
        }
    }

    /** Why a question that is no longer pending cannot be answered, for the owner. */
    private fun closedText(request: OwnerRequest): String = when (request.status) {
        OwnerRequestStatus.APPROVED -> "This question was already answered."
        OwnerRequestStatus.EXPIRED -> "The time to answer this question ran out, so ${request.agentName ?: "the agent"} decided itself."
        OwnerRequestStatus.CANCELLED -> "This question was withdrawn: ${request.decisionNote ?: "the agent stopped waiting"}"
        OwnerRequestStatus.DENIED, OwnerRequestStatus.PENDING -> "This question can no longer be answered."
    }

    private suspend fun decide(id: RequestId, decision: OwnerDecision, at: Long = clock.now()): DecideResult {
        val current = repository.get(id) ?: return DecideResult.NotFound
        if (current.status.isPending && current.problemWith(decision) == null && !current.isExpiredAt(at)) {
            val instead = when {
                !waiters.isWaiting(id) -> NOT_WAITING_NOTE
                decision is OwnerDecision.Deny -> null
                else -> facts.targetRefusal(current.constraint)
            }
            if (instead != null) return DecideResult.AlreadyClosed(closeInstead(current, instead))
        }
        val result = repository.decide(id, decision, at, ids.grantId())
        when (result) {
            is DecideResult.Decided -> {
                val request = result.request
                val what = when (decision) {
                    is OwnerDecision.Grant -> "Owner gave permission ${scopeWords(decision.scope)}"
                    is OwnerDecision.Approve -> "Owner approved"
                    is OwnerDecision.Deny -> if (request.kind == OwnerRequestKind.APPROVAL) "Owner did not approve" else "Owner refused permission"
                    // Phase 6 (WP1a): questions are answered through OwnerRequests.answer (WP5); logged without the text.
                    is OwnerDecision.Answer -> "Owner answered a question"
                }
                record(request, "$what: ${request.toolTitle ?: request.capability.id}", request.summary, Severity.INFO) {
                    put("status", request.status.name)
                    request.decisionScope?.let { put("scope", it.name) }
                    result.grant?.let { put("grantId", it.id.value) }
                }
                result.grant?.let { covered.settle(it, answered = request) }
            }
            is DecideResult.Expired -> record(result.request, "Answered too late: ${result.request.toolTitle ?: result.request.capability.id}", result.request.summary, Severity.WARNING) {
                put("status", result.request.status.name)
            }
            is DecideResult.AlreadyClosed, is DecideResult.NotAccepted, DecideResult.NotFound -> Unit
        }
        return result
    }

    /** Closes [request] as CANCELLED with [why] instead of answering it, and returns it as it now is. */
    private suspend fun closeInstead(request: OwnerRequest, why: String): OwnerRequest {
        val closed = repository.close(request.id, OwnerRequestStatus.CANCELLED, clock.now(), why)
        closed?.let { withdrawals.log(it, "Request closed, not answered", why) }
        return closed ?: repository.get(request.id) ?: request
    }

    private suspend fun record(request: OwnerRequest, title: String, detail: String, severity: Severity, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        logSafely {
            activity.record(
                activityTypeOf(request),
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

    /** Limits and texts. */
    companion object {
        const val MAX_NOTE = 500

        /** Why a request nothing waits for any more was closed instead of answered. */
        const val NOT_WAITING_NOTE = "No longer waiting: the app stopped, or the call that asked was stopped, before you answered. Nothing was done."

        /** Rule 32: an answer that looks like a key or a password, or holds a stored provider key, is never sent. */
        const val KEY_REFUSED =
            "Not sent: this looks like a key or a password. Agents never need your keys or passwords. Write your answer without it."

        /** Rule 32: an answer that looks like a code is sent only after the owner taps "Send it anyway" a second time. */
        const val CODE_ASK_AGAIN =
            "This looks like a code, such as one from a text message, an app or your bank. A code can let someone act as you. " +
                "If you are sure the agent should have it, tap \"Send it anyway\"."

        const val QUESTION_GONE = "This question no longer exists."
    }
}

/**
 * Settles the pending permission requests a new TASK or ALWAYS grant covers ([PermissionGrant.covers]:
 * same agent, permission and target, and the same task for TASK), so the owner is not asked the
 * same question twice and no covered call waits or expires for nothing:
 *  - a request whose call is waiting is answered "for this one call" ([GrantScope.ONCE], its own
 *    grant, used up by that call alone), with a note naming the answer that covered it, and the
 *    call goes on; later calls are covered by the lasting grant;
 *  - an agent's own ask, or a request nothing waits for, is closed as CANCELLED with that note:
 *    the lasting grant already answers it.
 * ONCE grants settle nothing. Every request settled is logged with its id.
 */
internal class CoveredRequests(
    private val repository: OwnerRequestRepository,
    private val waiters: RequestWaiters,
    private val withdrawals: RequestWithdrawals,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Settles what [grant] covers; [answered] is the request whose answer created it, null for a direct grant. Returns those settled. */
    suspend fun settle(grant: PermissionGrant, answered: OwnerRequest?): List<OwnerRequest> {
        if (grant.scope == GrantScope.ONCE) return emptyList()
        val now = clock.now()
        val how = if (grant.scope == GrantScope.TASK) "for this task" else "always"
        val note = "Already answered: you allowed this $how" + (if (answered != null) " in your answer to the same question." else " in Tools.")
        val settled = ArrayList<OwnerRequest>()
        for (other in orNull { repository.observePending().first() }.orEmpty()) {
            if (other.id == answered?.id || other.kind != OwnerRequestKind.PERMISSION || other.isExpiredAt(now)) continue
            if (!grant.covers(GrantQuery(other.agentId, other.capability, other.constraint, other.taskId, now))) continue
            val done = if (other.toolId != null && waiters.isWaiting(other.id)) {
                (orNull { repository.decide(other.id, OwnerDecision.Grant(GrantScope.ONCE, note), now, ids.grantId()) } as? DecideResult.Decided)?.request
            } else {
                orNull { repository.close(other.id, OwnerRequestStatus.CANCELLED, now, note) }
            } ?: continue
            settled += done
            withdrawals.log(done, "Settled by your answer", note, Severity.INFO)
        }
        return settled
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
    /** The requests a direct grant settles and a revocation withdraws. */
    private val requests: OwnerRequestRepository,
    private val waiters: RequestWaiters,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
) : OwnerGrants {
    /** Makes "look for an identical grant, else insert" one step for this process. */
    private val lock = Mutex()
    private val withdrawals = RequestWithdrawals(requests, activity, clock)
    private val covered = CoveredRequests(requests, waiters, withdrawals, ids, clock)

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
        // Calls already waiting for this very permission go on.
        covered.settle(grant, answered = null)
        OwnerResult.Done(grant)
    }

    override suspend fun revoke(id: GrantId): Boolean {
        val revoked = grants.revoke(id, clock.now()) ?: return false
        val title = capabilities.info(revoked.capability)?.title ?: revoked.capability.id
        logSafely {
            activity.record(
                ActivityType.PERMISSION, "Owner revoked a permission: $title",
                (agents.get(revoked.agentId)?.name ?: "A deleted agent") + targetOf(revoked.constraint),
                agentId = revoked.agentId, data = grantData(revoked),
            )
        }
        // An action waiting for approval that relied on it would be refused after the approval: withdraw the question now.
        val before = revoked.copy(revokedAt = null)
        withdrawals.withdraw("Request withdrawn, permission revoked", "You removed the permission \"$title\" this relied on, so it was not done.") {
            it.kind == OwnerRequestKind.APPROVAL && before.covers(queryOf(it)) && !coveredNow(it)
        }
        return true
    }

    override suspend fun revokeAll(agentId: AgentId): Int {
        var count = grants.revokeAllFor(agentId, clock.now())
        val name = agents.get(agentId)?.name ?: "a deleted agent"
        // Its open questions go too: what it asks for, and what waits on a permission it no longer has.
        withdrawals.withdraw("Request withdrawn, permissions revoked", "You removed every permission of $name, so this was not done.") {
            it.agentId == agentId && (it.kind == OwnerRequestKind.PERMISSION || (capabilities.info(it.capability)?.grantedByDefault != true && !coveredNow(it)))
        }
        // Once more, now that its questions are closed: an answer being written meanwhile stored its permission after the first pass.
        count += grants.revokeAllFor(agentId, clock.now())
        if (count > 0) {
            logSafely {
                activity.record(
                    ActivityType.PERMISSION, "Owner revoked every permission of $name", "$count permission(s) revoked.",
                    agentId = agentId, data = buildJsonObject { put("count", count) },
                )
            }
        }
        return count
    }

    private fun queryOf(request: OwnerRequest) = GrantQuery(request.agentId, request.capability, request.constraint, request.taskId, clock.now())

    /** Whether a grant in force still covers what [request] asks. */
    private suspend fun coveredNow(request: OwnerRequest): Boolean {
        val query = queryOf(request)
        return grants.observeForAgent(request.agentId).first().any { it.covers(query) }
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
 * then Android's access is released, then the questions still open about it are withdrawn
 * (and a grant a crossing answer stored meanwhile is revoked).
 *
 * Folder pick-up (security review of wave 1, F1): switching read-only on stores the time
 * ([SharedFolder.readOnlySince]); switching it off clears it and, in the same locked step, switches
 * off every pick-up rule of that folder and of any folder that is the same place, inside it or
 * around it ([FolderTrees.overlap]), or may hold the same files through another app
 * ([FolderTrees.mayShareFiles]), with the reason as its last result ([PickUpSafety.runProblemBeside]).
 * Sharing a new folder (writable at first) switches those rules off the same way.
 */
class DefaultOwnerFolders(
    private val gateway: SharedFolders,
    private val folders: SharedFolderRepository,
    private val grants: GrantStore,
    /** The requests about a folder, withdrawn when it is removed. */
    private val requests: OwnerRequestQueue,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    /** Folder pick-up's rules, switched off when their folder may be changed by agents; null where there are none. */
    private val pickUpRules: PickUpRuleRepository? = null,
) : OwnerFolders {
    private val lock = Mutex()
    private val withdrawals = RequestWithdrawals(requests, activity, clock)

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
        // A new folder is writable: a pick-up rule on the same place, or around or inside it, stops (F1).
        switchOffPickUpAround(folder)
        OwnerResult.Done(folder)
    }

    override suspend fun remove(id: FolderId): Boolean = lock.withLock {
        val folder = folders.get(id) ?: return false
        val constraint = CapabilityConstraint.Folder(id)
        var revoked = grants.revokeAllWithConstraint(constraint, clock.now())
        folders.delete(id)
        try {
            gateway.releaseAccess(folder)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            // The folder is gone from the app either way; Android drops an unused grant on its own.
        }
        withdrawals.withdraw("Request withdrawn, folder removed", "You removed the folder \"${folder.displayName}\", so this was not done.") {
            it.constraint == constraint
        }
        // Once more, now that its questions are closed: an answer being written while the folder was removed
        // stored its permission after the first pass, and would otherwise stay listed for a folder that is gone.
        revoked += grants.revokeAllWithConstraint(constraint, clock.now())
        log("Folder removed: ${folder.displayName}", folder, "$revoked permission(s) for it revoked.")
        true
    }

    override suspend fun setReadOnly(id: FolderId, readOnly: Boolean): SharedFolder? = lock.withLock {
        val current = folders.get(id) ?: return null
        if (current.readOnly == readOnly) return current
        val updated = current.copy(readOnly = readOnly, readOnlySince = if (readOnly) clock.now() else null)
        folders.upsert(updated)
        log(if (readOnly) "Folder made read-only: ${updated.displayName}" else "Folder made writable: ${updated.displayName}", updated)
        // Agents may change it again: folder pick-up from it, or from a folder that overlaps it, stops at once (F1).
        if (!readOnly) switchOffPickUpAround(updated)
        updated
    }

    /**
     * Switches off every pick-up rule whose folder is [writable], overlaps it (or may: unknown counts) or may hold the same
     * files through another app ([PickUpSafety.runProblemBeside]), saying why.
     */
    private suspend fun switchOffPickUpAround(writable: SharedFolder) {
        val rules = pickUpRules ?: return
        val all = folders.getAll().associateBy { it.id }
        val now = clock.now()
        for (rule in rules.getAll()) {
            if (!rule.enabled) continue
            val folder = all[rule.folderId] ?: continue
            val reason = PickUpSafety.runProblemBeside(folder, writable) ?: continue
            rules.update(rule.id) { it.copy(enabled = false, updatedAt = now, lastResult = reason) }
            log("Folder pick-up switched off: ${folder.displayName}", folder, reason)
        }
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
 * signing certificate is authorising a different app: earlier grants for it are revoked and the
 * questions still open about it are withdrawn. Withdrawing an app revokes its grants and
 * withdraws its questions; whatever a crossing answer still stored is revoked with them, and
 * again when the package is next authorised, so access to a withdrawn app never comes back by
 * itself.
 */
class DefaultOwnerOperatorApps(
    private val apps: OperatorAppRepository,
    private val grants: GrantStore,
    private val protectedPackages: () -> ProtectedPackages,
    /** The requests about an app, withdrawn when it is withdrawn. */
    private val requests: OwnerRequestQueue,
    private val activity: ActivityRecorder,
    private val clock: Clock,
) : OwnerOperatorApps {
    private val lock = Mutex()
    private val withdrawals = RequestWithdrawals(requests, activity, clock)

    override fun observeAll(): Flow<List<OperatorApp>> = apps.observeAll()

    override suspend fun authorise(packageName: String, label: String, signingCertSha256: String?): OwnerResult<OperatorApp> = lock.withLock {
        if (!CapabilityConstraint.isValidPackageName(packageName)) return OwnerResult.Refused("\"${packageName.take(80)}\" is not a package name.")
        if (protectedPackages().isProtected(packageName)) return OwnerResult.Refused("$packageName is never operated by agents.")
        val now = clock.now()
        val existing = apps.get(packageName)
        // The same app keeps when it was authorised and how often it asks; a new or different one asks before every action.
        val same = existing?.takeIf { it.signingCertSha256 == signingCertSha256 }
        var revoked = 0
        if (same == null) {
            // A different app under the same name: what was granted for the old one does not carry over. A newly
            // authorised app starts with nothing either: a permission still stored for a package that was not
            // authorised (an answer that crossed its withdrawal) is revoked here, so it can never come into force.
            revoked = grants.revokeAllWithConstraint(CapabilityConstraint.App(packageName), now)
        }
        val app = OperatorApp(packageName, label, same?.authorisedAt ?: now, signingCertSha256, same?.policy ?: ScreenActionPolicy.ASK_EVERY_ACTION)
        apps.upsert(app)
        log(
            "App authorised for screen control: ${app.label}", app,
            when {
                revoked == 0 -> ""
                existing != null -> "Its signing certificate changed: $revoked earlier permission(s) for it revoked."
                else -> "$revoked earlier permission(s) for it revoked."
            },
        )
        if (existing != null && same == null) {
            // Questions asked about the app this one replaced are not answered for this one.
            withdrawals.withdraw("Request withdrawn, a different app was authorised", "${app.label} is now a different app (its signing certificate changed), so this was not done.") {
                it.constraint == CapabilityConstraint.App(packageName)
            }
        }
        OwnerResult.Done(app)
    }

    override suspend fun setPolicy(packageName: String, policy: ScreenActionPolicy): OperatorApp? = lock.withLock {
        val app = apps.get(packageName) ?: return null
        val changed = app.copy(policy = policy)
        apps.upsert(changed)
        log(
            when (policy) {
                ScreenActionPolicy.ASK_EVERY_ACTION -> "Every screen action asks first: ${app.label}"
                ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY -> "Only buying, sending, deleting and similar ask first: ${app.label}"
            },
            changed, "",
        )
        changed
    }

    override suspend fun withdraw(packageName: String): Boolean = lock.withLock {
        val app = apps.get(packageName) ?: return false
        val constraint = CapabilityConstraint.App(packageName)
        var revoked = grants.revokeAllWithConstraint(constraint, clock.now())
        apps.delete(packageName)
        withdrawals.withdraw("Request withdrawn, app withdrawn", "You withdrew ${app.label} from screen control, so this was not done.") {
            it.constraint == constraint
        }
        // Once more, now that its questions are closed: an answer that was being written while the app was
        // withdrawn stored its permission after the first pass. No later answer can: there is nothing left to answer.
        revoked += grants.revokeAllWithConstraint(constraint, clock.now())
        log("App withdrawn from screen control: ${app.label}", app, "$revoked permission(s) for it revoked.")
        true
    }

    private suspend fun log(title: String, app: OperatorApp, detail: String) = logSafely {
        activity.record(
            ActivityType.USER_ACTION, title, detail,
            data = buildJsonObject {
                put("packageName", app.packageName)
                put("policy", app.policy.name)
            },
        )
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
