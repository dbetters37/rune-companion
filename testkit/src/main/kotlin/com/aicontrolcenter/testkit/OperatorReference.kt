package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.folderId
import com.aicontrolcenter.core.grantId
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.CapabilityRequester
import com.aicontrolcenter.core.operator.FolderAccess
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.requestId
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stores and switches the operator's owner ports and [CapabilityRequester] work over, all in
 * memory: what the contract suites set up, and what an implementation under test is built on.
 */
class OperatorWorld(
    val clock: FakeClock = FakeClock(1_000_000),
    val ids: SequentialIdGenerator = SequentialIdGenerator(),
) {
    val grants = InMemoryPermissionRepository()
    val requests = InMemoryOwnerRequestRepository(grants)
    val folders = InMemorySharedFolderRepository()
    val apps = InMemoryOperatorAppRepository()
    val agents = InMemoryAgentRepository()
    val settings = InMemorySettingsRepository(FrameworkSettings(operatorEnabled = true, accessibilityOperatorEnabled = true, approvalTimeoutMinutes = 30))
    val gateway = FakeSharedFolders(folders, clock)
    val screenStopped = MutableStateFlow(false)
    val protectedPackages = ProtectedPackages(OWN_PACKAGE, resolved = setOf("com.oem.securitycenter"))

    /** Every capability this app registers: the built-in ones and the operator's. */
    val capabilities: Map<Capability, CapabilityInfo> = (Capabilities.builtIn + OperatorCapabilities.all).associateBy { it.capability }

    fun capabilityInfo(capability: Capability): CapabilityInfo? = capabilities[capability]

    /** Facts for [OperatorGate.refusal] about an agent's own ask, read fresh. */
    suspend fun askFacts(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint) = OperatorGate.Facts(
        agent = agents.get(agentId), toolId = null, toolTitle = null, capability = capability, capabilityInfo = capabilityInfo(capability),
        constraint = constraint, sendsDataOffDevice = OperatorTools.all.any { it.capability == capability && it.sendsDataOffDevice },
        settings = settings.get(), screenStopped = screenStopped.value,
        folder = (constraint as? CapabilityConstraint.Folder)?.let { folders.get(it.folderId) },
        authorisedApps = apps.getAll().map { it.packageName }.toSet(), protectedPackages = protectedPackages,
    )

    /** Names. */
    companion object {
        const val OWN_PACKAGE = "com.aicontrolcenter.app"
    }
}

/** Reference [CapabilityRequester] over an [OperatorWorld], following every rule in its KDoc. */
class ReferenceCapabilityRequester(private val world: OperatorWorld) : CapabilityRequester {
    private val mutex = Mutex()

    override suspend fun ask(context: ToolContext, capability: Capability, constraint: CapabilityConstraint, reason: String): AskResult = mutex.withLock {
        val w = world
        val now = w.clock.now()
        OperatorGate.refusal(w.askFacts(context.agent.id, capability, constraint))?.let { return AskResult.Refused(it) }
        if (reason.isBlank()) return AskResult.Refused("Say in a sentence why you need it; the owner reads your reason.")
        if (reason.length > CapabilityRequester.MAX_REASON_CHARS) {
            return AskResult.Refused("The reason is longer than ${CapabilityRequester.MAX_REASON_CHARS} characters; shorten it.")
        }
        val agent = w.agents.get(context.agent.id)!!
        val info = w.capabilityInfo(capability)!!
        if (info.grantedByDefault || w.grants.findMatching(GrantQuery(agent.id, capability, constraint, context.taskId, now)).isNotEmpty()) {
            return AskResult.AlreadyGranted
        }
        val waiting = w.requests.observePending().first().filter { it.agentId == agent.id && it.toolId == null && !it.isExpiredAt(now) }
        waiting.firstOrNull { it.capability == capability && it.constraint == constraint }?.let { return AskResult.AlreadyPending(it.id) }
        if (waiting.size >= CapabilityRequester.MAX_PENDING_ASKS_PER_AGENT) {
            return AskResult.Refused("You already have ${waiting.size} questions waiting for the owner; wait for an answer first.")
        }
        val target = when (constraint) {
            is CapabilityConstraint.Folder -> w.folders.get(constraint.folderId)?.displayName
            is CapabilityConstraint.App -> w.apps.get(constraint.packageName)?.label
            CapabilityConstraint.None -> null
        }
        val request = OwnerRequest(
            id = w.ids.requestId(), kind = OwnerRequestKind.PERMISSION, agentId = agent.id, toolId = null, capability = capability,
            constraint = constraint, risk = OperatorTools.riskOf(capability),
            summary = "${agent.name} asks for \"${info.title}\"" + (target?.let { ": $it" } ?: ""), details = listOf(info.description),
            reason = reason, taskId = context.taskId, rootId = context.rootTaskId, conversationId = context.conversationId,
            createdAt = now, expiresAt = now + w.settings.get().approvalTimeoutMinutes.coerceAtLeast(1) * 60_000L,
            agentName = agent.name, targetLabel = target, delegationChain = context.delegationChain,
        )
        w.requests.create(request)
        AskResult.Raised(request.id)
    }
}

/** Reference [OwnerGrants] over an [OperatorWorld]. */
class ReferenceOwnerGrants(private val world: OperatorWorld) : OwnerGrants {
    override fun observeForAgent(agentId: AgentId): Flow<List<PermissionGrant>> = world.grants.observeForAgent(agentId)

    override fun observeAll(): Flow<List<PermissionGrant>> = world.grants.observeAll()

    override suspend fun grant(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint): OwnerResult<PermissionGrant> {
        val w = world
        val now = w.clock.now()
        if (w.agents.get(agentId) == null) return OwnerResult.Refused("That agent no longer exists.")
        val info = w.capabilityInfo(capability) ?: return OwnerResult.Refused("\"${capability.id}\" is not a permission this app knows.")
        if (info.grantedByDefault) return OwnerResult.Refused("\"${info.title}\" is already allowed for every agent.")
        if (info.constrainedBy != constraint.kind) return OwnerResult.Refused("\"${info.title}\" is given for one ${info.constrainedBy.name.lowercase()} at a time.")
        when (constraint) {
            is CapabilityConstraint.Folder -> if (w.folders.get(constraint.folderId) == null) return OwnerResult.Refused("That folder was removed.")
            is CapabilityConstraint.App -> {
                if (w.protectedPackages.isProtected(constraint.packageName)) return OwnerResult.Refused("${constraint.packageName} is never operated by agents.")
                if (w.apps.get(constraint.packageName) == null) return OwnerResult.Refused("Authorise ${constraint.packageName} for screen control first.")
            }
            CapabilityConstraint.None -> Unit
        }
        w.grants.observeForAgent(agentId).first()
            .firstOrNull { it.scope == GrantScope.ALWAYS && it.capability == capability && it.constraint == constraint && it.isActiveAt(now) }
            ?.let { return OwnerResult.Done(it) }
        val grant = PermissionGrant(w.ids.grantId(), agentId, capability, constraint, GrantScope.ALWAYS, grantedAt = now)
        w.grants.insert(grant)
        return OwnerResult.Done(grant)
    }

    override suspend fun revoke(id: GrantId): Boolean = world.grants.revoke(id, world.clock.now()) != null

    override suspend fun revokeAll(agentId: AgentId): Int = world.grants.revokeAllFor(agentId, world.clock.now())
}

/** Reference [OwnerFolders] over an [OperatorWorld]. */
class ReferenceOwnerFolders(private val world: OperatorWorld) : OwnerFolders {
    override fun observeAll(): Flow<List<SharedFolder>> = world.folders.observeAll()

    override suspend fun add(treeUri: String): OwnerResult<SharedFolder> {
        val w = world
        val name = when (val access = w.gateway.takeAccess(treeUri)) {
            is FolderAccess.Refused -> return OwnerResult.Refused(access.reason)
            is FolderAccess.Granted -> access.displayName
        }
        w.folders.getByTreeUri(treeUri)?.let { return OwnerResult.Done(it) }
        val folder = SharedFolder(w.ids.folderId(), treeUri, name, w.clock.now())
        w.folders.upsert(folder)
        return OwnerResult.Done(folder)
    }

    override suspend fun remove(id: FolderId): Boolean {
        val w = world
        val folder = w.folders.get(id) ?: return false
        w.grants.revokeAllWithConstraint(CapabilityConstraint.Folder(id), w.clock.now())
        w.folders.delete(id)
        w.gateway.releaseAccess(folder)
        return true
    }

    override suspend fun setReadOnly(id: FolderId, readOnly: Boolean): SharedFolder? {
        val current = world.folders.get(id) ?: return null
        if (current.readOnly == readOnly) return current
        return current.copy(readOnly = readOnly, readOnlySince = if (readOnly) world.clock.now() else null).also { world.folders.upsert(it) }
    }
}

/** Reference [OwnerOperatorApps] over an [OperatorWorld]. */
class ReferenceOwnerOperatorApps(private val world: OperatorWorld) : OwnerOperatorApps {
    override fun observeAll(): Flow<List<OperatorApp>> = world.apps.observeAll()

    override suspend fun authorise(packageName: String, label: String, signingCertSha256: String?): OwnerResult<OperatorApp> {
        val w = world
        if (!CapabilityConstraint.isValidPackageName(packageName)) return OwnerResult.Refused("\"${packageName.take(80)}\" is not a package name.")
        if (w.protectedPackages.isProtected(packageName)) return OwnerResult.Refused("$packageName is never operated by agents.")
        val now = w.clock.now()
        val existing = w.apps.get(packageName)
        val same = existing?.takeIf { it.signingCertSha256 == signingCertSha256 }
        if (same == null) {
            // A different app under the same name, or a newly authorised one: nothing granted earlier carries over.
            w.grants.revokeAllWithConstraint(CapabilityConstraint.App(packageName), now)
        }
        val app = OperatorApp(packageName, label, same?.authorisedAt ?: now, signingCertSha256, same?.policy ?: ScreenActionPolicy.ASK_EVERY_ACTION)
        w.apps.upsert(app)
        return OwnerResult.Done(app)
    }

    override suspend fun setPolicy(packageName: String, policy: ScreenActionPolicy): OperatorApp? {
        val app = world.apps.get(packageName) ?: return null
        return app.copy(policy = policy).also { world.apps.upsert(it) }
    }

    override suspend fun withdraw(packageName: String): Boolean {
        val w = world
        w.apps.get(packageName) ?: return false
        w.grants.revokeAllWithConstraint(CapabilityConstraint.App(packageName), w.clock.now())
        w.apps.delete(packageName)
        return true
    }
}
