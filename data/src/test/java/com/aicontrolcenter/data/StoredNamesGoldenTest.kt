package com.aicontrolcenter.data

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.agents.GenerationSettings
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.mapper.ColumnSerializers
import com.aicontrolcenter.data.db.mapper.encodeColumn
import com.aicontrolcenter.data.db.mapper.level
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.VaultFileStore
import com.aicontrolcenter.data.settings.DataStoreSettingsRepository
import com.aicontrolcenter.data.settings.SettingsCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins every name that ends up in stored data: enum constants written to text columns and
 * into JSON, the keys of the JSON objects, and the names of the files, preference keys and
 * keystore entries themselves.
 *
 * These names live in `:core` and in this module's constants, where renaming one looks like a
 * harmless refactoring. On a phone that already holds data it is not: rows stop decoding,
 * settings fall back to defaults, secrets and the database become unreachable. So a change to
 * any of them fails here first.
 *
 * When this test fails:
 * - a name was **added**: add it to the list below. Old data stays readable, no migration.
 * - a name was **renamed or removed**: stored data still uses the old name. Write a database
 *   migration (or a settings/vault conversion) first, then update the list.
 */
class StoredNamesGoldenTest {

    @Test
    fun `enum names stored in text columns and JSON`() {
        assertStoredNames<AgentKind>("DIRECTOR", "SPECIALIST", "REVIEWER", "OPERATOR")
        assertStoredNames<ProviderKind>("OPENAI_COMPATIBLE", "ANTHROPIC", "GEMINI", "OLLAMA", "ON_DEVICE", "SIMULATOR")
        assertStoredNames<Locality>("ON_DEVICE", "LOCAL_NETWORK", "CLOUD")
        assertStoredNames<ConnectionStatus>("UNKNOWN", "ONLINE", "OFFLINE", "AUTH_ERROR", "ERROR")
        assertStoredNames<ModelSource>("PROVIDER_LISTING", "MANUAL", "IMPORTED_FILE", "BUILT_IN")
        assertStoredNames<ModelStatus>("UNKNOWN", "AVAILABLE", "UNAVAILABLE")
        assertStoredNames<CapabilityTier>("UNKNOWN", "BASIC", "GOOD", "STRONG")
        assertStoredNames<DiscoverySource>("CATALOG", "INTENT_QUERY", "HEURISTIC", "MANUAL")
        assertStoredNames<AiAppStatus>("INSTALLED", "DISABLED_BY_SYSTEM", "NOT_INSTALLED")
        assertStoredNames<AiAppCapability>(
            "LAUNCH", "SHARE_TEXT", "SHARE_IMAGE", "PROCESS_TEXT", "ASSISTANT", "PROMPT_DEEP_LINK", "LOCAL_MODEL_HOST",
        )
        assertStoredNames<ActivityType>(
            "SYSTEM", "USER_ACTION", "REGISTRY", "MESSAGE", "MODEL_CALL", "TOOL_CALL", "PERMISSION", "APPROVAL",
            "TASK", "MEMORY", "DATA_EGRESS", "ERROR",
        )
    }

    @Test
    fun `severity is stored as a number per level`() {
        assertEquals(
            mapOf("INFO" to 10, "WARNING" to 20, "ERROR" to 30),
            Severity.entries.associate { it.name to it.level },
        )
    }

    @Test
    fun `keys of the JSON objects stored in columns`() {
        assertStoredKeys(GenerationSettings.serializer(), "temperature", "maxOutputTokens")
        assertStoredKeys(
            MemoryPolicy.serializer(),
            "privateMemoryEnabled", "readableSpaces", "writableSpaces", "autoRecall", "recallLimit",
        )
        assertStoredKeys(DelegationPolicy.serializer(), "canDelegate", "canRequestAssistance", "maxDepth")
        assertStoredKeys(
            ModelCapabilities.serializer(),
            "chat", "toolCalling", "vision", "streaming", "jsonOutput", "embeddings", "reasoning", "coding",
            "contextWindow", "maxOutputTokens",
        )
        assertStoredKeys(EgressInfo.serializer(), "destinationHost", "locality", "providerId", "purpose", "approxBytes")
    }

    @Test
    fun `exact text of every kind of JSON column`() {
        assertEquals(
            """{"temperature":0.4,"maxOutputTokens":2048}""",
            encodeColumn(GenerationSettings.serializer(), GenerationSettings(temperature = 0.4, maxOutputTokens = 2048)),
        )
        assertEquals(
            """{"privateMemoryEnabled":false,"readableSpaces":["spc_a","spc_b"],"writableSpaces":[],"autoRecall":true,"recallLimit":4}""",
            encodeColumn(
                MemoryPolicy.serializer(),
                MemoryPolicy(
                    privateMemoryEnabled = false, readableSpaces = setOf(SpaceId("spc_a"), SpaceId("spc_b")),
                    writableSpaces = emptySet(), autoRecall = true, recallLimit = 4,
                ),
            ),
        )
        assertEquals(
            """{"canDelegate":true,"canRequestAssistance":false,"maxDepth":2}""",
            encodeColumn(DelegationPolicy.serializer(), DelegationPolicy(canDelegate = true, canRequestAssistance = false, maxDepth = 2)),
        )
        assertEquals(
            """{"chat":true,"toolCalling":true,"vision":false,"streaming":true,"jsonOutput":false,"embeddings":false,""" +
                """"reasoning":"GOOD","coding":"UNKNOWN","contextWindow":8192,"maxOutputTokens":null}""",
            encodeColumn(
                ModelCapabilities.serializer(),
                ModelCapabilities(toolCalling = true, reasoning = CapabilityTier.GOOD, contextWindow = 8192),
            ),
        )
        assertEquals(
            """{"destinationHost":"api.example.com","locality":"CLOUD","providerId":"prv_1","purpose":"prompt","approxBytes":2048}""",
            encodeColumn(
                EgressInfo.serializer(),
                EgressInfo("api.example.com", Locality.CLOUD, ProviderId("prv_1"), purpose = "prompt", approxBytes = 2048),
            ),
        )
        assertEquals("""["b, \"quoted\"","a",""]""", encodeColumn(ColumnSerializers.stringList, listOf("b, \"quoted\"", "a", "")))
        assertEquals("""["research","analysis"]""", encodeColumn(ColumnSerializers.stringSet, setOf("research", "analysis")))
        assertEquals("""{"X-Title":"AI Control Center"}""", encodeColumn(ColumnSerializers.stringMap, mapOf("X-Title" to "AI Control Center")))
        assertEquals("""["mdl_z","mdl_a"]""", encodeColumn(ColumnSerializers.modelIdList, listOf(ModelId("mdl_z"), ModelId("mdl_a"))))
        assertEquals("""["memory.search"]""", encodeColumn(ColumnSerializers.toolIdSet, setOf(ToolId("memory.search"))))
        assertEquals(
            """["LAUNCH","SHARE_TEXT"]""",
            encodeColumn(ColumnSerializers.appCapabilitySet, setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT)),
        )
    }

    @Test
    fun `keys and text of the stored settings`() {
        assertStoredKeys(
            FrameworkSettings.serializer(),
            "localOnlyMode", "activityRetentionDays", "directorAgentId", "maxConcurrentTasks", "maxDelegationDepth",
            "maxTasksPerObjective", "maxMessagesPerObjective", "maxModelCallsPerTask", "maxToolCallsPerTask",
            "maxRevisionRounds", "maxTaskAttempts", "taskTimeoutMinutes", "objectiveTimeoutMinutes",
            "approvalTimeoutMinutes", "autoResumeInterruptedTasks", "operatorEnabled", "accessibilityOperatorEnabled",
            "onboardingCompleted", "seedVersion",
        )
        // The defaults are deliberately not pinned as text: changing a default is not a format change.
        val stored = SettingsCodec.encode(FrameworkSettings(localOnlyMode = true, seedVersion = 3))
        assertEquals(true, stored.startsWith("""{"localOnlyMode":true,"activityRetentionDays":"""), stored)
        assertEquals(true, stored.endsWith(""","onboardingCompleted":false,"seedVersion":3}"""), stored)
    }

    @Test
    fun `keys of the vault index file`() {
        assertStoredKeys(VaultFileStore.VaultIndex.serializer(), "version", "entries")
        assertEquals(
            """{"version":1,"entries":{"provider/a":"AAAA"}}""",
            DataJson.encodeToString(VaultFileStore.VaultIndex.serializer(), VaultFileStore.VaultIndex(1, mapOf("provider/a" to "AAAA"))),
        )
        assertEquals(1, VaultFileStore.FORMAT_VERSION)
    }

    @Test
    fun `names of files, preference keys and keystore entries`() {
        assertEquals("aicc.db", AiccDatabase.DEFAULT_NAME)
        assertEquals("aicc_settings", DataStoreSettingsRepository.DEFAULT_FILE_NAME)
        assertEquals("framework_settings", DataStoreSettingsRepository.SETTINGS_KEY.name)
        assertEquals("framework_settings_unreadable", DataStoreSettingsRepository.UNREADABLE_COPY_KEY.name)
        assertEquals("vault/secrets.json", DataLayer.VAULT_FILE)
        assertEquals("database-keys/aicc.db.key", DataLayer.databaseKeyFile(AiccDatabase.DEFAULT_NAME))
        assertEquals("aicc.secret-vault.v1", AndroidKeystoreVaultCipher.DEFAULT_KEY_ALIAS)
        assertEquals("aicc.database-key.v1", AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS)
    }

    /**
     * Checks both spellings of an enum: the constant name, which the mappers write to text
     * columns, and the serial name, which is what appears inside JSON columns.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified E : Enum<E>> assertStoredNames(vararg expected: String) {
        val type = E::class.simpleName
        assertEquals(expected.toSet(), enumValues<E>().map { it.name }.toSet(), "$type constants (text columns)")
        assertEquals(expected.toSet(), serializer<E>().descriptor.elementNames.toSet(), "$type serial names (JSON columns)")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun assertStoredKeys(serializer: KSerializer<*>, vararg expected: String) {
        assertEquals(expected.toList(), serializer.descriptor.elementNames.toList(), "JSON keys of ${serializer.descriptor.serialName}")
    }
}
