package com.aicontrolcenter.app.di.wiring

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.di.AgentFacingWalk
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.di.createProductionPorts
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.device.net.AndroidNetworkStatus
import com.aicontrolcenter.framework.llm.DefaultProviderRechecks
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
import kotlin.test.assertIs

/**
 * WP4 item 10 in production: the app follows Android's own network, so providers are re-checked when it comes back,
 * and the production re-checks (with Android's network, Room's providers and the real connections) reach no
 * owner-only port, as the production graph's walk will require once they are a root.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NetworkWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val data = DataLayer(context, databaseName = "network-wiring-test.db", databaseEncryption = DatabaseEncryption.None)

    @After
    fun stop() {
        scope.cancel()
        runBlocking { data.close() }
    }

    @Test
    fun `the app's network status is Android's default network`() {
        assertIs<AndroidNetworkStatus>(ModelsWiring.network(context))
    }

    @Test
    fun `the production re-checks reach no owner-only port`() {
        val graph = AppGraph(createProductionPorts(context, data), scope)
        assertIs<DefaultProviderRechecks>(graph.providerRechecks)
        val productionViews = listOf(Class.forName("com.aicontrolcenter.data.DataLayer\$ReadOnlyModelPrices"))

        val result = AgentFacingWalk(extraViews = productionViews).walk(graph.providerRechecks, "re-checks")

        assertEquals(emptyList(), result.violations)
        assertEquals(emptyList(), result.unreadable)
    }
}
