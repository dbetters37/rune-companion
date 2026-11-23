package com.aicontrolcenter.app.ui.chat

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.LiveTurn
import com.aicontrolcenter.core.chat.TurnPhase
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tools.RiskLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/*
 * Turns stored conversation entries and the live turn into what the chat screen shows. Pure
 * functions, so every way a transcript can look is unit-tested without a screen.
 */

/** What is known about a tool from the registry, by the function name the model used. */
@Immutable
data class ToolInfo(val title: String, val risk: RiskLevel)

@Immutable
sealed interface ToolOutcomeUi {
    data object Running : ToolOutcomeUi

    data class Succeeded(val content: String) : ToolOutcomeUi

    data class Failed(val content: String) : ToolOutcomeUi

    /** The call has no stored result: the turn ended before it ran. */
    data object NoResult : ToolOutcomeUi
}

@Immutable
data class ToolCallUi(
    val callId: String,
    val title: String,
    /** "timezone: Europe/Paris", or "No arguments". */
    val argumentsSummary: String,
    /** The arguments as the model sent them, pretty-printed, for the expanded row. */
    val argumentsJson: String,
    val outcome: ToolOutcomeUi,
)

/** Shown under an agent's final reply: who answered and what it cost. */
@Immutable
data class ReplyFooterUi(
    /** The model the provider says answered. */
    val answeredBy: String?,
    /** The assigned model's wire name, when a different model answered. */
    val assignedModel: String?,
    val usage: String?,
) {
    val differs: Boolean get() = assignedModel != null
}

enum class NoticeKind { FAILURE, CUT_OFF }

@Immutable
sealed interface TranscriptItem {
    val key: String

    data class Owner(override val key: String, val text: String) : TranscriptItem

    data class Agent(
        override val key: String,
        val text: String,
        val reasoning: String?,
        val toolCalls: List<ToolCallUi>,
        val footer: ReplyFooterUi?,
    ) : TranscriptItem

    data class Notice(override val key: String, val text: String, val kind: NoticeKind) : TranscriptItem
}

/** The turn in progress, as the screen shows it under the transcript. */
@Immutable
data class LiveTurnUi(
    /** "Waiting for the model", "Running tool: Current time", … */
    val phase: String,
    val partialText: String,
    val partialReasoning: String,
)

private val prettyJson = Json { prettyPrint = true }

/**
 * Builds the transcript. Tool results are shown with the call that asked for them, never on
 * their own. A failure note right after a complete reply means the reply was cut off;
 * anywhere else it means the turn failed.
 *
 * @param assignedModelName the wire name of the agent's primary model, to flag replies from another model
 * @param turnRunning true while a turn is in progress; its last reply gets no footer yet
 */
fun transcriptOf(
    entries: List<ConversationEntry>,
    tools: (String) -> ToolInfo?,
    assignedModelName: String?,
    turnRunning: Boolean,
    activeToolCallId: String? = null,
): List<TranscriptItem> {
    val results = entries.mapNotNull { it.message.toolResult }.associateBy { it.callId }
    // Index of the last assistant message of every turn (a turn starts at an owner message).
    val turnEnds = HashMap<Int, Int>()
    var turnStart = -1
    entries.forEachIndexed { index, entry ->
        when {
            entry.message.role == ChatRole.USER -> turnStart = index
            entry.message.role == ChatRole.ASSISTANT -> turnEnds[turnStart] = index
        }
    }
    val lastTurnStart = turnStart
    val items = ArrayList<TranscriptItem>()
    turnStart = -1
    entries.forEachIndexed { index, entry ->
        val message = entry.message
        val key = "e${entry.seq}"
        when {
            entry.error != null -> {
                val previous = entries.getOrNull(index - 1)?.message
                val cutOff = previous != null && previous.role == ChatRole.ASSISTANT && previous.toolCalls.isEmpty() && previous.text.isNotBlank()
                items += TranscriptItem.Notice(key, entry.error!!, if (cutOff) NoticeKind.CUT_OFF else NoticeKind.FAILURE)
            }
            message.role == ChatRole.USER -> {
                turnStart = index
                items += TranscriptItem.Owner(key, message.text)
            }
            message.role == ChatRole.ASSISTANT -> {
                val isTurnEnd = turnEnds[turnStart] == index && !(turnRunning && turnStart == lastTurnStart)
                items += TranscriptItem.Agent(
                    key = key,
                    text = message.text,
                    reasoning = message.reasoning?.takeIf { it.isNotBlank() },
                    toolCalls = message.toolCalls.map { call ->
                        toolCallUi(call, tools(call.name), results[call.id]?.let { it.content to it.isError }, call.id == activeToolCallId)
                    },
                    footer = if (isTurnEnd) footerFor(entries, turnStart, index, assignedModelName) else null,
                )
            }
            // Tool results appear with their call; a result without one (an unreadable entry) is shown alone.
            message.role == ChatRole.TOOL -> {
                val result = message.toolResult ?: return@forEachIndexed
                val hasCall = entries.any { other -> other.message.toolCalls.any { it.id == result.callId } }
                if (!hasCall) {
                    items += TranscriptItem.Agent(
                        key = key, text = "", reasoning = null, footer = null,
                        toolCalls = listOf(
                            ToolCallUi(
                                result.callId, tools(result.toolName)?.title ?: result.toolName, "Arguments not recorded", "",
                                if (result.isError) ToolOutcomeUi.Failed(result.content) else ToolOutcomeUi.Succeeded(result.content),
                            ),
                        ),
                    )
                }
            }
            // Other system messages are never stored by the chat; nothing to show.
            else -> Unit
        }
    }
    return items
}

private fun toolCallUi(call: ToolCallRequest, info: ToolInfo?, result: Pair<String, Boolean>?, running: Boolean) = ToolCallUi(
    callId = call.id,
    title = info?.title ?: call.name,
    argumentsSummary = summariseArguments(call.arguments),
    argumentsJson = if (call.arguments.isEmpty()) "{}" else prettyJson.encodeToString(JsonObject.serializer(), call.arguments),
    outcome = when {
        result != null -> if (result.second) ToolOutcomeUi.Failed(result.first) else ToolOutcomeUi.Succeeded(result.first)
        running -> ToolOutcomeUi.Running
        else -> ToolOutcomeUi.NoResult
    },
)

private fun footerFor(entries: List<ConversationEntry>, from: Int, to: Int, assigned: String?): ReplyFooterUi {
    val turn = entries.subList((from + 1).coerceAtLeast(0), to + 1).filter { it.message.role == ChatRole.ASSISTANT }
    val usage = turn.mapNotNull { it.usage }.takeIf { it.isNotEmpty() }?.reduce(TokenUsage::plus)
    val answeredBy = turn.lastOrNull { it.modelName != null }?.modelName
    return ReplyFooterUi(
        answeredBy = answeredBy,
        assignedModel = assigned?.takeIf { answeredBy != null && !sameModel(answeredBy, it) },
        usage = usage?.let(::formatUsage),
    )
}

/**
 * True when [answered] is the assigned model: the same name, or the dated version a provider
 * resolves an alias to ("gpt-4o" answered as "gpt-4o-2024-08-06").
 */
internal fun sameModel(answered: String, assigned: String): Boolean {
    val a = answered.trim().lowercase().substringAfterLast('/')
    val b = assigned.trim().lowercase().substringAfterLast('/')
    return a == b || a.startsWith("$b-") || a.startsWith("$b@") || a.startsWith("$b:")
}

/** "1,234 tokens in · 56 out", with cached input when the provider reported it. */
fun formatUsage(usage: TokenUsage): String = buildString {
    append(formatCount(usage.inputTokens)).append(" tokens in · ").append(formatCount(usage.outputTokens)).append(" out")
    if (usage.cachedInputTokens > 0) append(" · ").append(formatCount(usage.cachedInputTokens)).append(" cached")
}

/** "timezone: Europe/Paris, units: metric", shortened; "No arguments" for an empty call. */
internal fun summariseArguments(arguments: JsonObject): String {
    if (arguments.isEmpty()) return "No arguments"
    val shown = arguments.entries.take(3).joinToString(", ") { (name, value) -> "$name: ${short(value)}" }
    return if (arguments.size > 3) "$shown, +${arguments.size - 3} more" else shown
}

private fun short(value: JsonElement): String {
    val text = when (value) {
        is JsonPrimitive -> value.content
        is JsonNull -> "null"
        is JsonArray -> "[${value.size} items]"
        is JsonObject -> "{…}"
    }
    return if (text.length > 40) text.take(39) + "…" else text
}

/** The live turn in words. [toolTitle] names the running tool. */
fun LiveTurn.toUi(toolTitle: (String) -> String?): LiveTurnUi = LiveTurnUi(
    phase = when (phase) {
        TurnPhase.PREPARING -> "Preparing"
        TurnPhase.WAITING_FOR_MODEL -> if (modelCalls > 1) "Waiting for the model (step $modelCalls)" else "Waiting for the model"
        TurnPhase.RECEIVING -> if (partialText.isEmpty() && partialReasoning.isNotEmpty()) "Thinking" else "Writing the reply"
        TurnPhase.RUNNING_TOOL -> "Running tool: " + (activeTool?.let { toolTitle(it.name) ?: it.name } ?: "…")
    },
    partialText = partialText,
    partialReasoning = partialReasoning,
)

/** Where this conversation's text goes, for the line above the input. */
@Immutable
data class DestinationUi(
    val modelName: String,
    val providerName: String,
    val locality: Locality,
    val isSimulator: Boolean,
    /** The primary model is unavailable and this is a fallback. */
    val usingFallback: Boolean,
)
