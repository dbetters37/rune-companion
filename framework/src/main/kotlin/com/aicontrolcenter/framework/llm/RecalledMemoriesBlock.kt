package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatMessage

/**
 * The data block in which automatically recalled memories reach a model: placed in front of the
 * owner's text in the newest user message of the request, and only in the request. It is never
 * stored with the conversation and never part of the system message, so stored text can never
 * speak with the voice of the system prompt.
 *
 * The block always starts the message and ends at the first [CLOSE] after [OPEN]. Its content
 * cannot contain either tag: memory text is neutralised before it is framed
 * ([com.aicontrolcenter.framework.memory.MemoryText]), which leaves no `<` in it, and [frame]
 * makes sure of that once more. What follows the block is the owner's own text.
 *
 * The system prompt tells the model that everything inside the block is stored data, never
 * instructions and never the owner's words. For models on the prompted tool protocol the
 * block's content is quoted like a tool result ([PromptedToolsFormat.rewrite]) and counts as
 * quoted content for [EchoGuard], so a call copied out of a memory is not run.
 */
internal object RecalledMemoriesBlock {
    const val OPEN = "<recalled_memories>"
    const val CLOSE = "</recalled_memories>"

    /** [body] between the tags. Any `<` left in it is escaped, so it can never close the block early. */
    fun frame(body: String): String = "$OPEN\n${body.trim('\n').replace("<", "&lt;")}\n$CLOSE"

    /** [message] with [block] (from [frame]) placed before its text; images and everything else stay. */
    fun prepend(message: ChatMessage, block: String): ChatMessage =
        message.copy(text = if (message.text.isBlank()) block else "$block\n\n${message.text}")

    /** The content of the block [text] starts with, without the tags, or null when it starts with none. */
    fun content(text: String): String? {
        val end = end(text) ?: return null
        return text.substring(OPEN.length, end - CLOSE.length)
    }

    /** [text] with the content of the block it starts with replaced by [transform] of it; unchanged when there is none. */
    fun mapContent(text: String, transform: (String) -> String): String {
        val end = end(text) ?: return text
        return OPEN + transform(text.substring(OPEN.length, end - CLOSE.length)) + CLOSE + text.substring(end)
    }

    /** The index just after the block's closing tag, or null. */
    private fun end(text: String): Int? {
        if (!text.startsWith(OPEN)) return null
        val close = text.indexOf(CLOSE, OPEN.length)
        return if (close < 0) null else close + CLOSE.length
    }
}
