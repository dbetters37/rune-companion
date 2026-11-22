package com.aicontrolcenter.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.registry.AiAppStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The real activity and application class, with the composition root swapped for in-memory ports. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val testApp = TestApp(scope)

    /** Runs before the activity is created, which is the only moment a graph may be installed. */
    @get:Rule(order = 0)
    val installedGraph = object : ExternalResource() {
        override fun before() {
            runBlocking {
                testApp.installSampleApps()
                testApp.graph.aiAppRegistry.discover()
                // Uninstalled while the app was not running.
                testApp.scanner.aiApps = listOf(TestApp.chatbot, TestApp.lookalike)
            }
            ApplicationProvider.getApplicationContext<AiControlCenterApp>().installGraph(testApp.graph)
        }

        override fun after() = scope.cancel()
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `launching uses the installed graph, seeds the starter team and re-checks registered apps`() {
        compose.waitForIdle()
        assertSame(testApp.graph, (compose.activity.application as AiControlCenterApp).graph)

        compose.onNodeWithText("0 of 6 agents ready to run").assertExists()
        assertEquals(6, runBlocking { testApp.agents.getAll() }.size)
        assertEquals(AiAppStatus.NOT_INSTALLED, runBlocking { testApp.appNamed("Sample Assistant") }.status)
    }
}
