package com.aicontrolcenter.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
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
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.DeferredOpenHelperFactory
import com.aicontrolcenter.data.db.SqlCipher
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
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.DatabaseKeyStore
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
 * @param databaseName file name of the database.
 * @param databaseEncryption how the database file is protected. Tests on the JVM pass
 *   [DatabaseEncryption.None], because the SQLCipher native library only exists on a device.
 * @param settingsFileName name of the settings DataStore file.
 * @param vaultCipher encrypts the secret vault. The default uses the AndroidKeyStore; tests on
 *   the JVM, where no keystore exists, pass a software cipher.
 * @param clock stamps the owner's prices and gives "today" for clearing the usage history.
 * @param zone the owner's time zone, read at each use (the owner can change it), for "today".
 */
class DataLayer(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val databaseName: String = AiccDatabase.DEFAULT_NAME,
    private val databaseEncryption: DatabaseEncryption = DatabaseEncryption.Keystore,
    private val settingsFileName: String = DataStoreSettingsRepository.DEFAULT_FILE_NAME,
    private val vaultCipher: VaultCipher = AndroidKeystoreVaultCipher(),
    private val clock: Clock = WallClock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val appContext = context.applicationContext
    private val settingsJob = SupervisorJob()

    private val lazyDatabase = lazy {
        AiccDatabase.create(appContext, databaseName, openHelperFactory(), ioDispatcher)
    }

    val database: AiccDatabase by lazyDatabase

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

    val secretVault: SecretVault by lazy {
        EncryptedFileSecretVault(File(appContext.noBackupFilesDir, VAULT_FILE), vaultCipher, ioDispatcher)
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
     * Null means the platform's own SQLite. The encrypted factory is deferred: fetching the
     * key needs the keystore and a file, which must not happen while the database object is
     * being built, possibly on the main thread.
     */
    private fun openHelperFactory(): SupportSQLiteOpenHelper.Factory? = when (databaseEncryption) {
        DatabaseEncryption.None -> null
        DatabaseEncryption.Keystore -> DeferredOpenHelperFactory {
            val keys = DatabaseKeyStore(
                file = File(appContext.noBackupFilesDir, databaseKeyFile(databaseName)),
                cipher = AndroidKeystoreVaultCipher(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS),
                databaseName = databaseName,
            )
            val key = keys.loadOrCreate(databaseExists = databaseHoldsData())
            try {
                SqlCipher.openHelperFactory(key)
            } finally {
                key.fill(0)
            }
        }
    }

    /** SQLite creates an empty file before it writes anything; only content needs a key to exist. */
    private fun databaseHoldsData(): Boolean =
        try {
            Files.size(appContext.getDatabasePath(databaseName).toPath()) > 0
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
