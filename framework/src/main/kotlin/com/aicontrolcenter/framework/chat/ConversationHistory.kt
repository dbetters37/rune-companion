package com.aicontrolcenter.framework.chat

import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ToolCallRequest

/**
 * Rebuilds what a model is sent from what a conversation stored.
 *
 * Stored messages are passed on unchanged and in order; entries that only record a failure
 * are not part of the dialogue and are left out. One repair is made: a tool call without a
 * stored result (the app was killed while the tool ran) gets a result saying so, because
 * providers reject a transcript in which a call was never answered. The repair is derived
 * from the stored entries alone, so every later turn rebuilds the identical history.
 *
 * Stored memory_search results are references, not memory text; the runner writes them out as
 * the agent may read memory when the turn starts.
 */
internal object ConversationHistory {
    private const val INTERRUPTED = "No result: the app was interrupted before this tool finished."

    fun toMessages(entries: List<ConversationEntry>): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(entries.size)
        val unanswered = LinkedHashMap<String, ToolCallRequest>()
        fun answerLeftovers() {
            for (call in unanswered.values) messages += ChatMessage.toolResult(call.id, call.name, INTERRUPTED, isError = true)
            unanswered.clear()
        }
        for (entry in entries) {
            if (entry.error != null) continue
            val message = entry.message
            if (message.role == ChatRole.TOOL) {
                message.toolResult?.let { unanswered.remove(it.callId) }
                messages += message
                continue
            }
            answerLeftovers()
            messages += message
            if (message.role == ChatRole.ASSISTANT) message.toolCalls.forEach { unanswered[it.id] = it }
        }
        answerLeftovers()
        return messages
    }
}
