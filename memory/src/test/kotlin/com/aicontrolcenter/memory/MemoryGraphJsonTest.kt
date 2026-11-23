package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.memory.MemoryWorld.Companion.private
import com.aicontrolcenter.memory.MemoryWorld.Companion.shared
import com.aicontrolcenter.memory.MemoryWorld.Companion.task
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryGraphJsonTest {
    private suspend fun sampleSnapshot(): com.aicontrolcenter.core.memory.MemoryGraphSnapshot {
        val world = MemoryWorld()
        val team = world.space("Team")
        val agent = world.agent("agt_a", read = setOf(team.id), write = setOf(team.id))
        val a = world.remember(agent, private(agent), "Quote \"marks\"", "Line one\nline two, with emoji 🧠 and ünïcödé", tags = setOf("x", "y"))
        val b = world.remember(agent, shared(team), "Shared", "Shared content")
        world.remember(agent, task(agent, "tsk_1"), "Working", "Task content")
        world.service.link(agent, a.id, b.id, LinkRelation.SUPPORTS, 0.25)
        world.service.share(agent, a.id, team.id)
        return world.service.graph(agent, GraphRequest(includeTaskMemory = true))
    }

    @Test
    fun `a snapshot survives a round trip unchanged, compact or pretty`() = runTest {
        val snapshot = sampleSnapshot()
        assertTrue(snapshot.nodes.size == 4 && snapshot.edges.size == 2 && snapshot.clusters.isNotEmpty())
        assertEquals(snapshot, MemoryGraphJson.decode(MemoryGraphJson.encode(snapshot)))
        assertEquals(snapshot, MemoryGraphJson.decode(MemoryGraphJson.encode(snapshot, prettyPrint = true)))
        val root = Json.parseToJsonElement(MemoryGraphJson.encode(snapshot)).jsonObject
        assertEquals("\"aicc.memory-graph\"", root["schema"].toString())
        assertEquals("1", root["schemaVersion"].toString())
        // Nulls are left out rather than written: a shared node has no owner field at all.
        val compact = MemoryGraphJson.encode(snapshot)
        assertFalse("\"ownerAgentId\":null" in compact)
        assertTrue("\"ownerAgentId\":\"agt_a\"" in compact)
    }

    @Test
    fun `unknown fields are ignored and missing optional ones get neutral defaults`() {
        val document = """
            {
              "schema": "aicc.memory-graph", "schemaVersion": 1, "graphId": "graph:1", "generatedAt": 1795348800000,
              "scope": {"kind": "merged"}, "tombstones": [{"id": "mem:old", "deletedAt": 1}],
              "generator": {"app": "lifelong-pet", "appVersion": "9", "exporter": "x", "build": 7},
              "nodes": [
                {"id": "mem:1", "kind": "memory", "label": "Loves Earl Grey", "createdAt": 1795262400000,
                 "category": "Preference", "layout": {"x": 0.1, "y": 0.2},
                 "provenance": {"origin": "llm_extraction", "method": "structured_output", "model": {"providerId": "p", "modelId": "m"}}},
                {"id": "sum:2", "kind": "summary", "label": "Evening routine", "content": "Tea and quiet music", "createdAt": 5,
                 "provenance": {"origin": "agent", "recordedAt": 6}},
                "not a node"
              ],
              "edges": [
                {"id": "edge:1", "source": "sum:2", "target": "mem:1", "relation": "DERIVED_FROM", "reinforcementCount": 3},
                {"id": "edge:2", "source": "sum:2", "target": "mem:missing", "relation": "MENTIONS"}
              ],
              "clusters": [{"id": "cl:lobe:LIMBIC", "kind": "lobe", "color": "#D946EF"}]
            }
        """.trimIndent()
        val snapshot = MemoryGraphJson.decode(document)
        val (memory, summary) = snapshot.nodes
        assertEquals(2, snapshot.nodes.size)
        assertEquals("Loves Earl Grey", memory.content)
        assertEquals(emptyList(), memory.tags)
        assertEquals(1795262400000, memory.updatedAt)
        assertEquals(ProvenanceOrigin.IMPORT, memory.provenance.origin)
        assertEquals(1795262400000, memory.provenance.recordedAt)
        assertEquals(ProvenanceOrigin.AGENT, summary.provenance.origin)
        assertEquals(6, summary.provenance.recordedAt)
        assertEquals(listOf("edge:1"), snapshot.edges.map { it.id }, "edges must resolve to two nodes")
        assertTrue(snapshot.edges.single().directed)
        assertEquals(1795348800000, snapshot.edges.single().createdAt)
        assertEquals("cl:lobe:LIMBIC", snapshot.clusters.single().label)
        assertEquals(JsonObject(emptyMap()), memory.attrs)
    }

    @Test
    fun `documents of another schema, another version or no JSON at all are refused without quoting them`() {
        val secret = "the owner's diary entry"
        val cases = listOf(
            """{"schema": "something-else", "schemaVersion": 1, "generatedAt": 1, "nodes": []}""",
            """{"schemaVersion": 1, "generatedAt": 1, "nodes": []}""",
            """{"schema": "aicc.memory-graph", "schemaVersion": 2, "generatedAt": 1, "nodes": []}""",
            """{"schema": "aicc.memory-graph", "generatedAt": 1}""",
            """{"schema": "aicc.memory-graph", "schemaVersion": 1, "nodes": [{"id": "mem:1", "label": "$secret"}]}""",
            """{"schema": "aicc.memory-graph", "schemaVersion": 1, "generatedAt": 1, "nodes": [{"label": "$secret" """,
            """["$secret"]""",
        )
        for (document in cases) {
            val error = assertFailsWith<MemoryGraphFormatException>(document) { MemoryGraphJson.decode(document) }
            assertFalse(secret in error.message.orEmpty(), error.message)
            assertEquals(null, error.cause)
        }
        assertTrue("version 2" in assertFailsWith<MemoryGraphFormatException> { MemoryGraphJson.decode(cases[2]) }.message!!)
    }
}
