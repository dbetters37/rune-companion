package com.aicontrolcenter.app.ui.questions

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.ui.tasks.NOTE_FIELD
import com.aicontrolcenter.app.ui.tasks.WRITE_TO_THE_TEAM
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Rule 27 for WP11a's new fields: the answer to an agent's question and the note to the team hold exactly what was
 * typed, however fast, with the frame clock paused and a frame let through only every few keys (as `FastTypingTest`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class AnswerAndNoteTypingTest {
    @get:Rule val compose = createComposeRule()

    private var robot: AppRobot? = null

    @After
    fun stopApp() {
        robot?.close()
    }

    @Test
    fun `the answer to a question is exactly what was typed, and is what is sent`() {
        val started = AppRobot(compose).given {
            graph.start().join()
            raise(sampleQuestion(agentNamed("Researcher").profile.id, createdAt = clock.current, options = emptyList()))
        }.launch().also { robot = it }
        started.app.graph.requestApproval(RequestId("req_question"))
        started.waitUntilShown(QuestionTexts.NOT_A_PERMISSION)

        val field = compose.onNode(hasContentDescription(ANSWER_FIELD) and hasSetTextAction())
        field.typeFast(ANSWER)
        assertEquals(ANSWER, field.typed())

        compose.onAllNodes(hasText(QuestionTexts.SEND) and hasClickAction()).onFirst().performScrollTo().performClick()
        started.waitUntilShown("Answered")
        val stored = runBlocking { started.app.ownerRequestStore.get(RequestId("req_question")) }!!
        assertEquals(OwnerRequestStatus.APPROVED, stored.status)
        assertEquals(ANSWER, stored.answer)
    }

    @Test
    fun `a note to the team is exactly what was typed, and is what is stored`() {
        val root = TaskId("obj_typing")
        val started = AppRobot(compose).given {
            graph.start().join()
            val now = clock.current
            tasks.upsert(
                TaskRecord(
                    id = root, rootId = root, parentId = null, kind = TaskKind.OBJECTIVE, title = "Plan a weekend in Porto", instructions = "Plan it.",
                    assignee = director().profile.id, requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = now, updatedAt = now,
                ),
            )
        }.launch().also { robot = it }
        started.openSection("Tasks").tapItemContaining("Plan a weekend in Porto").tap("Conversation").tap(WRITE_TO_THE_TEAM)

        val field = compose.onNode(hasContentDescription(NOTE_FIELD) and hasSetTextAction())
        field.typeFast(NOTE)
        assertEquals(NOTE, field.typed())

        started.tap("Add note")
        started.waitUntil { runBlocking { started.app.messages.list(root) }.any { it.type == MessageType.GUIDANCE } }
        assertEquals(NOTE, runBlocking { started.app.messages.list(root) }.single { it.type == MessageType.GUIDANCE }.body)
    }

    /** Types [text] one key at a time with the frame clock paused, letting a frame through every [keysPerFrame] keys. */
    private fun SemanticsNodeInteraction.typeFast(text: String, keysPerFrame: Int = 3): SemanticsNodeInteraction {
        try {
            performScrollTo()
        } catch (_: AssertionError) {
            // Not inside a scrolling column.
        }
        performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            text.forEachIndexed { index, key ->
                performTextInput(key.toString())
                if (index % keysPerFrame == keysPerFrame - 1) compose.mainClock.advanceTimeByFrame()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        return this
    }

    /** What the field holds now, exactly. */
    private fun SemanticsNodeInteraction.typed(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private companion object {
        const val ANSWER = "Porto, near the river; two nights (Fri-Sun), €300 at most."
        const val NOTE = "Keep it to one page; prefer trains over flights."
    }
}
