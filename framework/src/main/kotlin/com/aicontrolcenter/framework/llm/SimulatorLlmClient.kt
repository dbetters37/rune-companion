package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolChoice
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.core.llm.ToolResultContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The simulator's "model". The same request always produces the same reply:
 *  - after tool results, it reports what the tools returned;
 *  - when a tool is offered that plausibly answers the request (the time or date, who is on
 *    the team), it calls that tool;
 *  - otherwise it restates the request and shows what a real model would have received.
 */
internal class SimulatorLlmClient(private val chunkDelayMs: Long) : LlmClient {

    override suspend fun chat(request: ChatRequest): ChatResponse = respond(request)

    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        val response = respond(request)
        for (piece in PIECE.findAll(response.message.text)) {
            emit(ChatChunk.Text(piece.value))
            if (chunkDelayMs > 0) delay(chunkDelayMs)
        }
        response.message.toolCalls.forEach { emit(ChatChunk.ToolCall(it)) }
        emit(ChatChunk.Completed(response))
    }

    private fun respond(request: ChatRequest): ChatResponse {
        val lastUser = request.messages.indexOfLast { it.role == ChatRole.USER }
        val question = request.messages.getOrNull(lastUser)?.text?.trim().orEmpty()
        val results = request.messages.drop(lastUser + 1).mapNotNull { it.toolResult }
        // One round of tools per request: after results the simulator always answers, so it cannot loop.
        val calls = if (results.isEmpty()) plannedCalls(question, request) else emptyList()

        var finish = if (calls.isEmpty()) FinishReason.STOP else FinishReason.TOOL_CALLS
        var text = when {
            calls.isNotEmpty() -> "${Simulator.DISCLOSURE} Looking this up with: ${calls.joinToString(", ") { it.name }}."
            request.jsonOutput -> jsonReply(question, results)
            results.isNotEmpty() -> answerFromResults(question, results)
            else -> restate(question, request)
        }
        val limit = request.maxOutputTokens
        if (calls.isEmpty() && !request.jsonOutput && limit != null && tokens(text) > limit) {
            text = text.take(maxOf(limit * CHARS_PER_TOKEN, Simulator.DISCLOSURE.length))
            finish = FinishReason.LENGTH
        }
        val usage = TokenUsage(inputTokens = request.messages.sumOf { tokens(it.text) + tokens(it.toolResult?.content.orEmpty()) }, outputTokens = tokens(text))
        return ChatResponse(ChatMessage.assistant(text, calls), finish, usage, request.model.ifBlank { Simulator.MODEL_NAME })
    }

    private fun plannedCalls(question: String, request: ChatRequest): List<ToolCallRequest> {
        if (request.toolChoice != ToolChoice.AUTO || request.tools.isEmpty() || !REQUEST_CUE.containsMatchIn(question)) return emptyList()
        val earlierCalls = request.messages.sumOf { it.toolCalls.size }
        return INTENTS
            .filter { it.topic.containsMatchIn(question) }
            .mapNotNull { intent -> request.tools.firstOrNull { intent.tool.containsMatchIn(it.name) && it.needsNoArguments() } }
            .distinctBy { it.name }
            .mapIndexed { index, tool -> ToolCallRequest("sim_call_${earlierCalls + index + 1}", tool.name, JsonObject(emptyMap())) }
    }

    private fun ToolDefinition.needsNoArguments(): Boolean = (parameters["required"] as? JsonArray).isNullOrEmpty()

    private fun answerFromResults(question: String, results: List<ToolResultContent>): String = buildString {
        appendLine(Simulator.DISCLOSURE)
        appendLine()
        appendLine("You asked: \"${shorten(question, MAX_QUOTE)}\"")
        for (result in results) {
            appendLine()
            if (result.isError) {
                appendLine("The tool \"${result.toolName}\" could not be used:")
            } else {
                appendLine("The tool \"${result.toolName}\" returned:")
            }
            appendLine(shorten(result.content, MAX_RESULT))
        }
        appendLine()
        append("A real model would turn this into a proper answer. The simulator only shows what the tools returned.")
    }

    private fun restate(question: String, request: ChatRequest): String = buildString {
        appendLine(Simulator.DISCLOSURE)
        appendLine()
        if (question.isEmpty()) {
            appendLine("Your message had no text.")
        } else {
            appendLine("Your request, restated: \"${shorten(question, MAX_QUOTE)}\"")
            val points = keyPoints(question)
            if (points.size > 1) {
                appendLine()
                appendLine("It has ${points.size} parts:")
                points.forEachIndexed { index, point -> appendLine("${index + 1}. $point") }
            }
        }
        appendLine()
        appendLine("What a real model would have received:")
        val instructions = request.messages.filter { it.role == ChatRole.SYSTEM }.sumOf { it.text.length }
        appendLine("- Instructions for this agent: $instructions characters")
        appendLine("- Conversation so far: ${request.messages.count { it.role != ChatRole.SYSTEM }} messages")
        val images = request.messages.sumOf { it.images.size }
        if (images > 0) appendLine("- Images attached: $images (the simulator cannot look at them)")
        appendLine("- Tools offered: ${request.tools.joinToString(", ") { it.name }.ifEmpty { "none" }}")
        appendLine()
        append("Assign a real model to this agent to get a real answer.")
    }

    private fun jsonReply(question: String, results: List<ToolResultContent>): String = buildJsonObject {
        put("simulated", true)
        put("notice", Simulator.DISCLOSURE)
        put("request", shorten(question, MAX_QUOTE))
        put("points", buildJsonArray { keyPoints(question).forEach { add(it) } })
        if (results.isNotEmpty()) {
            put("toolResults", buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("tool", result.toolName)
                        put("ok", !result.isError)
                        put("content", shorten(result.content, MAX_RESULT))
                    })
                }
            })
        }
    }.toString()

    private fun keyPoints(question: String): List<String> =
        SENTENCE_BREAK.split(question).map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_POINTS).map { shorten(it, MAX_POINT) }

    private fun shorten(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    private fun tokens(text: String): Long = ((text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN).toLong()

    /** A subject the request is about, and the pattern a tool's function name must contain to know about it. */
    private class Intent(val topic: Regex, val tool: Regex)

    private companion object {
        const val CHARS_PER_TOKEN = 4
        const val MAX_QUOTE = 400
        const val MAX_RESULT = 1_500
        const val MAX_POINT = 160
        const val MAX_POINTS = 5

        /** Whitespace-delimited pieces, each with its trailing space, so the pieces concatenate back to the text. */
        val PIECE = Regex("\\S+\\s*|\\s+")
        val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+|\\n+")

        /** The request reads like a question or an order, not a passing mention. */
        val REQUEST_CUE = Regex("\\?|\\b(what|what's|which|who|who's|when|how many|tell|show|list|give|check|name|current|now)\\b", RegexOption.IGNORE_CASE)

        val INTENTS = listOf(
            Intent(Regex("\\b(time|date|day|today|clock|weekday|year|month)\\b", RegexOption.IGNORE_CASE), Regex("(^|_)(clock|time|date)(_|$)", RegexOption.IGNORE_CASE)),
            Intent(Regex("\\b(team|agents?|colleagues?|teammates?)\\b", RegexOption.IGNORE_CASE), Regex("(^|_)(team|agents?)(_|$)", RegexOption.IGNORE_CASE)),
        )
    }
}
