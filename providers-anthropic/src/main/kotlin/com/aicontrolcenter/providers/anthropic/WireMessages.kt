package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ToolResultContent
import com.anthropic.models.beta.messages.BetaAssistantMessageParam
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlockParam
import kotlinx.serialization.json.JsonArray

/**
 * Turns the neutral conversation (without its leading system messages) into the `messages`
 * array of a Messages API request.
 *
 * The rules it enforces:
 *  - An assistant turn that came from this provider is replayed from its stored content blocks
 *    (see [StoredTurn]); any other assistant turn is rebuilt from text and tool calls. With
 *    [replayThinking] off, the stored thinking blocks of every turn are left out, which is the
 *    recovery for a history the API no longer accepts them in.
 *  - Everything between two assistant turns becomes ONE user message: first the result of
 *    every tool call of the preceding assistant turn, then the user's own text and images.
 *    The API rejects tool results that are split up or that follow other content.
 *  - A tool call that was never answered (the run was stopped, or the reply was cut off) gets
 *    an error result saying so. The API refuses a history with an unanswered call, and removing
 *    the call instead would edit a turn whose thinking blocks must come back unchanged.
 *  - A later system message is sent as a real `system` entry where the model and the position
 *    allow it, otherwise as a marked block inside the user turn. The choice depends only on
 *    the message and its neighbours, so it stays the same as the history grows.
 *
 * The output is a pure function of the input, which keeps an append-only history
 * append-only on the wire: a requirement of both prompt caching and preserved thinking.
 */
internal class WireMessages(private val traits: ModelTraits, private val replayThinking: Boolean = true) {
    private val out = ArrayList<BetaMessageParam>()
    private val pendingResults = ArrayList<BetaContentBlockParam>()
    private val pendingContent = ArrayList<BetaContentBlockParam>()
    private val unansweredCalls = LinkedHashSet<String>()

    fun convert(messages: List<ChatMessage>): List<BetaMessageParam> {
        messages.forEachIndexed { index, message ->
            when (message.role) {
                ChatRole.USER -> addUser(message)
                ChatRole.TOOL -> message.toolResult?.let(::addToolResult)
                ChatRole.ASSISTANT -> addAssistant(message)
                ChatRole.SYSTEM -> addSystem(message.text, next = messages.getOrNull(index + 1))
            }
        }
        flushUserTurn()
        return out
    }

    private fun addUser(message: ChatMessage) {
        message.images.forEach { image ->
            val source = BetaBase64ImageSource.builder()
                .mediaType(BetaBase64ImageSource.MediaType.of(image.mimeType))
                .data(image.base64)
                .build()
            pendingContent += BetaContentBlockParam.ofImage(source)
        }
        if (message.text.isNotBlank()) pendingContent += BetaContentBlockParam.ofText(message.text)
    }

    private fun addToolResult(result: ToolResultContent) {
        unansweredCalls -= result.callId
        pendingResults += toolResult(result.callId, result.content, result.isError)
    }

    private fun addAssistant(message: ChatMessage) {
        val stored = StoredTurn.read(message.providerData)
            ?.let { StoredTurn.replayable(it, replayThinking) }
            ?.takeIf { it.isNotEmpty() }
        val turn = if (stored != null) {
            // Raw JSON in place of typed blocks: the SDK sends it through untouched.
            BetaAssistantMessageParam.builder().content(JsonArray(stored).toSdkJson()).build()
        } else {
            val blocks = neutralBlocks(message)
            // An empty turn (a refusal with no explanation, a blank reply) cannot be sent.
            if (blocks.isEmpty()) return
            BetaAssistantMessageParam.builder().contentOfContentBlockParams(blocks).build()
        }
        flushUserTurn()
        out += BetaMessageParam.ofAssistant(turn)
        unansweredCalls += stored?.let(StoredTurn::toolUseIds) ?: message.toolCalls.map { it.id }
    }

    private fun neutralBlocks(message: ChatMessage): List<BetaContentBlockParam> = buildList {
        if (message.text.isNotBlank()) add(BetaContentBlockParam.ofText(message.text))
        message.toolCalls.forEach { call ->
            val block = BetaToolUseBlockParam.builder().id(call.id).name(call.name).input(call.arguments.toSdkJson()).build()
            add(BetaContentBlockParam.ofToolUse(block))
        }
    }

    private fun addSystem(text: String, next: ChatMessage?) {
        if (text.isBlank()) return
        // The API accepts a system entry only directly after a user turn, and only as the last
        // entry or right before an assistant turn.
        val followsUserTurn = pendingResults.isNotEmpty() || pendingContent.isNotEmpty() || unansweredCalls.isNotEmpty()
        val endsTurn = next == null || next.role == ChatRole.ASSISTANT
        if (traits.midConversationSystem && followsUserTurn && endsTurn) {
            flushUserTurn()
            out += BetaMessageParam.ofSystem(text)
        } else {
            pendingContent += BetaContentBlockParam.ofText("<system-reminder>\n$text\n</system-reminder>")
        }
    }

    private fun flushUserTurn() {
        unansweredCalls.forEach { pendingResults += toolResult(it, NOT_RUN, isError = true) }
        unansweredCalls.clear()
        if (pendingResults.isEmpty() && pendingContent.isEmpty()) return
        out += BetaMessageParam.ofUserOfContentBlockParams(pendingResults + pendingContent)
        pendingResults.clear()
        pendingContent.clear()
    }

    private fun toolResult(callId: String, content: String, isError: Boolean): BetaContentBlockParam {
        val block = BetaToolResultBlockParam.builder().toolUseId(callId)
        // An empty text is not valid content; a result without content is.
        if (content.isNotEmpty()) block.content(content)
        if (isError) block.isError(true)
        return BetaContentBlockParam.ofToolResult(block.build())
    }

    private companion object {
        const val NOT_RUN = "This tool call was not run and has no result."
    }
}
