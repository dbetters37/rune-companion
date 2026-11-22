package com.aicontrolcenter.data.db

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.data.StoredDataException
import com.aicontrolcenter.data.db.mapper.level
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.testkit.Samples
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What exactly lands in the columns, and how rows written by other versions are read. */
class StoredColumnsTest {

    @Test
    fun `values that are filtered, ordered or joined on are plain columns`() {
        val agent = Samples.agent().toEntity()
        assertEquals("agt_sample", agent.id)
        assertEquals("SPECIALIST", agent.kind)
        assertEquals("mdl_sample", agent.modelId)
        assertEquals(1_000L, agent.createdAt)
        assertTrue(agent.enabled)

        val provider = Samples.provider().toEntity()
        assertEquals("OPENAI_COMPATIBLE", provider.kind)
        assertEquals("LOCAL_NETWORK", provider.locality)
        assertEquals("ONLINE", provider.status)
        assertEquals("provider/prv_sample", provider.apiKeyAlias)

        val model = Samples.model().toEntity()
        assertEquals("prv_sample", model.providerId)
        assertEquals("PROVIDER_LISTING", model.source)
        assertEquals("AVAILABLE", model.status)

        val app = Samples.aiApp().toEntity()
        assertEquals("com.example.chatbot", app.packageName)
        assertEquals("CATALOG", app.source)
        assertEquals("INSTALLED", app.status)
        assertEquals("prv_sample", app.linkedProviderId)
        assertTrue(app.accessibilityAuthorized)
        assertEquals(500L, app.firstInstallTime)
        assertEquals("ab".repeat(32), app.signingCertSha256)
        assertTrue(app.verifiedByUser)
        assertFalse(app.hidden)

        val event = Samples.event().toEntity()
        assertEquals("MODEL_CALL", event.type)
        assertEquals("agt_sample", event.agentId)
        assertEquals("tsk_child", event.taskId)
        assertEquals("tsk_root", event.rootTaskId)
        assertEquals(Severity.INFO.level, event.severity)
        assertTrue(event.hasEgress)
    }

    @Test
    fun `the egress flag follows the egress info`() {
        val without = Samples.event(egress = false).toEntity()
        assertFalse(without.hasEgress)
        assertNull(without.egressJson)
        assertTrue(Samples.event(egress = true).toEntity().egressJson!!.contains("192.168.1.20"))
    }

    @Test
    fun `severity levels grow with severity so that a minimum is a range`() {
        val levels = Severity.entries.map { it.level }
        assertEquals(levels.sorted(), levels)
        assertEquals(levels.size, levels.toSet().size)
    }

    @Test
    fun `nested objects are stored with every field, including defaults`() {
        val entity = Samples.agent().copy(generation = GenerationSettings(), memory = MemoryPolicy()).toEntity()
        assertEquals(setOf("temperature", "maxOutputTokens"), Json.parseToJsonElement(entity.generationJson).jsonObject.keys)
        assertEquals(
            setOf("privateMemoryEnabled", "readableSpaces", "writableSpaces", "autoRecall", "recallLimit"),
            Json.parseToJsonElement(entity.memoryJson).jsonObject.keys,
        )
    }

    @Test
    fun `a nested object written before a field existed, or with a field this build lacks, still loads`() {
        val stored = Samples.agent().toEntity().copy(
            memoryJson = """{"privateMemoryEnabled":false,"addedInALaterVersion":{"x":1}}""",
            generationJson = "{}",
        )
        val agent = stored.toDomain()
        assertEquals(MemoryPolicy(privateMemoryEnabled = false), agent.memory)
        assertEquals(GenerationSettings(), agent.generation)
    }

    @Test
    fun `unreadable JSON is reported with table, column and row but without the content`() {
        val stored = Samples.agent().toEntity().copy(memoryJson = """{"privateMemoryEnabled": tru""")
        val failure = assertFailsWith<StoredDataException> { stored.toDomain() }
        assertEquals("Column agents.memory_json of row 'agt_sample' does not hold readable JSON.", failure.message)
    }

    @Test
    fun `an unknown enum name or severity level is reported, not guessed`() {
        val badKind = assertFailsWith<StoredDataException> { Samples.agent().toEntity().copy(kind = "OVERLORD").toDomain() }
        assertEquals("Column agents.kind of row 'agt_sample' holds the unknown value 'OVERLORD'.", badKind.message)

        assertFailsWith<StoredDataException> { Samples.provider().toEntity().copy(locality = "MOON").toDomain() }
        assertFailsWith<StoredDataException> { Samples.model().toEntity().copy(status = "").toDomain() }
        assertFailsWith<StoredDataException> { Samples.aiApp().toEntity().copy(capabilitiesJson = """["TELEPATHY"]""").toDomain() }

        val badLevel = assertFailsWith<StoredDataException> { Samples.event().toEntity().copy(severity = 15).toDomain() }
        assertEquals("Column activity_events.severity of row 'evt_sample' holds the unknown level 15.", badLevel.message)
    }

    @Test
    fun `stats keep 64-bit token counts`() {
        val big = AgentStats(inputTokens = Long.MAX_VALUE, outputTokens = 6_000_000_000, totalTaskDurationMs = Long.MAX_VALUE - 1)
        assertEquals(big, big.toEntity(AgentId("agt_big")).toDomain())
    }
}
