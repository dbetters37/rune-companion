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
        ) + graph.toolRegistry.all().map { it to "tool ${it.spec.id.value}" }
        val walk = AgentFacingWalk(extraViews = productionViews, leafPackages = listOf("com.aicontrolcenter.data.db."))
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
        // The walk would see the production stores where they must not be.
        for (store in listOf<Any>(data.permissions, data.ownerRequests, data.sharedFolders, data.operatorApps, data.ownerUsage, data.ownerModelPrices,
            data.usageHousekeeping, data.settings, data.secretVault, graph.providerManager)) {
            assertTrue(walk.walk(object { val held: Any = store }, "holder").violations.isNotEmpty(), store.javaClass.name)
        }
    }
}
