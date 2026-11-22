package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ToolDefinition

/**
 * A provider-independent estimate of how many tokens a request takes. Real tokenizers differ
 * by model and are not available offline, so this uses the usual rule of thumb (about four
 * characters of Latin text per token) and counts every other character as a token of its own,
 * which is close for CJK and errs high elsewhere. It is for deciding whether a conversation
 * plausibly fits a context window, not for billing.
 */
internal object TokenEstimator {
    private const val LATIN_CHARS_PER_TOKEN = 4
    private const val MESSAGE_OVERHEAD = 4
    private const val IMAGE_TOKENS = 1_000

    fun tokens(text: String): Int {
        var latin = 0
        var other = 0
        for (c in text) if (c.code < 0x250) latin++ else other++
        return (latin + LATIN_CHARS_PER_TOKEN - 1) / LATIN_CHARS_PER_TOKEN + other
    }

    fun tokens(message: ChatMessage): Int =
        MESSAGE_OVERHEAD + tokens(message.text) + message.images.size * IMAGE_TOKENS +
            message.toolCalls.sumOf { tokens(it.name) + tokens(it.arguments.toString()) } +
            (message.toolResult?.let { tokens(it.content) } ?: 0)

    fun tokens(messages: List<ChatMessage>, tools: List<ToolDefinition>): Int =
        messages.sumOf { tokens(it) } + tools.sumOf { tokens(it.name) + tokens(it.description) + tokens(it.parameters.toString()) }
}
