package com.aicontrolcenter.core.backup

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.operator.AnswerCheck
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.usage.ModelPrice
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/*
 * The records of a backup file's body (format `aicc-backup` v1, FROZEN; `phase6-contracts.md`
 * section 4; security review of wave 1, F13 and the second review's FORMAT item). The JSON keys,
 * their types and the enum spellings of every record are those of the classes below and of the
 * core classes they hold (`AgentProfile`, `AgentStats`, `ModelProvider`, `ModelDescriptor`,
 * `AiApp`, `KnowledgeSpace`, `MemoryRecord`, `MemoryLink`, `Conversation`), as
 * [BackupRecords.JSON] writes them; `BackupRecordsGoldenTest` pins every key path and every enum
 * spelling, so a change to any of these classes that would change the file fails a test first.
 * Times are milliseconds since 1970 (UTC); money is millionths of the currency unit; sizes bytes.
 *
 * What a save leaves out of each record, and what a restore does with it, are the pure functions
 * of [BackupRecords]: WP3 calls them and adds no rule of its own.
 */

/** How many of each thing a backup holds (or a restore wrote): the manifest's `"counts"` object, exactly [KEYS]. */
@Serializable
data class BackupCounts(
    val agents: Int = 0,
    val providers: Int = 0,
    val models: Int = 0,
    val modelPrices: Int = 0,
    val aiApps: Int = 0,
    val spaces: Int = 0,
    val memories: Int = 0,
    val memoryLinks: Int = 0,
    val conversations: Int = 0,
    val conversationEntries: Int = 0,
) {
    companion object {
        /** The manifest's count keys, in the order a save writes them. */
        val KEYS: List<String> = listOf(
            "agents", "providers", "models", "modelPrices", "aiApps", "spaces", "memories", "memoryLinks", "conversations", "conversationEntries",
        )
    }
}

/** Who wrote a conversation entry in the file: the owner, or the agent (its reply text, and a line for each tool it used). */
@Serializable
enum class EntryAuthor {
    @SerialName("owner")
    OWNER,

    @SerialName("agent")
    AGENT,
}

/**
 * One record of the body: a 4-byte length and this as UTF-8 JSON, its `"type"` the [SerialName]
 * below. The first record is [Manifest], the last [End]; the others come in the order of section 4.
 */
@Serializable
sealed interface BackupRecord {
    /** First: what the file holds and the allow-listed settings ([BackupSettings]). */
    @Serializable
    @SerialName("manifest")
    data class Manifest(val counts: BackupCounts, val settings: BackupSettings) : BackupRecord

    /** An agent and its track record. */
    @Serializable
    @SerialName("agent")
    data class Agent(val agent: AgentProfile, val stats: AgentStats = AgentStats()) : BackupRecord

    /** A provider as [BackupRecords.providerForSave] leaves it: no key alias, no status, only the allowed headers. */
    @Serializable
    @SerialName("provider")
    data class Provider(val provider: ModelProvider) : BackupRecord

    @Serializable
    @SerialName("model")
    data class Model(val model: ModelDescriptor) : BackupRecord

    /** A model's price ([ModelPrice]): millionths of [currencyLabel] per million tokens. */
    @Serializable
    @SerialName("modelPrice")
    data class Price(
        val modelId: ModelId,
        val inputPerMTokMicros: Long,
        val outputPerMTokMicros: Long,
        val cachedInputPerMTokMicros: Long? = null,
        val currencyLabel: String = ModelPrice.DEFAULT_CURRENCY,
        val updatedAt: Long = 0,
    ) : BackupRecord {
        /** The price, or null when the file's values are not a valid price (the restore drops it and counts it). */
        fun toPrice(): ModelPrice? = runCatching {
            ModelPrice(modelId, inputPerMTokMicros, outputPerMTokMicros, cachedInputPerMTokMicros, currencyLabel, updatedAt)
        }.getOrNull()

        companion object {
            fun of(price: ModelPrice) = Price(
                price.modelId, price.inputPerMTokMicros, price.outputPerMTokMicros, price.cachedInputPerMTokMicros, price.currencyLabel, price.updatedAt,
            )
        }
    }

    /** An AI app as [BackupRecords.aiAppForSave] leaves it: nothing that describes the install on this phone. */
    @Serializable
    @SerialName("aiApp")
    data class App(val app: AiApp) : BackupRecord

    @Serializable
    @SerialName("space")
    data class Space(val space: KnowledgeSpace) : BackupRecord

    /** A memory of Agent or Shared scope, with its provenance and history (no embedding: the phone indexes it again). */
    @Serializable
    @SerialName("memory")
    data class Memory(val memory: MemoryRecord) : BackupRecord

    @Serializable
    @SerialName("memoryLink")
    data class Link(val link: MemoryLink) : BackupRecord

    @Serializable
    @SerialName("conversation")
    data class Chat(val conversation: Conversation) : BackupRecord

    /** One entry of a conversation as [BackupRecords.entryForSave] makes it: who wrote it and its text. */
    @Serializable
    @SerialName("entry")
    data class Entry(
        val conversationId: ConversationId,
        val seq: Int,
        val author: EntryAuthor,
        val text: String,
        val at: Long,
        val modelName: String? = null,
    ) : BackupRecord

    /** Last: the number of records before it, the manifest included. */
    @Serializable
    @SerialName("end")
    data class End(val records: Int) : BackupRecord
}

/** What [BackupRecords.providerForSave] made of a provider. */
data class ProviderForSave(
    /** The record to write, or null when the provider is left out (its address looks like it holds a key). */
    val record: BackupRecord.Provider?,
    /** How many of its extra headers were left out. */
    val headersLeftOut: Int,
)

/** The rules of what goes into a backup file and what comes out of it (rule 8; F13). Pure. */
object BackupRecords {
    /** How every record is written and read: `"type"` names the record, defaults and nulls are written, unknown keys ignored. */
    val JSON: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    /** The record types of format v1, in the order a save writes them (between the manifest and the end). */
    val TYPES: List<String> = listOf(
        "manifest", "agent", "provider", "model", "modelPrice", "aiApp", "space", "memory", "memoryLink", "conversation", "entry", "end",
    )

    /** The extra provider headers a file may hold (case ignored), and only with a value that does not look like a key. */
    val HEADERS_KEPT: Set<String> = setOf("http-referer", "x-title")

    /** What the save report and the inspect screen say when headers were left out. */
    const val HEADERS_LEFT_OUT: String = "Some provider settings (extra headers) are not in the backup; enter them again after restoring."

    /** What they say when a provider was left out because its address looks like it holds a key. */
    const val PROVIDER_LEFT_OUT: String =
        "A provider whose web address seems to hold a key is not in the backup; add it again after restoring."

    /** The JSON text of [record]. */
    fun encode(record: BackupRecord): String = JSON.encodeToString(BackupRecord.serializer(), record)

    /**
     * The record in [text], or null when its `"type"` is not one of format v1 (a reader skips it).
     * @throws SerializationException or IllegalArgumentException when it is not a JSON object with a
     * string `"type"`, or a known record is malformed: the file is damaged.
     */
    fun decode(text: String): BackupRecord? {
        val obj = JSON.parseToJsonElement(text).jsonObject
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SerializationException("A record has no type.")
        if (type !in TYPES) return null
        return JSON.decodeFromJsonElement(BackupRecord.serializer(), obj)
    }

    /**
     * A provider as a save writes it: no key alias, status UNKNOWN without message or check time,
     * and only the [HEADERS_KEPT] whose value [AnswerCheck.looksLikeKey] does not flag. Left out
     * entirely when a part of its address looks like a key (a key in the URL's path).
     */
    fun providerForSave(provider: ModelProvider): ProviderForSave {
        val headers = keptHeaders(provider.extraHeaders)
        val leftOut = provider.extraHeaders.size - headers.size
        if (baseUrlHoldsKey(provider.baseUrl)) return ProviderForSave(null, leftOut)
        return ProviderForSave(BackupRecord.Provider(withoutSecrets(provider).copy(extraHeaders = headers)), leftOut)
    }

    /**
     * A provider from a file as a restore stores it: no key alias whatever the file says (also when
     * its id is that of a provider whose key is in the vault), status UNKNOWN, only the allowed
     * headers, and the farther of the file's locality and the one its address implies. WP3 also
     * validates it as owner input (`ProviderDraftValidator`).
     */
    fun providerForRestore(provider: ModelProvider): ModelProvider =
        withoutSecrets(provider).copy(
            extraHeaders = keptHeaders(provider.extraHeaders),
            locality = maxOf(provider.locality, Locality.infer(provider.kind, provider.baseUrl)),
        )

    /** An AI app without what describes its install on this phone: signing certificate, install time, the owner's check, screen control. */
    fun aiAppForSave(app: AiApp): AiApp =
        app.copy(signingCertSha256 = null, firstInstallTime = null, verifiedByUser = false, accessibilityAuthorized = false)

    /** An AI app from a file as a restore stores it: the same fields cleared; the registry scans the phone again. */
    fun aiAppForRestore(app: AiApp): AiApp = aiAppForSave(app)

    /** True for a memory scope a file may hold: an agent's own memory or a space's; never a task's (objectives are not in the file). */
    fun memoryInFile(scope: MemoryScope): Boolean = scope !is MemoryScope.Task

    /** The memory as a save writes it, or null for a Task-scope memory (left out). */
    fun memoryForSave(memory: MemoryRecord): BackupRecord.Memory? = if (memoryInFile(memory.scope)) BackupRecord.Memory(memory) else null

    /** What an agent's tool use becomes in the file: never its arguments or its result. */
    fun toolLine(toolTitle: String): String = "A tool was used here: ${toolTitle.trim().ifEmpty { "a tool" }}. Its result is not in the backup."

    /**
     * A conversation entry as a save writes it: the owner's text, or the agent's reply text with a
     * [toolLine] for each tool it asked for ([toolTitle] names a tool from its function name); null
     * for what is left out: instructions to the model, tool results, images, reasoning, provider
     * data, and an entry that is left with no text.
     */
    fun entryForSave(entry: ConversationEntry, toolTitle: (String) -> String): BackupRecord.Entry? {
        val message = entry.message
        val (author, text) = when (message.role) {
            ChatRole.USER -> EntryAuthor.OWNER to message.text
            ChatRole.ASSISTANT -> EntryAuthor.AGENT to (listOf(message.text) + message.toolCalls.map { toolLine(toolTitle(it.name)) })
                .filter { it.isNotBlank() }.joinToString("\n")
            ChatRole.SYSTEM, ChatRole.TOOL -> return null
        }
        if (text.isBlank()) return null
        return BackupRecord.Entry(entry.conversationId, entry.seq, author, text, entry.at, entry.modelName.takeIf { author == EntryAuthor.AGENT })
    }

    private fun withoutSecrets(provider: ModelProvider): ModelProvider =
        provider.copy(apiKeyAlias = null, status = ConnectionStatus.UNKNOWN, statusMessage = null, lastCheckedAt = null)

    private fun keptHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filter { (name, value) -> name.trim().lowercase() in HEADERS_KEPT && !AnswerCheck.looksLikeKey(value) }

    /** True when one part of [baseUrl] (between "/", "?", "#", "&", "=", ";" and "@") looks like a key. */
    fun baseUrlHoldsKey(baseUrl: String?): Boolean =
        baseUrl != null && baseUrl.split('/', '?', '#', '&', '=', ';', '@').any { it.isNotEmpty() && AnswerCheck.looksLikeKey(it) }
}
