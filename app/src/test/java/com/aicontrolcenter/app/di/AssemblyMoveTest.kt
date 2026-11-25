package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.operator.ScreenSessions
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.DefaultAgentActivityMonitor
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.framework.llm.DefaultModelGateway
import com.aicontrolcenter.framework.llm.DefaultProviderManager
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.operator.LocalWorkspace
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.operator.PermissionBroker
import com.aicontrolcenter.framework.operator.RequestWaiters
import com.aicontrolcenter.framework.orchestration.DefaultOrchestrator
import com.aicontrolcenter.framework.orchestration.TeamTools
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.memory.MemoryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 6, WP1d: AppGraph's wiring moved into one assembly per wave-2 package (di/wiring) as pure moves. The graph's
 * public members are the assemblies' own instances, and nothing was built twice: one gateway, one broker, one
 * orchestrator, one memory service and so on in the whole graph.
 */
class AssemblyMoveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val app = TestApp(scope)
    private val graph = app.graph

    @After
    fun stop() = scope.cancel()

    @Test
    fun `the graph's public members are the instances its assemblies built`() {
        // Models (WP4).
        assertSame(graph.modelsAssembly.registryDirectory, graph.registryDirectory)
        assertSame(graph.modelsAssembly.modelGateway, graph.modelGateway)
        assertSame(graph.modelsAssembly.providerManager, graph.providerManager)
        // The phone operator (WP5).
        assertSame(graph.operatorAssembly.workspace, graph.workspace)
        assertSame(graph.operatorAssembly.screenSessions, graph.screenSessions)
        assertSame(graph.operatorAssembly.screenControl, graph.screenControl)
        assertSame(graph.operatorAssembly.requestWaiters, graph.requestWaiters)
        assertSame(graph.operatorAssembly.capabilities, graph.capabilities)
        assertSame(graph.operatorAssembly.toolRegistry, graph.toolRegistry)
        assertSame(graph.operatorAssembly.toolExecutor, graph.toolExecutor)
        assertSame(graph.operatorAssembly.permissionBroker, graph.permissionBroker)
        assertSame(graph.operatorAssembly.ownerRequests, graph.ownerRequests)
        assertSame(graph.operatorAssembly.ownerRequests, graph.ownerQuestions)
        assertSame(graph.operatorAssembly.ownerGrants, graph.ownerGrants)
        assertSame(graph.operatorAssembly.ownerFolders, graph.ownerFolders)
        assertSame(graph.operatorAssembly.ownerOperatorApps, graph.ownerOperatorApps)
        assertSame(graph.operatorAssembly.systemScreens, graph.systemScreens)
        assertSame(graph.operatorAssembly.installedApps, graph.installedApps)
        // Agents (WP15).
        assertSame(graph.agentsAssembly.activityMonitor, graph.activityMonitor)
        assertSame(graph.agentsAssembly.monitor, graph.activityMonitor)
        // The team and chat (WP7).
        assertSame(graph.teamAssembly.orchestrator, graph.orchestrator)
        assertSame(graph.teamAssembly.agentChat, graph.agentChat)
        assertSame<Any>(graph.teamAssembly.agentChat, graph.teamAssembly.chatDeletionListener)
        assertSame(graph.teamAssembly.ownerConversation, graph.ownerConversation)
        // Routines and folder pick-up (WP17): the owner's ports of the assembly.
        assertSame(graph.routinesAssembly.ownerRoutines, graph.ownerRoutines)
        assertSame(graph.routinesAssembly.ownerPickUpRules, graph.ownerPickUpRules)
        assertNotNull(graph.ownerRoutines)
        assertNotNull(graph.ownerPickUpRules)
        // Every read is the same instance (the members delegate; nothing is built on read).
        assertSame(graph.modelGateway, graph.modelGateway)
        assertSame(graph.toolExecutor, graph.toolExecutor)
        assertSame(graph.orchestrator, graph.orchestrator)
    }

    @Test
    fun `the Phase 6 members come from the ports, and the gate is the process's one`() {
        assertSame(app.ports.usageReports, graph.usageReports)
        assertSame(app.ports.modelPrices, graph.modelPrices)
        assertSame(app.prices, graph.ownerModelPrices)
        assertSame(app.usage, graph.ownerUsage)
        assertSame(app.backgroundHealth, graph.backgroundHealth)
        assertSame(app.storage, graph.storageWatch)
        assertSame(app.exits, graph.exitHistory)
        assertSame(app.recovery, graph.ownerRecovery)
        assertSame<MaintenanceGate>(app.maintenanceGate, graph.maintenanceGate)
        assertEquals(null, graph.ownerBackup, "no backup in wave 1")
        assertTrue(graph.settingsView is ReadOnlySettings)
    }

    @Test
    fun `nothing in the graph is built twice`() {
        val found = instancesReachableFrom(graph)
        val once = listOf(
            DefaultRegistryDirectory::class.java, ProviderConnections::class.java, DefaultModelGateway::class.java,
            DefaultProviderManager::class.java, DefaultMemoryService::class.java, LocalWorkspace::class.java,
            ScreenSessions::class.java, RequestWaiters::class.java, CapabilityRegistry::class.java, DefaultToolRegistry::class.java,
            PermissionBroker::class.java, ToolExecutor::class.java, OperatorTaskHooks::class.java, DefaultAgentActivityMonitor::class.java,
            AgentRunner::class.java, DefaultOrchestrator::class.java, DefaultAgentChat::class.java, ReadOnlySettings::class.java,
        )
        for (type in once) assertEquals(1, found[type] ?: 0, "${type.simpleName} instances reachable from the graph")
        // The team assembly registered its tools into the operator's one registry, before any agent runs.
        assertTrue(graph.toolRegistry.byId(TeamTools.DELEGATE_ID) != null)
        assertTrue(graph.toolRegistry.byId(TeamTools.ASK_ID) != null)
    }

    @Test
    fun `a memory service handed to the graph is the one it uses everywhere`() {
        val shared = DefaultMemoryService(
            app.memories, app.agents, ReadOnlySettings(app.settings), HashingEmbedder(), graph.activityRecorder, app.ports.idGenerator, app.clock,
            config = MemoryConfig(), computeContext = EmptyCoroutineContext,
        )
        val embedder = HashingEmbedder()
        val other = AppGraph(
            app.ports, scope, app.factories.values.toList(), backgroundContext = EmptyCoroutineContext,
            memoryService = shared, embedder = embedder,
        )
        assertSame(shared, other.memoryService)
        assertSame(embedder, other.embedder)
        val found = instancesReachableFrom(other)
        assertEquals(1, found[DefaultMemoryService::class.java], "the graph built no second memory service")
    }

    /** Counts the app's and the framework's objects reachable from [root], by class, each object once. */
    private fun instancesReachableFrom(root: Any): Map<Class<*>, Int> {
        val counts = HashMap<Class<*>, Int>()
        val seen = IdentityHashMap<Any, Unit>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val value = queue.removeFirst()
            if (seen.put(value, Unit) != null) continue
            val type = value.javaClass
            counts.merge(type, 1, Int::plus)
            when {
                value is Map<*, *> -> value.entries.forEach { (k, v) -> k?.let(queue::add); v?.let(queue::add) }
                value is Iterable<*> && !follow(type) -> value.forEach { it?.let(queue::add) }
                type.isArray && !type.componentType.isPrimitive -> (value as Array<*>).forEach { it?.let(queue::add) }
                follow(type) -> {
                    var current: Class<*>? = type
                    while (current != null && current != Any::class.java && follow(current)) {
                        for (field in current.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                            val held = runCatching { field.isAccessible = true; field.get(value) }.getOrNull() ?: continue
                            queue += held
                        }
                        current = current.superclass
                    }
                }
            }
        }
        return counts
    }

    /** The app's, the framework's, the fakes' classes and Kotlin's holders are followed; the test harness is a leaf. */
    private fun follow(type: Class<*>): Boolean = !type.name.startsWith("com.aicontrolcenter.app.testing.") && (
        type.name.startsWith("com.aicontrolcenter.") || type.name.startsWith("kotlin.jvm.internal.") ||
            type.name == "kotlin.SynchronizedLazyImpl" || type.name == "kotlin.UnsafeLazyImpl" || type.name == "kotlin.InitializedLazyImpl"
        )
}
