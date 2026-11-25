package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.backup.BackupCounts
import com.aicontrolcenter.core.backup.BackupHeader
import com.aicontrolcenter.core.backup.BackupKeys
import com.aicontrolcenter.core.backup.BackupPreamble
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.BackupSettings
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.data.BackupStores
import com.aicontrolcenter.data.Transactions
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryAgentRepository
import com.aicontrolcenter.testkit.InMemoryAiAppRepository
import com.aicontrolcenter.testkit.InMemoryConversationRepository
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import com.aicontrolcenter.testkit.InMemoryModelPrices
import com.aicontrolcenter.testkit.InMemoryModelRepository
import com.aicontrolcenter.testkit.InMemoryProviderRepository
import com.aicontrolcenter.testkit.InMemorySettingsRepository
import com.aicontrolcenter.testkit.InMemoryTaskRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Shared helpers of WP3's tests: backup files made and taken apart, and an app of in-memory stores. */
internal object BackupFiles {
    /** A passphrase in the generated form (the save screen's default). */
    const val PASSPHRASE = "7KQ2-M9XD-4RTB-8HJN-3VCP"

    /** The lowest count a file may ask for: keeps the tests quick. */
    const val ITERATIONS = 100_000

    val salt = ByteArray(16) { (it * 7 + 3).toByte() }

    val STARTER = StarterContent(
        agentTemplates = setOf("director", "researcher", "analyst", "writer", "reviewer", "operator"),
        builtInProviders = setOf(ProviderId("prv_simulator")),
    )

    val APP = BackupAppInfo(appVersion = "0.6.0", appVersionCode = 9, schemaVersion = 6)

    fun header(iterations: Int = ITERATIONS, createdAt: Long = 1_795_000_000_000) =
        BackupHeader.forSave(schemaVersion = 6, appVersion = "0.6.0", appVersionCode = 9, createdAt = createdAt, salt = salt, iterations = iterations)

    /** [body] between a manifest whose counts are the body's and an end record with the right count. */
    fun framed(body: List<BackupRecord>, settings: BackupSettings = BackupSettings(), counts: BackupCounts = countsOf(body)): List<BackupRecord> =
        listOf<BackupRecord>(BackupRecord.Manifest(counts, settings)) + body + BackupRecord.End(body.size + 1)

    fun countsOf(body: List<BackupRecord>) = BackupCounts(
        agents = body.count { it is BackupRecord.Agent }, providers = body.count { it is BackupRecord.Provider },
        models = body.count { it is BackupRecord.Model }, modelPrices = body.count { it is BackupRecord.Price },
        aiApps = body.count { it is BackupRecord.App }, spaces = body.count { it is BackupRecord.Space },
        memories = body.count { it is BackupRecord.Memory }, memoryLinks = body.count { it is BackupRecord.Link },
        conversations = body.count { it is BackupRecord.Chat }, conversationEntries = body.count { it is BackupRecord.Entry },
    )

    /** A file holding exactly [records] (framing included), written with core's preamble and WP3's body writer. */
    fun craft(records: List<BackupRecord>, passphrase: String = PASSPHRASE, header: BackupHeader = header()): ByteArray =
        craftRaw(header, passphrase) { writer -> records.forEach(writer::write) }

    /** A file whose body is whatever [body] writes (raw records, too). */
    fun craftRaw(header: BackupHeader = header(), passphrase: String = PASSPHRASE, body: (BackupBodyWriter) -> Unit): ByteArray = runBlocking {
        val keys = BackupKeys.derive(passphrase, header.saltBytes()!!, header.kdf.iterations)
        val preamble = BackupPreamble.write(header, keys)
        val hash = BackupPreamble.read(ByteArrayInputStream(preamble)).orFail().headerHash
        val out = ByteArrayOutputStream()
        out.write(preamble)
        val writer = BackupBodyWriter(out, keys, hash, SecureRandom())
        body(writer)
        writer.finish()
        keys.clear()
        out.toByteArray()
    }

    /** The decrypted body of [file]: every plaintext byte, the records' framing included. */
    fun plaintext(file: ByteArray, passphrase: String = PASSPHRASE): ByteArray = runBlocking {
        val input = ByteArrayInputStream(file)
        val preamble = BackupPreamble.read(input).orFail()
        val keys = preamble.unlock(passphrase).orFail()
        ChunkPlaintextStream(input, keys, preamble.headerHash).readBytes()
    }

    /** The preamble of [file] and its chunks, each with its flags, length, nonce and ciphertext. */
    fun split(file: ByteArray): Pair<ByteArray, List<ByteArray>> {
        val headerLength = ByteBuffer.wrap(file, 10, 4).int
        val preambleLength = 8 + 2 + 4 + headerLength + 32 + 32
        val chunks = ArrayList<ByteArray>()
        var at = preambleLength
        while (at < file.size) {
            val length = ByteBuffer.wrap(file, at + 1, 4).int
            val end = at + 1 + 4 + 12 + length
            chunks += file.copyOfRange(at, end)
            at = end
        }
        return file.copyOfRange(0, preambleLength) to chunks
    }

    /** A preamble with [headerJson] as its header, a key check of zeros and a correct damage digest. */
    fun preambleWith(headerJson: String, version: Int = 1): ByteArray {
        val json = headerJson.encodeToByteArray()
        val prefix = ByteBuffer.allocate(8 + 2 + 4 + json.size).put("AICCBKUP".encodeToByteArray()).putShort(version.toShort()).putInt(json.size).put(json).array()
        val check = ByteArray(32)
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(prefix)
            update(check)
            digest()
        }
        return prefix + check + digest
    }

    fun opener(file: ByteArray): suspend () -> InputStream = { ByteArrayInputStream(file) }
}

/** A dispatcher that counts how often work was sent to it: the key derivation's, to prove it never ran. */
internal class CountingDispatcher(private val inner: CoroutineDispatcher = Dispatchers.Default) : CoroutineDispatcher() {
    val dispatches = AtomicInteger()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches.incrementAndGet()
        inner.dispatch(context, block)
    }
}

/** One transaction that is no transaction: the in-memory stores write at once (Room's tests cover all-or-nothing). */
internal object DirectTransactions : Transactions {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

/** An app made of the in-memory stores (testkit), with its backup port. */
internal class MemoryApp(
    val clock: FakeClock = FakeClock(),
    val gate: MaintenanceGate = LocalMaintenanceGate(),
    var free: Long = Long.MAX_VALUE,
    val kdf: CoroutineContext = Dispatchers.Default,
) {
    val agents = InMemoryAgentRepository()
    val providers = InMemoryProviderRepository()
    val models = InMemoryModelRepository()
    val prices = InMemoryModelPrices(clock)
    val aiApps = InMemoryAiAppRepository()
    val memories = InMemoryMemoryRepository()
    val conversations = InMemoryConversationRepository()
    val tasks = InMemoryTaskRepository()
    val settings = InMemorySettingsRepository()
    val activity = InMemoryActivityLog()
    val toolTitles = ToolTitles(mapOf("tools_open" to "Open more tools"))

    val stores = BackupStores(
        agents = agents, providers = providers, models = models, modelPrices = prices, ownerModelPrices = prices, aiApps = aiApps,
        memories = memories, conversations = conversations, tasks = tasks, settings = settings, activityLog = activity,
        transactions = DirectTransactions,
    )

    val backup = RoomOwnerBackup(
        stores, gate, freeBytes = { free }, app = BackupFiles.APP, starter = BackupFiles.STARTER, toolTitles = toolTitles,
        clock = clock, ids = SequentialIdGenerator(), iterations = BackupFiles.ITERATIONS, kdfContext = kdf,
    )

    /** Saves this app to bytes with the generated-form passphrase. */
    suspend fun saveToBytes(passphrase: String = BackupFiles.PASSPHRASE): Pair<BackupResult<SaveOutcome>, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = backup.saveWithNotes(passphrase, generatedShown = passphrase, open = { out })
        return result to out.toByteArray()
    }
}

internal fun <T> BackupResult<T>.done(): T = when (this) {
    is BackupResult.Done -> value
    is BackupResult.Failed -> throw AssertionError("Expected Done, got ${problem::class.simpleName}: ${problem.text}")
}

internal fun BackupResult<*>.problem(): BackupProblem? = (this as? BackupResult.Failed)?.problem
