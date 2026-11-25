package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.ui.tasks.NOTE_FIELD
import com.aicontrolcenter.app.ui.tasks.ObjectiveViewModel
import com.aicontrolcenter.app.ui.tasks.WRITE_TO_THE_TEAM
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Phase 6 (C2, Tier B; Q2): the owner writes to the team while an objective runs, and an objective paused by today's
 * limit says so, with the way to the limits.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class NotesJourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var robot: AppRobot
    private val app: TestApp get() = robot.app
    private val root = TaskId("obj_porto")

    @Before
    fun startApp() {
        robot = AppRobot(compose).given { graph.start().join() }
    }

    @After
    fun stopApp() = robot.close()

    private suspend fun TestApp.porto(status: TaskStatus, detail: String? = null) {
        val director = director().profile.id
        val researcher = agentNamed("Researcher").profile.id
        val now = clock.current
        tasks.upsert(
            TaskRecord(
                id = root, rootId = root, parentId = null, kind = TaskKind.OBJECTIVE, title = "Plan a weekend in Porto", instructions = "Plan a weekend in Porto for two.",
                assignee = director, requestedBy = TaskRequester.Owner, status = status, statusDetail = detail, createdAt = now, updatedAt = now, startedAt = now,
            ),
        )
        tasks.upsert(
            TaskRecord(
                id = TaskId("tsk_hotels"), rootId = root, parentId = root, kind = TaskKind.WORK, title = "Find three hotels", instructions = "Find hotels.",
                assignee = researcher, requestedBy = TaskRequester.Agent(director), status = TaskStatus.RUNNING, createdAt = now, updatedAt = now,
            ),
        )
    }

    @Test
    fun `the owner writes a note to the agent on a task, and it is in the conversation`() {
        robot.given { porto(TaskStatus.RUNNING) }.launch()
            .openSection("Tasks")
            .tapItemContaining("Plan a weekend in Porto")
            .tap("Conversation")
            .tap(WRITE_TO_THE_TEAM)
            .assertShown(WRITE_TO_THE_TEAM.uppercase())
            .assertShown("Notes reach the team from their next step.")
            .tap("Researcher · Find three hotels")
        compose.onNode(hasContentDescription(NOTE_FIELD) and hasSetTextAction()).performScrollTo().performTextReplacement("Stay near the river, please.")
        compose.waitForIdle()
        robot.tap("Add note")
            .waitUntilShown(ObjectiveViewModel.NOTE_SENT)
            .scrollListTo("Stay near the river, please.")
            .assertShown("Stay near the river, please.")
            .assertShown("Your note")
        val note = runBlocking { app.messages.list(root) }.single { it.type == MessageType.GUIDANCE }
        assertEquals(Participant.Owner, note.from)
        assertEquals(TaskId("tsk_hotels"), note.taskId)
        assertEquals("Stay near the river, please.", note.body)
    }

    @Test
    fun `an objective paused by today's limit says so and leads to the limits`() {
        robot.given { porto(TaskStatus.PAUSED, DailyLimits.PAUSED_TEXT) }.launch()
            .openSection("Tasks")
            .tapItemContaining("Plan a weekend in Porto")
            .assertShown("Paused: today's limit is reached")
            .assertShown("raise today's limit in Daily limits")
            .assertShown("Resume")
            .assertShown("Team view")
            .tap("Daily limits")
            .waitUntilShown("Daily limits")
    }
}
