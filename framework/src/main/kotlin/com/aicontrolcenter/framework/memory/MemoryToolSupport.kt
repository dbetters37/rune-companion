package com.aicontrolcenter.framework.memory

import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the memory tools share: who is acting, which space a name means, and how the memory
 * service's refusals become answers a model can act on.
 *
 * Every failure text built here goes into the activity log as well as back to the model (the
 * tool executor logs the first part of a failed outcome), so none of them may contain memory
 * content or a search query: only argument names, ids, space names and the service's own
 * refusal sentences, which by the [MemoryService] contract carry no content either.
 */
internal class MemoryToolSupport(
    val memory: MemoryService,
    private val agents: AgentRepository,
) {
    /**
     * The calling agent as stored now. Permissions are read from the profile on every memory
     * call, so the tools never act on a copy the owner has since changed.
     * @throws Refused when the agent has been deleted.
     */
    suspend fun actor(context: ToolContext): Pair<AgentProfile, MemoryActor.Agent> {
        val profile = agents.get(context.agent.id) ?: throw Refused("${context.agent.name} no longer exists, so it cannot use memory.")
        return profile to MemoryActor.Agent(profile)
    }

    suspend fun spaces(): List<KnowledgeSpace> = memory.observeSpaces().first()

    /** The space called [name], compared trimmed and ignoring case, as the owner sees names. */
    fun find(spaces: List<KnowledgeSpace>, name: String): KnowledgeSpace? =
        spaces.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

    /** "Spaces you can …: A, B." or a sentence saying there are none. */
    fun spaceList(spaces: List<KnowledgeSpace>, verb: String): String =
        if (spaces.isEmpty()) "There are no shared spaces you can $verb."
        else "Shared spaces you can $verb: ${spaces.joinToString(", ") { "\"${MemoryText.safeTitle(it.name)}\"" }}."

    /**
     * Runs [block] and turns what the memory service refuses into a failed outcome: a
     * permission refusal or a validation problem is relayed in the service's own words, which
     * are written for agents. Anything else is reported by type only, because an unexpected
     * exception's message could quote stored text.
     */
    suspend fun guarded(action: String, block: suspend () -> ToolOutcome): ToolOutcome = try {
        block()
    } catch (e: Refused) {
        ToolOutcome.failure("$action: ${e.message}")
    } catch (e: MemoryPermissionException) {
        ToolOutcome.failure("$action: not allowed. ${reason(e)} Only the owner can change what an agent may read or write, in the agent's memory settings.")
    } catch (e: MemoryValidationException) {
        ToolOutcome.failure("$action: ${reason(e)} Correct the arguments and try again.")
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        ToolOutcome.failure("$action: the memory store failed (${e::class.simpleName}). Try again later, or go on without it.")
    }

    private fun reason(e: Exception): String =
        e.message?.trim()?.take(MAX_REASON)?.let { if (it.endsWith('.')) it else "$it." } ?: "No reason was given."

    private companion object {
        const val MAX_REASON = 300
    }
}

/**
 * Why a memory tool call cannot go ahead, said to the model: an argument that is missing or of
 * the wrong kind, or an agent that no longer exists. Names arguments, never their values.
 */
internal class Refused(message: String) : Exception(message)

/**
 * Typed reads of a call's arguments. The executor has already checked them against the schema,
 * but a tool must be correct when called directly too, so a wrong type is [Refused].
 */
internal class MemoryArguments(private val json: JsonObject) {
    private fun value(name: String) = json[name]?.takeUnless { it is JsonNull }

    fun string(name: String): String? = when (val v = value(name)) {
        null -> null
        is JsonPrimitive -> if (v.isString) v.content else throw Refused("\"$name\" must be a string.")
        else -> throw Refused("\"$name\" must be a string.")
    }

    fun requiredText(name: String): String =
        string(name)?.trim()?.takeIf { it.isNotEmpty() } ?: throw Refused("\"$name\" is required and must not be empty.")

    fun int(name: String): Int? = when (val v = value(name)) {
        null -> null
        is JsonPrimitive -> v.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.takeIf { d -> d % 1.0 == 0.0 }?.toLong() }
            ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
            ?: throw Refused("\"$name\" must be a whole number.")
        else -> throw Refused("\"$name\" must be a whole number.")
    }

    fun double(name: String): Double? = when (val v = value(name)) {
        null -> null
        is JsonPrimitive -> v.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            ?: throw Refused("\"$name\" must be a number.")
        else -> throw Refused("\"$name\" must be a number.")
    }

    fun boolean(name: String): Boolean? = when (val v = value(name)) {
        null -> null
        is JsonPrimitive -> v.takeUnless { it.isString }?.booleanOrNull ?: throw Refused("\"$name\" must be true or false.")
        else -> throw Refused("\"$name\" must be true or false.")
    }

    fun strings(name: String): List<String>? = when (val v = value(name)) {
        null -> null
        is JsonArray -> v.map { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Refused("\"$name\" must be a list of strings.")
        }
        else -> throw Refused("\"$name\" must be a list of strings.")
    }
}
