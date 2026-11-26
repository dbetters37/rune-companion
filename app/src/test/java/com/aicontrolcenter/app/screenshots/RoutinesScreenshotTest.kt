package com.aicontrolcenter.app.screenshots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.makePickUpRule
import com.aicontrolcenter.app.testing.makeRoutine
import com.aicontrolcenter.app.testing.makeSpace
import com.aicontrolcenter.app.testing.otherAppFolder
import com.aicontrolcenter.app.testing.phoneFolder
import com.aicontrolcenter.app.testing.runPickUp
import com.aicontrolcenter.app.testing.shareFolder
import com.aicontrolcenter.app.ui.routines.OBJECTIVE_LABEL
import com.aicontrolcenter.app.ui.routines.TITLE_LABEL
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.framework.routines.RoutineRunner
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.ZoneId

/**
 * Pictures of WP10c's screens (wave 3): Settings › Routines and folder pick-up, the Routines list and editor, the "Tap to
 * start" step with its question, and the Folder pick-up list and editor with a refusal. Dark, light, wide (900 dp), 360 dp
 * and font scale 2.0. Review material, recorded into `build/outputs/roborazzi/` (`ScreenshotTest`'s KDoc), not golden files.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class RoutinesScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    private fun app(dark: Boolean = true, populate: suspend TestApp.() -> Unit = {}): AppRobot =
        AppRobot(compose).given { graph.start().join(); populate() }.launch(dark).also { robot = it }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/p6_wp10c_$name.png")

    private fun shootScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/p6_wp10c_$name.png")

    // --- the worlds ---

    /** Three routines: one on, one that paused itself after three failed runs, one with screens and a failure; and a history. */
    private suspend fun TestApp.routines(masterOn: Boolean = true) {
        if (masterOn) settings.update { it.copy(routinesEnabled = true) }
        val morning = makeRoutine("Morning news", "Summarise the news about electric cars in five lines.", RoutineSchedule.Daily(7 * 60))
        val digest = makeRoutine(
            "Evening digest", "Collect what the team learned today and write a short digest.",
            RoutineSchedule.Daily(19 * 60 + 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)),
        )
        val inbox = makeRoutine(
            "Inbox check", "Read the newest mail in the mail app and list what needs an answer.",
            RoutineSchedule.EveryHours(3, 8 * 60), allowScreenActions = true, approvalTimeoutMinutes = 120,
        )
        routineStore.update(digest.id) { it.copy(enabled = false, nextRunAt = null, consecutiveFailures = 3, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES) }
        routineStore.update(inbox.id) { it.copy(consecutiveFailures = 1) }
        val day = 86_400_000L
        val start = TestApp.START_TIME
        fun run(n: Int, dueAt: Long, outcome: RoutineRunOutcome, note: String? = null, objective: String? = null) = RoutineRun(
            RoutineRunId("rrn_$n"), morning.id, dueAt, outcome, startedAt = objective?.let { dueAt + 2_000 }, objectiveId = objective?.let(::TaskId), note = note,
        )
        routineStore.appendRun(run(1, start - 4 * day - 5 * 3_600_000L, RoutineRunOutcome.MISSED, RoutineRunner.EXPIRED_NOTE))
        routineStore.appendRun(run(2, start - 3 * day - 5 * 3_600_000L, RoutineRunOutcome.SKIPPED_DAILY_LIMIT))
        routineStore.appendRun(run(3, start - 2 * day - 5 * 3_600_000L, RoutineRunOutcome.STARTED, RoutineRunner.TAPPED_NOTE, "tsk_morning_2"))
        routineStore.appendRun(run(4, start - day - 5 * 3_600_000L, RoutineRunOutcome.STARTED, objective = "tsk_morning_1"))
    }

    /** One run waiting for a tap. */
    private suspend fun TestApp.waiting() {
        settings.update { it.copy(routinesEnabled = true) }
        val routine = makeRoutine("Morning news", "Summarise the news about electric cars in five lines.")
        clock.current = routine.nextRunAt!! + 7 * 60_000L
        graph.waitingRoutines.catchUp()
    }

    /** Folders and rules: one rule working, one stopped because its folder was made writable; files picked up and left out. */
    private suspend fun TestApp.pickUp() {
        settings.update { it.copy(folderPickUpEnabled = true) }
        val inbox = shareFolder("Inbox", readOnly = true, treeUri = phoneFolder("Documents/Inbox"))
        val exports = shareFolder("Exports", readOnly = true, treeUri = phoneFolder("Download/Exports"))
        folderAccess.putFile(inbox.treeUri, "reading-list.md", "# Reading list\nOld notes.", "text/markdown")
        val notes = makeSpace("Inbox notes")
        val reading = makeSpace("Reading list")
        makePickUpRule(inbox, notes)
        makePickUpRule(exports, reading)
        folderAccess.putFile(inbox.treeUri, "notes-2026-11-14.txt", "Notes the other app exported this morning.")
        folderAccess.putFile(inbox.treeUri, "photo.jpg", ByteArray(64), "image/jpeg")
        runPickUp()
        graph.ownerFolders.setReadOnly(exports.id, false)
    }

    /** Folders for a new rule: one writable, one read-only inside a writable one, one usable, and another app's. */
    private suspend fun TestApp.foldersForRule() {
        makeSpace("Inbox notes")
        shareFolder("Exports", readOnly = false)
        shareFolder("Documents", readOnly = false, treeUri = phoneFolder("Documents"))
        shareFolder("Letters", readOnly = true, treeUri = phoneFolder("Documents/Letters"))
        shareFolder("Inbox", readOnly = true, treeUri = phoneFolder("Inbox"))
        shareFolder("Work", readOnly = true, treeUri = otherAppFolder("Work"))
    }

    /**
     * Opens a section: from the rail on a wide window, else through "More", scrolling the sheet to it first (at font scale
     * 2.0 on a 360 dp phone Settings is below the sheet's fold).
     */
    private fun AppRobot.section(title: String): AppRobot {
        if (wide) return openSection(title)
        openMore()
        val opens = SemanticsMatcher("opens $title") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open $title" }
        compose.onNode(hasText(title) and hasAnyAncestor(opens), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        return this
    }

    private var wide = false

    private fun AppRobot.routinesList() = section("Tools").tap("Routines: objectives that start on a schedule")

    private fun AppRobot.pickUpList() = section("Settings").scrollTo("Open folder pick-up").tap("Open folder pick-up")

    // --- Settings ---

    @Test
    fun settingsSection() {
        app { makeRoutine() }.section("Settings").scrollTo("Open folder pick-up")
        shoot("settings_section")
    }

    @Test
    fun settingsSectionLight() {
        app(dark = false) { settings.update { it.copy(routinesEnabled = true) }; makeRoutine() }.section("Settings").scrollTo("Open folder pick-up")
        shoot("settings_section_light")
    }

    @Test
    fun settingsConfirmRoutines() {
        app().section("Settings").tap("Routines")
        shootScreen("settings_confirm_routines")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C, fontScale = 2.0f)
    fun settingsSection360LargeFont() {
        app().section("Settings").scrollTo("Open routines")
        shoot("settings_section_360_font2")
    }

    // --- the Routines list ---

    @Test
    fun routinesList() {
        app { routines() }.routinesList()
        shoot("routines_1_list")
        robot!!.scrollListTo("Android may still hold a routine back")
        shoot("routines_2_how")
    }

    @Test
    fun routinesListLight() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        app(dark = false) { routines() }.routinesList()
        shoot("routines_list_light")
    }

    @Test
    fun routinesListMasterOff() {
        app { routines(masterOn = false) }.routinesList()
        shoot("routines_list_off")
    }

    @Test
    fun routinesListWaiting() {
        // A run waiting when the app opens (on the Dashboard, whose banner shows it): the list says so and links to it.
        app {
            routines()
            // Due a little ahead of the test app's clock, which moves on with every reading, so the run is still waiting
            // (not yet missed) when the picture is taken.
            val morning = routineStore.getAll().first()
            routineStore.appendRun(RoutineRun(RoutineRunId("rrn_wait"), morning.id, clock.current + 3_600_000L, RoutineRunOutcome.WAITING_FOR_TAP, note = RoutineRunner.WAITING_NOTE))
        }.routinesList()
        shoot("routines_list_waiting")
    }

    @Test
    fun routinesEmpty() {
        app().routinesList()
        shoot("routines_empty")
    }

    @Test
    @Config(qualifiers = WIDE_WP10C)
    fun routinesWide() {
        wide = true
        app { routines() }.routinesList()
        shoot("routines_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C)
    fun routines360() {
        app { routines() }.routinesList()
        shoot("routines_360")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C, fontScale = 2.0f)
    fun routines360LargeFont() {
        app { routines() }.routinesList()
        shoot("routines_360_font2")
        robot!!.scrollListTo("Android may still hold a routine back")
        shoot("routines_360_font2_how")
    }

    // --- the editor ---

    @Test
    fun editorNew() {
        val robot = app { settings.update { it.copy(routinesEnabled = true) } }.routinesList().tap("New routine")
        compose.onNode(hasSetTextAction() and hasText(TITLE_LABEL)).performTextReplacement("Morning news")
        compose.onNode(hasSetTextAction() and hasText(OBJECTIVE_LABEL)).performTextReplacement("Summarise the news about electric cars in five lines.")
        compose.waitForIdle()
        shoot("editor_new_1")
        robot.scrollTo("With this schedule")
        shoot("editor_new_2_when")
        robot.scrollTo("How long its requests wait for you")
        shoot("editor_new_3_while")
    }

    @Test
    fun editorExistingWithHistory() {
        val robot = app(dark = false) { routines() }.routinesList().tapItemContaining("Morning news")
        shoot("editor_existing_light_1")
        robot.scrollTo("Started after you tapped Start.")
        shoot("editor_existing_light_2_history")
    }

    @Test
    fun editorPaused() {
        app { routines() }.routinesList().tapItemContaining("Evening digest")
        shoot("editor_paused")
    }

    @Test
    fun editorEveryFewHoursWithScreens() {
        val robot = app { routines() }.routinesList().tapItemContaining("Inbox check").scrollTo("At 08:00")
        shoot("editor_every_hours")
        robot.scrollTo("Screen actions work only")
        shoot("editor_screens_on")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C, fontScale = 2.0f)
    fun editorEveryHours360LargeFont() {
        app { routines() }.routinesList().scrollListTo("Inbox check").tapItemContaining("Inbox check").scrollTo("Runs start again each day")
        shoot("editor_every_hours_360_font2")
    }

    @Test
    fun editorScreensQuestion() {
        app { routines() }.routinesList().tapItemContaining("Morning news").tap("May use other apps' screens")
        shootScreen("editor_screens_question")
    }

    @Test
    fun editorClockChange() {
        val robot = app {
            zone = ZoneId.of("Europe/Berlin")
            makeRoutine("Night backup check", "Check that last night's notes were saved.", RoutineSchedule.Daily(2 * 60 + 30))
        }.routinesList().tapItemContaining("Night backup check")
        robot.scrollTo("When the clocks change")
        shoot("editor_clock_change")
    }

    @Test
    @Config(qualifiers = WIDE_WP10C)
    fun editorWide() {
        wide = true
        app { routines() }.routinesList().tapItemContaining("Morning news")
        shoot("editor_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C, fontScale = 2.0f)
    fun editor360LargeFont() {
        val robot = app { routines() }.routinesList().tapItemContaining("Morning news")
        shoot("editor_360_font2_1")
        robot.scrollTo("Once a day").scrollTo("Every day at 07:00")
        shoot("editor_360_font2_2_when")
        robot.scrollTo("On these days")
        shoot("editor_360_font2_3_time")
    }

    // --- "Tap to start" ---

    @Test
    fun waitingStep() {
        // The app brings the step up by itself once the owner is in the app (not on the Dashboard) and a run waits.
        val robot = app().section("Settings")
        kotlinx.coroutines.runBlocking { robot.app.waiting() }
        robot.waitUntilShown("is waiting to start")
        shoot("waiting_step")
        robot.tap("Start")
        shootScreen("waiting_step_question")
    }

    @Test
    fun waitingStepEmptyLight() {
        app(dark = false) { settings.update { it.copy(routinesEnabled = true) } }.routinesList()
        compose.waitForIdle()
        // Opened from the list's link once nothing waits any more.
        kotlinx.coroutines.runBlocking { robot!!.app.waiting() }
        robot!!.waitUntilShown("is waiting to start").tap("Not now").waitUntilShown("Nothing is waiting to start")
        shoot("waiting_step_empty_light")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C, fontScale = 2.0f)
    fun waitingStep360LargeFont() {
        val robot = app().section("Settings")
        kotlinx.coroutines.runBlocking { robot.app.waiting() }
        robot.waitUntilShown("is waiting to start")
        shoot("waiting_step_360_font2")
    }

    // --- Folder pick-up ---

    @Test
    fun pickUpList() {
        app { pickUp() }.pickUpList()
        shoot("pickup_1_list")
        robot!!.scrollListTo("Exports")
        shoot("pickup_2_stopped")
        robot!!.scrollListTo("The folder must be read-only for agents")
        shoot("pickup_3_how")
    }

    @Test
    fun pickUpListLight() {
        app(dark = false) { pickUp() }.pickUpList()
        shoot("pickup_list_light")
    }

    @Test
    fun pickUpEmptyAndOff() {
        app().pickUpList()
        shoot("pickup_empty_off")
    }

    @Test
    @Config(qualifiers = WIDE_WP10C)
    fun pickUpWide() {
        wide = true
        app { pickUp() }.pickUpList()
        shoot("pickup_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C)
    fun pickUp360() {
        app { pickUp() }.pickUpList()
        shoot("pickup_360")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C, fontScale = 2.0f)
    fun pickUp360LargeFont() {
        app { pickUp() }.pickUpList()
        shoot("pickup_360_font2")
    }

    @Test
    fun pickUpEditorRefusal() {
        val robot = app { foldersForRule() }.pickUpList().tap("New rule").tap("Exports")
        shoot("pickup_editor_1_not_read_only")
        robot.tap("Letters").scrollTo("This folder cannot be used yet")
        shoot("pickup_editor_2_overlaps")
        robot.scrollTo("Make the rule")
        shoot("pickup_editor_3_space")
    }

    @Test
    fun pickUpEditorLight() {
        app(dark = false) { foldersForRule() }.pickUpList().tap("New rule").tap("Inbox")
        shoot("pickup_editor_light")
    }

    @Test
    fun pickUpEditorExisting() {
        app { pickUp() }.pickUpList().tap("Change")
        shoot("pickup_editor_existing")
    }

    @Test
    @Config(qualifiers = WIDE_WP10C)
    fun pickUpEditorWide() {
        wide = true
        app { foldersForRule() }.pickUpList().tap("New rule").tap("Exports")
        shoot("pickup_editor_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10C, fontScale = 2.0f)
    fun pickUpEditor360LargeFont() {
        val robot = app { foldersForRule() }.pickUpList().tap("New rule").tap("Exports")
        shoot("pickup_editor_360_font2_1")
        robot.scrollTo("This folder cannot be used yet")
        shoot("pickup_editor_360_font2_2")
    }
}

/** The wide and the narrow window of the wave-3 pictures (WP10c's own names, as WP10a's file has its own). */
private const val WIDE_WP10C = "w900dp-h600dp-xhdpi"
private const val NARROW_WP10C = "w360dp-h740dp-xhdpi"
