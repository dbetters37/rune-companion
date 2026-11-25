package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.operator.AuthorisedAppsView
import com.aicontrolcenter.app.operator.GrantLookupView
import com.aicontrolcenter.app.operator.RequestQueueView
import com.aicontrolcenter.app.operator.ScreenSessions
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.framework.operator.OperatorToolDependencies
import com.aicontrolcenter.framework.operator.PermissionRequestTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dependency rule of the phone operator, checked on the graph as the app wires it: nothing
 * reachable from the tool executor, the permission broker or any registered tool can answer a
 * request, give a grant, add or remove a folder or an app, take or release Android's access to a
 * folder, or press Resume (review 5, R2: the stores of folders and apps, the folder gateway's
 * owner side and the device's Stop switch are forbidden too, and the screen operator behind the
 * agents' screen view is walked).
 *
 * The framework's own test walks declared field types. This one walks the objects themselves,
 * field by field from the executor, the broker and every registered tool, through the app's,
 * the framework's and the test fakes' classes and through lambdas, collections and lazies, so a
 * lambda that captures the composition root (which holds the owner's services) or a store
 * handed over whole behind a narrow type is caught too. A full store may appear only inside
 * the narrow views made for that purpose, which expose the agent-side interface alone.
 */
class OperatorDependencyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val app = TestApp(scope)

    @After
    fun stop() = scope.cancel()

    private val rules = AgentFacingWalk()
    private val ownerOnly: List<Class<*>> = rules.ownerOnly

    private fun isOwnerOnly(type: Class<*>): Boolean = rules.isOwnerOnly(type)

    private fun ownerActionsOf(type: Class<*>): List<String> = rules.ownerActionsOf(type)

    private fun walk(root: Any, name: String): AgentFacingWalk.Result = rules.walk(root, name)

    private fun fieldsOf(type: Class<*>) = rules.fieldsOf(type)

    /**
     * What agents run through: the executor, the broker, every registered tool, and (security review of wave 1, F10)
     * the chat, the model gateway and the registry directory, whose held objects nothing else reaches. WP8 adds the
     * wave-2 roots (the question port's agent side, the script host, the provider re-checks).
     */
    private fun roots(): List<Pair<Any, String>> =
        listOf(
            app.graph.toolExecutor to "executor", app.graph.permissionBroker to "broker", app.graph.agentChat to "chat",
            app.graph.modelGateway to "model gateway", app.graph.registryDirectory to "registry directory",
        ) + app.graph.toolRegistry.all().map { it to "tool ${it.spec.id.value}" }

    @Test
    fun `nothing reachable from the executor, the broker or any registered tool reaches an owner-only port`() {
        for ((root, name) in roots()) {
            val walk = walk(root, name)
            assertEquals(emptyList(), walk.violations, "$name reaches an owner-only port")
            assertEquals(emptyList(), walk.unreadable, "$name has fields the walk could not read, so it proves nothing about them")
        }
        // The walk goes deep: the executor reaches the registry, every tool and the broker's stores.
        assertTrue(walk(app.graph.toolExecutor, "executor").objects > 100)
    }

    @Test
    fun `letting an agent ask again after a refusal cannot be reached from the executor, the broker or any tool`() {
        // Round 2 (B2). The owner's screens can: the port offers it, and the store's owner side writes it.
        assertEquals(setOf("letAskAgain", "answer"), ownerActionsOf(app.graph.ownerRequests.javaClass).toSet())
        assertEquals(listOf("letAskAgain"), ownerActionsOf(app.ownerRequestStore.javaClass))
        assertTrue(walk(app.graph.ownerRequests, "ownerRequests").violations.any { it.endsWith("offers letAskAgain") })
        // What the broker, the requester and the hooks are handed cannot, by type and as wired.
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.operator.OwnerRequestQueue::class.java))
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.operator.GrantLookup::class.java))
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.operator.CapabilityRequester::class.java))
        assertEquals(emptyList(), ownerActionsOf(RequestQueueView::class.java), "the view over the full request store exposes the queue alone")
        assertEquals(emptyList(), ownerActionsOf(GrantLookupView::class.java))
        for ((root, name) in roots()) {
            val offering = walk(root, name).violations.filter { " offers " in it }
            assertEquals(emptyList(), offering, "$name reaches an action that is the owner's alone")
        }
        // A view that handed the action through would be caught, although views are not walked into.
        val leakyView = object : com.aicontrolcenter.core.operator.OwnerRequestQueue by app.ownerRequestStore {
            @Suppress("unused")
            suspend fun letAskAgain(id: com.aicontrolcenter.core.RequestId) = app.ownerRequestStore.letAskAgain(id, 0)
        }
        assertTrue(walk(object { val queue: Any = leakyView }, "holder").violations.any { it.endsWith("offers letAskAgain") })
    }

    @Test
    fun `the walk finds what it looks for`() {
        // The owner's services hold the full stores, and the composition root holds the owner's services.
        assertTrue(walk(app.graph.ownerRequests, "ownerRequests").violations.isNotEmpty())
        assertTrue(walk(app.graph.ownerGrants, "ownerGrants").violations.isNotEmpty())
        // A lambda that captures the composition root is caught, though its type says nothing.
        val graph = app.graph
        val leaky: () -> ProtectedPackages = { graph.ownerRequests.let { ProtectedPackages(null) } }
        assertTrue(walk(leaky, "leaky lambda").violations.isNotEmpty())
        // A full store behind a narrow type is caught outside the views.
        val holder = object { val queue: com.aicontrolcenter.core.operator.OwnerRequestQueue = app.ownerRequestStore }
        assertTrue(walk(holder, "holder").violations.isNotEmpty())
        // The views themselves are what the broker and the tools hold.
        assertTrue(walk(app.graph.permissionBroker, "broker").let { it.violations.isEmpty() && it.unreadable.isEmpty() })
        // R2: a folder or app store, the folder gateway's owner side or a screen that can resume, held by anything else, is caught.
        val stores = object {
            val folders: Any = app.sharedFolders
            val apps: Any = app.operatorApps
            val gateway: Any = app.folderAccess
        }
        assertEquals(3, walk(stores, "stores").violations.size, walk(stores, "stores").violations.toString())
        val resumable = object : com.aicontrolcenter.core.operator.ScreenOperator by app.screen, ScreenControl by app.screen.asControl() {
            override val stopped get() = app.screen.stopped
        }
        val screenView = ScreenSessions(resumable, AuthorisedAppsView(app.operatorApps), scope)
        assertTrue(walk(screenView, "screen view").violations.isNotEmpty(), "the walk goes behind the agents' screen view")
    }

    @Test
    fun `only the owner writes the settings - agent-facing code holds the read-only view`() {
        // Rule 3: the writer is owner-only by type and by its update; the view offers neither.
        assertTrue(isOwnerOnly(app.settings.javaClass))
        assertEquals(listOf("update"), ownerActionsOf(SettingsRepository::class.java))
        assertEquals(listOf("update"), ownerActionsOf(app.settings.javaClass))
        assertEquals(emptyList(), ownerActionsOf(SettingsView::class.java))
        assertEquals(emptyList(), ownerActionsOf(ReadOnlySettings::class.java))
        assertTrue(!isOwnerOnly(ReadOnlySettings::class.java), "the read-only view is not a writer")
        // An update of a task, a memory or an agent is not the owner's alone.
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.tasks.TaskRepository::class.java))
        assertEquals(emptyList(), ownerActionsOf(com.aicontrolcenter.core.memory.MemoryService::class.java))
        // As wired: the graph hands the view to everything agent-facing, and the walk finds a writer anywhere else.
        assertTrue(app.graph.settingsView is ReadOnlySettings)
        assertTrue(walk(object { val settings: Any = app.settings }, "holder").violations.isNotEmpty())
        assertTrue(walk(object { val read: suspend () -> FrameworkSettings = app.settings::get }, "bound reference").violations.isNotEmpty())
        assertEquals(emptyList(), walk(object { val settings: Any = app.graph.settingsView }, "view").violations)
        // The owner's own services keep the writer: the agent manager and the memory seeder write their bookkeeping.
        assertTrue(walk(app.graph.agentManager, "agentManager").violations.any { "SettingsRepository" in it || "InMemorySettingsRepository" in it })
    }

    @Test
    fun `the provider manager, the key vault, the owner's key check and usage pruning are owner-only, wherever they hide`() {
        // Security review of wave 1, F3 and F4: listed, by type.
        for (type in listOf(
            com.aicontrolcenter.core.llm.ProviderManager::class.java, com.aicontrolcenter.core.security.SecretVault::class.java,
            com.aicontrolcenter.core.llm.OwnerKeyCheck::class.java, com.aicontrolcenter.core.usage.UsageHousekeeping::class.java,
        )) assertTrue(isOwnerOnly(type), type.simpleName)
        assertTrue(isOwnerOnly(app.graph.providerManager.javaClass))
        // What agent-facing work is given instead is not: the re-checks and the ledger.
        assertTrue(!isOwnerOnly(com.aicontrolcenter.core.llm.ProviderRechecks::class.java))
        assertTrue(!isOwnerOnly(com.aicontrolcenter.core.usage.UsageLedger::class.java))
        assertTrue(!isOwnerOnly(app.graph.providerRechecks.javaClass))
        // As wired: a chat, a gateway or a tool that held any of them would fail, also behind a lambda.
        val graph = app.graph
        val leaks = mapOf(
            "provider manager" to object { val providers: Any = graph.providerManager },
            "vault" to object { val vault: Any = app.vault },
            "key check" to object { val check = object : com.aicontrolcenter.core.llm.OwnerKeyCheck { override suspend fun matchesStoredKey(text: String) = false } },
            "usage pruning" to object { val usage: Any = app.usage },
            "lambda over the provider manager" to { graph.providerManager.presets },
        )
        for ((what, holder) in leaks) assertTrue(walk(holder, what).violations.isNotEmpty(), "a holder of the $what is caught")
        // The one sealed holder of the vault: the provider connections, which put a key only on its own provider's requests.
        val connections = fieldsOf(graph.modelGateway.javaClass).first { it.type == com.aicontrolcenter.framework.llm.ProviderConnections::class.java }
            .also { it.isAccessible = true }.get(graph.modelGateway)
        assertTrue(fieldsOf(connections.javaClass).any { it.type == com.aicontrolcenter.core.security.SecretVault::class.java }, "the walk skips a real field")
        val leaky = com.aicontrolcenter.framework.llm.ProviderConnections::class.java.methods.filter { m ->
            !m.isSynthetic && (com.aicontrolcenter.core.security.SecretVault::class.java.isAssignableFrom(m.returnType) || m.name.contains("key", ignoreCase = true))
        }
        assertEquals(emptyList(), leaky.map { it.name }, "the provider connections offer no way to the vault or a key")
    }

    @Test
    fun `answering an agent's question is the owner's alone`() {
        // Security review of wave 1, F10: `answer` taking a RequestId is an owner-only action, as letAskAgain is.
        assertTrue("answer" in ownerActionsOf(OwnerRequests::class.java))
        assertTrue("answer" in ownerActionsOf(app.graph.ownerRequests.javaClass))
        for (agentSide in listOf(
            com.aicontrolcenter.core.operator.OwnerRequestQueue::class.java, com.aicontrolcenter.core.chat.AgentChat::class.java,
            RequestQueueView::class.java, com.aicontrolcenter.core.tools.Tool::class.java,
        )) assertTrue("answer" !in ownerActionsOf(agentSide), agentSide.simpleName)
        val graph = app.graph
        assertTrue(walk(object { val port: Any = graph.ownerQuestions }, "holder").violations.any { it.endsWith("offers answer") || "OwnerRequests" in it })
    }

    @Test
    fun `every operator tool is registered with the spec of its catalogue entry, and every capability is known`() {
        val registered = app.graph.toolRegistry.all().associateBy { it.spec.id }
        for (info in OperatorTools.all) {
            val spec = registered[info.id]?.spec ?: error("${info.id.value} is not registered")
            assertEquals(info.capability, spec.capability, info.id.value)
            assertEquals(info.risk, spec.risk, info.id.value)
            assertEquals(info.category, spec.category, info.id.value)
            assertEquals(info.sendsDataOffDevice, spec.sendsDataOffDevice, info.id.value)
            assertTrue(app.graph.capabilities.info(spec.capability) != null, "${spec.capability.id} is not a known permission")
        }
        assertEquals(45, OperatorTools.all.size)
        assertTrue(registered[PermissionRequestTool.ID] is PermissionRequestTool)
        assertEquals(OperatorCapabilities.all, OperatorCapabilities.all.map { app.graph.capabilities.info(it.capability) })
        // Only reading the workspace is granted without asking.
        assertEquals(listOf(OperatorCapabilities.WORKSPACE_READ), OperatorCapabilities.all.filter { it.grantedByDefault }.map { it.capability })
        // The registry holds no second copy of any tool id.
        assertEquals(registered.size, app.graph.toolRegistry.all().size)
        assertTrue(ToolId("workspace.write") in registered)
    }

    @Test
    fun `the tools get the one workspace the owner's screens use`() {
        val write = app.graph.toolRegistry.byId(ToolId("workspace.write"))!!
        val dependencies = fieldsOf(write.javaClass).first { it.type == OperatorToolDependencies::class.java }.also { it.isAccessible = true }.get(write) as OperatorToolDependencies
        assertTrue(dependencies.workspace === app.graph.workspace)
        assertTrue(dependencies.screen === app.graph.screenSessions, "screen tools get the agent-side view, not the device operator")
    }
}
