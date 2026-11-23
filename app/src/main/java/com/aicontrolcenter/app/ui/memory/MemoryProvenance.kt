package com.aicontrolcenter.app.ui.memory

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.components.formatFullDateTime
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Somewhere a provenance fact leads: the agent that saved it, or the chat it came from. */
sealed interface ProvenanceTarget {
    data class Agent(val id: AgentId) : ProvenanceTarget
    data class Conversation(val id: ConversationId) : ProvenanceTarget
}

/** One labelled fact about where a memory came from. */
@Immutable
data class ProvenanceRowUi(val label: String, val value: String, val target: ProvenanceTarget? = null)

/** Another memory as a reference. [title] null: it was deleted, so there is nothing to open. */
@Immutable
data class MemoryRefUi(val id: MemoryId, val title: String?, val scope: ScopeUi? = null) {
    val exists: Boolean get() = title != null
}

/** One thing done to a memory after it was written: "Edited by you: content changed", with when, and the memory it involved. */
@Immutable
data class HistoryRowUi(val text: String, val at: String, val related: MemoryRefUi? = null)

/**
 * Where a memory came from, in plain words: one sentence on how its text was first written, the
 * facts behind it, its sources, and what was done to it since (oldest first).
 */
@Immutable
data class ProvenanceUi(
    /** "Saved by Researcher during a chat, using Llama 3.2 3B, on 14 Nov 2026 at 13:44." */
    val summary: String,
    val rows: List<ProvenanceRowUi>,
    val derivedFrom: List<MemoryRefUi>,
    val history: List<HistoryRowUi> = emptyList(),
)

private val sentenceDate = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH)

/**
 * Describes [record]'s provenance for the owner.
 *
 * @param conversationTitle the title of the chat it came from; null when it has none or the chat was deleted.
 * @param memories titles of other memories by id, for "derived from".
 */
internal fun describeProvenance(
    record: MemoryRecord,
    names: MemoryNames,
    conversationTitle: String?,
    memories: Map<MemoryId, MemoryRecord>,
    zone: ZoneId,
): ProvenanceUi {
    val p = record.provenance
    val agent = p.agentId?.let(names::agentName)
    val model = modelText(p, names)
    val summary = buildString {
        append(
            when (p.origin) {
                ProvenanceOrigin.OWNER -> "Written by you"
                ProvenanceOrigin.AGENT -> "Saved by ${agent ?: "an agent"}"
                ProvenanceOrigin.TOOL -> if (agent != null) "Recorded from a tool result by $agent" else "Recorded from a tool result"
                ProvenanceOrigin.SYSTEM -> "Created by this app"
                ProvenanceOrigin.DERIVED -> derivedPhrase(p) + (if (agent != null) " by $agent" else "")
                ProvenanceOrigin.IMPORT -> "Imported from outside this app"
            },
        )
        // A copy keeps its original's chat and task; "during a chat" would claim the copy was made there.
        if (p.origin != ProvenanceOrigin.DERIVED) {
            when {
                p.conversationId != null -> append(" during a chat")
                p.taskId != null -> append(" while working on a task")
            }
        }
        if (model != null) append(", using ").append(model)
        append(", on ").append(sentenceDate.format(Instant.ofEpochMilli(p.recordedAt).atZone(zone))).append('.')
    }
    val rows = buildList {
        add(ProvenanceRowUi("Created by", creator(p, agent), p.agentId?.let { ProvenanceTarget.Agent(it) }))
        p.conversationId?.let { id ->
            add(ProvenanceRowUi("Chat", conversationTitle ?: "A chat that has since been deleted", ProvenanceTarget.Conversation(id).takeIf { conversationTitle != null }))
        }
        p.taskId?.let { add(ProvenanceRowUi("Task", it.value)) }
        if (model != null) add(ProvenanceRowUi("Model", model))
        // A source naming the memories it came from repeats "Derived from" below, with ids instead of titles.
        p.source?.takeIf { it.isNotBlank() && p.derivedFrom.none { id -> id.value in it } }?.let { add(ProvenanceRowUi("Source", it)) }
        add(ProvenanceRowUi("Recorded", formatFullDateTime(p.recordedAt, zone)))
    }
    fun ref(id: MemoryId): MemoryRefUi = memories[id].let { MemoryRefUi(id, it?.title, it?.let { found -> names.scope(found.scope) }) }
    val history = record.history.sortedBy { it.at }.map { event ->
        val who = when (event.byOrigin) {
            ProvenanceOrigin.OWNER -> "you"
            ProvenanceOrigin.AGENT -> event.byAgentId?.let(names::agentName) ?: "an agent"
            ProvenanceOrigin.SYSTEM -> "this app"
            else -> event.byAgentId?.let(names::agentName) ?: "this app"
        }
        val text = when (event.action) {
            ProvenanceAction.EDITED -> "Edited by $who" + (event.note?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            ProvenanceAction.SHARED_FROM -> "Shared here by $who, as a copy of"
            ProvenanceAction.PROMOTED_FROM -> "Kept long-term by $who, from task memory"
        }
        HistoryRowUi(text, formatFullDateTime(event.at, zone), event.relatedMemory?.let(::ref))
    }
    return ProvenanceUi(summary, rows, p.derivedFrom.distinct().map(::ref), history)
}

private fun derivedPhrase(p: Provenance): String =
    if (p.derivedFrom.size <= 1) "Copied from another memory" else "Combined from ${p.derivedFrom.size} memories"

private fun creator(p: Provenance, agent: String?): String = when (p.origin) {
    ProvenanceOrigin.OWNER -> "You"
    ProvenanceOrigin.AGENT -> agent ?: "An agent"
    ProvenanceOrigin.TOOL -> if (agent != null) "A tool used by $agent" else "A tool"
    ProvenanceOrigin.SYSTEM -> "This app"
    ProvenanceOrigin.DERIVED -> agent ?: derivedPhrase(p)
    ProvenanceOrigin.IMPORT -> "An import"
}

/** The registered model's name, and the name the provider answered with when that differs. */
private fun modelText(p: Provenance, names: MemoryNames): String? {
    val registered = p.modelId?.let { names.models[it] ?: "a model no longer registered" }
    val answered = p.modelName?.takeIf { it.isNotBlank() }
    return when {
        registered != null && answered != null && !registered.equals(answered, ignoreCase = true) -> "$registered ($answered)"
        registered != null -> registered
        else -> answered
    }
}
