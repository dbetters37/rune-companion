package com.aicontrolcenter.data.db

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.core.usage.WorkItem
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.data.StoredDataException
import com.aicontrolcenter.data.db.entity.MemoryEntity
import com.aicontrolcenter.data.db.entity.MemoryRow
import com.aicontrolcenter.data.db.entity.UsageHourEntity
import com.aicontrolcenter.data.db.mapper.addedTo
import com.aicontrolcenter.data.db.mapper.level
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.contract.MemorySamples
import com.aicontrolcenter.testkit.contract.RoutineSamples
import com.aicontrolcenter.testkit.contract.TaskSamples
import com.aicontrolcenter.testkit.contract.UsageSamples
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

    @Test
    fun `a conversation and its entries keep what is queried on as plain columns and the message as JSON`() {
        val conversation = Conversation(ConversationId("cnv_1"), AgentId("agt_sample"), "Chat", createdAt = 10, updatedAt = 20).toEntity()
        assertEquals("agt_sample", conversation.agentId)
        assertEquals(20L, conversation.updatedAt)
        assertEquals(Conversation(ConversationId("cnv_1"), AgentId("agt_sample"), "Chat", 10, 20), conversation.toDomain())

        val entry = ConversationEntry(
            ConversationId("cnv_1"), seq = 4, ChatMessage.assistant("Hi"), at = 30, modelName = "model-x",
            usage = TokenUsage(120, 30, 100), error = "The provider is overloaded.",
        )
        val stored = entry.toEntity()
        assertEquals("cnv_1", stored.conversationId)
        assertEquals(4, stored.seq)
        assertEquals(30L, stored.at)
        assertEquals("model-x", stored.modelName)
        assertEquals("The provider is overloaded.", stored.error)
        assertEquals(
            setOf("role", "text", "images", "toolCalls", "toolResult", "reasoning", "providerData"),
            Json.parseToJsonElement(stored.messageJson).jsonObject.keys,
        )
        assertEquals(entry, stored.toDomain())

        val plain = entry.copy(modelName = null, usage = null, error = null)
        assertNull(plain.toEntity().usageJson)
        assertEquals(plain, plain.toEntity().toDomain())
    }

    @Test
    fun `an unreadable message is reported with its conversation and number but without the content`() {
        val stored = ConversationEntry(ConversationId("cnv_1"), seq = 4, ChatMessage.user("private"), at = 30).toEntity()
        val failure = assertFailsWith<StoredDataException> { stored.copy(messageJson = """{"role":"USER","text":"private""").toDomain() }
        assertEquals("Column conversation_entries.message_json of row 'cnv_1#4' does not hold readable JSON.", failure.message)
        assertFailsWith<StoredDataException> { stored.copy(usageJson = "[]").toDomain() }
    }

    @Test
    fun `a task keeps its retry time and the length of its result in characters`() {
        val task = TaskSamples.work("tsk_1").copy(result = "Résumé 📝", retryAt = 9_000)
        val stored = task.toEntity()
        assertEquals(9_000L, stored.retryAt)
        assertEquals(8, stored.resultChars, "characters, not UTF-16 units: the emoji is one")
        assertEquals(0, task.copy(result = null).toEntity().resultChars)
        assertEquals(task, stored.toDomain())
        // A row written before schema 6 reads with no retry time.
        assertNull(stored.copy(retryAt = null).toDomain().retryAt)
    }

    @Test
    fun `a call is summed into its hour row with the empty agent for none, and into its work rows`() {
        val at = UsageSamples.at("2026-11-25T10:20")
        val first = UsageSamples.call(at, agent = null, source = UsageSource.OTHER)
        val row = first.addedTo(stored = null)
        assertEquals(UsageHourEntity.NO_AGENT, row.agentId)
        assertEquals(first.hourStart, row.hourStart)
        assertEquals("OTHER", row.source)
        assertEquals("CLOUD", row.locality)
        assertEquals(at, row.lastCallAt)
        val failed = UsageSamples.call(at - 60_000, agent = null, source = UsageSource.OTHER, outcome = CallOutcome.FAILED, error = ErrorClass.TIMEOUT,
            input = null, output = null, cached = null, label = "Renamed", locality = Locality.LOCAL_NETWORK)
        val summed = failed.addedTo(row)
        assertEquals(2L, summed.counts.calls)
        assertEquals(1L, summed.counts.errTimeout)
        assertEquals(listOf("Renamed", "LOCAL_NETWORK"), listOf(summed.modelLabel, summed.locality), "the labels of the call recorded last")
        assertEquals(at, summed.lastCallAt, "the newest call's time stays")
        val work = first.addedTo(WorkItem(WorkKind.TASK, "tsk_1"), stored = null)
        assertEquals(listOf("TASK", "tsk_1", "mdl_grok"), listOf(work.workKind, work.workId, work.modelId))
        assertEquals(listOf(1L, 1_000L, 100L, 400L), listOf(work.calls, work.inputTokens, work.outputTokens, work.cachedInputTokens))
    }

    @Test
    fun `a price keeps a missing cached price as missing, and an impossible stored price is unreadable`() {
        val price = ModelPrice(ModelId("mdl_1"), 3_000_000, 15_000_000, cachedInputPerMTokMicros = null, currencyLabel = "€", updatedAt = 7)
        assertNull(price.toEntity().cachedInputPerMTokMicros)
        assertEquals(price, price.toEntity().toDomain())
        val failure = assertFailsWith<StoredDataException> { price.toEntity().copy(inputPerMTokMicros = -1).toDomain() }
        assertEquals("Row 'mdl_1' of model_prices holds values that contradict each other.", failure.message)
    }

    @Test
    fun `a routine keeps its schedule and options as JSON, and a run or import that cannot be read is reported`() {
        val routine = RoutineSamples.routine("rtn_1")
        val stored = routine.toEntity()
        assertEquals("""{"type":"daily","minuteOfDay":420,"days":["MONDAY","FRIDAY"]}""", stored.scheduleJson)
        assertTrue(""""routineId":"rtn_1"""" in stored.optionsJson, stored.optionsJson)
        assertEquals(routine, stored.toDomain())
        val badSchedule = assertFailsWith<StoredDataException> { stored.copy(scheduleJson = """{"type":"monthly"}""").toDomain() }
        assertEquals("Column routines.schedule_json of row 'rtn_1' does not hold readable JSON.", badSchedule.message)
        val run = RoutineSamples.run("rrn_1", "rtn_1", dueAt = 5).toEntity()
        assertEquals("STARTED", run.outcome)
        val badOutcome = assertFailsWith<StoredDataException> { run.copy(outcome = "POSTPONED").toDomain() }
        assertEquals("Column routine_runs.outcome of row 'rrn_1' holds the unknown value 'POSTPONED'.", badOutcome.message)
        assertEquals(RoutineRunOutcome.MISSED, RoutineSamples.run("rrn_2", "rtn_1", 5, RoutineRunOutcome.MISSED).toEntity().toDomain().outcome)
        val import = RoutineSamples.file("pku_1", 1).toEntity()
        assertEquals(RoutineSamples.file("pku_1", 1), import.toDomain())
        assertFailsWith<StoredDataException> { import.copy(contentSha256 = "NOT-A-HASH").toDomain() }
        val rule = RoutineSamples.rule("pku_1")
        assertEquals(rule, rule.toEntity().toDomain())
    }

    private fun MemoryEntity.toRow() = MemoryRow(
        id, scopeType, scopeAgentId, scopeSpaceId, scopeTaskId, kind, tier, title, content, tagsJson, importance, confidence,
        provenanceJson, createdAt, updatedAt, lastAccessedAt, accessCount, expiresAt, revision, historyJson,
    )

    @Test
    fun `a memory keeps scope, kind, tier and times as plain columns, and tags and provenance as JSON`() {
        val record = MemorySamples.memory(
            "mem_1", MemorySamples.task, updatedAt = 500, kind = MemoryKind.DECISION, tier = MemoryTier.SHORT_TERM,
            title = "Ölpreis", content = "STEIGT", tags = setOf("markt"), expiresAt = 9_000,
        )
        val stored = record.toEntity()
        assertEquals("TASK", stored.scopeType)
        assertEquals("agt_researcher", stored.scopeAgentId)
        assertNull(stored.scopeSpaceId)
        assertEquals("tsk_1", stored.scopeTaskId)
        assertEquals("DECISION", stored.kind)
        assertEquals("SHORT_TERM", stored.tier)
        assertEquals(500L, stored.updatedAt)
        assertEquals(9_000L, stored.expiresAt)
        assertEquals("""["markt"]""", stored.tagsJson)
        assertEquals(
            setOf("origin", "agentId", "taskId", "conversationId", "modelId", "modelName", "derivedFrom", "source", "recordedAt"),
            Json.parseToJsonElement(stored.provenanceJson).jsonObject.keys,
        )
        assertEquals("olpreis\nsteigt\nmarkt", stored.searchText)
        assertEquals(
            listOf(listOf("action", "byOrigin", "byAgentId", "at", "relatedMemory", "note")),
            Json.parseToJsonElement(stored.historyJson).jsonArray.map { it.jsonObject.keys.toList() }.distinct(),
        )
        assertEquals(record, stored.toRow().toDomain())
        assertEquals(2, record.history.size, "the sample has a history, so the round trip covers it")

        val shared = MemorySamples.memory("mem_2", MemorySamples.commons).toEntity()
        assertEquals(listOf("SHARED", null, "spc_commons", null), listOf(shared.scopeType, shared.scopeAgentId, shared.scopeSpaceId, shared.scopeTaskId))
    }

    @Test
    fun `an unreadable memory or link is reported with table, column and row but without the content`() {
        val stored = MemorySamples.memory("mem_1", content = "the owner's secret").toEntity().toRow()
        val failures = listOf(
            stored.copy(scopeType = "GALAXY"),
            stored.copy(scopeAgentId = null),
            stored.copy(tier = "FOREVER"),
            stored.copy(provenanceJson = """{"origin":"AGENT","""),
            stored.copy(tagsJson = "[1"),
            stored.copy(historyJson = """[{"action":"EDITED","""),
        ).map { assertFailsWith<StoredDataException> { it.toDomain() }.message!! }
        assertEquals(
            listOf(
                "Column memories.scope_type of row 'mem_1' holds the unknown value 'GALAXY'.",
                "Column memories.scope_agent_id of row 'mem_1' is empty, but private memory has an agent.",
                "Column memories.tier of row 'mem_1' holds the unknown value 'FOREVER'.",
                "Column memories.provenance_json of row 'mem_1' does not hold readable JSON.",
                "Column memories.tags_json of row 'mem_1' does not hold readable JSON.",
                "Column memories.history_json of row 'mem_1' does not hold readable JSON.",
            ),
            failures,
        )
        assertTrue(failures.none { "secret" in it })
        // A kind added by a later version reads as a note instead (see MemoryKind).
        assertEquals(MemoryKind.NOTE, stored.copy(kind = "DREAM").toDomain().kind)

        val link = MemorySamples.link("lnk_1", "mem_1", "mem_2").toEntity()
        assertEquals(MemorySamples.link("lnk_1", "mem_1", "mem_2"), link.toDomain())
        val badRelation = assertFailsWith<StoredDataException> { link.copy(relation = "ENTANGLED").toDomain() }
        assertEquals("Column memory_links.relation of row 'lnk_1' holds the unknown value 'ENTANGLED'.", badRelation.message)
    }
}
