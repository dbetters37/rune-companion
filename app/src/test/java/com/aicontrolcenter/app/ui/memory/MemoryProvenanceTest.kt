package com.aicontrolcenter.app.ui.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceEvent
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How provenance and history are put into words, independent of any engine. */
class MemoryProvenanceTest {
    private val researcher = AgentProfile(AgentId("agt_r"), "Researcher", AgentKind.SPECIALIST, colorIndex = 1, createdAt = 0, updatedAt = 0)
    private val names = MemoryNames(agents = mapOf(researcher.id to researcher), models = mapOf(ModelId("mdl_l") to "Llama 3.2 3B"))
    private val at = 1_794_657_600_000L // 14 Nov 2026, 12:00 UTC

    private fun record(id: String, provenance: Provenance, history: List<ProvenanceEvent> = emptyList(), title: String = "Title $id") = MemoryRecord(
        MemoryId(id), MemoryScope.Agent(researcher.id), MemoryKind.NOTE, MemoryTier.LONG_TERM, title, "Content", provenance = provenance,
        createdAt = at, updatedAt = at, history = history,
    )

    @Test
    fun `an agent's memory from a chat names the agent, the chat and the model that answered`() {
        val memory = record(
            "mem_1",
            Provenance(ProvenanceOrigin.AGENT, agentId = researcher.id, conversationId = ConversationId("cnv_1"), modelId = ModelId("mdl_l"), modelName = "llama3.2:3b", recordedAt = at),
        )
        val ui = describeProvenance(memory, names, "Planning the trip", emptyMap(), ZoneOffset.UTC)

        assertEquals("Saved by Researcher during a chat, using Llama 3.2 3B (llama3.2:3b), on 14 Nov 2026 at 12:00.", ui.summary)
        val rows = ui.rows.associateBy { it.label }
        assertEquals(ProvenanceTarget.Agent(researcher.id), rows.getValue("Created by").target)
        assertEquals("Planning the trip", rows.getValue("Chat").value)
        assertEquals(ProvenanceTarget.Conversation(ConversationId("cnv_1")), rows.getValue("Chat").target)
    }

    @Test
    fun `a deleted chat, agent and model are named as gone rather than shown as ids`() {
        val memory = record(
            "mem_1",
            Provenance(ProvenanceOrigin.AGENT, agentId = AgentId("agt_gone"), conversationId = ConversationId("cnv_gone"), modelId = ModelId("mdl_gone"), recordedAt = at),
        )
        val ui = describeProvenance(memory, names, null, emptyMap(), ZoneOffset.UTC)
        val rows = ui.rows.associateBy { it.label }
        assertEquals(MemoryNames.DELETED_AGENT, rows.getValue("Created by").value)
        assertEquals("A chat that has since been deleted", rows.getValue("Chat").value)
        assertNull(rows.getValue("Chat").target, "nothing to open")
        assertEquals("a model no longer registered", rows.getValue("Model").value)
    }

    @Test
    fun `history tells what was done since, by whom, and links the memory it came from`() {
        val original = record("mem_orig", Provenance(ProvenanceOrigin.OWNER, recordedAt = at), title = "The original")
        val copy = record(
            "mem_copy",
            Provenance(ProvenanceOrigin.OWNER, recordedAt = at),
            history = listOf(
                ProvenanceEvent(ProvenanceAction.EDITED, ProvenanceOrigin.AGENT, researcher.id, at + 2_000, note = "content changed"),
                ProvenanceEvent(ProvenanceAction.SHARED_FROM, ProvenanceOrigin.OWNER, at = at + 1_000, relatedMemory = original.id),
                ProvenanceEvent(ProvenanceAction.PROMOTED_FROM, ProvenanceOrigin.SYSTEM, at = at + 3_000, relatedMemory = MemoryId("mem_gone")),
            ),
        )
        val ui = describeProvenance(copy, names, null, mapOf(original.id to original), ZoneOffset.UTC)

        assertEquals(
            listOf("Shared here by you, as a copy of", "Edited by Researcher: content changed", "Kept long-term by this app, from task memory"),
            ui.history.map { it.text },
            "oldest first",
        )
        assertEquals("The original", ui.history[0].related?.title)
        assertTrue(ui.history[2].related?.exists == false, "a source that was deleted is named as gone")
        assertEquals("14 Nov 2026, 12:00:01", ui.history[0].at)
    }

    @Test
    fun `a combined memory says how many it came from, and a source naming them is not repeated`() {
        val parts = listOf(MemoryId("mem_a"), MemoryId("mem_b"))
        val memory = record(
            "mem_sum",
            Provenance(ProvenanceOrigin.DERIVED, derivedFrom = parts, source = "Summary of mem_a and mem_b", recordedAt = at),
        )
        val ui = describeProvenance(memory, names, null, emptyMap(), ZoneOffset.UTC)
        assertTrue(ui.summary.startsWith("Combined from 2 memories"), ui.summary)
        assertTrue(ui.rows.none { it.label == "Source" })
        assertEquals(parts, ui.derivedFrom.map { it.id })
    }
}
