package com.aicontrolcenter.app.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.SAMPLE_QUESTION
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.ui.questions.ANSWER_FIELD
import com.aicontrolcenter.app.ui.questions.QuestionTexts
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.framework.operator.DefaultOwnerRequests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 6 (Q3, rule 13, rule 32): an agent asks the owner a question while it works, and the owner answers it in the
 * app, from the "Waiting for you" strip, from its notification, or decides to let the agent decide. A question never
 * looks like an approval, and a code needs a second tap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class QuestionJourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var robot: AppRobot
    private val app: TestApp get() = robot.app

    @Before
    fun startApp() {
        robot = AppRobot(compose).given { graph.start().join() }
    }

    @After
    fun stopApp() = robot.close()

    private fun stored() = runBlocking { app.ownerRequestStore.get(RequestId("req_question")) }!!

    private fun typeAnswer(text: String) = apply {
        compose.onNode(hasContentDescription(ANSWER_FIELD) and hasSetTextAction()).performTextReplacement(text)
        compose.waitForIdle()
    }

    @Test
    fun `the owner answers a question from the waiting strip, in their own words`() {
        robot.given { raise(sampleQuestion(agentNamed("Researcher").profile.id, createdAt = clock.current)) }
            .launch()
            .waitUntilShown("1 question waits for your answer")
            .tap("1 question waits for your answer")
            .assertShown("Waiting for you · 1 question".uppercase())
            .assertShown("Question for you")
            .assertShown("Researcher asks you")
            .assertShown("Not a permission: your answer is only text.")
            .tap("Answer")
            .assertShown("A question from Researcher")
            .assertShown(QuestionTexts.NOT_A_PERMISSION)
            .assertShown("“$SAMPLE_QUESTION”")
            .assertShown("Answers Researcher suggests".uppercase())
            .assertNotShown("Approve")
            .assertNotShown("Allow just this once")
        robot.scrollTo("Paris").tap(QuestionTexts.USE_THIS_ANSWER)
        assertEquals(OwnerRequestStatus.PENDING, stored().status, "a suggestion only fills the answer")
        typeAnswer("Porto, please")
        robot.scrollTo(QuestionTexts.SEND).tap(QuestionTexts.SEND)
            .waitUntilShown("Answered")
            .assertShown("Porto, please")
        val answered = stored()
        assertEquals(OwnerRequestStatus.APPROVED, answered.status)
        assertEquals("Porto, please", answered.answer)
        robot.back().assertShown("Nothing is waiting for you")
    }

    @Test
    fun `the question's notification opens the question, never an approval screen`() {
        robot.given { raise(sampleQuestion(agentNamed("Researcher").profile.id, createdAt = clock.current)) }.launch()
        // As MainActivity does for a tapped notification: it names the request, and the Approval route hands it over.
        app.graph.requestApproval(RequestId("req_question"))
        robot.waitUntilShown(QuestionTexts.NOT_A_PERMISSION)
            .assertShown("A question from Researcher")
            .assertNotShown("Exactly what would happen".uppercase())
            .scrollToEnd()
            .tap(QuestionTexts.DECIDE_YOURSELF)
            .waitUntilShown("You let Researcher decide itself.")
        val decided = stored()
        assertEquals(OwnerRequestStatus.APPROVED, decided.status)
        assertNull(decided.answer)
    }

    @Test
    fun `a code is sent only after the second tap, and a key never`() {
        robot.given {
            raise(sampleQuestion(agentNamed("Researcher").profile.id, createdAt = clock.current, question = "What is the code in the text message?", options = emptyList()))
        }.launch()
        app.graph.requestApproval(RequestId("req_question"))
        robot.waitUntilShown(QuestionTexts.NOT_A_PERMISSION)
        typeAnswer("sk-ant-api03-abcdefghijklmnopqrstuvwx")
        robot.assertShown("This looks like a key or a password")
            .scrollTo(QuestionTexts.SEND).tap(QuestionTexts.SEND)
            .waitUntilShown(DefaultOwnerRequests.KEY_REFUSED)
            .assertNotShown(QuestionTexts.SEND_ANYWAY)
        assertEquals(OwnerRequestStatus.PENDING, stored().status)

        typeAnswer("482913")
        robot.scrollTo(QuestionTexts.SEND).tap(QuestionTexts.SEND)
            .waitUntilShown("Is this a code?")
        assertEquals(OwnerRequestStatus.PENDING, stored().status, "not sent on the first tap")
        robot.scrollTo(QuestionTexts.SEND_ANYWAY).tap(QuestionTexts.SEND_ANYWAY)
            .waitUntilShown("Answered")
        assertEquals("482913", stored().answer)
    }

    @Test
    fun `the objective whose task asked shows the question, and opens it on its own screen`() {
        robot.given {
            val director = director().profile.id
            val researcher = agentNamed("Researcher").profile.id
            val root = com.aicontrolcenter.core.TaskId("obj_porto")
            val now = clock.current
            tasks.upsert(
                com.aicontrolcenter.core.tasks.TaskRecord(
                    id = root, rootId = root, parentId = null, kind = com.aicontrolcenter.core.tasks.TaskKind.OBJECTIVE,
                    title = "Plan a weekend in Porto", instructions = "Plan a weekend in Porto.", assignee = director,
                    requestedBy = com.aicontrolcenter.core.tasks.TaskRequester.Owner, status = com.aicontrolcenter.core.tasks.TaskStatus.RUNNING,
                    createdAt = now, updatedAt = now,
                ),
            )
            raise(sampleQuestion(researcher, createdAt = now, taskId = null, rootId = root))
        }.launch()
            .openSection("Tasks")
            .tapItemContaining("Plan a weekend in Porto")
            .assertShown("Waiting for you · 1".uppercase())
            .assertShown("Question for you")
            .tap("Answer")
            .assertShown(QuestionTexts.NOT_A_PERMISSION)
            .assertShown("A question from Researcher")
    }

    @Test
    fun `questions and approvals wait side by side, each opening its own screen`() {
        robot.given {
            val researcher = agentNamed("Researcher").profile.id
            raise(sampleApproval(researcher, agentName = "Researcher", id = "req_approval", createdAt = clock.current, minutes = 240))
            raise(sampleQuestion(researcher, createdAt = clock.current + 1))
        }.launch()
            .waitUntilShown("1 request and 1 question wait for your answer")
            .tap("1 request and 1 question wait for your answer")
            .assertShown("Waiting for you · 1 request and 1 question".uppercase())
            .tap("Review")
            .assertShown("Exactly what would happen".uppercase())
            .back()
            .tap("Answer")
            .assertShown(QuestionTexts.NOT_A_PERMISSION)
    }
}
