package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lets an agent put a copy of one memory it can read into a shared space it may write. The
 * original stays where it was; the memory service links the copy to it and records where it
 * came from. Sharing is always one memory at a time and always explicit: nothing an agent
 * remembers becomes visible to others unless it is shared like this or saved into a space.
 *
 * Not switched on for any agent by default; the owner decides who may share.
 *
 * The confirmation names memories by id only. It is stored with the conversation as it is, and
 * the call it answers names the memory by id too, so nothing of a memory's text stays in the
 * conversation once the memory is forgotten.
 */
internal class MemoryShareTool(private val support: MemoryToolSupport) : Tool {
    override val spec = ToolSpec(
        id = MemoryTools.SHARE_ID,
        functionName = "memory_share",
        title = "Share a memory",
        description = "Copies one of your memories into a shared space so other agents with access to that space can use it. " +
            "The original stays where it is. Give the memory's id (from memory_search) and the space's name.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("memory_id") {
                    put("type", "string")
                    put("description", "The id of the memory to share, such as \"mem_ab12\".")
                }
                putJsonObject("space") {
                    put("type", "string")
                    put("description", "The name of the shared space to copy it into.")
                }
            }
            putJsonArray("required") { add("memory_id"); add("space") }
            put("additionalProperties", false)
        },
        category = ToolCategory.MEMORY,
        capability = MemoryTools.WRITE,
        risk = RiskLevel.MODIFYING,
    )

    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = MemoryArguments(arguments)
        val id = runCatching { args.string("memory_id") }.getOrNull()?.trim()?.take(MAX_ECHO)
        val space = runCatching { args.string("space") }.getOrNull()?.let(MemoryText::safeTitle)?.take(MAX_ECHO)
        val summary = if (id != null && space != null) "Share memory $id into the shared space \"$space\"" else "Share a memory into a shared space"
        return ActionAssessment(RiskLevel.MODIFYING, summary)
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome = support.guarded("The memory was not shared") {
        val args = MemoryArguments(arguments)
        val id = MemoryId(args.requiredText("memory_id").trim('"', '\'', '[', ']', ' '))
        val spaceName = args.requiredText("space")
        val (profile, actor) = support.actor(context)
        val original = support.memory.get(actor, id) ?: return@guarded ToolOutcome.failure(
            "The memory was not shared: no memory with the id \"${id.value.take(MAX_ECHO)}\" is stored where ${profile.name} can read it. " +
                "Use memory_search to find the id.",
        )
        val spaces = support.spaces()
        val space = support.find(spaces, spaceName) ?: run {
            val access = support.memory.access(actor)
            val writable = spaces.filter { access.canWrite(MemoryScope.Shared(it.id)) }
            return@guarded ToolOutcome.failure(
                "The memory was not shared: there is no shared space called \"${MemoryText.safeTitle(spaceName).take(MAX_ECHO)}\". " +
                    support.spaceList(writable, "write"),
            )
        }
        val copy = support.memory.share(actor, original.id, space.id)
        val names = spaces.associate { it.id to it.name }
        // Ids only, no title: this confirmation is stored with the conversation and must not keep memory text after the memory is gone.
        ToolOutcome.success(
            "Shared memory ${original.id.value} into the shared space \"${MemoryText.safeTitle(space.name)}\" as memory ${copy.id.value}. " +
                "The original stays in ${MemoryText.scope(original.scope, profile.id, names)}.",
            buildJsonObject {
                put("id", copy.id.value)
                put("originalId", original.id.value)
                put("spaceId", space.id.value)
            },
        )
    }

    private companion object {
        const val MAX_ECHO = 60
    }
}
