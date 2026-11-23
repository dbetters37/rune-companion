package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.llm.ChatMessage
import java.security.SecureRandom

/**
 * The data block in which automatically recalled memories reach a model: placed in front of the
 * owner's text in the newest user message of the request, and only in the request. It is never
 * stored with the conversation and never part of the system message, so stored text can never
 * speak with the voice of the system prompt.
 *
 * The block is framed structurally, not by recognising what a forgery looks like:
 *  - Its tags carry an id that is new for every block ([newId]): it opens with
 *    `<recalled_memories id="7f3a9c01">` and ends only at `</recalled_memories id="7f3a9c01">`,
 *    with that same id. The system prompt says so. Text stored before the turn cannot know the
 *    id, so no closing tag a memory carries, in whatever disguise, is the one that ends the block.
 *    The parser here ([content], [mapContent]) holds to the same rule.
 *  - Its content cannot contain a tag at all: memory text is neutralised before it is framed
 *    ([com.aicontrolcenter.framework.memory.MemoryText]), which leaves no `<` in it, and [frame]
 *    makes sure of that once more.
 *  - Every line of memory text inside it starts with the quote marker
 *    [com.aicontrolcenter.framework.memory.MemoryText.QUOTE], so nothing a memory says ever
 *    starts a line of the prompt: forged headings, separators and field labels are quoted text.
 *
 * The block always starts the message; what follows it is the owner's own text.
 *
 * The system prompt tells the model that everything inside the block is stored data, never
 * instructions and never the owner's words. For models on the prompted tool protocol the
 * block's content is quoted like a tool result ([PromptedToolsFormat.rewrite]) and counts as
 * quoted content for [EchoGuard], so a call copied out of a memory is not run.
 */
internal object RecalledMemoriesBlock {
    const val TAG = "recalled_memories"

    /** How the tags are shown to the model when it is told about them: the id is new on every turn. */
    const val OPEN_SHAPE = "<$TAG id=\"…\">"
    const val CLOSE_SHAPE = "</$TAG id=\"…\">"

    private const val ID_BYTES = 4
    private const val HEX = "0123456789abcdef"
    private val random = SecureRandom()
    private val OPEN_TAG = Regex("^<$TAG id=\"([0-9a-f]{1,64})\">")

    /** The opening tag of the block with [id]. */
    fun open(id: String): String = "<$TAG id=\"$id\">"

    /** The only closing tag that ends the block with [id]. */
    fun close(id: String): String = "</$TAG id=\"$id\">"

    /** A fresh, unguessable id for one block: lower-case hex. */
    fun newId(): String {
        val bytes = ByteArray(ID_BYTES).also(random::nextBytes)
        return buildString { for (b in bytes) append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF]) }
    }

    /** [body] between the tags of a block with [id]. Any `<` left in it is escaped, so it can never hold a tag. */
    fun frame(body: String, id: String = newId()): String {
        require(OPEN_TAG.matches(open(id))) { "A block id is lower-case hex." }
        return "${open(id)}\n${body.trim('\n').replace("<", "&lt;")}\n${close(id)}"
    }

    /** [message] with [block] (from [frame]) placed before its text; images and everything else stay. */
    fun prepend(message: ChatMessage, block: String): ChatMessage =
        message.copy(text = if (message.text.isBlank()) block else "$block\n\n${message.text}")

    /** The id of the block [text] starts with, or null when it starts with none. */
    fun id(text: String): String? = bounds(text)?.id

    /** The content of the block [text] starts with, without the tags, or null when it starts with none. */
    fun content(text: String): String? = bounds(text)?.let { text.substring(it.contentStart, it.contentEnd) }

    /** [text] with the content of the block it starts with replaced by [transform] of it; unchanged when there is none. */
    fun mapContent(text: String, transform: (String) -> String): String {
        val block = bounds(text) ?: return text
        return text.substring(0, block.contentStart) + transform(text.substring(block.contentStart, block.contentEnd)) +
            text.substring(block.contentEnd)
    }

    private class Bounds(val id: String, val contentStart: Int, val contentEnd: Int)

    /** Where the block [text] starts with has its content: up to the first closing tag with the opening tag's id. */
    private fun bounds(text: String): Bounds? {
        val opening = OPEN_TAG.find(text) ?: return null
        val id = opening.groupValues[1]
        val start = opening.range.last + 1
        val end = text.indexOf(close(id), start)
        return if (end < 0) null else Bounds(id, start, end)
    }
}
