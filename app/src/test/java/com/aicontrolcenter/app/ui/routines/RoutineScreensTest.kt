package com.aicontrolcenter.app.ui.routines

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import com.aicontrolcenter.app.di.LocalAppGraph
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.typeFast
import com.aicontrolcenter.app.testing.typed
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.routines.RoutineLimits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import kotlin.test.assertEquals

/**
 * The Routines screens on screen (WP10c): rule 27's paused-clock typing into the editor's two fields and what Save stores;
 * what screen readers hear (one item per routine with its status in words, the days by their full names, the schedule
 * choices as radio buttons, the screens switch off); 48 dp targets; the "Tap to start" step when nothing waits.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class RoutineScreensTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test
    fun `the name and what the team should do hold exactly what was typed, however fast, and Save stores it`() {
        val robot = AppRobot(compose).given { graph.start().join() }.launch().also { robot = it }
            .openSectionViaMore("Tools").tap("Routines: objectives that start on a schedule").tap("New routine")
        val title = "Morning news, short & sweet!"
        val objective = "Summarise today's news about electric cars in five lines; then list three sources."
        field(TITLE_LABEL).typeFast(compose, title)
        field(OBJECTIVE_LABEL).typeFast(compose, objective, keysPerFrame = 4)
        assertEquals(title, field(TITLE_LABEL).typed())
        assertEquals(objective, field(OBJECTIVE_LABEL).typed())
        robot.tap("Save routine")
        val saved = runBlocking { robot.app.routineStore.getAll().single() }
        assertEquals(title, saved.title)
        assertEquals(objective, saved.objectiveText)
    }

    @Test
    fun `the name cannot grow past its limit, even when pasted`() {
        AppRobot(compose).given { graph.start().join() }.launch().also { robot = it }
            .openSectionViaMore("Tools").tap("Routines: objectives that start on a schedule").tap("New routine")
        field(TITLE_LABEL).typeFast(compose, "x".repeat(RoutineLimits.MAX_TITLE_CHARS + 5), keysPerFrame = 20)
        assertEquals(RoutineLimits.MAX_TITLE_CHARS, field(TITLE_LABEL).typed().length)
        compose.onNodeWithText("${RoutineLimits.MAX_TITLE_CHARS} / ${RoutineLimits.MAX_TITLE_CHARS}").assertExists()
    }

    @Test
    fun `screen readers hear the days by name, the schedule choices as radio buttons and the screens switch off`() {
        val days = mutableListOf<DayOfWeek>()
        var hours = emptyList<Int>()
        compose.setContent {
            AppTheme(darkTheme = true) {
                RoutineEditorScreen(
                    state = RoutineEditorUiState(isLoading = false, scheduleText = "Every day at 07:00"),
                    title = TextFieldState(),
                    objective = TextFieldState(),
                    actions = RoutineEditorActions(onClose = {}, onToggleDay = { days += it }, onHour = { hours = hours + it }),
                )
            }
        }
        for (day in DayOfWeek.entries) {
            compose.onNodeWithContentDescription(RoutineWords.dayName(day)).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsSelected()
        }
        compose.onNodeWithContentDescription("Wednesday").performClick()
        assertEquals(listOf(DayOfWeek.WEDNESDAY), days)
        compose.onNode(hasText("Once a day") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertIsSelected()
        compose.onNode(hasText("Every few hours") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertExists()
        compose.onNodeWithContentDescription("Increase Hour").performScrollTo().performClick()
        assertEquals(listOf(8), hours, "07 goes to 08")
        compose.onNode(isToggleable() and hasText("May use other apps' screens")).performScrollTo().assertIsOff()
        compose.onNodeWithText(SCREENS_OFF).assertExists()
    }

    @Test
    fun `each routine is one item that says its status in words, with its pause note`() {
        var opened: RoutineId? = null
        val row = RoutineRowUi(
            id = RoutineId("rtn_1"), title = "Morning news", schedule = "Every day at 07:00", status = "Paused", tone = StatusTone.ATTENTION,
            next = null, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES, failures = null, screens = "No screens",
        )
        compose.setContent { AppTheme(darkTheme = false) { RoutineCard(row, onClick = { opened = row.id }) } }
        val card = compose.onNode(hasClickAction() and hasText("Morning news") and hasText("Paused") and hasText("Every day at 07:00"))
        card.assertHasClickAction().assertHeightIsAtLeast(48.dp)
        card.assert(hasText(RoutineLimits.PAUSED_AFTER_FAILURES, substring = true))
        card.performClick()
        assertEquals(RoutineId("rtn_1"), opened)
    }

    @Test
    fun `the Tap to start step says when nothing waits, and starts nothing`() {
        val robot = AppRobot(compose).given { graph.start().join(); settings.update { it.copy(routinesEnabled = true) } }.also { robot = it }
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalAppGraph provides robot.app.graph) { WaitingRoutinesRoute(onBack = {}, onOpenObjective = {}) }
            }
        }
        compose.onNodeWithText("Nothing is waiting to start").assertExists()
        compose.onNodeWithText(NOTHING_WAITS).assertExists()
        compose.onAllNodesWithText("Start").assertCountEqualsZero()
        assertEquals(emptyList(), runBlocking { robot.app.routineStore.getAll() })
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
        assertEquals(0, fetchSemanticsNodes().size)
    }
}
