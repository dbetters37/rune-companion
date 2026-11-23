package com.aicontrolcenter.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.DeferredOpenHelperFactory
import com.aicontrolcenter.data.db.SqlCipher
import com.aicontrolcenter.data.repository.RoomActivityLog
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.repository.RoomAiAppRepository
import com.aicontrolcenter.data.repository.RoomConversationRepository
import com.aicontrolcenter.data.repository.RoomMemoryRepository
import com.aicontrolcenter.data.repository.RoomMessageRepository
import com.aicontrolcenter.data.repository.RoomModelRepository
import com.aicontrolcenter.data.repository.RoomProviderRepository
import com.aicontrolcenter.data.repository.RoomTaskRepository
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
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

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
 */
class DataLayer(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val databaseName: String = AiccDatabase.DEFAULT_NAME,
    private val databaseEncryption: DatabaseEncryption = DatabaseEncryption.Keystore,
    private val settingsFileName: String = DataStoreSettingsRepository.DEFAULT_FILE_NAME,
    private val vaultCipher: VaultCipher = AndroidKeystoreVaultCipher(),
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

    companion object {
        /** Path of the vault file, relative to `noBackupFilesDir`. */
        const val VAULT_FILE = "vault/secrets.json"

        /** Path of the sealed key of the database [databaseName], relative to `noBackupFilesDir`. */
        fun databaseKeyFile(databaseName: String): String = "database-keys/$databaseName.key"
    }
}
