package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.MemoryLinkId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.backup.BackupCounts
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.BackupRecords
import com.aicontrolcenter.core.backup.EntryAuthor
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.data.BackupStores

/*
 * One pass over the records of a backup file on restore (rule 8; `phase6-contracts.md` section 4, "What a restore does
 * with each record"). The same code runs twice: first without writing (to check the whole file, count it and learn the
 * file's spaces before anything is stored), then inside the restore's one transaction, writing each record as it is
 * read. Every record is checked like owner input ([RestoreChecks]) and by the rules of the format: the order of record
 * types, no id twice, a memory's agent or space and a chat's agent in the file, the manifest's counts. Whatever the
 * file says, a restore grants nothing: providers are stored by `BackupRecords.providerForRestore` (no key, status
 * UNKNOWN, the farther locality, two headers at most), AI apps by `aiAppForRestore` (unverified, no screen control),
 * prices through the owner's own price store, memories with the ids and scopes they had.
 */

/** What a fresh app holds before the owner made anything, given by the app (`:framework` knows the templates). */
class StarterContent(
    /** Template ids of the starter team (`AgentTemplates.starterTeam`). */
    val agentTemplates: Set<String>,
    /** Providers the app builds in (the simulator), which a fresh app holds and every restore keeps. */
    val builtInProviders: Set<ProviderId>,
)

/**
 * One pass over a file's records. [fileSpaces] is the set of spaces the file holds, known from the first pass; the
 * second pass cuts agents' space lists to it. [stores] null: check only; otherwise each record is stored as it passes.
 */
internal class RestorePass(
    private val starter: StarterContent,
    private val fileSpaces: Set<SpaceId>?,
    private val stores: BackupStores?,
) {
    var manifest: BackupRecord.Manifest? = null
        private set
    private var lastType = 0

    /** The file's agents, id to name: who exists after a restore (the starter team is replaced). */
    val agents = LinkedHashMap<AgentId, String>()
    private val agentNames = HashSet<String>()
    private val providers = HashSet<ProviderId>()
    private val providerNames = HashSet<String>()
    private val models = HashSet<ModelId>()
    private val priceModels = HashSet<ModelId>()
    private var priceRecords = 0
    private var pricesKept = 0

    /** Prices the file holds that are not valid prices: left out and counted. */
    var droppedPrices = 0
        private set
    private val appIds = HashSet<AiAppId>()

    /** Package names of the file's AI apps: a scan's entry for the same app is replaced by the file's. */
    val appPackages = HashSet<String>()
    val spaces = LinkedHashSet<SpaceId>()
    private val spaceNames = HashSet<String>()
    private val memoryIds = HashSet<MemoryId>()
    private val keptMemories = HashSet<MemoryId>()
    private var memoryRecords = 0

    /** Memories left out: a task's memory, or one whose agent or space is not in the file (rule 8). */
    var droppedMemories = 0
        private set
    private val linkIds = HashSet<MemoryLinkId>()
    private var linkRecords = 0
    private var linksKept = 0
    private val conversations = HashSet<ConversationId>()
    private val lastSeq = HashMap<ConversationId, Int>()
    private var entries = 0

    /** Every record, by its kind, as the file holds it (what the manifest's counts must say). */
    fun fileCounts() = BackupCounts(
        agents = agents.size, providers = providers.size, models = models.size, modelPrices = priceRecords, aiApps = appIds.size,
        spaces = spaces.size, memories = memoryRecords, memoryLinks = linkRecords, conversations = conversations.size,
        conversationEntries = entries,
    )

    /** What a restore stores: the file's records less those left out. */
    fun restoredCounts() = BackupCounts(
        agents = agents.size, providers = providers.size, models = models.size, modelPrices = pricesKept, aiApps = appIds.size,
        spaces = spaces.size, memories = keptMemories.size, memoryLinks = linksKept, conversations = conversations.size,
        conversationEntries = entries,
    )

    /** Takes the next record of the file (null: a type this version does not know, skipped). */
    suspend fun accept(record: BackupRecord?) {
        if (record == null) return
        val type = BackupRecords.TYPES.indexOf(typeOf(record))
        // Records come in the order of section 4; a file in another order was not written by a save.
        if (type < lastType) throw BackupFailure(BackupProblem.Damaged)
        lastType = type
        when (record) {
            is BackupRecord.Manifest -> manifest = record
            is BackupRecord.Agent -> agent(record)
            is BackupRecord.Provider -> provider(record)
            is BackupRecord.Model -> model(record)
            is BackupRecord.Price -> price(record)
            is BackupRecord.App -> app(record)
            is BackupRecord.Space -> space(record)
            is BackupRecord.Memory -> memory(record)
            is BackupRecord.Link -> link(record)
            is BackupRecord.Chat -> chat(record)
            is BackupRecord.Entry -> entry(record)
            is BackupRecord.End -> Unit // readRecords hands the end record to nobody
        }
    }

    /** After the end record: the manifest's counts must be the records the file holds. */
    fun finish() {
        val manifest = manifest ?: throw BackupFailure(BackupProblem.Damaged)
        if (manifest.counts != fileCounts()) throw BackupFailure(BackupProblem.Damaged)
    }

    private fun invalid(what: String): Nothing = throw BackupFailure(BackupProblem.Invalid(what))

    private suspend fun agent(record: BackupRecord.Agent) {
        val agent = record.agent
        if (agent.id in agents) invalid("the same agent twice")
        RestoreChecks.agent(agent, record.stats, agentNames)?.let(::invalid)
        if (agents.size >= com.aicontrolcenter.core.backup.BackupFormat.MAX_AGENTS) throw BackupFailure(BackupProblem.TooLarge)
        agents[agent.id] = agent.name.trim()
        agentNames += RestoreChecks.nameKey(agent.name)
        val store = stores ?: return
        // An agent keeps only the spaces the file holds (rule 8).
        val inFile = fileSpaces ?: emptySet()
        val policy = agent.memory
        store.agents.upsert(
            agent.copy(memory = policy.copy(readableSpaces = policy.readableSpaces intersect inFile, writableSpaces = policy.writableSpaces intersect inFile)),
        )
        store.agents.updateStats(agent.id) { record.stats }
    }

    private suspend fun provider(record: BackupRecord.Provider) {
        val provider = BackupRecords.providerForRestore(record.provider)
        if (provider.id in providers) invalid("the same provider twice")
        RestoreChecks.provider(provider, providerNames)?.let(::invalid)
        providers += provider.id
        providerNames += RestoreChecks.nameKey(provider.name)
        stores?.providers?.upsert(provider)
    }

    private suspend fun model(record: BackupRecord.Model) {
        val model = record.model
        if (model.id in models) invalid("the same model twice")
        RestoreChecks.model(model)?.let(::invalid)
        if (model.providerId !in providers && model.providerId !in starter.builtInProviders) invalid("a model of a provider that is not in the backup")
        models += model.id
        stores?.models?.upsert(model)
    }

    private suspend fun price(record: BackupRecord.Price) {
        RestoreChecks.id(record.modelId.value, "a price")?.let(::invalid)
        if (!priceModels.add(record.modelId)) invalid("two prices for the same model")
        priceRecords++
        val price = record.toPrice()
        if (price == null) {
            droppedPrices++
            return
        }
        pricesKept++
        val store = stores ?: return
        // Through the owner's own price store, which stamps the time of this restore.
        when (val result = store.ownerModelPrices.set(price)) {
            is OwnerResult.Done -> Unit
            is OwnerResult.Refused -> invalid("a price the app could not store")
        }
    }

    private suspend fun app(record: BackupRecord.App) {
        val app = BackupRecords.aiAppForRestore(record.app)
        if (app.id in appIds) invalid("the same AI app twice")
        RestoreChecks.aiApp(app)?.let(::invalid)
        if (!appPackages.add(app.packageName)) invalid("the same AI app twice")
        appIds += app.id
        stores?.aiApps?.upsert(app)
    }

    private suspend fun space(record: BackupRecord.Space) {
        val space = record.space
        if (space.id in spaces) invalid("the same space twice")
        RestoreChecks.space(space, spaceNames)?.let(::invalid)
        spaces += space.id
        spaceNames += RestoreChecks.spaceNameKey(space.name)
        stores?.memories?.upsertSpace(space)
    }

    private suspend fun memory(record: BackupRecord.Memory) {
        val memory = record.memory
        if (!memoryIds.add(memory.id)) invalid("the same memory twice")
        RestoreChecks.memory(memory)?.let(::invalid)
        memoryRecords++
        if (memoryRecords > com.aicontrolcenter.core.backup.BackupFormat.MAX_MEMORIES) throw BackupFailure(BackupProblem.TooLarge)
        // Kept with its id and scope; a task's memory, or one whose agent or space is not in the file, is left out.
        val kept = when (val scope = memory.scope) {
            is MemoryScope.Agent -> scope.agentId in agents
            is MemoryScope.Shared -> scope.spaceId in spaces
            is MemoryScope.Task -> false
        }
        if (!kept) {
            droppedMemories++
            return
        }
        keptMemories += memory.id
        val store = stores ?: return
        try {
            store.memories.upsert(memory)
        } catch (_: IllegalArgumentException) {
            invalid("a memory too large to store")
        }
    }

    private suspend fun link(record: BackupRecord.Link) {
        val link = record.link
        if (!linkIds.add(link.id)) invalid("the same memory link twice")
        RestoreChecks.link(link)?.let(::invalid)
        linkRecords++
        // A link goes with its memories: one whose end was left out is left out too.
        if (link.from !in keptMemories || link.to !in keptMemories) return
        linksKept++
        val store = stores ?: return
        try {
            store.memories.upsertLink(link)
        } catch (_: IllegalArgumentException) {
            invalid("a memory link the app could not store")
        }
    }

    private suspend fun chat(record: BackupRecord.Chat) {
        val conversation = record.conversation
        if (!conversations.add(conversation.id)) invalid("the same chat twice")
        RestoreChecks.conversation(conversation)?.let(::invalid)
        if (conversation.agentId !in agents) invalid("a chat with an agent that is not in the backup")
        stores?.conversations?.upsertConversation(conversation)
    }

    private suspend fun entry(record: BackupRecord.Entry) {
        if (record.conversationId !in conversations) invalid("a chat message of a chat that is not in the backup")
        val previous = lastSeq[record.conversationId]
        if (record.seq < 0 || (previous != null && record.seq <= previous)) invalid("chat messages out of order")
        lastSeq[record.conversationId] = record.seq
        RestoreChecks.entryText(record.text)?.let(::invalid)
        if (record.at < 0) invalid("a chat message with an invalid time")
        entries++
        val store = stores ?: return
        // The owner's words and the agent's reply text only: nothing in the file becomes a tool call, a tool result or
        // an instruction to a model (section 4).
        val message = when (record.author) {
            EntryAuthor.OWNER -> ChatMessage.user(record.text)
            EntryAuthor.AGENT -> ChatMessage.assistant(record.text)
        }
        val modelName = record.modelName.takeIf { record.author == EntryAuthor.AGENT }?.take(RestoreChecks.LABEL_CHARS)
        try {
            store.conversations.appendEntry(ConversationEntry(record.conversationId, seq = 0, message = message, at = record.at, modelName = modelName))
        } catch (_: IllegalArgumentException) {
            invalid("a chat message the app could not store")
        }
    }

    companion object {
        /** The `"type"` of [record], as `BackupRecords.TYPES` names it. */
        fun typeOf(record: BackupRecord): String = when (record) {
            is BackupRecord.Manifest -> "manifest"
            is BackupRecord.Agent -> "agent"
            is BackupRecord.Provider -> "provider"
            is BackupRecord.Model -> "model"
            is BackupRecord.Price -> "modelPrice"
            is BackupRecord.App -> "aiApp"
            is BackupRecord.Space -> "space"
            is BackupRecord.Memory -> "memory"
            is BackupRecord.Link -> "memoryLink"
            is BackupRecord.Chat -> "conversation"
            is BackupRecord.Entry -> "entry"
            is BackupRecord.End -> "end"
        }
    }
}
