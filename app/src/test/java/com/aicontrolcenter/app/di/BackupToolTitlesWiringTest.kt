package com.aicontrolcenter.app.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import com.aicontrolcenter.data.backup.RoomOwnerBackup
import com.aicontrolcenter.framework.agents.ToolMenu
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The backup of the graph as production builds it names the tools a saved chat used by the titles the owner knows
 * (wave-2b merge, WP3 hand-off §2 item 1): `AppGraph` gives the backup a title look-up once its tool registry exists.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupToolTitlesWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val data = DataLayer(context, databaseName = "backup-tool-titles.db", databaseEncryption = DatabaseEncryption.None)

    @After
    fun stop() {
        scope.cancel()
        runBlocking { data.close() }
    }

    @Test
    fun `the production backup names every registered tool by its title, and the tool menu by its fixed title`() {
        val graph = AppGraph(createProductionPorts(context, data), scope)
        val backup = assertIs<RoomOwnerBackup>(graph.ownerBackup)
        val tools = graph.toolRegistry.all()
        assertTrue(tools.size > 10, "the production registry holds the tools (${tools.size})")
        for (tool in tools) {
            assertEquals(tool.spec.title.trim(), backup.toolTitles.titleOf(tool.spec.functionName), tool.spec.functionName)
        }
        val clock = tools.single { it.spec.functionName == "clock_now" }
        assertNotEquals("clock_now", backup.toolTitles.titleOf("clock_now"), "a title, not the function name: ${clock.spec.title}")
        assertEquals(ToolMenu.TITLE, backup.toolTitles.titleOf(ToolMenu.FUNCTION_NAME))
        assertEquals("not_a_tool", backup.toolTitles.titleOf("not_a_tool"), "an unknown name stays as it is")
    }
}
