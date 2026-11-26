package com.aicontrolcenter.app.ui.questions

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.AgentId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The question screen as TalkBack and a large finger meet it (rule 13): the not-a-permission line, each suggestion
 * named with its own words, the answers marked sensitive and at least 48 dp high, and "Send it anyway" only after a
 * code warning. Counts and properties, never times.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class QuestionScreenSemanticsTest {
    @get:Rule val compose = createComposeRule()

    private val used = mutableListOf<Int>()
    private var sent = 0
    private var sentAnyway = 0

    private fun show(state: QuestionUiState) {
        val actions = QuestionActions(
            onBack = {}, onSend = { sent++ }, onSendAnyway = { sentAnyway++ }, onDecideYourself = {}, onUseSuggestion = { used += it },
            onOpenTask = {}, onOpenObjective = {}, onMessageShown = {},
        )
        compose.setContent { AppTheme(darkTheme = true) { QuestionScreen(state, TextFieldState(), actions) } }
        compose.waitForIdle()
    }

    private fun waiting(): QuestionUiState {
        val request = sampleQuestion(AgentId("agt_researcher"), createdAt = TestApp.START_TIME)
        return QuestionUiState(isLoading = false, question = questionUi(request, null, TestApp.START_TIME + 60_000, ZoneOffset.UTC))
    }

    private val sensitive = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsSensitiveData)

    @Test
    fun `the screen says it is not a permission, and names each suggestion in its own words`() {
        show(waiting())
        compose.onAllNodes(hasText(QuestionTexts.NOT_A_PERMISSION, substring = true)).onFirst().assertExists()
        val berlin = compose.onNode(hasContentDescription("${QuestionTexts.USE_THIS_ANSWER}: Berlin") and hasClickAction())
        berlin.performScrollTo().assertHeightIsAtLeast(48.dp)
        berlin.performClick()
        assertEquals(listOf(0), used)
        assertEquals(1, compose.onAllNodes(hasContentDescription("${QuestionTexts.USE_THIS_ANSWER}: Paris, in “France”")).fetchSemanticsNodes().size)
    }

    @Test
    fun `the answers are marked sensitive and big enough to tap`() {
        show(waiting())
        for (label in listOf(QuestionTexts.SEND, QuestionTexts.DECIDE_YOURSELF)) {
            val button = compose.onNode(hasText(label) and hasClickAction() and sensitive, useUnmergedTree = false)
            button.performScrollTo().assertHeightIsAtLeast(48.dp)
        }
        assertEquals(0, compose.onAllNodesWithText(QuestionTexts.SEND_ANYWAY).fetchSemanticsNodes().size, "no second tap before a code warning")
        compose.onNode(hasText(QuestionTexts.SEND) and hasClickAction()).performScrollTo().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun `send it anyway appears only with the code warning, outlined, never leading`() {
        show(waiting().copy(refusal = "This looks like a code.", sendAnywayOffered = true))
        compose.onAllNodes(hasText("Is this a code?")).onFirst().assertExists()
        val anyway = compose.onNode(hasText(QuestionTexts.SEND_ANYWAY) and hasClickAction() and sensitive)
        anyway.performScrollTo().assertHeightIsAtLeast(48.dp)
        anyway.performClick()
        assertEquals(1, sentAnyway)
        // Send still leads: it comes first on the screen.
        val send = compose.onNode(hasText(QuestionTexts.SEND) and hasClickAction()).fetchSemanticsNode().positionInRoot.y
        val second = compose.onNode(hasText(QuestionTexts.SEND_ANYWAY) and hasClickAction()).fetchSemanticsNode().positionInRoot.y
        assertTrue(send < second)
    }

    @Test
    fun `a closed question offers no answer at all`() {
        val request = sampleQuestion(AgentId("agt_researcher"), createdAt = TestApp.START_TIME).let {
            it.copy(status = com.aicontrolcenter.core.operator.OwnerRequestStatus.APPROVED, decidedAt = it.createdAt + 1, answer = "Berlin")
        }
        show(QuestionUiState(isLoading = false, question = questionUi(request, null, TestApp.START_TIME + 60_000, ZoneOffset.UTC)))
        assertEquals(0, compose.onAllNodes(hasText(QuestionTexts.SEND) and hasClickAction()).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText(QuestionTexts.USE_THIS_ANSWER)).fetchSemanticsNodes().size)
        compose.onAllNodes(hasText("Your answer, as it was sent.", substring = true)).onFirst().assertExists()
    }
}
