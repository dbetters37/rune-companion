package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.OperatorToolInfo
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.PreviewText
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.PreparedAction
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * A call whose arguments cannot be turned into an action (a malformed path, an unknown folder
 * name, a refused link). Thrown from [Tool.assess] only for problems that depend on the
 * arguments and the owner's configuration, never on file, folder or screen content, so it
 * reveals nothing before the call is authorised; `ToolExecutor` returns the message to the model
 * without running anything. Inside [PreparedCall.action] it becomes a failed outcome.
 */
open class ToolCallRejected(message: String) : RuntimeException(message)

/**
 * A problem with what a call would act on (a missing file, a folder whose access was withdrawn),
 * found while assessing it. Unlike [ToolCallRejected] it depends on content, so it is never told
 * to the model before the call is authorised: [OperatorTool.orFailing] turns it into a call that
 * fails with this message when it runs.
 */
class StateProblem(message: String) : ToolCallRejected(message)

/**
 * What an operator tool's [Tool.assess] resolved: [check] re-verifies what the assessment relied
 * on, and [action] does exactly what the assessment described, on the targets captured when it
 * was built. Owned by the tool that made it; another tool's prepared call is refused.
 */
class PreparedCall internal constructor(
    internal val tool: Tool,
    private val check: suspend () -> String?,
    internal val action: suspend (ToolContext) -> ToolOutcome,
    /** The re-check right after the owner answered ([PreparedAction.changedSinceOwnerAnswered]); null: [check]. */
    private val afterOwner: (suspend () -> String?)? = null,
) : PreparedAction {
    override suspend fun changedSince(): String? = check()

    override suspend fun changedSinceOwnerAnswered(): String? = afterOwner?.invoke() ?: check()

    internal companion object {
        /** A call that will fail with [reason] whatever happens (its target was missing when assessed); it changes nothing. */
        fun failing(tool: Tool, reason: String) = PreparedCall(tool, { null }, { ToolOutcome.failure(reason) })
    }
}

/**
 * Base of every operator tool: the spec comes from [OperatorTools.info] (capability, declared
 * risk, category and `sendsDataOffDevice` cannot drift from the catalogue), and execution goes
 * only through the assessment that was authorised. The two-argument [execute] refuses: an
 * operator tool never acts on a fresh lookup.
 */
abstract class OperatorTool(
    id: String,
    title: String,
    description: String,
    parameters: JsonObject,
) : Tool {
    /** The catalogue entry this tool implements. */
    val info: OperatorToolInfo = requireNotNull(OperatorTools.info(ToolId(id))) { "$id is not in OperatorTools.all" }

    final override val spec: ToolSpec = ToolSpec(
        id = info.id,
        functionName = id.replace('.', '_'),
        title = title,
        description = description,
        parameters = parameters,
        category = info.category,
        capability = info.capability,
        risk = info.risk,
        sendsDataOffDevice = info.sendsDataOffDevice,
    )

    /** Must prepare the call ([ActionAssessment.prepared] is a [PreparedCall] of this tool). */
    abstract override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment

    final override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome = ToolOutcome.failure(NOT_ASSESSED)

    final override suspend fun execute(arguments: JsonObject, context: ToolContext, assessment: ActionAssessment): ToolOutcome {
        val prepared = assessment.prepared as? PreparedCall
        if (prepared == null || prepared.tool !== this) return ToolOutcome.failure(NOT_ASSESSED)
        return try {
            prepared.changedSince()?.let { return ToolOutcome.changedSinceApproved(it) }
            prepared.action(context)
        } catch (e: ToolCallRejected) {
            // Includes StateProblem: what was found when acting, said after the call was authorised.
            ToolOutcome.failure(e.message ?: "The call was refused.")
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            ToolOutcome.failure("${spec.title} failed (${e::class.simpleName}).")
        }
    }

    /** An assessment of this tool's call; [risk] is raised to the declared risk, never lowered. */
    protected fun assessment(
        summary: String,
        prepared: PreparedCall,
        details: List<String> = emptyList(),
        risk: RiskLevel = spec.risk,
        constraint: CapabilityConstraint = CapabilityConstraint.None,
        dataGoesTo: String? = null,
        leadsTo: String? = null,
    ) = ActionAssessment(
        risk = maxOf(risk, spec.risk), summary = summary, details = details, constraint = constraint,
        dataGoesTo = dataGoesTo, prepared = prepared, leadsTo = leadsTo,
    )

    /** A prepared call of this tool; [afterOwner] replaces [check] right after the owner answered a request for it. */
    protected fun prepared(
        check: suspend () -> String? = { null },
        afterOwner: (suspend () -> String?)? = null,
        action: suspend (ToolContext) -> ToolOutcome,
    ) = PreparedCall(this, check, action, afterOwner)

    protected fun failing(reason: String) = PreparedCall.failing(this, reason)

    /** [block]'s assessment, or, when it meets a [StateProblem], one that fails with it when run. */
    protected suspend fun orFailing(
        summary: String,
        constraint: CapabilityConstraint = CapabilityConstraint.None,
        dataGoesTo: String? = null,
        block: suspend () -> ActionAssessment,
    ): ActionAssessment = try {
        block()
    } catch (e: StateProblem) {
        assessment(summary, failing(e.message ?: "The target is not available."), constraint = constraint, dataGoesTo = dataGoesTo)
    }

    internal companion object {
        const val NOT_ASSESSED = "Nothing was done: this operator tool runs only with the assessment it was checked and approved with."
    }
}

/** Reads one call's arguments; every problem is a [ToolCallRejected] naming the argument. */
internal class Args(private val json: JsonObject) {
    private fun value(name: String): JsonElement? = json[name]?.takeUnless { it is JsonNull }

    fun string(name: String): String? {
        val v = value(name) ?: return null
        if (v !is JsonPrimitive || !v.isString) throw ToolCallRejected("\"$name\" must be a string.")
        return v.content
    }

    fun requireString(name: String): String = string(name) ?: throw ToolCallRejected("\"$name\" is required.")

    /** A non-blank string, at most [maxChars] characters. */
    fun text(name: String, maxChars: Int, required: Boolean = true): String? {
        val s = if (required) requireString(name) else string(name) ?: return null
        if (required && s.isBlank()) throw ToolCallRejected("\"$name\" is empty.")
        if (s.length > maxChars) throw ToolCallRejected("\"$name\" is longer than $maxChars characters.")
        return s
    }

    fun long(name: String, default: Long, range: LongRange): Long {
        val v = value(name) ?: return default
        val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            ?: (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
            ?: throw ToolCallRejected("\"$name\" must be a whole number.")
        if (n !in range) throw ToolCallRejected("\"$name\" must be between ${range.first} and ${range.last}.")
        return n
    }

    fun int(name: String, default: Int, range: IntRange): Int = long(name, default.toLong(), range.first.toLong()..range.last.toLong()).toInt()

    fun optionalInt(name: String, range: IntRange): Int? = if (value(name) == null) null else int(name, 0, range)

    fun bool(name: String, default: Boolean): Boolean {
        val v = value(name) ?: return default
        return (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw ToolCallRejected("\"$name\" must be true or false.")
    }

    fun strings(name: String, maxItems: Int, maxChars: Int): List<String>? {
        val v = value(name) ?: return null
        if (v !is JsonArray) throw ToolCallRejected("\"$name\" must be an array of strings.")
        if (v.size > maxItems) throw ToolCallRejected("\"$name\" may hold at most $maxItems items.")
        return v.map { item ->
            val p = item as? JsonPrimitive
            if (p == null || !p.isString) throw ToolCallRejected("Every item of \"$name\" must be a string.")
            if (p.content.length > maxChars) throw ToolCallRejected("An item of \"$name\" is longer than $maxChars characters.")
            p.content
        }
    }

    fun element(name: String): JsonElement? = value(name)

    /** A path relative to its root; [default] when absent, or rejected if there is none. */
    fun path(name: String, default: RelativePath? = null): RelativePath {
        val raw = string(name) ?: return default ?: throw ToolCallRejected("\"$name\" is required.")
        return when (val check = RelativePath.parse(raw)) {
            is RelativePath.Check.Valid -> check.path
            is RelativePath.Check.Invalid -> throw ToolCallRejected("\"$name\": ${check.reason}")
        }
    }

    /** A path that names a file or folder, not the root itself. */
    fun entryPath(name: String): RelativePath = path(name).also { if (it.isRoot) throw ToolCallRejected("\"$name\" must name a file or folder, not the top level.") }

    companion object {
        /** [raw] as a path that names a file or folder; problems name the argument [name]. */
        fun entryPathOf(raw: String, name: String): RelativePath = when (val check = RelativePath.parse(raw)) {
            is RelativePath.Check.Valid -> check.path.also { if (it.isRoot) throw ToolCallRejected("\"$name\" must name files or folders, not the top level.") }
            is RelativePath.Check.Invalid -> throw ToolCallRejected("\"$name\": ${check.reason}")
        }
    }

    fun has(name: String): Boolean = value(name) != null
}

/** Builds a closed JSON Schema object. */
internal fun objectSchema(vararg required: String, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties", properties)
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    put("additionalProperties", false)
}

internal fun JsonObjectBuilder.stringProp(name: String, description: String, enum: List<String>? = null) = putJsonObject(name) {
    put("type", "string")
    put("description", description)
    if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
}

internal fun JsonObjectBuilder.intProp(name: String, description: String, minimum: Long, maximum: Long) = putJsonObject(name) {
    put("type", "integer")
    put("minimum", minimum)
    put("maximum", maximum)
    put("description", description)
}

internal fun JsonObjectBuilder.boolProp(name: String, description: String) = putJsonObject(name) {
    put("type", "boolean")
    put("description", description)
}

internal fun JsonObjectBuilder.stringArrayProp(name: String, description: String, maxItems: Int) = putJsonObject(name) {
    put("type", "array")
    putJsonObject("items") { put("type", "string") }
    put("maxItems", maxItems)
    put("description", description)
}

/** Text helpers shared by the operator tools. */
internal object OperatorText {
    /** A model-chosen value as the owner must see it: exact and quoted. */
    fun q(value: String): String = "\"${PreviewText.exact(value)}\""

    /** A workspace or folder path for messages; the top level is named as such. */
    fun path(path: RelativePath, root: String = "the workspace"): String = if (path.isRoot) "the top level of $root" else q(path.value)

    fun size(bytes: Long?): String = when {
        bytes == null -> "unknown size"
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    fun count(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

    /** A failed [DocResult] in words the model can act on; [what] names the target. */
    fun problem(result: DocResult<*>, what: String): String = when (result) {
        is DocResult.Ok -> "$what: done."
        is DocResult.NotFound -> "$what: there is nothing at ${q(result.path)}."
        is DocResult.AlreadyExists -> "$what: something already exists at ${q(result.path)}."
        is DocResult.TooLarge -> "$what: ${size(result.sizeBytes)} is more than the limit of ${size(result.limitBytes)}."
        is DocResult.NotAllowed -> "$what: not allowed. ${result.reason}"
        is DocResult.Ambiguous -> "$what: ${result.count} entries are called ${q(result.path)}, so which one is meant is unknown. Nothing was done."
        is DocResult.Failed -> "$what: ${result.reason}"
    }

    /** Decodes [bytes] as UTF-8 text, or null when they are not text (malformed UTF-8 or NUL bytes). A sequence cut at the end is dropped. */
    fun decodeText(bytes: ByteArray, cutAtEnd: Boolean): String? {
        if (bytes.any { it == 0.toByte() }) return null
        var end = bytes.size
        if (cutAtEnd) {
            // Drop an incomplete UTF-8 sequence at the end (at most three bytes).
            var i = end - 1
            var back = 0
            while (i >= 0 && back < 4 && (bytes[i].toInt() and 0xC0) == 0x80) { i--; back++ }
            if (i >= 0) {
                val lead = bytes[i].toInt() and 0xFF
                val need = when {
                    lead >= 0xF0 -> 4
                    lead >= 0xE0 -> 3
                    lead >= 0xC0 -> 2
                    else -> 1
                }
                if (need > back + 1) end = i
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, end)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /** True for names that are read as HTML and shown as text. */
    fun isHtml(name: String): Boolean = name.lowercase(Locale.ROOT).let { it.endsWith(".html") || it.endsWith(".htm") || it.endsWith(".xhtml") }
}

/** MIME types by file extension, for listings and sharing. */
internal object MimeTypes {
    private val byExtension = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown", "csv" to "text/csv",
        "tsv" to "text/tab-separated-values", "json" to "application/json", "html" to "text/html", "htm" to "text/html",
        "xhtml" to "application/xhtml+xml", "xml" to "text/xml", "js" to "text/javascript", "css" to "text/css",
        "log" to "text/plain", "yaml" to "text/yaml", "yml" to "text/yaml", "sh" to "text/x-shellscript",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "heic" to "image/heic", "svg" to "image/svg+xml", "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4", "ogg" to "audio/ogg", "wav" to "audio/wav", "pdf" to "application/pdf", "zip" to "application/zip",
        "apk" to "application/vnd.android.package-archive",
    )

    fun of(name: String): String = byExtension[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"
}
