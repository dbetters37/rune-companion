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
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.framework.tools.DefaultToolAuthorizer
import com.aicontrolcenter.framework.tools.ToolExecutor
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    ) + PHASE6_OWNER_ONLY

    /**
     * The one owner-only type an agent-facing class may declare, where it must: [ProviderConnections] keeps the
     * vault to put a provider's key on that provider's own requests, and offers no way to read one (checked below).
     */
    private val sealedFields: Map<Class<*>, Class<*>> = mapOf(ProviderConnections::class.java to com.aicontrolcenter.core.security.SecretVault::class.java)

    /** Every type held by a field reachable from [root] through the framework's classes, with the path that reaches it. */
    private fun reachable(root: Class<*>): Map<Class<*>, String> {
        val seen = LinkedHashMap<Class<*>, String>()
        val queue = ArrayDeque(listOf(root to root.simpleName))
        while (queue.isNotEmpty()) {
            val (type, path) = queue.removeFirst()
            if (type in seen) continue
            seen[type] = path
            if (!type.name.startsWith(FRAMEWORK)) continue
            var current: Class<*>? = type
            while (current != null && current.name.startsWith(FRAMEWORK)) {
                val sealed = sealedFields[current]
                current.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type != sealed }.forEach { queue += it.type to "$path.${it.name}" }
                current.declaredClasses.forEach { queue += it to "$path/${it.simpleName}" }
                current = current.superclass
            }
        }
        return seen
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

    private fun violations(root: Class<*>): List<String> =
        reachable(root).filter { (type, _) -> ownerOnly.any { it.isAssignableFrom(type) } || offersOwnerAction(type) }.map { (type, path) -> "$path: ${type.simpleName}" }

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
        val PHASE6_OWNER_ONLY: List<Class<*>> = listOf(
            com.aicontrolcenter.core.tasks.OwnerConversation::class.java, com.aicontrolcenter.core.usage.OwnerModelPrices::class.java,
            com.aicontrolcenter.core.usage.OwnerUsage::class.java, com.aicontrolcenter.core.backup.OwnerBackup::class.java,
            com.aicontrolcenter.core.health.OwnerRecovery::class.java, com.aicontrolcenter.core.routines.OwnerRoutines::class.java,
            com.aicontrolcenter.core.routines.RoutineRepository::class.java, com.aicontrolcenter.core.pickup.OwnerPickUpRules::class.java,
            com.aicontrolcenter.core.pickup.PickUpRuleRepository::class.java,
        )
    }
}
