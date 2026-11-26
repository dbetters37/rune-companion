package com.aicontrolcenter.app.ui.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.ui.activity.ActivityTab
import com.aicontrolcenter.app.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave 3, part 3a (WP8): every new route opens its screen in the real navigation graph, in the section the placement
 * table gives it (settings-like screens in Settings, lists of the owner's things in Tools, the Team view in Tasks), and
 * the Activity route carries its tab and filters. The screens themselves are the 3b packages'.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class Wave3RoutesTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val app = TestApp(scope)
    private lateinit var nav: NavHostController

    @After
    fun stop() = scope.cancel()

    private fun show() {
        compose.setContent {
            AppTheme(darkTheme = true) {
                CompositionLocalProvider(LocalAppGraph provides app.graph) {
                    nav = rememberNavController()
                    AppNavHost(nav)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Opens [route] and returns its section; fails unless [title] is shown. */
    private fun open(route: Any, title: String): Section {
        compose.runOnUiThread { nav.navigate(route) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty(), "$route shows \"$title\"")
        return compose.runOnIdle { nav.currentDestination!!.section() }
    }

    @Test
    fun `every new screen opens in its section`() {
        show()
        assertEquals(Section.TASKS, open(TeamRoute(), "Team view"))
        assertEquals(Section.TASKS, open(TeamRoute(objectiveId = "tsk_1"), "Team view"))
        assertEquals(Section.TOOLS, open(QuestionRoute("req_1"), "A question from an agent"))
        assertEquals(Section.SETTINGS, open(DailyLimitsRoute, "Daily limits"))
        assertEquals(Section.SETTINGS, open(BackupSaveRoute, "Save a backup"))
        assertEquals(Section.SETTINGS, open(BackupRestoreRoute, "Restore a backup"))
        assertEquals(Section.TOOLS, open(RoutinesRoute, "Routines"))
        assertEquals(Section.TOOLS, open(RoutineEditorRoute(), "New routine"))
        assertEquals(Section.TOOLS, open(RoutineEditorRoute("rtn_1"), "Routine"))
        assertEquals(Section.TOOLS, open(WaitingRoutinesRoute, "Routines waiting to start"))
        assertEquals(Section.TOOLS, open(PickUpRulesRoute, "Folder pick-up"))
        assertEquals(Section.TOOLS, open(PickUpRuleEditorRoute(folderId = "fld_1"), "New pick-up rule"))
        assertEquals(Section.TOOLS, open(PickUpRuleEditorRoute(ruleId = "pkr_1"), "Pick-up rule"))
    }

    @Test
    fun `the Activity section is a route with a tab and filters, and stays a section root`() {
        show()
        assertEquals(Section.ACTIVITY, open(ActivityRoute(ActivityTab.USAGE, agentId = "agt_1", text = "Grok"), "Activity"))
        assertTrue(compose.runOnIdle { nav.currentDestination!!.isTopLevel() })
        assertEquals(ActivityRoute(), Section.ACTIVITY.route)
        assertEquals(listOf("Log", "Usage and cost"), ActivityTab.entries.map { it.title })
    }
}
