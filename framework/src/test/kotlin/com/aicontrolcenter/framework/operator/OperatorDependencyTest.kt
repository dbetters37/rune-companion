package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.GrantStore
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequestDecisions
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.operator.SharedFolders
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.framework.llm.DefaultModelGateway
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.llm.ProviderLease
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.framework.tools.DefaultToolAuthorizer
import com.aicontrolcenter.framework.tools.ToolExecutor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Nothing reachable from the tool executor, the permission broker, the agent runner, the
 * orchestrator or any tool may hold an owner-only port: only the owner's screens can answer a
 * request, grant, or change folders, apps or Stop. Checked on declared field types, following
 * the framework's own classes (and their nested classes) field by field. Since review 5 (R2) the
 * stores of folders and authorised apps and the folder gateway's owner side are forbidden too:
 * agent-facing classes declare the read-only lookups ([FolderDirectory], [AuthorisedApps]) and
 * the gateway without taking or releasing access. Since Phase 6 (rule 3) only the owner changes
 * the settings: agent-facing classes declare the read-only [SettingsView], never the
 * [SettingsRepository] that can write them.
 */
class OperatorDependencyTest {
    private val ownerOnly: List<Class<*>> = listOf(
        OwnerRequests::class.java, OwnerGrants::class.java, OwnerFolders::class.java, OwnerOperatorApps::class.java, ScreenControl::class.java,
        OwnerRequestDecisions::class.java, GrantStore::class.java, OwnerRequestRepository::class.java, PermissionRepository::class.java,
        // Owner-capable mutators: adding and removing folders and apps, taking and releasing a folder's access.
        SharedFolderRepository::class.java, OperatorAppRepository::class.java, SharedFolders::class.java,
        // Phase 6 (rule 3): the settings store, which can change them.
        SettingsRepository::class.java,
        // Security review of wave 1, F3: the owner's provider manager (it adds, repoints and removes providers and replaces
        // their keys), the key vault, and the owner's check of an answer against the stored keys. F4: deleting usage.
        com.aicontrolcenter.core.llm.ProviderManager::class.java, com.aicontrolcenter.core.security.SecretVault::class.java,
        com.aicontrolcenter.core.llm.OwnerKeyCheck::class.java, com.aicontrolcenter.core.usage.UsageHousekeeping::class.java,
    ) + PHASE6_OWNER_ONLY + REGISTRY_STORES

    /**
     * The owner-only types an agent-facing class may declare, where it must: [ProviderConnections] keeps the vault to
     * put a provider's key on that provider's own requests, and the provider store to read that key's record and to
     * store a status a call learned; it offers no way to read a key or change a record (checked below).
     */
    private val sealedFields: Map<Class<*>, Set<Class<*>>> = mapOf(
        ProviderConnections::class.java to setOf(com.aicontrolcenter.core.security.SecretVault::class.java, ProviderRepository::class.java),
    )

    /**
     * Types only some classes may declare (F3, second review): a provider lease knows whether a text is the provider's
     * key, so only the connections that make it and the gateway that maps a failure with it may hold one.
     */
    private val confined: Map<Class<*>, List<String>> = mapOf(
        ProviderLease::class.java to listOf(ProviderConnections::class.java.name, DefaultModelGateway::class.java.name),
    )

    /** Fields typed `Any` hide what they hold from this walk; these, by name, are plain locks and the script sandbox's marker object. */
    private val lockNames = Regex("(?i).*lock|notPlain")

    private class Walk(val types: Map<Class<*>, String>, val problems: List<String>)

    /**
     * Every type held by a field reachable from [root] through the framework's classes, with the path that reaches it.
     * A field's generic type is read too (F3, second review): `Lazy<X>`, `() -> X`, `List<X>`, `StateFlow<X>` and
     * `AtomicReference<X>` reach X. A field typed `Any` (other than a lock) and a [confined] type outside its holders
     * are [Walk.problems].
     */
    private fun walk(root: Class<*>): Walk {
        val seen = LinkedHashMap<Class<*>, String>()
        val problems = ArrayList<String>()
        val queue = ArrayDeque(listOf(root to root.simpleName))
        while (queue.isNotEmpty()) {
            val (type, path) = queue.removeFirst()
            if (type in seen) continue
            seen[type] = path
            if (!type.name.startsWith(FRAMEWORK)) continue
            var current: Class<*>? = type
            while (current != null && current.name.startsWith(FRAMEWORK)) {
                val holder: Class<*> = current
                val sealed = sealedFields[holder].orEmpty()
                for (field in holder.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type !in sealed }) {
                    val fieldPath = "$path.${field.name}"
                    if (field.type == Any::class.java && !lockNames.matches(field.name)) problems += "$fieldPath: typed Any"
                    for (held in listOf(field.type) + typeArguments(field.genericType)) {
                        confined[held]?.let { holders ->
                            if (holders.none { holder.name == it || holder.name.startsWith("$it$") }) {
                                problems += "$fieldPath: ${held.simpleName} outside ${holders.map { it.substringAfterLast('.') }}"
                            }
                        }
                        queue += held to fieldPath
                    }
                }
                holder.declaredClasses.forEach { queue += it to "$path/${it.simpleName}" }
                current = holder.superclass
            }
        }
        return Walk(seen, problems)
    }

    private fun reachable(root: Class<*>): Map<Class<*>, String> = walk(root).types

    /** The classes named in [type]'s type arguments, all the way down (a wildcard's bounds included). */
    private fun typeArguments(type: java.lang.reflect.Type): List<Class<*>> = when (type) {
        is java.lang.reflect.ParameterizedType -> type.actualTypeArguments.flatMap { argument ->
            when (argument) {
                is Class<*> -> listOf(argument)
                else -> listOfNotNull(rawClass(argument)) + typeArguments(argument)
            }
        }
        is java.lang.reflect.WildcardType -> (type.upperBounds + type.lowerBounds).flatMap { listOfNotNull(rawClass(it)) + typeArguments(it) }
        is java.lang.reflect.GenericArrayType -> listOfNotNull(rawClass(type.genericComponentType)) + typeArguments(type.genericComponentType)
        else -> emptyList()
    }

    private fun rawClass(type: java.lang.reflect.Type): Class<*>? = when (type) {
        is Class<*> -> type
        is java.lang.reflect.ParameterizedType -> type.rawType as? Class<*>
        is java.lang.reflect.WildcardType -> type.upperBounds.firstOrNull()?.let(::rawClass)
        else -> null
    }

    /**
     * Actions that are the owner's alone, by method name: a type that offers one is owner-only
     * whatever it is called. Round 2 (B2): letting an agent ask again after a refusal. Phase 6:
     * starting over, clearing the usage history, and `update` of the settings ([changesSettings]).
     */
    private val ownerOnlyActions = setOf("letAskAgain", "startOver", "clearHistory")

    /**
     * Phase 6 (rule 3): an `update` that changes the owner's settings, told by its signature, which names
     * [FrameworkSettings] (the transform, and the result). An `update` of a task or a memory is not the owner's alone.
     */
    private fun changesSettings(method: Method): Boolean =
        method.name.substringBefore('-') == "update" && method.genericParameterTypes.any { FrameworkSettings::class.java.name in it.typeName }

    /**
     * Security review of wave 1, F10: `answer` of a request (OwnerRequests.answer) is the owner's alone. It takes a
     * `RequestId`, a value class, so its compiled name is mangled ("answer-…") and its first parameter is the id's string.
     */
    private fun answersARequest(method: Method): Boolean = method.name.startsWith("answer-") && method.parameterTypes.firstOrNull() == String::class.java

    /** A method taking an id (a value class) is compiled as "name-hash": compared by the name in front. */
    private fun offersOwnerAction(type: Class<*>): Boolean =
        type.methods.any { it.name.substringBefore('-') in ownerOnlyActions || changesSettings(it) || answersARequest(it) }

    private fun violations(root: Class<*>): List<String> = walk(root).let { walk ->
        walk.types.filter { (type, _) -> ownerOnly.any { it.isAssignableFrom(type) } || offersOwnerAction(type) }.map { (type, path) -> "$path: ${type.simpleName}" } +
            walk.problems
    }

    /**
     * What agents run through: the executor, the broker and what it reads, the requester, the hooks, the runner, the
     * orchestrator and every tool; since Phase 6 also the model gateway, the chat, the registry directory and the tool
     * authoriser (rule 3).
     */
    private fun agentFacingRoots(): List<Class<*>> = (
        listOf(
            ToolExecutor::class.java, PermissionBroker::class.java, OperatorFacts::class.java, DefaultCapabilityRequester::class.java,
            PermissionRequestTool::class.java, OperatorTaskHooks::class.java, AgentRunner::class.java, DefaultOrchestrator::class.java,
            DefaultModelGateway::class.java, DefaultAgentChat::class.java, DefaultRegistryDirectory::class.java, DefaultToolAuthorizer::class.java,
            // Phase 6 (WP5): the agent's side of a question to the owner, its tool, and what it shares with the broker.
            OwnerQuestions::class.java, com.aicontrolcenter.framework.orchestration.AskOwnerTool::class.java, OwnerAttentionGate::class.java,
            OwnerRequestWait::class.java, AnsweredQuestions::class.java, OwnerWaitLedger::class.java,
        ) + TestWorld().toolRegistry.all().map { it.javaClass }
        ).distinct()

    @Test
    fun `letting an agent ask again after a refusal is offered by the owner's port and the owner's side of the store only`() {
        // Round 2 (B2). Where it is declared: the owner's port, and the store's owner side behind it.
        assertTrue(offersOwnerAction(OwnerRequests::class.java) && offersOwnerAction(OwnerRequestDecisions::class.java))
        assertTrue(offersOwnerAction(DefaultOwnerRequests::class.java))
        // What agent-facing code is handed instead cannot do it: the request queue, the grant lookup, the requester, the waiters.
        val agentSide = listOf(
            com.aicontrolcenter.core.operator.OwnerRequestQueue::class.java, com.aicontrolcenter.core.operator.GrantLookup::class.java,
            com.aicontrolcenter.core.operator.CapabilityRequester::class.java, RequestWaiters::class.java, OperatorFacts::class.java,
            // Phase 6 (WP5): the question's agent side never answers (its wait is `ask`, never `answer`).
            OwnerQuestions::class.java, OwnerAttentionGate::class.java,
        )
        for (type in agentSide) assertTrue(!offersOwnerAction(type), "${type.simpleName} must not offer an owner-only action")
        // Nothing reachable from the executor, the broker, the requester, the runner, the orchestrator or any tool offers it.
        for (root in agentFacingRoots()) {
            val offering = reachable(root).filter { (type, _) -> offersOwnerAction(type) }.map { (type, path) -> "$path: ${type.simpleName}" }
            assertEquals(emptyList(), offering, "${root.simpleName} reaches an owner-only action")
        }
        // The walk does find it where it is: behind the owner's own service.
        assertTrue(reachable(DefaultOwnerRequests::class.java).keys.any { offersOwnerAction(it) })
    }

    @Test
    fun `the executor, the broker, the runner, the orchestrator and every tool reach no owner-only port`() {
        val roots = agentFacingRoots()
        for (root in roots) assertEquals(emptyList(), violations(root), "${root.simpleName} reaches an owner-only port")
        // The walk does find what it looks for: the owner's own services hold the full stores.
        assertTrue(violations(DefaultOwnerRequests::class.java).isNotEmpty())
        assertTrue(violations(DefaultOwnerGrants::class.java).isNotEmpty())
        assertTrue(violations(DefaultOwnerFolders::class.java).any { it.endsWith(": SharedFolders") }, "the folder gateway's owner side is looked for")
        assertTrue(violations(DefaultOwnerOperatorApps::class.java).any { it.endsWith(": OperatorAppRepository") }, "and the stores of apps and folders")
        assertTrue(reachable(PermissionBroker::class.java).keys.any { it == com.aicontrolcenter.core.operator.OwnerRequestQueue::class.java })
    }

    /** Test-only holders, in the framework's package so the walk follows them as it would a framework class. */
    private class HoldsProviderManager(val providers: com.aicontrolcenter.core.llm.ProviderManager)

    // Second review of F3: owner-only objects hidden in containers, behind `Any`, or a lease outside the gateway.
    private class HoldsLazy(val providers: Lazy<com.aicontrolcenter.core.llm.ProviderManager>)

    private class HoldsFunction(val providers: () -> com.aicontrolcenter.core.llm.ProviderManager)

    private class HoldsList(val vaults: List<com.aicontrolcenter.core.security.SecretVault>)

    private class HoldsStateFlow(val check: kotlinx.coroutines.flow.StateFlow<com.aicontrolcenter.core.llm.OwnerKeyCheck>)

    private class HoldsAtomic(val providers: java.util.concurrent.atomic.AtomicReference<com.aicontrolcenter.core.llm.ProviderManager>)

    private class HoldsWildcard(val stores: Map<String, List<out ProviderRepository>>)

    private class HoldsAny(val anything: Any)

    private class HoldsLock(val lock: Any, private val stateLock: Any)

    private class HoldsLease(val lease: ProviderLease)

    private class HoldsProviderStore(val providers: ProviderRepository)

    private class HoldsAgentStore(val agents: com.aicontrolcenter.core.registry.AgentRepository)

    private class HoldsModelStore(val models: com.aicontrolcenter.core.registry.ModelRepository)

    private class HoldsAppStore(val apps: com.aicontrolcenter.core.registry.AiAppRepository)

    private class HoldsDirectories(
        val agents: com.aicontrolcenter.core.registry.AgentStatsLog,
        val providers: com.aicontrolcenter.core.registry.ProviderDirectory,
        val models: com.aicontrolcenter.core.registry.ModelDirectory,
        val apps: com.aicontrolcenter.core.registry.AiAppDirectory,
    )

    @Test
    fun `owner-only objects inside containers, behind Any, or a lease outside the gateway are caught`() {
        // Second review of F3: one test per way of hiding.
        for (holder in listOf(HoldsLazy::class.java, HoldsFunction::class.java, HoldsList::class.java, HoldsStateFlow::class.java, HoldsAtomic::class.java,
            HoldsWildcard::class.java, HoldsAny::class.java, HoldsLease::class.java)) {
            assertTrue(violations(holder).isNotEmpty(), "${holder.simpleName} is caught")
        }
        assertEquals(emptyList(), violations(HoldsLock::class.java), "a lock is not a hiding place")
        assertTrue(violations(HoldsAny::class.java).single().endsWith("typed Any"))
        assertTrue(violations(HoldsLease::class.java).single().contains("ProviderLease outside"))
    }

    @Test
    fun `agent-facing code reads agents, providers, models and apps through directories, never the stores that change them`() {
        // Second review of F3: a provider record could otherwise borrow the owner's key alias, be repointed or be deleted,
        // and an agent's tools, spaces or model could be changed.
        for (holder in listOf(HoldsProviderStore::class.java, HoldsAgentStore::class.java, HoldsModelStore::class.java, HoldsAppStore::class.java)) {
            assertTrue(violations(holder).isNotEmpty(), "${holder.simpleName} is caught")
        }
        assertEquals(emptyList(), violations(HoldsDirectories::class.java))
        for (root in listOf(DefaultModelGateway::class.java, DefaultAgentChat::class.java, DefaultRegistryDirectory::class.java, AgentRunner::class.java,
            OperatorFacts::class.java, DefaultOrchestrator::class.java)) {
            val types = reachable(root).keys
            assertTrue(REGISTRY_STORES.none { store -> types.any { store.isAssignableFrom(it) } }, "${root.simpleName} reaches no registry store")
        }
        // The one holder of the provider store is the connections, which offer no method that changes a record but the status.
        val writes = ProviderConnections::class.java.methods.filter { !it.isSynthetic && it.declaringClass == ProviderConnections::class.java }
            .map { it.name.substringBefore('-') }.filter { it.startsWith("upsert") || it.startsWith("delete") || it.startsWith("put") || it.startsWith("add") || it.startsWith("remove") }
        assertEquals(emptyList(), writes)
        // A lease's key oracle is internal to the framework's module: no public method asks it about a text.
        assertEquals(emptyList(), ProviderLease::class.java.methods.filter { '$' !in it.name && it.parameterTypes.contains(String::class.java) }.map { it.name })
    }

    private class HoldsVault(val vault: com.aicontrolcenter.core.security.SecretVault)

    private class HoldsKeyCheck(val check: com.aicontrolcenter.core.llm.OwnerKeyCheck)

    private class HoldsUsagePruning(val usage: com.aicontrolcenter.core.usage.UsageHousekeeping)

    private class HoldsRechecks(val rechecks: com.aicontrolcenter.core.llm.ProviderRechecks, val ledger: com.aicontrolcenter.core.usage.UsageLedger)

    @Test
    fun `agent-facing code cannot reach the provider manager, the vault, the owner's key check or usage pruning`() {
        // Security review of wave 1, F3, F4. The roots reach none of them (the main walk above), and a holder of any is caught.
        for (holder in listOf(HoldsProviderManager::class.java, HoldsVault::class.java, HoldsKeyCheck::class.java, HoldsUsagePruning::class.java)) {
            assertTrue(violations(holder).isNotEmpty(), "${holder.simpleName} is caught")
        }
        // What agent-facing work gets instead is allowed: the re-checks and the ledger.
        assertEquals(emptyList(), violations(HoldsRechecks::class.java))
        // The owner's provider manager holds the vault and is itself owner-only.
        assertTrue(violations(com.aicontrolcenter.framework.llm.DefaultProviderManager::class.java).isNotEmpty())
        // The gateway reaches the vault only inside the provider connections, which offer no way to it or to a key.
        assertTrue(ProviderConnections::class.java.declaredFields.any { it.type == com.aicontrolcenter.core.security.SecretVault::class.java })
        assertEquals(
            emptyList(),
            ProviderConnections::class.java.methods.filter { !it.isSynthetic }.filter { com.aicontrolcenter.core.security.SecretVault::class.java.isAssignableFrom(it.returnType) || it.name.contains("key", ignoreCase = true) }.map { it.name },
        )
        assertTrue(DefaultModelGateway::class.java in agentFacingRoots() && DefaultAgentChat::class.java in agentFacingRoots())
    }

    @Test
    fun `answering an agent's question is the owner's alone`() {
        assertTrue(offersOwnerAction(OwnerRequests::class.java))
        assertTrue(offersOwnerAction(DefaultOwnerRequests::class.java))
        for (agentSide in listOf(com.aicontrolcenter.core.operator.OwnerRequestQueue::class.java, com.aicontrolcenter.core.chat.AgentChat::class.java)) {
            assertTrue(!offersOwnerAction(agentSide), agentSide.simpleName)
        }
    }

    @Test
    fun `every owner-only port the core declares is on the list`() {
        // Phase 6 (WP1a): a new Owner… port in :core that nobody added here would be reachable unchecked.
        val agentSide = setOf("OwnerRequestQueue", "OwnerResult", "OwnerDecision")
        val ownerPorts = coreClassNames().map { Class.forName(it, false, javaClass.classLoader) }
            .filter { it.isInterface && it.simpleName.startsWith("Owner") && it.simpleName !in agentSide }
        assertTrue(ownerPorts.size >= 13, "the scan finds the core's owner ports: ${ownerPorts.map { it.simpleName }}")
        for (port in ownerPorts) assertTrue(port in ownerOnly, "${port.name} is an owner-only port missing from the list")
        for (store in listOf(com.aicontrolcenter.core.routines.RoutineRepository::class.java, com.aicontrolcenter.core.pickup.PickUpRuleRepository::class.java)) {
            assertTrue(store in ownerOnly, "${store.simpleName} can create work that starts while nobody watches")
        }
    }

    @Test
    fun `only the owner changes the settings - agent-facing code holds the read-only view`() {
        // Rule 3: the writer and its update are owner-only; the view and its read-only wrapper are not.
        assertTrue(SettingsRepository::class.java in ownerOnly)
        assertTrue(offersOwnerAction(SettingsRepository::class.java), "update of the settings is an owner-only action")
        assertTrue(!offersOwnerAction(SettingsView::class.java) && !offersOwnerAction(ReadOnlySettings::class.java), "the read-only view offers no update")
        assertTrue(ownerOnly.none { it.isAssignableFrom(ReadOnlySettings::class.java) }, "ReadOnlySettings is not a writer")
        // An update of a task is not the owner's alone: the orchestrator keeps changing tasks.
        assertTrue(!offersOwnerAction(TaskRepository::class.java))
        // Every agent-facing root that reads the settings declares the view, and the walk sees it.
        for (root in listOf(AgentRunner::class.java, OperatorFacts::class.java, DefaultOrchestrator::class.java, DefaultModelGateway::class.java,
            DefaultAgentChat::class.java, DefaultRegistryDirectory::class.java, DefaultToolAuthorizer::class.java)) {
            assertTrue(SettingsView::class.java in reachable(root).keys, "${root.simpleName} reads the settings through SettingsView")
        }
        // The walk does find the writer where it is: the owner's agent manager and the memory seeder keep it, and no agent-facing root reaches them.
        assertTrue(violations(com.aicontrolcenter.framework.agents.DefaultAgentManager::class.java).any { it.endsWith(": SettingsRepository") })
        assertTrue(violations(com.aicontrolcenter.framework.memory.MemorySeeder::class.java).any { it.endsWith(": SettingsRepository") })
    }

    /** Every top-level and nested class of `:core`, read from its classes folder or jar. */
    private fun coreClassNames(): List<String> {
        val location = java.io.File(OwnerRequests::class.java.protectionDomain.codeSource.location.toURI())
        val paths = if (location.isDirectory) {
            location.walkTopDown().filter { it.isFile }.map { it.relativeTo(location).invariantSeparatorsPath }.toList()
        } else {
            java.util.zip.ZipFile(location).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        }
        return paths.filter { it.startsWith("com/aicontrolcenter/core/") && it.endsWith(".class") && "module-info" !in it }
            .map { it.removeSuffix(".class").replace('/', '.') }
    }

    private companion object {
        const val FRAMEWORK = "com.aicontrolcenter.framework"

        /**
         * Phase 6 (rule 3, WP1a): the owner's notes to the team, prices, usage history, backup,
         * recovery, routines and folder pick-up rules, and the two stores that can create or change
         * routines and pick-up rules (work or intake that starts while nobody watches).
         */
        /** Security review of wave 1, F3, second review: the stores that add, change and remove agents, providers, models and AI apps. */
        val REGISTRY_STORES: List<Class<*>> = listOf(
            ProviderRepository::class.java, com.aicontrolcenter.core.registry.ModelRepository::class.java,
            com.aicontrolcenter.core.registry.AiAppRepository::class.java, com.aicontrolcenter.core.registry.AgentRepository::class.java,
        )

        val PHASE6_OWNER_ONLY: List<Class<*>> = listOf(
            com.aicontrolcenter.core.tasks.OwnerConversation::class.java, com.aicontrolcenter.core.usage.OwnerModelPrices::class.java,
            com.aicontrolcenter.core.usage.OwnerUsage::class.java, com.aicontrolcenter.core.backup.OwnerBackup::class.java,
            com.aicontrolcenter.core.health.OwnerRecovery::class.java, com.aicontrolcenter.core.routines.OwnerRoutines::class.java,
            com.aicontrolcenter.core.routines.RoutineRepository::class.java, com.aicontrolcenter.core.pickup.OwnerPickUpRules::class.java,
            com.aicontrolcenter.core.pickup.PickUpRuleRepository::class.java,
        )
    }
}
