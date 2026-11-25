package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.backup.BackupCounts
import com.aicontrolcenter.core.backup.BackupFormat
import com.aicontrolcenter.core.backup.BackupHeader
import com.aicontrolcenter.core.backup.BackupKeys
import com.aicontrolcenter.core.backup.BackupPreamble
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.BackupRecords
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.BackupSettings
import com.aicontrolcenter.core.backup.BackupSummary
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.backup.OwnerBackup
import com.aicontrolcenter.core.backup.Passphrases
import com.aicontrolcenter.core.backup.RestoreReport
import com.aicontrolcenter.core.backup.RestoreSettings
import com.aicontrolcenter.core.backup.SaveReport
import com.aicontrolcenter.core.backup.SettingKeptBack
import com.aicontrolcenter.core.eventId
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.data.BackupStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext

/*
 * The backup file (Phase 6, WP3, Tier B; rules 8, 21, 27, 30): `aicc-backup` v1, FROZEN (`phase6-contracts.md`
 * section 4). The crypto and the record rules are core's (BackupKeys, BackupPreamble, BackupChunks, BackupRecords,
 * RestoreSettings, Passphrases); this is the part that reads the app's stores, streams the file and restores it.
 */

/** What a backup's header says about the app that wrote it. */
class BackupAppInfo(val appVersion: String, val appVersionCode: Int, val schemaVersion: Int)

/**
 * Names a tool for the line a save writes in place of each tool call ("A tool was used here: <title>. Its result is not
 * in the backup."): [fixed] names first (`tools_open` is not a registered tool), then the tool registry once [use] gave
 * it (the registry exists only with the app's graph), else the function name itself.
 */
class ToolTitles(private val fixed: Map<String, String> = emptyMap()) {
    @Volatile
    private var lookup: ((String) -> String?)? = null

    /** Names tools from now on with [lookup] (a function name to its title, or null when unknown). */
    fun use(lookup: (String) -> String?) {
        this.lookup = lookup
    }

    fun titleOf(functionName: String): String {
        fixed[functionName]?.let { return it }
        val found = try {
            lookup?.invoke(functionName)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return found?.trim()?.takeIf { it.isNotEmpty() } ?: functionName
    }
}

/** A save's report and what the owner should know about it ([BackupTexts], `BackupRecords.PROVIDER_LEFT_OUT`, …). */
data class SaveOutcome(val report: SaveReport, val notes: List<String>)

/**
 * What the restore screen shows before the owner confirms (rule 8: "the screen lists every setting that would change").
 * [refusal] is what a restore would meet now (the app is not fresh, a restore runs, too little free space), or null.
 */
data class RestorePreview(
    val summary: BackupSummary,
    /** Every value of the file a restore does not take as it is (RestoreSettings.merge). */
    val keptBack: List<SettingKeptBack>,
    /** The switches a restore always leaves off (RestoreSettings.ALWAYS_OFF_AFTER_RESTORE), in words. */
    val alwaysOff: List<String>,
    val notes: List<String>,
    val refusal: BackupProblem?,
)

/** A restore's report and its notes; the app restarts its process next (rule 30, the screen's part). */
data class RestoreOutcome(val report: RestoreReport, val notes: List<String>)

/**
 * The owner's backup port with what the screens need beyond [OwnerBackup] (notes, settings kept back, a refusal known
 * in advance). Owner-only, as [OwnerBackup] is.
 */
interface DetailedOwnerBackup : OwnerBackup {
    suspend fun saveWithNotes(
        passphrase: CharSequence,
        generatedShown: String?,
        open: suspend () -> OutputStream,
        progress: (BackupProgress) -> Unit = {},
    ): BackupResult<SaveOutcome>

    suspend fun preview(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit = {}): BackupResult<RestorePreview>

    suspend fun restoreWithNotes(
        open: suspend () -> InputStream,
        passphrase: CharSequence,
        progress: (BackupProgress) -> Unit = {},
    ): BackupResult<RestoreOutcome>

    override suspend fun save(
        passphrase: CharSequence,
        generatedShown: String?,
        open: suspend () -> OutputStream,
        progress: (BackupProgress) -> Unit,
    ): BackupResult<SaveReport> = saveWithNotes(passphrase, generatedShown, open, progress).map { it.report }

    override suspend fun inspect(open: suspend () -> InputStream, passphrase: CharSequence): BackupResult<BackupSummary> =
        preview(open, passphrase).map { it.summary }

    override suspend fun restore(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit): BackupResult<RestoreReport> =
        restoreWithNotes(open, passphrase, progress).map { it.report }
}

private inline fun <T, R> BackupResult<T>.map(transform: (T) -> R): BackupResult<R> = when (this) {
    is BackupResult.Done -> BackupResult.Done(transform(value))
    is BackupResult.Failed -> this
}

/**
 * [OwnerBackup] over the backup's stores and nothing else ([BackupStores]: no vault, grants, requests, folders or
 * authorised apps can be reached from here, F13).
 *
 * - **Save** takes the passphrase only through [Passphrases.problemForSave]; reads the stores (outside any transaction,
 *   so agents at work are not held up) and writes the records section 4 lists with core's rules for what each leaves
 *   out (`providerForSave`, `aiAppForSave`, `memoryForSave`, `entryForSave`); the key is derived on [kdfContext] before
 *   the file is opened, so a save cancelled while the key is derived writes nothing.
 * - **Preview** reads the whole file with the passphrase and writes nothing.
 * - **Restore** runs inside [MaintenanceGate.runExclusive]: it checks the whole file (nothing written), checks free
 *   space (twice the file), then in one transaction whose first step checks that the app is fresh, replaces the starter
 *   team and the empty spaces with the file's, stores every record as it reads the file a second time (the same bytes,
 *   checked by digest), and last merges the settings ([RestoreSettings.merge]). Any problem and nothing is written.
 *
 * Every save and restore, done or not, is logged as USER_ACTION with counts only.
 */
class RoomOwnerBackup(
    private val stores: BackupStores,
    private val gate: MaintenanceGate,
    private val freeBytes: suspend () -> Long,
    private val app: BackupAppInfo,
    private val starter: StarterContent,
    val toolTitles: ToolTitles = ToolTitles(),
    private val clock: Clock = WallClock,
    private val ids: IdGenerator = RandomIdGenerator,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = BackupFormat.DEFAULT_ITERATIONS,
    private val kdfContext: CoroutineContext = Dispatchers.Default,
) : DetailedOwnerBackup {

    // ---- Save ----

    override suspend fun saveWithNotes(
        passphrase: CharSequence,
        generatedShown: String?,
        open: suspend () -> OutputStream,
        progress: (BackupProgress) -> Unit,
    ): BackupResult<SaveOutcome> {
        val result = try {
            Passphrases.problemForSave(passphrase, generatedShown)?.let { throw BackupFailure(BackupProblem.WeakPassphrase(it)) }
            if (gate.busy.value) throw BackupFailure(BackupProblem.Busy(MaintenanceGate.RESTORING))
            progress(BackupProgress(BackupTexts.STEP_GATHERING, 0, null))
            val content = gather()
            if (content.counts.agents > BackupFormat.MAX_AGENTS || content.counts.memories > BackupFormat.MAX_MEMORIES) {
                throw BackupFailure(BackupProblem.TooLarge)
            }
            progress(BackupProgress(BackupTexts.STEP_LOCKING, 0, null))
            val salt = ByteArray(BackupFormat.SALT_BYTES).also(random::nextBytes)
            val header = BackupHeader.forSave(app.schemaVersion, app.appVersion, app.appVersionCode, clock.now(), salt, iterations)
            val keys = withContext(kdfContext) { BackupKeys.derive(passphrase, salt, iterations) }
            val size = try {
                writeFile(open, header, keys, content, progress)
            } finally {
                keys.clear()
            }
            BackupResult.Done(SaveOutcome(SaveReport(content.counts, size), content.notes))
        } catch (e: BackupFailure) {
            BackupResult.Failed(e.problem)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupResult.Failed(BackupProblem.CannotOpenFile)
        }
        when (result) {
            is BackupResult.Done -> log(BackupTexts.LOG_SAVED, BackupTexts.counts(result.value.report.counts) + ".")
            is BackupResult.Failed -> log(BackupTexts.LOG_NOT_SAVED, result.problem.text, Severity.WARNING)
        }
        return result
    }

    /** The records of a save, encoded, in the order of section 4, with the manifest's counts and the notes. */
    private class Gathered(val records: List<ByteArray>, val counts: BackupCounts, val notes: List<String>)

    private suspend fun gather(): Gathered {
        val body = ArrayList<ByteArray>()
        fun add(record: BackupRecord) = body.add(BackupRecords.encode(record).encodeToByteArray())
        val notes = ArrayList<String>()

        val settings = BackupSettings.of(stores.settings.get())
        val agents = stores.agents.getAll()
        agents.forEach { add(BackupRecord.Agent(it, stores.agents.getStats(it.id))) }
        val agentIds = agents.mapTo(HashSet()) { it.id }

        var headersLeftOut = 0
        var providersLeftOut = 0
        val savedProviders = HashSet<ProviderId>()
        for (provider in stores.providers.getAll()) {
            val saved = BackupRecords.providerForSave(provider)
            headersLeftOut += saved.headersLeftOut
            val record = saved.record
            if (record == null) {
                providersLeftOut++
                continue
            }
            savedProviders += provider.id
            add(record)
        }
        if (providersLeftOut > 0) notes += BackupRecords.PROVIDER_LEFT_OUT
        if (headersLeftOut > 0) notes += BackupRecords.HEADERS_LEFT_OUT

        // A model goes with its provider: none of a provider left out (or gone).
        val models = stores.models.getAll().filter { it.providerId in savedProviders || it.providerId in starter.builtInProviders }
        models.forEach { add(BackupRecord.Model(it)) }
        val prices = stores.modelPrices.getAll()
        prices.forEach { add(BackupRecord.Price.of(it)) }
        val apps = stores.aiApps.getAll()
        apps.forEach { add(BackupRecord.App(BackupRecords.aiAppForSave(it))) }
        val spaces = stores.memories.observeSpaces().first()
        spaces.forEach { add(BackupRecord.Space(it)) }
        val spaceIds = spaces.mapTo(HashSet()) { it.id }

        // Memories of an agent or a space in the file (never a task's), and the links between them.
        val memories = stores.memories.list().mapNotNull { memory ->
            val inFile = when (val scope = memory.scope) {
                is MemoryScope.Agent -> scope.agentId in agentIds
                is MemoryScope.Shared -> scope.spaceId in spaceIds
                is MemoryScope.Task -> false
            }
            if (inFile) BackupRecords.memoryForSave(memory) else null
        }
        memories.forEach(::add)
        val memoryIds = memories.mapTo(HashSet()) { it.memory.id }
        val links = stores.memories.links().filter { it.from in memoryIds && it.to in memoryIds }
        links.forEach { add(BackupRecord.Link(it)) }

        val conversations = stores.conversations.observeConversations().first().filter { it.agentId in agentIds }
        conversations.forEach { add(BackupRecord.Chat(it)) }
        var entries = 0
        for (conversation in conversations) {
            for (entry in stores.conversations.getEntries(conversation.id)) {
                val record = BackupRecords.entryForSave(entry, toolTitles::titleOf) ?: continue
                add(record)
                entries++
            }
        }

        val counts = BackupCounts(
            agents = agents.size, providers = savedProviders.size, models = models.size, modelPrices = prices.size, aiApps = apps.size,
            spaces = spaces.size, memories = memories.size, memoryLinks = links.size, conversations = conversations.size,
            conversationEntries = entries,
        )
        val manifest = BackupRecords.encode(BackupRecord.Manifest(counts, settings)).encodeToByteArray()
        val end = BackupRecords.encode(BackupRecord.End(records = body.size + 1)).encodeToByteArray()
        return Gathered(listOf(manifest) + body + listOf(end), counts, notes)
    }

    /** Writes the file; returns its size in bytes. */
    private suspend fun writeFile(open: suspend () -> OutputStream, header: BackupHeader, keys: BackupKeys, content: Gathered, progress: (BackupProgress) -> Unit): Long {
        val preamble = BackupPreamble.write(header, keys)
        // H, as core computes it for every chunk's AAD: read back from the preamble just written.
        val headerHash = BackupPreamble.read(ByteArrayInputStream(preamble)).orFail().headerHash
        val stream = try {
            withContext(Dispatchers.IO) { open() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw BackupFailure(BackupProblem.CannotOpenFile)
        }
        var closed = false
        try {
            return withContext(Dispatchers.IO) {
                val out = BufferedOutputStream(stream, OUTPUT_BUFFER_BYTES)
                try {
                    out.write(preamble)
                    val body = BackupBodyWriter(out, keys, headerHash, random)
                    val total = content.records.size.toLong()
                    content.records.forEachIndexed { i, json ->
                        body.writeEncoded(json)
                        if (i % PROGRESS_EVERY == 0 || i == content.records.lastIndex) progress(BackupProgress(BackupTexts.STEP_WRITING, i + 1L, total))
                    }
                    body.finish()
                    out.flush()
                    out.close()
                    closed = true
                    preamble.size + body.writtenBytes
                } catch (_: IOException) {
                    throw BackupFailure(BackupProblem.CannotOpenFile)
                }
            }
        } finally {
            if (!closed) runCatching { stream.close() }
        }
    }

    // ---- Reading a file ----

    /** The file as its first, checking pass found it (nothing written). */
    private class Checked(
        val header: BackupHeader,
        val headerHash: ByteArray,
        val pass: RestorePass,
        val sizeBytes: Long,
        val digest: ByteArray,
    ) {
        val manifest: BackupRecord.Manifest get() = pass.manifest!!
    }

    /**
     * The file, counted and bounded. Not buffered: the preamble is read with exact reads, so a wrong passphrase is found
     * without a byte of chunk 0 having been read; the chunks are read through [chunks].
     */
    private suspend fun openInput(open: suspend () -> InputStream): BoundedFileStream = try {
        BoundedFileStream(withContext(Dispatchers.IO) { open() })
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw BackupFailure(BackupProblem.CannotOpenFile)
    }

    /**
     * Opens the file, unlocks it with [passphrase] (the key derived on [kdfContext]: a wrong passphrase is found before
     * any chunk is read) and checks every record ([RestorePass] without stores). The caller clears the keys.
     */
    private suspend fun unlockAndCheck(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit): Pair<BackupKeys, Checked> {
        val input = openInput(open)
        try {
            val preamble = withContext(Dispatchers.IO) { BackupPreamble.read(input) }.orFail()
            progress(BackupProgress(BackupTexts.STEP_UNLOCKING, 0, null))
            val keys = withContext(kdfContext) { preamble.unlock(passphrase) }.orFail()
            try {
                val pass = RestorePass(starter, fileSpaces = null, stores = null)
                val plaintext = ChunkPlaintextStream(chunks(input), keys, preamble.headerHash)
                readAll(plaintext, pass, BackupTexts.STEP_CHECKING, progress)
                return keys to Checked(preamble.header, preamble.headerHash, pass, input.count, plaintext.digest())
            } catch (e: Throwable) {
                keys.clear()
                throw e
            }
        } finally {
            runCatching { input.close() }
        }
    }

    /** The chunks of [input], read in large blocks once the passphrase was found right. */
    private fun chunks(input: BoundedFileStream): InputStream = BufferedInputStream(input, INPUT_BUFFER_BYTES)

    /** Reads every record of [plaintext] into [pass], with progress counted against the manifest's counts. */
    private suspend fun readAll(plaintext: ChunkPlaintextStream, pass: RestorePass, step: String, progress: (BackupProgress) -> Unit) {
        var done = 0L
        var total: Long? = null
        withContext(Dispatchers.IO) {
            readRecords(plaintext) { record ->
                pass.accept(record)
                done++
                if (total == null) total = pass.manifest?.counts?.let { recordsIn(it) }
                if (done % PROGRESS_EVERY == 0L) progress(BackupProgress(step, done, total))
            }
        }
        pass.finish()
        progress(BackupProgress(step, done, total ?: done))
    }

    private fun recordsIn(c: BackupCounts): Long =
        1L + c.agents + c.providers + c.models + c.modelPrices + c.aiApps + c.spaces + c.memories + c.memoryLinks + c.conversations + c.conversationEntries

    // ---- Preview ----

    override suspend fun preview(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit): BackupResult<RestorePreview> {
        val result = try {
            val (keys, checked) = unlockAndCheck(open, passphrase, progress)
            keys.clear()
            val merged = RestoreSettings.merge(stores.settings.get(), checked.manifest.settings, checked.pass.agents)
            val summary = BackupSummary(
                createdAt = checked.header.createdAt, appVersion = checked.header.appVersion, schemaVersion = checked.header.schemaVersion,
                counts = checked.pass.fileCounts(), settingChanges = merged.changes, sizeBytes = checked.sizeBytes,
            )
            val refusal = when {
                gate.busy.value -> BackupProblem.Busy(MaintenanceGate.RESTORING)
                holdsOwnContent() -> BackupProblem.NotEmpty
                else -> spaceProblem(checked.sizeBytes)
            }
            BackupResult.Done(RestorePreview(summary, merged.keptBack, RestoreSettings.ALWAYS_OFF_AFTER_RESTORE, notes(checked.pass), refusal))
        } catch (e: BackupFailure) {
            BackupResult.Failed(e.problem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            BackupResult.Failed(BackupProblem.CannotOpenFile)
        }
        when (result) {
            is BackupResult.Done -> log(BackupTexts.LOG_OPENED, BackupTexts.counts(result.value.summary.counts) + ".")
            is BackupResult.Failed -> log(BackupTexts.LOG_NOT_OPENED, result.problem.text, Severity.WARNING)
        }
        return result
    }

    /** What the restore screen says before the owner confirms. */
    private fun notes(pass: RestorePass): List<String> = listOf(BackupTexts.STARTER_REPLACED) + restoreNotes(pass)

    /** What a restore left out, and that keys are entered again. */
    private fun restoreNotes(pass: RestorePass): List<String> = buildList {
        if (pass.droppedMemories > 0) add(BackupTexts.droppedMemories(pass.droppedMemories))
        if (pass.droppedPrices > 0) add(BackupTexts.droppedPrices(pass.droppedPrices))
        add(BackupTexts.KEYS_AGAIN)
    }

    private suspend fun spaceProblem(fileBytes: Long): BackupProblem? {
        val needed = SPACE_FACTOR * fileBytes
        return if (freeBytes() < needed) BackupProblem.NotEnoughSpace(needed) else null
    }

    // ---- Restore ----

    override suspend fun restoreWithNotes(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit): BackupResult<RestoreOutcome> {
        val gated = gate.runExclusive<BackupResult<RestoreOutcome>> {
            try {
                BackupResult.Done(restoreInGate(open, passphrase, progress))
            } catch (e: BackupFailure) {
                BackupResult.Failed(e.problem)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BackupResult.Failed(BackupProblem.Invalid(COULD_NOT_STORE))
            }
        }
        val result = when (gated) {
            is OwnerResult.Done -> gated.value
            is OwnerResult.Refused -> BackupResult.Failed(BackupProblem.Busy(gated.reason))
        }
        when (result) {
            is BackupResult.Done -> log(BackupTexts.LOG_RESTORED, BackupTexts.counts(result.value.report.counts) + ".")
            is BackupResult.Failed -> log(BackupTexts.LOG_NOT_RESTORED, result.problem.text, Severity.WARNING)
        }
        return result
    }

    private suspend fun restoreInGate(open: suspend () -> InputStream, passphrase: CharSequence, progress: (BackupProgress) -> Unit): RestoreOutcome {
        val (keys, checked) = unlockAndCheck(open, passphrase, progress)
        try {
            spaceProblem(checked.sizeBytes)?.let { throw BackupFailure(it) }
            val fileSettings = checked.manifest.settings
            var settingsBefore: FrameworkSettings? = null
            try {
                val (pass, before) = stores.transactions.run {
                    // First: is the app fresh? Checked inside the transaction, so nothing can be added in between.
                    if (holdsOwnContent()) throw BackupFailure(BackupProblem.NotEmpty)
                    replaceStarterContent(checked)
                    val pass = RestorePass(starter, fileSpaces = checked.pass.spaces.toSet(), stores = stores)
                    val input = openInput(open)
                    try {
                        val preamble = withContext(Dispatchers.IO) { BackupPreamble.read(input) }.orFail()
                        // The same file as the one checked: the same header, then the same plaintext (digest below).
                        if (!MessageDigest.isEqual(preamble.headerHash, checked.headerHash)) throw BackupFailure(BackupProblem.Damaged)
                        val plaintext = ChunkPlaintextStream(chunks(input), keys, preamble.headerHash)
                        readAll(plaintext, pass, BackupTexts.STEP_RESTORING, progress)
                        if (!MessageDigest.isEqual(plaintext.digest(), checked.digest)) throw BackupFailure(BackupProblem.Damaged)
                    } finally {
                        runCatching { input.close() }
                    }
                    // Last: the settings, only through RestoreSettings.merge (F2).
                    var before: FrameworkSettings? = null
                    stores.settings.update { current ->
                        before = current
                        RestoreSettings.merge(current, fileSettings, pass.agents).settings
                    }
                    settingsBefore = before
                    pass to before!!
                }
                val merged = RestoreSettings.merge(before, fileSettings, pass.agents)
                val report = RestoreReport(pass.restoredCounts(), pass.droppedMemories, merged.changes)
                return RestoreOutcome(report, restoreNotes(pass))
            } catch (e: Throwable) {
                // The settings are not in the database's transaction: put them back if the transaction did not commit.
                settingsBefore?.let { previous -> withContext(NonCancellable) { runCatching { stores.settings.update { previous } } } }
                throw e
            }
        } finally {
            keys.clear()
        }
    }

    /**
     * True when the app holds something the owner made (rule 8: a restore writes only into a fresh app): an objective,
     * a chat, a memory, an agent that is not one of the starter team (or two of one template), a provider or a model
     * that is not built in, or an AI app the owner added, confirmed, allowed screen control for or linked. Empty spaces,
     * the starter team and AI apps a scan found are what a fresh app may hold; a restore replaces them.
     */
    private suspend fun holdsOwnContent(): Boolean {
        if (stores.tasks.withStatus(TaskStatus.entries.toSet()).isNotEmpty()) return true
        if (stores.conversations.observeConversations().first().isNotEmpty()) return true
        if (stores.memories.list(limit = 1).isNotEmpty()) return true
        val agents = stores.agents.getAll()
        if (agents.any { it.templateId == null || it.templateId !in starter.agentTemplates }) return true
        if (agents.groupingBy { it.templateId }.eachCount().any { it.value > 1 }) return true
        if (stores.providers.getAll().any { it.id !in starter.builtInProviders }) return true
        if (stores.models.getAll().any { it.providerId !in starter.builtInProviders }) return true
        return stores.aiApps.getAll().any(::ownersApp)
    }

    private fun ownersApp(app: AiApp): Boolean =
        app.source == DiscoverySource.MANUAL || app.verifiedByUser || app.accessibilityAuthorized || app.linkedProviderId != null

    /**
     * The fresh app's starter team, its empty spaces and the scan's entries for the file's AI apps make room for the
     * file's (the same names would otherwise be there twice). Built-in providers stay; the file's records of them, by id,
     * replace them.
     */
    private suspend fun replaceStarterContent(checked: Checked) {
        for (agent in stores.agents.getAll()) stores.agents.delete(agent.id)
        val keepSpaces: Set<SpaceId> = checked.pass.spaces
        for (space in stores.memories.observeSpaces().first()) if (space.id !in keepSpaces) stores.memories.deleteSpace(space.id)
        for (app in stores.aiApps.getAll()) if (app.packageName in checked.pass.appPackages) stores.aiApps.delete(app.id)
    }

    private suspend fun log(title: String, detail: String, severity: Severity = Severity.INFO) {
        try {
            stores.activityLog.record(
                ActivityEvent(id = ids.eventId(), at = clock.now(), type = ActivityType.USER_ACTION, severity = severity, title = title, detail = detail),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The log is a record of what happened, never a reason for a save or a restore to fail.
        }
    }

    companion object {
        /** A restore needs this many times the file's size free before it starts. */
        const val SPACE_FACTOR: Long = 2

        private const val OUTPUT_BUFFER_BYTES = 64 * 1024
        private const val INPUT_BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_EVERY = 64

        /** For a restore that failed on something the database refused (not the file's fault, as far as it can tell). */
        const val COULD_NOT_STORE = "something the app could not store"
    }
}
