package com.aicontrolcenter.app.di.wiring

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.data.backup.RestoreChecks
import com.aicontrolcenter.framework.agents.DefaultAgentManager
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import com.aicontrolcenter.framework.llm.DefaultProviderManager
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.memory.DefaultMemoryService
import com.aicontrolcenter.memory.HashingEmbedder
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySecretVault
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

/**
 * A restore checks every record like the owner's own input (rule 8), with `RestoreChecks` in `:data`, which cannot call
 * the editors' validators (they live in `:framework` and `:memory`, two of them internal). This pins that the two agree:
 * the same bounds, and the same verdict on the same provider, memory and space, at each edge. The one difference is
 * deliberate: a cloud provider without a key is accepted on restore (keys are never in a backup).
 */
class RestoreChecksAgreementTest {
    private val clock = FakeClock()
    private val ids = SequentialIdGenerator()
    private val activity = ActivityRecorder(InMemoryActivityLog(), ids, clock)

    @Test
    fun `agent and chat bounds are the editors' own`() {
        assertEquals(DefaultAgentManager.MAX_NAME_LENGTH, RestoreChecks.AGENT_NAME_CHARS)
        assertEquals(DefaultAgentManager.MAX_OUTPUT_TOKENS, RestoreChecks.AGENT_MAX_OUTPUT_TOKENS)
        assertEquals(DefaultAgentManager.MAX_RECALL_LIMIT, RestoreChecks.AGENT_MAX_RECALL)
        assertEquals(DefaultAgentManager.MAX_DELEGATION_DEPTH, RestoreChecks.AGENT_MAX_DELEGATION_DEPTH)
        assertEquals(DefaultAgentChat.MAX_TITLE_LENGTH, RestoreChecks.CHAT_TITLE_CHARS)
    }

    @Test
    fun `providers are judged as the provider form judges them`() = runBlocking<Unit> {
        val providers = InMemoryProviderRepository()
        val vault = InMemorySecretVault()
        val manager = DefaultProviderManager(
            providers, InMemoryModelRepository(), ReadOnlySettings(InMemorySettingsRepository()), vault,
            ProviderConnections(providers, vault, emptyList(), clock), activity, ids, clock,
        )
        val cases = listOf(
            ProviderDraft("x".repeat(60), ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1"),
            ProviderDraft("x".repeat(61), ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1"),
            ProviderDraft("  ", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1"),
            ProviderDraft("Plain", ProviderKind.OPENAI_COMPATIBLE, "http://example.com/v1"),
            ProviderDraft("Home", ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.20:8080/v1"),
            ProviderDraft("Here", ProviderKind.OLLAMA, "http://localhost:11434"),
            ProviderDraft("User", ProviderKind.OPENAI_COMPATIBLE, "https://me:pw@example.com/v1"),
            ProviderDraft("Query", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1?key=1"),
            ProviderDraft("Fragment", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1#x"),
            ProviderDraft("Port", ProviderKind.OPENAI_COMPATIBLE, "https://example.com:70000/v1"),
            ProviderDraft("Scheme", ProviderKind.OPENAI_COMPATIBLE, "ftp://example.com/v1"),
            ProviderDraft("Spaces", ProviderKind.OPENAI_COMPATIBLE, "https://exa mple.com/v1"),
            ProviderDraft("Nothing", ProviderKind.OPENAI_COMPATIBLE, null),
            ProviderDraft("Ollama", ProviderKind.OLLAMA, "  "),
            ProviderDraft("Device", ProviderKind.ON_DEVICE, "https://example.com"),
            ProviderDraft("Sim", ProviderKind.SIMULATOR, null),
            ProviderDraft("Header", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1", extraHeaders = mapOf("X-Title" to "AI Control Center")),
            ProviderDraft("Break", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1", extraHeaders = mapOf("X-Title" to "a\r\nHost: evil")),
            ProviderDraft("Long", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1", extraHeaders = mapOf("X-Title" to "v".repeat(1_001))),
            ProviderDraft("Unicode", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1", extraHeaders = mapOf("HTTP-Referer" to "café")),
            ProviderDraft("Name", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1", extraHeaders = mapOf("X Title" to "a")),
            ProviderDraft("Twice", ProviderKind.OPENAI_COMPATIBLE, "https://example.com/v1", extraHeaders = mapOf("X-Title" to "a", "x-title" to "b")),
        )
        for (draft in cases) {
            val editor = manager.validate(draft).isEmpty()
            val record = ModelProvider(
                id = ProviderId("prv_case"), name = draft.name, kind = draft.kind, baseUrl = draft.baseUrl, extraHeaders = draft.extraHeaders,
                locality = Locality.infer(draft.kind, draft.baseUrl), createdAt = 1, updatedAt = 1,
            )
            val restore = RestoreChecks.provider(record, others = emptyList()) == null
            assertEquals(editor, restore, "$draft: the editor says ${manager.validate(draft)}, the restore ${RestoreChecks.provider(record, emptyList())}")
        }
        // The deliberate difference: a cloud Anthropic provider without its key.
        val anthropic = ProviderDraft("Claude", ProviderKind.ANTHROPIC)
        assertEquals(1, manager.validate(anthropic).size)
        assertEquals(null, RestoreChecks.provider(ModelProvider(ProviderId("p"), "Claude", ProviderKind.ANTHROPIC, locality = Locality.CLOUD, createdAt = 1, updatedAt = 1), emptyList()))
    }

    @Test
    fun `memories and spaces are judged as the memory service judges them`() = runBlocking<Unit> {
        val memory = DefaultMemoryService(
            InMemoryMemoryRepository(), InMemoryAgentRepository(), ReadOnlySettings(InMemorySettingsRepository()), HashingEmbedder(), activity, ids, clock,
            computeContext = EmptyCoroutineContext,
        )
        val space = memory.createSpace(MemoryActor.Owner, "Team")
        val provenance = Provenance(ProvenanceOrigin.OWNER, recordedAt = 1)
        fun draft(title: String = "t", content: String = "c", tags: Set<String> = emptySet(), importance: Double = 0.5) =
            MemoryDraft(MemoryScope.Shared(space.id), MemoryKind.NOTE, title, content, tags = tags, importance = importance, provenance = provenance)
        val cases = listOf(
            draft(title = "x".repeat(200)), draft(title = "x".repeat(201)), draft(title = " "),
            draft(content = "x".repeat(20_000)), draft(content = "x".repeat(20_001)), draft(content = "\n"),
            draft(tags = (1..20).map { "t$it" }.toSet()), draft(tags = (1..21).map { "t$it" }.toSet()),
            draft(tags = setOf("x".repeat(40))), draft(tags = setOf("x".repeat(41))),
            draft(importance = 1.0), draft(importance = 1.01), draft(importance = -0.01),
        )
        for (case in cases) {
            val editor = runCatching { memory.remember(MemoryActor.Owner, case) }.isSuccess
            val record = MemoryRecord(
                MemoryId("mem_case"), case.scope, case.kind, MemoryTier.LONG_TERM, case.title, case.content, tags = case.tags,
                importance = case.importance, provenance = provenance, createdAt = 1, updatedAt = 1,
            )
            assertEquals(editor, RestoreChecks.memory(record) == null, "title ${case.title.length}, content ${case.content.length}, ${case.tags.size} tags, ${case.importance}")
        }
        val names = mutableListOf(RestoreChecks.spaceNameKey(space.name))
        val spaceCases = listOf(
            "x".repeat(80) to "", "x".repeat(81) to "", " " to "", "ok" to "d".repeat(1_000), "other" to "d".repeat(1_001),
            " TEAM " to "", "team" to "", "Team two" to "",
        )
        for ((name, description) in spaceCases) {
            val editor = runCatching { memory.createSpace(MemoryActor.Owner, name, description) }.isSuccess
            val restore = RestoreChecks.space(KnowledgeSpace(SpaceId("spc_case"), name, description, 1, 1), names) == null
            assertEquals(editor, restore, "space \"${name.take(12)}\" ${name.length}/${description.length}")
            if (editor) names += RestoreChecks.spaceNameKey(name)
        }
        // Not about bounds: an agent's id must be one an id generator could make.
        assertEquals(null, RestoreChecks.id(AgentId("agt_0123").value, "an agent"))
    }
}
