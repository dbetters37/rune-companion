package com.aicontrolcenter.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.OwnerModelPrices
import com.aicontrolcenter.core.usage.OwnerUsage
import com.aicontrolcenter.core.usage.UsageHousekeeping
import com.aicontrolcenter.core.usage.UsageLedger
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.DeferredOpenHelperFactory
import com.aicontrolcenter.data.recovery.DatabaseHealth
import com.aicontrolcenter.data.recovery.FileOwnerRecovery
import com.aicontrolcenter.data.recovery.FolderGrants
import com.aicontrolcenter.data.recovery.GenerationOpenHelperFactory
import com.aicontrolcenter.data.recovery.GenerationSecretVault
import com.aicontrolcenter.data.recovery.KeyGenerations
import com.aicontrolcenter.data.recovery.RecoveryFiles
import com.aicontrolcenter.data.recovery.SetAside
import com.aicontrolcenter.data.repository.RoomActivityLog
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.repository.RoomAiAppRepository
import com.aicontrolcenter.data.repository.RoomConversationRepository
import com.aicontrolcenter.data.repository.RoomMemoryRepository
import com.aicontrolcenter.data.repository.RoomMessageRepository
import com.aicontrolcenter.data.repository.RoomModelPrices
import com.aicontrolcenter.data.repository.RoomModelRepository
import com.aicontrolcenter.data.repository.RoomOperatorAppRepository
import com.aicontrolcenter.data.repository.RoomOwnerRequestRepository
import com.aicontrolcenter.data.repository.RoomOwnerUsage
import com.aicontrolcenter.data.repository.RoomPermissionRepository
import com.aicontrolcenter.data.repository.RoomPickUpRuleRepository
import com.aicontrolcenter.data.repository.RoomProviderRepository
import com.aicontrolcenter.data.repository.RoomRoutineRepository
import com.aicontrolcenter.data.repository.RoomSharedFolderRepository
import com.aicontrolcenter.data.repository.RoomTaskRepository
import com.aicontrolcenter.data.repository.RoomUsageHousekeeping
import com.aicontrolcenter.data.repository.RoomUsageLedger
import com.aicontrolcenter.data.repository.RoomUsageReports
import com.aicontrolcenter.data.security.DatabaseKeyStore
import com.aicontrolcenter.data.security.DatabaseKeying
import com.aicontrolcenter.data.security.EncryptedFileSecretVault
import com.aicontrolcenter.data.security.VaultCipher
import com.aicontrolcenter.data.settings.DataStoreSettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.time.ZoneId

/**
 * Everything the app persists on the device, behind the ports of `:core`. The composition
 * root creates exactly one of these and hands its members to the framework.
 *
 * Members are created on first use and then shared, so constructing a [DataLayer] is cheap and
 * touches no file. Every database query, file read and write, and encryption runs on
 * [ioDispatcher], whatever thread the caller is on.
 *
 * Where things live (all app-private, nothing leaves the device):
 * - the database in the app's database directory, encrypted as a whole (see
 *   [DatabaseEncryption.Keystore]);
 * - the sealed database key and the secret vault under `noBackupFilesDir`, so they are never
 *   part of a backup or device transfer (they could not be opened on another device anyway);
 * - settings in a Preferences DataStore file.
 *
 * The database key is fetched when the database is first opened, on [ioDispatcher]. If it
 * cannot be provided, the calls that need the database fail with
 * [com.aicontrolcenter.data.security.DatabaseKeyException]; the database is never opened
 * unencrypted instead, and neither the key nor the database is replaced.
 *
 * Key generations (Phase 6, rule 9): the database file, its sealed key, the vault and their keystore entries are those
 * of the generation in use ([KeyGenerations]); generation 1 keeps 0.5.2's names, so its data opens unchanged. Only
 * [ownerRecovery]'s Start over moves on to the next generation.
 *
 * @param databaseName file name of the database (of generation 1; later generations add `.vN` before `.db`).
 * @param databaseEncryption how the database file is protected. Tests on the JVM pass
 *   [DatabaseEncryption.None], because the SQLCipher native library only exists on a device.
 * @param settingsFileName name of the settings DataStore file.
 * @param vaultCipher encrypts the secret vault. The default uses the AndroidKeyStore; tests on
 *   the JVM, where no keystore exists, pass a software cipher.
 * @param clock stamps the owner's prices and gives "today" for clearing the usage history.
 * @param zone the owner's time zone, read at each use (the owner can change it), for "today".
 */
class DataLayer internal constructor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val databaseName: String,
    private val databaseEncryption: DatabaseEncryption,
    private val settingsFileName: String,
    private val vaultCipher: VaultCipher?,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    /** How keys are sealed and a keyed database is opened: the keystore and SQLCipher in the app. */
    private val keying: DatabaseKeying,
) {
    /**
     * @param vaultCipher seals the vault's values; null (the app) uses the keystore entry of the key generation in use.
     */
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        databaseName: String = AiccDatabase.DEFAULT_NAME,
        databaseEncryption: DatabaseEncryption = DatabaseEncryption.Keystore,
        settingsFileName: String = DataStoreSettingsRepository.DEFAULT_FILE_NAME,
        vaultCipher: VaultCipher? = null,
        clock: Clock = WallClock,
        zone: () -> ZoneId = { ZoneId.systemDefault() },
    ) : this(context, ioDispatcher, databaseName, databaseEncryption, settingsFileName, vaultCipher, clock, zone, DatabaseKeying.Device)

    private val appContext = context.applicationContext
    private val settingsJob = SupervisorJob()

    /** Marked by Start over: this process opens no database or vault through the layer any more (rule 9). */
    private val setAside = SetAside()

    /** Which generation of names and keystore entries is in use (Phase 6, rule 9). Built on first use; touches no file. */
    private val generations: KeyGenerations by lazy { KeyGenerations(appContext.noBackupFilesDir, databaseName) }

    private val lazyDatabase = lazy {
        // Room is built with the base name; the open helper opens the file of the generation in use, chosen when the
        // database is first opened (off the main thread).
        AiccDatabase.create(appContext, databaseName, openHelperFactory(), ioDispatcher)
    }

    /**
     * The database itself: internal to this module (security review of wave 1, F4/F10, second review). Through it every
     * table can be read and changed, so outside `:data` only the stores below are handed out; the app's dependency
     * walks fail on any holder of it, or of a DAO, that is not a Room store or the one transaction.
     */
    internal val database: AiccDatabase by lazyDatabase

    val agents: AgentRepository by lazy { RoomAgentRepository(database, ioDispatcher) }
    val providers: ProviderRepository by lazy { RoomProviderRepository(database, ioDispatcher) }
    val models: ModelRepository by lazy { RoomModelRepository(database, ioDispatcher) }
    val aiApps: AiAppRepository by lazy { RoomAiAppRepository(database, ioDispatcher) }
    val activityLog: ActivityLog by lazy { RoomActivityLog(database, ioDispatcher) }
    val conversations: ConversationRepository by lazy { RoomConversationRepository(database, ioDispatcher) }
    val memories: MemoryRepository by lazy { RoomMemoryRepository(database, ioDispatcher) }

    /** The tasks of the owner's objectives; the orchestrator's crash-safe record of its work. */
    val tasks: TaskRepository by lazy { RoomTaskRepository(database, ioDispatcher) }

    /** Every message exchanged while working on an objective. */
    val messages: MessageRepository by lazy { RoomMessageRepository(database, ioDispatcher) }

    /**
     * The owner's permission grants. The composition root hands out only its narrow sides:
     * [com.aicontrolcenter.core.operator.GrantLookup] to the broker and the orchestrator,
     * [com.aicontrolcenter.core.operator.GrantStore] to the owner's ports.
     */
    val permissions: PermissionRepository by lazy { RoomPermissionRepository(database, ioDispatcher) }

    /**
     * Questions put to the owner. Answering one with a grant writes that grant into
     * [permissions] in the same transaction. The broker gets only
     * [com.aicontrolcenter.core.operator.OwnerRequestQueue].
     */
    val ownerRequests: OwnerRequestRepository by lazy { RoomOwnerRequestRepository(database, ioDispatcher) }

    /** The shared-storage folders the owner picked. */
    val sharedFolders: SharedFolderRepository by lazy { RoomSharedFolderRepository(database, ioDispatcher) }

    /** The apps the owner authorised for screen operation; the screen tools read only this. */
    val operatorApps: OperatorAppRepository by lazy { RoomOperatorAppRepository(database, ioDispatcher) }

    /**
     * Where the model gateway records every call (schema 6, rule 7: numbers, ids and labels only).
     * Agent-facing code may hold it (the gateway's journal and daily limiter): it adds and sums,
     * and deletes nothing (security review of wave 1, F4).
     */
    val usageLedger: UsageLedger by lazy { RoomUsageLedger(database, ioDispatcher) }

    /**
     * Retention pruning of usage (F4). Never handed to anything an agent reaches: only the upkeep
     * side holds it, and it never deletes today's rows (they seed the daily limits).
     */
    val usageHousekeeping: UsageHousekeeping by lazy { RoomUsageHousekeeping(database, clock, zone, ioDispatcher) }

    /** Usage and costs for the Usage tab, the Dashboard and the daily limiter. */
    val usageReports: UsageReports by lazy { RoomUsageReports(database, ioDispatcher) }

    /** "Clear usage history" (keeps today). Owner-only: only the Usage tab holds it. */
    val ownerUsage: OwnerUsage by lazy { RoomOwnerUsage(database, clock, zone, ioDispatcher) }

    private val roomModelPrices by lazy { RoomModelPrices(database, clock, ioDispatcher) }

    /**
     * The owner's prices, read (the daily limiter, the Usage tab). A read-only view of the store
     * behind [ownerModelPrices]: it implements [ModelPrices] only, so code an agent reaches cannot
     * cast it into the owner's writer (rule 3), as `ReadOnlySettings` does for the settings.
     */
    val modelPrices: ModelPrices by lazy { ReadOnlyModelPrices(roomModelPrices) }

    /** The owner's prices, written (Settings > Models). Owner-only. Same store as [modelPrices]. */
    val ownerModelPrices: OwnerModelPrices get() = roomModelPrices

    /** Routines (Appendix A). Owner-capable: only the owner's port and the routine runner may hold it. */
    val routines: RoutineRepository by lazy { RoomRoutineRepository(database, ioDispatcher) }

    /** Folder pick-up rules and their imports (Appendix A). Owner-capable, like [routines]. */
    val pickUpRules: PickUpRuleRepository by lazy { RoomPickUpRuleRepository(database, ioDispatcher) }

    /**
     * What the backup file (WP3, Tier B) may read and write, and nothing else (security review of wave 1, F13): the
     * stores whose records the file holds, the settings (merged on restore by `RestoreSettings`), the task store read
     * for the "empty app" check, the activity log, and one database transaction for a restore. Never the key vault,
     * the grants, the requests to the owner, the shared folders or the authorised screen apps (rule 8).
     */
    val backupStores: BackupStores by lazy {
        BackupStores(
            agents = agents, providers = providers, models = models, modelPrices = modelPrices, ownerModelPrices = ownerModelPrices,
            aiApps = aiApps, memories = memories, conversations = conversations, tasks = tasks, settings = settings, activityLog = activityLog,
            transactions = RoomTransactions(database),
        )
    }

    val settings: SettingsRepository by lazy {
        DataStoreSettingsRepository.create(appContext, CoroutineScope(settingsJob + ioDispatcher), settingsFileName)
    }

    /**
     * The API-key vault of the key generation in use (generation 1: `vault/secrets.json` under `aicc.secret-vault.v1`, as
     * in 0.5.2). The generation is read on the vault's first use, off the main thread.
     */
    val secretVault: SecretVault by lazy {
        val folder = appContext.noBackupFilesDir
        val generations = generations
        val cipher = vaultCipher
        val keying = keying
        val io = ioDispatcher
        GenerationSecretVault(io, setAside) {
            val names = generations.namesOf(generations.current())
            EncryptedFileSecretVault(File(folder, names.vaultFile), cipher ?: keying.cipher(names.vaultKeyAlias), io)
        }
    }

    /**
     * Whether the database opens, and if not why (Phase 6, R5): opens it once for writing, on the I/O dispatcher. The
     * app's gate and the Recovery screen use it through [ownerRecovery]; a failure is never "repaired" here.
     */
    val databaseHealth: DatabaseHealth by lazy {
        DatabaseHealth { withContext(ioDispatcher) { database.openHelper.writableDatabase } }
    }

    /**
     * Owner-only (rule 9; both architecture tests list [OwnerRecovery]): the database check and Start over, for the
     * Recovery screen. Start over puts the database, its sealed key and the vault aside in `noBackupFilesDir`, moves on to
     * the next key generation and resets the first-run bookkeeping; it never deletes. The app restarts next (rule 30).
     */
    val ownerRecovery: OwnerRecovery by lazy {
        FileOwnerRecovery(
            health = databaseHealth,
            generationsOf = { generations },
            settings = settings,
            filesOf = {
                RecoveryFiles(
                    databasesFolder = appContext.getDatabasePath(databaseName).absoluteFile.parentFile ?: appContext.filesDir,
                    noBackupFilesDir = appContext.noBackupFilesDir,
                )
            },
            beforeSettingAside = { retireForStartOver() },
            releaseFolderGrants = { FolderGrants.releaseAll(appContext) },
            clock = clock,
            zone = zone,
            io = ioDispatcher,
        )
    }

    /** Start over: closes the database and refuses every later open of this generation in this process. */
    private suspend fun retireForStartOver() {
        setAside.mark()
        if (lazyDatabase.isInitialized()) {
            withContext(ioDispatcher) {
                try {
                    database.close()
                } catch (_: RuntimeException) {
                    // It did not open in the first place; there is nothing to close.
                }
            }
        }
    }

    /**
     * Releases the database and the settings file and returns once both are free, so that
     * another [DataLayer] can be opened over the same files. Meant for tests; the app keeps
     * its instance for the life of the process. The instance must not be used afterwards.
     */
    suspend fun close() {
        settingsJob.cancelAndJoin()
        if (lazyDatabase.isInitialized()) {
            withContext(ioDispatcher) { database.close() }
        }
    }

    /**
     * Deferred: reading the generation note, fetching the key (the keystore and a file) and choosing the file must not
     * happen while the database object is being built, possibly on the main thread. [DatabaseEncryption.None] opens the
     * generation's file with the platform's own SQLite.
     */
    private fun openHelperFactory(): SupportSQLiteOpenHelper.Factory = DeferredOpenHelperFactory {
        // Phase 6 (rule 9): the file, sealed key and keystore entry of the key generation in use. Generation 1 is what
        // 0.5.2 used, so its data opens unchanged.
        setAside.check()
        val names = generations.namesOf(generations.current())
        when (databaseEncryption) {
            DatabaseEncryption.None -> GenerationOpenHelperFactory(names.databaseFile, FrameworkSQLiteOpenHelperFactory(), setAside)
            DatabaseEncryption.Keystore -> {
                val keys = DatabaseKeyStore(
                    file = File(appContext.noBackupFilesDir, names.databaseKeyFile),
                    cipher = keying.cipher(names.databaseKeyAlias),
                    databaseName = names.databaseFile,
                )
                val key = keys.loadOrCreate(databaseExists = databaseHoldsData(names.databaseFile))
                try {
                    GenerationOpenHelperFactory(names.databaseFile, keying.openHelperFactory(key), setAside)
                } finally {
                    key.fill(0)
                }
            }
        }
    }

    /** SQLite creates an empty file before it writes anything; only content needs a key to exist. */
    private fun databaseHoldsData(fileName: String): Boolean =
        try {
            Files.size(appContext.getDatabasePath(fileName).toPath()) > 0
        } catch (_: NoSuchFileException) {
            false
        }

    /** [ModelPrices] and nothing else, over [source]. */
    private class ReadOnlyModelPrices(private val source: ModelPrices) : ModelPrices by source

    /** One Room transaction around a block (the database file opens on first use). Holds the database, not this layer. */
    private class RoomTransactions(private val database: AiccDatabase) : Transactions {
        override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction { block() }
    }

    companion object {
        /** Path of the vault file, relative to `noBackupFilesDir`. */
        const val VAULT_FILE = "vault/secrets.json"

        /** Path of the sealed key of the database [databaseName], relative to `noBackupFilesDir`. */
        fun databaseKeyFile(databaseName: String): String = "database-keys/$databaseName.key"
    }
}

/** Runs a block in one database transaction: all of it is written, or nothing. */
interface Transactions {
    suspend fun <R> run(block: suspend () -> R): R
}

/**
 * The stores a backup saves and restores (security review of wave 1, F13), built by [DataLayer.backupStores]. It
 * holds no key vault, grant store, request store, shared-folder store or authorised-app store, so rule 8's
 * exclusions do not rest on the backup code alone: it cannot reach them. `BackupWiringTest` (app) walks it.
 */
class BackupStores(
    val agents: AgentRepository,
    val providers: ProviderRepository,
    val models: ModelRepository,
    /** The owner's prices, read when saving. */
    val modelPrices: ModelPrices,
    /** The owner's prices, written when restoring. */
    val ownerModelPrices: OwnerModelPrices,
    val aiApps: AiAppRepository,
    /** Spaces, memories and their links. */
    val memories: MemoryRepository,
    val conversations: ConversationRepository,
    /** Read only for "is this app empty?" before a restore: objectives are never in the file. */
    val tasks: TaskRepository,
    /** Read when saving ([com.aicontrolcenter.core.backup.BackupSettings.of]); merged when restoring. */
    val settings: SettingsRepository,
    /** A save or a restore is logged with counts only. */
    val activityLog: ActivityLog,
    /** One transaction for a whole restore. */
    val transactions: Transactions,
)
