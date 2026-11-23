package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.MediaAccess
import com.aicontrolcenter.core.operator.MediaId
import com.aicontrolcenter.core.operator.MediaKind
import com.aicontrolcenter.core.operator.MediaQuery
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import com.aicontrolcenter.framework.operator.OperatorText.size
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale

/** The photo and video tools (`media.*`): metadata through [com.aicontrolcenter.core.operator.MediaLibrary], and explicit copies. */
internal object MediaTools {
    fun create(d: OperatorToolDependencies): List<Tool> = listOf(MediaList(d), MediaCopyToWorkspace(d))

    /** An ISO date ("2025-01-31", taken as UTC midnight) or instant ("2025-01-31T10:00:00Z") as epoch ms. */
    fun time(raw: String?, name: String): Long? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        return try {
            if (s.length == 10) LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() else Instant.parse(s).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ToolCallRejected("\"$name\" must be a date like 2025-01-31 or a time like 2025-01-31T10:00:00Z.")
        }
    }
}

/** media.list */
internal class MediaList(private val d: OperatorToolDependencies) : OperatorTool(
    "media.list", "List photos and videos",
    "Lists photos and videos on the phone, newest first: id, name, kind, date, size, dimensions and album. Never their content; " +
        "use media_copy_to_workspace for one item.",
    objectSchema {
        putJsonArrayOfKinds()
        stringProp("after", "Only items taken after this date or time (ISO 8601, e.g. 2025-01-31).")
        stringProp("before", "Only items taken before this date or time.")
        intProp("limit", "At most this many items. Default 50.", 1, 500)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val kinds = args.strings("kinds", 2, 10)?.map { k ->
            MediaKind.entries.firstOrNull { it.name.equals(k.trim(), ignoreCase = true) } ?: throw ToolCallRejected("\"kinds\" may contain \"image\" and \"video\".")
        }?.toSet()?.ifEmpty { null } ?: MediaKind.entries.toSet()
        val query = MediaQuery(kinds, MediaTools.time(args.string("after"), "after"), MediaTools.time(args.string("before"), "before"), args.int("limit", 50, 1..500))
        val what = kinds.joinToString(" and ") { if (it == MediaKind.IMAGE) "photos" else "videos" }
        return assessment("List $what on the phone (names, dates and sizes only)", prepared { list(query) })
    }

    private suspend fun list(query: MediaQuery): ToolOutcome {
        val items = when (val r = d.media.list(query)) {
            is DocResult.Ok -> r.value
            else -> return ToolOutcome.failure(OperatorText.problem(r, "Listing photos and videos"))
        }
        val partial = if (d.media.access() == MediaAccess.SELECTED_ONLY) " Android shows only the items the owner selected for this app." else ""
        if (items.isEmpty()) return ToolOutcome.success("No photos or videos match.$partial")
        val lines = items.joinToString("\n") { m ->
            listOfNotNull(
                m.id.value, q(m.displayName), m.kind.name.lowercase(Locale.ROOT), m.takenAt?.let { Instant.ofEpochMilli(it).toString() },
                if (m.widthPx != null && m.heightPx != null) "${m.widthPx}×${m.heightPx}" else null, m.durationMs?.let { "${it / 1000} s" },
                m.sizeBytes?.let { size(it) }, m.album?.let { "album ${q(it)}" },
            ).joinToString(" · ")
        }
        return ToolOutcome.success(
            "${OperatorText.count(items.size, "item")}, newest first (id · name · kind · taken · size):\n$lines$partial",
            buildJsonArray {
                items.forEach { m ->
                    addJsonObject {
                        put("id", m.id.value)
                        put("name", m.displayName)
                        put("kind", m.kind.name.lowercase(Locale.ROOT))
                        m.takenAt?.let { put("taken", Instant.ofEpochMilli(it).toString()) }
                        m.sizeBytes?.let { put("size", it) }
                    }
                }
            },
        )
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonArrayOfKinds() =
    stringArrayProp("kinds", "\"image\", \"video\" or both. Default both.", 2)

/** media.copy_to_workspace */
internal class MediaCopyToWorkspace(private val d: OperatorToolDependencies) : OperatorTool(
    "media.copy_to_workspace", "Copy a photo or video",
    "Copies one photo or video (by its id from media_list) into the workspace. Never replaces a workspace file.",
    objectSchema("id", "to") {
        stringProp("id", "The item's id from media_list.")
        stringProp("to", "Where to put it in the workspace, e.g. \"photos/beach.jpg\".")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val id = MediaId(args.text("id", 200)!!.trim())
        val to: RelativePath = args.entryPath("to")
        val summary = "Copy the photo or video ${q(id.value)} to the workspace as ${q(to.value)}"
        val target = WorkspaceStates.of(d.workspace, to)
        if (target.exists) return assessment(summary, failing("${q(to.value)} already exists in the workspace, and copies never replace a file."))
        return assessment(
            summary,
            prepared(check = { target.changeTo(WorkspaceStates.of(d.workspace, to), q(to.value)) }) {
                val file = when (val r = d.workspace.resolve(to)) {
                    is DocResult.Ok -> r.value
                    else -> return@prepared ToolOutcome.failure(OperatorText.problem(r, "Copying to ${q(to.value)}"))
                }
                when (val r = d.media.copyToWorkspace(id, file, d.limits.maxCopyBytes)) {
                    is DocResult.Ok -> ToolOutcome.success("Copied ${id.value} to ${q(to.value)} (${size(r.value)}).")
                    else -> ToolOutcome.failure(OperatorText.problem(r, "Copying ${id.value}"))
                }
            },
        )
    }
}
