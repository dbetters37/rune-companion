package com.aicontrolcenter.app.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.DailyLimitKind
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * TalkBack and touch for the chat's Phase 6 parts: "Send again", "Daily limits" and "Show earlier messages" are
 * buttons of at least 48 dp that say what they do, and the cost line is read as one sentence with its explanation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ChatSemanticsTest {
    @get:Rule val compose = createComposeRule()

    private val limit = DailyLimits.DEFAULT.sentence(DailyLimitKind.CALLS)

    private fun state(notice: TranscriptItem.Notice, cost: ChatCostUi? = null, hasEarlier: Boolean = false) = ChatUiState(
        isLoading = false,
        title = "Tides",
        agentName = "Tidewatch",
        items = listOf(TranscriptItem.Owner("e0", "When is high tide?"), notice),
        destination = DestinationUi("Hosted model", "Hosted", Locality.CLOUD, isSimulator = false, usingFallback = false),
        cost = cost,
        hasEarlier = hasEarlier,
    )

    private fun show(state: ChatUiState, actions: ChatActions) = compose.setContent {
        AppTheme(darkTheme = true) { ChatScreen(state, TextFieldState(), actions) }
    }

    private fun actions(log: MutableList<String>) = ChatActions(
        onBack = {}, onSend = {}, onStop = {}, onRename = {}, onDelete = {}, onCheckProvider = {}, onOpenProvider = {}, onMessageShown = {},
        onSendAgain = { log += "send again" }, onShowEarlier = { log += "earlier" }, onOpenDailyLimits = { log += "limits" },
    )

    @Test
    fun `a reply the app stopped offers Send again as a 48 dp button`() {
        val log = mutableListOf<String>()
        show(state(TranscriptItem.Notice("e1", DefaultAgentChat.cutOff("Tidewatch"), NoticeKind.APP_STOPPED)), actions(log))
        compose.onNodeWithText("The app stopped").assertIsDisplayed()
        compose.onNode(hasText("Send again") and hasClickAction(), useUnmergedTree = false).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("send again"), log)
        assertEquals(0, compose.onAllNodesWithText("Daily limits").fetchSemanticsNodes().size, "no limit, no link to the limits")
    }

    @Test
    fun `today's limit is said alone and links to Daily limits, with Send again beside it`() {
        val log = mutableListOf<String>()
        show(state(TranscriptItem.Notice("e1", limit, NoticeKind.LIMIT)), actions(log))
        compose.onNodeWithText("Today's limit is reached").assertIsDisplayed()
        compose.onNodeWithText(limit).assertIsDisplayed()
        compose.onNode(hasText("Daily limits") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNode(hasText("Send again") and hasClickAction()).assertHeightIsAtLeast(48.dp)
        assertEquals(listOf("limits"), log)
    }

    @Test
    fun `the cost line is read as one sentence, and Show earlier is a button`() {
        val log = mutableListOf<String>()
        val cost = ChatCostUi("This chat so far: 120,000 tokens, about US$0.60.", "$TOKENS_EXPLAINED Estimated from the prices you entered; your provider's bill is what counts.", priced = true)
        show(state(TranscriptItem.Notice("e1", "Tidewatch could not answer. Timed out.", NoticeKind.FAILURE), cost, hasEarlier = true), actions(log))
        // One merged node holds both lines, so TalkBack reads the cost with what it means.
        compose.onNode(hasText(cost.line) and hasText(cost.explanation)).assertExists()
        compose.onNode(hasText("Show earlier messages") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("earlier"), log)
    }
}
