package com.aicontrolcenter.app.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dependency rule of `OperatorDependencyTest`, checked on the graph as production builds it (security review of
 * wave 1, F10): `createProductionPorts` over a data layer without encryption (Robolectric has no SQLCipher), the real
 * device layer and the real adapters, walked from every agent-facing root. `TestApp` passes narrow views of its
 * in-memory stores; production passes Room's and the device's own classes, so a wiring mistake that hands an owner's
 * store to agent-facing code in `ProductionPorts` or an assembly fails here even when the test app's wiring is right.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProductionGraphDependencyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val data = DataLayer(context, databaseName = "production-graph-test.db", databaseEncryption = DatabaseEncryption.None)

    @After
    fun stop() {
        scope.cancel()
        runBlocking { data.close() }
    }

    /**
     * Narrow views that only production has, each exposing the agent-side interface alone over an owner-capable
     * object: the read-only price view over Room's price store, the screen operator's Stop signal over the persisted
     * Stop switch (it can stop, never resume), and the screen operator's lookup of authorised apps (never authorising
     * or withdrawing one).
     */
    private val productionViews: List<Class<*>> = listOf(
        Class.forName("com.aicontrolcenter.data.DataLayer\$ReadOnlyModelPrices"),
        Class.forName("com.aicontrolcenter.device.screen.StopSignal"),
        Class.forName("com.aicontrolcenter.device.screen.AuthorisedAppView"),
    )

    @Test
    fun `nothing reachable from the production graph's agent-facing roots reaches an owner-only port`() {
        val graph = AppGraph(createProductionPorts(context, data), scope)
        val roots = listOf(
            graph.toolExecutor to "executor", graph.permissionBroker to "broker", graph.agentChat to "chat",
            graph.modelGateway to "model gateway", graph.registryDirectory to "registry directory",
            // Wave 2a: the provider re-checks as production builds them (WP4) and the question's agent side (WP5).
            graph.providerRechecks to "provider re-checks", graph.operatorAssembly.ownerQuestions to "questions to the owner (agent side)",
            // Wave 3 (WP8): the script host a running script calls into (WP6), made here as a run makes it (it is a binder,
            // made per run; ScriptsWalkTest walks it too). The engine itself is reached through the scripts.run_js tool.
            scriptHost() to "script host",
        ) + graph.toolRegistry.all().map { it to "tool ${it.spec.id.value}" }
        // Room's database and DAOs are walked by their holder (second review of F4/F10): a Room store may hold them, nothing else.
        val walk = AgentFacingWalk(extraViews = productionViews)
        var objects = 0
        val violations = ArrayList<String>()
        val unreadable = ArrayList<String>()
        for ((root, name) in roots) {
            val result = walk.walk(root, name)
            violations += result.violations
            unreadable += result.unreadable
            objects = maxOf(objects, result.objects)
        }
        assertEquals(emptyList(), violations.distinct(), "an agent-facing root reaches an owner-only port")
        assertEquals(emptyList(), unreadable.distinct(), "fields the walk could not read prove nothing")
        assertTrue(objects > 100, "the walk goes deep ($objects objects)")
        // The walk would see the production stores where they must not be: the owner's stores, the registry's stores
        // (second review of F3), and Room's database, its open helper and a DAO outside a Room store (F4/F10).
        val database = databaseOf(data)
        for (store in listOf<Any>(data.permissions, data.ownerRequests, data.sharedFolders, data.operatorApps, data.ownerUsage, data.ownerModelPrices,
            data.usageHousekeeping, data.settings, data.secretVault, graph.providerManager, data.agents, data.providers, data.models, data.aiApps,
            database, database.openHelper, database.openHelper.writableDatabase, daoOf(database, "ownerRequestDao"))) {
            assertTrue(walk.walk(object { val held: Any = store }, "holder").violations.isNotEmpty(), store.javaClass.name)
        }
        // A Room store holds them: that is the storage engine, not a port someone wired.
        assertEquals(emptyList(), walk.walk(data.memories, "memory store").violations)
    }

    /**
     * Wave 3 (WP8; rule 1, WP2's hand-off): the upkeep job is a root of the architecture tests too. What it reaches, as
     * the application builds it, holds no graph, orchestrator, gateway, executor, chat, provider manager, key vault,
     * grant or request store, and builds no graph (`UpkeepJobTest` checks the same from the job's side).
     */
    @Test
    fun `the upkeep job reaches nothing of the graph, no model, tool or provider, and no owner's store but its own`() {
        val app = context as com.aicontrolcenter.app.AiControlCenterApp
        val upkeep = (app as com.aicontrolcenter.app.work.UpkeepHost).upkeep
        val forbidden = listOf(
            AppGraph::class.java, com.aicontrolcenter.core.tasks.Orchestrator::class.java, com.aicontrolcenter.core.llm.ModelGateway::class.java,
            com.aicontrolcenter.framework.tools.ToolExecutor::class.java, com.aicontrolcenter.core.chat.AgentChat::class.java,
            com.aicontrolcenter.core.llm.ProviderManager::class.java, com.aicontrolcenter.core.security.SecretVault::class.java,
            com.aicontrolcenter.core.operator.PermissionRepository::class.java, com.aicontrolcenter.core.operator.OwnerRequestRepository::class.java,
            com.aicontrolcenter.core.llm.OwnerKeyCheck::class.java, com.aicontrolcenter.core.settings.SettingsRepository::class.java,
            com.aicontrolcenter.app.AiControlCenterApp::class.java, DataLayer::class.java,
        )
        val result = AgentFacingWalk(ownerOnly = forbidden, checkActions = false).walk(upkeep, "upkeep job")
        assertEquals(emptyList(), result.violations, "the upkeep job reaches the graph or an owner's store")
        assertTrue(result.objects > 10, "the walk saw the upkeep's ports (${result.objects} objects)")
        kotlin.test.assertNull(app.graphIfBuilt(), "walking the upkeep built no graph")
    }

    /** A run's host, as `IsolatedJsEngine` makes one for each script it runs. */
    private fun scriptHost(): Any {
        val workspace = object : com.aicontrolcenter.framework.operator.JsHost {
            override val canWrite = true

            override fun read(path: String): String = ""

            override fun list(path: String): List<Triple<String, Boolean, Long?>> = emptyList()

            override fun write(path: String, text: String): String = path
        }
        return com.aicontrolcenter.app.scripts.RunHost(
            workspace, com.aicontrolcenter.framework.operator.JsLimits(), com.aicontrolcenter.app.scripts.ScriptPipes.Android,
            java.util.concurrent.Executors.newSingleThreadExecutor(), deadlineMs = { 0L },
        )
    }

    /** The data layer's database, which is internal to `:data`: read the way a wiring slip could hand it on. */
    private fun databaseOf(data: DataLayer): androidx.room.RoomDatabase {
        val field = DataLayer::class.java.getDeclaredField("lazyDatabase").apply { isAccessible = true }
        return (field.get(data) as Lazy<*>).value as androidx.room.RoomDatabase
    }

    private fun daoOf(database: androidx.room.RoomDatabase, name: String): Any =
        // Internal to `:data`: its compiled name carries the module's ("ownerRequestDao$data_debug").
        database.javaClass.methods.first { (it.name == name || it.name.startsWith("$name$")) && it.parameterCount == 0 }.invoke(database)!!
}
