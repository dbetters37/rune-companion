package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.agents.currentAgentTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Lets an agent remember something: in its private memory by default, in a shared space its
 * memory policy lets it write, or, as a short-term note, in the working memory of the task it
 * is doing.
 *
 * The memory service checks the write against the agent's current profile and validates the
 * draft; its refusals come back to the model in its own words. Provenance records that an
 * agent saved it, which agent, in which task and conversation, and the model of the turn, read
 * from [com.aicontrolcenter.framework.agents.AgentTurnInfo] because the tool context does not
 * carry it.
 *
 * Where a short-term note goes: with a task running and no space named, into that task's
 * working memory; otherwise into the named scope with the short-term tier, so a note made in a
 * chat still expires.
 */
internal class MemorySaveTool(
    private val support: MemoryToolSupport,
    private val clock: Clock,
) : Tool {
    override val spec = ToolSpec(
        id = MemoryTools.SAVE_ID,
        functionName = "memory_save",
        title = "Save to memory",
        description = "Saves something worth remembering beyond this conversation: a fact about the owner, a preference, a decision, a finding or a procedure. " +
            "Saved to your private memory unless you name a shared space you may write. Save one self-contained item per call, " +
            "with a short title; never save secrets such as passwords or keys.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "A short label, a few words.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "The memory itself, complete enough to be understood later without this conversation.")
                }
                putJsonObject("kind") {
                    put("type", "string")
                    putJsonArray("enum") { MemoryKind.entries.forEach { add(it.name.lowercase()) } }
                    put("description", "What sort of memory it is. Default \"note\".")
                }
                putJsonObject("importance") {
                    put("type", "number")
                    put("minimum", 0)
                    put("maximum", 1)
                    put("description", "How much it matters, 0 to 1. Default $DEFAULT_IMPORTANCE.")
                }
                putJsonObject("tags") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                    put("description", "A few lower-case keywords.")
                }
                putJsonObject("where") {
                    put("type", "string")
                    put("description", "\"private\" (default) for your own memory, or the name of a shared space you may write.")
                }
                putJsonObject("short_term") {
                    put("type", "boolean")
                    put("description", "True for a working note that is only needed for the current task and may expire. Default false.")
                }
            }
            putJsonArray("required") { add("title"); add("content") }
            put("additionalProperties", false)
        },
        category = ToolCategory.MEMORY,
        capability = MemoryTools.WRITE,
        risk = RiskLevel.MODIFYING,
    )

    /** Names the title and the destination, never the content: the summary goes into the activity log. */
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = runCatching { MemoryArguments(arguments) }.getOrNull()
        val title = runCatching { args?.string("title") }.getOrNull()?.let(MemoryText::safeTitle)?.take(MAX_TITLE_SHOWN)
        val where = runCatching { args?.string("where") }.getOrNull()?.trim()?.takeUnless { it.isEmpty() || it.equals(PRIVATE, true) }
        val destination = where?.let { "the shared space \"${MemoryText.safeTitle(it).take(MAX_TITLE_SHOWN)}\"" } ?: "private memory"
        return ActionAssessment(RiskLevel.MODIFYING, if (title == null) "Save a memory to $destination" else "Save the memory \"$title\" to $destination")
    }

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome = support.guarded("The memory was not saved") {
        val args = MemoryArguments(arguments)
        val title = args.requiredText("title")
        val content = args.requiredText("content")
        val kindName = args.string("kind")?.trim()?.lowercase()?.ifEmpty { null }
        val kind = if (kindName == null) MemoryKind.NOTE else MemoryKind.entries.firstOrNull { it.name.lowercase() == kindName }
            ?: throw Refused("\"kind\" must be one of ${MemoryKind.entries.joinToString(", ") { it.name.lowercase() }}.")
        val importance = args.double("importance") ?: DEFAULT_IMPORTANCE
        if (importance !in 0.0..1.0) throw Refused("\"importance\" must be between 0 and 1.")
        val tags = args.strings("tags").orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val where = args.string("where")?.trim().orEmpty()
        val shortTerm = args.boolean("short_term") ?: false

        val (profile, actor) = support.actor(context)
        val inPrivate = where.isEmpty() || where.equals(PRIVATE, ignoreCase = true)
        val scope: MemoryScope = when {
            inPrivate && shortTerm && context.taskId != null -> MemoryScope.Task(context.taskId!!, profile.id)
            inPrivate -> MemoryScope.Agent(profile.id)
            else -> {
                val spaces = support.spaces()
                val space = support.find(spaces, where) ?: run {
                    val access = support.memory.access(actor)
                    val writable = spaces.filter { access.canWrite(MemoryScope.Shared(it.id)) }
                    return@guarded ToolOutcome.failure(
                        "The memory was not saved: there is no shared space called \"${MemoryText.safeTitle(where).take(MAX_TITLE_SHOWN)}\". " +
                            "Use \"private\" or one of these. ${support.spaceList(writable, "write")}",
                    )
                }
                MemoryScope.Shared(space.id)
            }
        }
        val turn = currentAgentTurn()
        val draft = MemoryDraft(
            scope = scope, kind = kind, title = title, content = content,
            tier = if (shortTerm) MemoryTier.SHORT_TERM else MemoryTier.LONG_TERM,
            tags = tags, importance = importance,
            provenance = Provenance(
                origin = ProvenanceOrigin.AGENT, agentId = profile.id, taskId = context.taskId,
                conversationId = turn?.conversationId, modelId = turn?.modelId, modelName = turn?.modelName,
                source = "Saved by ${profile.name}", recordedAt = clock.now(),
            ),
        )
        val saved = support.memory.remember(actor, draft)
        val names = if (saved.scope is MemoryScope.Shared) support.spaces().associate { it.id to it.name } else emptyMap()
        val tier = if (saved.tier == MemoryTier.SHORT_TERM) "short-term" else "long-term"
        ToolOutcome.success(
            "Saved \"${MemoryText.safeTitle(saved.title)}\" as memory ${saved.id.value} in ${MemoryText.scope(saved.scope, profile.id, names)} " +
                "(${MemoryText.kindName(saved)}, $tier).",
            buildJsonObject {
                put("id", saved.id.value)
                put("title", MemoryText.safeTitle(saved.title))
                put("kind", MemoryText.kindName(saved))
                put("tier", saved.tier.name)
            },
        )
    }

    private companion object {
        const val PRIVATE = "private"
        const val DEFAULT_IMPORTANCE = 0.5
        const val MAX_TITLE_SHOWN = 80
    }
}
