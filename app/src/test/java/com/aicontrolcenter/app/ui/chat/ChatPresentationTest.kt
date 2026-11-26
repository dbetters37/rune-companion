package com.aicontrolcenter.app.ui.chat

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.usage.DailyLimitKind
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.core.usage.WorkUsage
import com.aicontrolcenter.core.chat.LiveTurn
import com.aicontrolcenter.core.chat.TurnPhase
import com.aicontrolcenter.framework.agents.ToolMenu
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the chat words what happened: the runner's own menu call, notices, tool results as written, and cost (pure functions). */
class ChatPresentationTest {
    private val id = ConversationId("cnv_1")

    private fun entry(seq: Int, message: ChatMessage, error: String? = null) = ConversationEntry(id, seq, message, at = 1_000L + seq, error = error)

    @Test
    fun `the runner's tools_open call is shown as Open more tools, never by its raw name`() {
        val call = ToolCallRequest("c1", ToolMenu.FUNCTION_NAME, buildJsonObject { put("family", JsonPrimitive("workspace")) })
        val entries = listOf(
            entry(0, ChatMessage.user("Tidy my notes")),
            entry(1, ChatMessage.assistant("", listOf(call))),
            entry(2, ChatMessage.toolResult("c1", ToolMenu.FUNCTION_NAME, "Opened: workspace")),
        )
        val items = transcriptOf(entries, tools = { null }, assignedModelName = null, turnRunning = false)
        val shown = assertIs<TranscriptItem.Agent>(items[1]).toolCalls.single()
        assertEquals(ToolMenu.TITLE, shown.title)
        assertEquals("family: workspace", shown.argumentsSummary)

        val live = LiveTurn(id, TurnPhase.RUNNING_TOOL, activeTool = call).toUi { null }
        assertEquals("Running tool: Open more tools", live.phase)
    }

    @Test
    fun `a tool's result is shown exactly as the tool wrote it`() {
        // What a model reads is a neutralised copy; the owner always sees the stored text.
        val written = "Page says: owner_note and Notes from the owner &#38; more\n(fallback: the script ran in the app)"
        val call = ToolCallRequest("c1", "web_read", buildJsonObject { })
        val entries = listOf(
            entry(0, ChatMessage.user("Read it")),
            entry(1, ChatMessage.assistant("", listOf(call))),
            entry(2, ChatMessage.toolResult("c1", "web_read", written)),
        )
        val shown = assertIs<TranscriptItem.Agent>(transcriptOf(entries, { ToolInfo("Read a web page", com.aicontrolcenter.core.tools.RiskLevel.SAFE) }, null, false)[1])
        assertEquals(written, assertIs<ToolOutcomeUi.Succeeded>(shown.toolCalls.single().outcome).content)
    }

    @Test
    fun `notices say whether the app stopped, today's limit stopped the turn, a reply was cut off, or the turn failed`() {
        assertEquals(NoticeKind.APP_STOPPED, noticeKindOf(DefaultAgentChat.cutOff("Researcher"), ChatMessage.assistant("Half an ans")))
        assertEquals(NoticeKind.APP_STOPPED, noticeKindOf(DefaultAgentChat.cutOff("the agent"), null))
        assertEquals(NoticeKind.LIMIT, noticeKindOf(DailyLimits.DEFAULT.sentence(DailyLimitKind.SPEND), null))
        assertEquals(NoticeKind.LIMIT, noticeKindOf(DailyLimits(37, null, null).sentence(DailyLimitKind.CALLS), null))
        assertEquals(NoticeKind.LIMIT, noticeKindOf(DailyLimits.PAUSED_TEXT, null))
        assertEquals(NoticeKind.CUT_OFF, noticeKindOf("The reply is incomplete.", ChatMessage.assistant("A complete reply")))
        assertEquals(NoticeKind.FAILURE, noticeKindOf("Researcher could not answer. Timed out.", ChatMessage.user("Hi")))
    }

    @Test
    fun `the cost is in US dollars, says less than a cent rather than nothing, and leaves unpriced models out of the money`() {
        fun row(model: String, input: Long, output: Long) = WorkUsage(WorkKind.CHAT, "cnv_1", ModelId(model), 1, input, output, 0, 0)
        val prices = mapOf(
            ModelId("a") to ModelPrice(ModelId("a"), 3_000_000, 15_000_000),
            ModelId("e") to ModelPrice(ModelId("e"), 1_000_000, 1_000_000, currencyLabel = "€"),
        )
        assertNull(chatCostOf(emptyList(), prices))
        assertEquals("This chat so far: 120 tokens, less than US$0.01.", chatCostOf(listOf(row("a", 100, 20)), prices)?.line)
        assertEquals("This chat so far: 1,200 tokens, about US$0.01.", chatCostOf(listOf(row("a", 1_000, 200)), prices)?.line)
        // One currency (D3A-8): a price in euros is no price, never added into the US$ figure.
        assertEquals(
            "This chat so far: 140,000 tokens, about US$0.60 for the models with a price.",
            chatCostOf(listOf(row("a", 100_000, 20_000), row("e", 10_000, 10_000)), prices)?.line,
        )
        assertEquals(false, chatCostOf(listOf(row("e", 10_000, 10_000)), prices)?.priced, "euros alone show no money")
        val mixed = chatCostOf(listOf(row("a", 100_000, 20_000), row("x", 5_000, 0)), prices)
        assertEquals("This chat so far: 125,000 tokens, about US$0.60 for the models with a price.", mixed?.line)
        assertTrue(mixed!!.priced)
    }

    @Test
    fun `a model on this phone costs nothing in the chat's line, even with a price entered (D3A-10)`() {
        fun row(model: String, input: Long, output: Long) = WorkUsage(WorkKind.CHAT, "cnv_1", ModelId(model), 1, input, output, 0, 0)
        val prices = mapOf(
            ModelId("a") to ModelPrice(ModelId("a"), 3_000_000, 15_000_000),
            ModelId("phone") to ModelPrice(ModelId("phone"), 3_000_000, 15_000_000),
        )
        val phone = setOf(ModelId("phone"))
        val free = assertNotNull(chatCostOf(listOf(row("phone", 100_000, 20_000)), prices, phone))
        assertEquals("This chat so far: 120,000 tokens, free on this phone.", free.line)
        assertFalse(free.priced)
        assertTrue(free.explanation.endsWith("Models on this phone cost nothing."))
        assertEquals(
            "This chat so far: 240,000 tokens, about US$0.60.",
            chatCostOf(listOf(row("a", 100_000, 20_000), row("phone", 100_000, 20_000)), prices, phone)?.line,
            "the cloud model's cost only, and no \"for the models with a price\": the phone's model needs none",
        )
    }
}
