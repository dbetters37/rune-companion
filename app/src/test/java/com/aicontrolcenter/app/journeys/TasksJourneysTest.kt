package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The Phase 4 journeys: giving the Director an objective, following it, and reading what the team did. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class TasksJourneysTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var robot: AppRobot
    private val app: TestApp get() = robot.app

    @Before
    fun startApp() {
        robot = AppRobot(compose).given { teamOnSimulator() }
    }

    @After
    fun stopApp() = robot.close()

    private fun giveObjective() = robot.launch()
        .openSection("Tasks")
        .assertShown("No objectives yet")
        .tap("New objective")
        .assertShown("Your Director. It plans the work")
        .assertShown("Stays on this phone")
        .assertShown("Simulator: not a real model")
        .assertShown("For example: Research note-taking apps")
        .typeIn("Objective", OBJECTIVE)
        .scrollTo("Start objective")
        .tap("Start objective")
        .waitUntilShown("Took ")

    @Test
    fun `give the Director an objective on the simulator and read its final document`() {
        giveObjective()
        val planned = runBlocking { app.tasks.getTree(app.tasks.observeObjectives().first().single().id) }
            .count { it.kind == TaskKind.WORK || it.kind == TaskKind.SYNTHESIS }
        robot.assertShown("Completed")
            .assertShown("$planned of $planned tasks completed")
            .assertShown("Nothing has left this phone for this objective.")
            .tap("Result")
            .assertShown("Simulated document")
            .assertShown("Copy")
            .assertShown("Share")
        val objective = runBlocking { app.tasks.observeObjectives().first() }.single()
        assertEquals(TaskStatus.COMPLETED, objective.status)

        // Back from the objective lands on the list (the form is left behind); it is under Finished.
        robot.back()
            .assertShown("Active · 0")
            .tap("Finished · 1")
            .assertShown(objective.title)
    }

    @Test
    fun `open a task of the plan and read the agent's transcript`() {
        giveObjective()
        val firstTask = runBlocking { app.tasks.getTree(app.tasks.observeObjectives().first().single().id) }
            .filter { it.kind == TaskKind.WORK }.minBy { it.order }
        robot.tapItemContaining(firstTask.title)
            .assertShown("Objective: ")
            .assertShown("Researcher")
            .assertShown("DONE WHEN")
            .scrollListTo("Brief written by the app for this task")
            .assertShown("Brief written by the app for this task")
            .scrollListTo("worked on this task (simulated)")
            .assertShown("worked on this task (simulated)")
            // The way back to the whole objective, from the top of the task.
            .scrollListTo("Objective: ")
            .tapItemContaining("Objective: ")
            .assertShown("How the Director understood it")
    }

    private companion object {
        const val OBJECTIVE = "Research offline note apps, compare the best three, and write a recommendation"
    }
}
