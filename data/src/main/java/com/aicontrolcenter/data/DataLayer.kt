package com.aicontrolcenter.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.repository.RoomActivityLog
import com.aicontrolcenter.data.repository.RoomAgentRepository
import com.aicontrolcenter.data.repository.RoomAiAppRepository
import com.aicontrolcenter.data.repository.RoomModelRepository
import com.aicontrolcenter.data.repository.RoomProviderRepository
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
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

/**
 * Everything the app persists on the device, behind the ports of `:core`. The composition
 * root creates exactly one of these and hands its members to the framework.
 *
 * Members are created on first use and then shared, so constructing a [DataLayer] is cheap and
 * touches no file. Every database query, file read and write, and encryption runs on
 * [ioDispatcher], whatever thread the caller is on.
 *
 * Where things live (all app-private, nothing leaves the device):
 * - the database in the app's database directory;
 * - settings in a Preferences DataStore file;
 * - the secret vault under `noBackupFilesDir`, so it is never part of a backup or device
 *   transfer (it could not be decrypted on another device anyway).
 *
 * @param databaseName file name of the database.
 * @param openHelperFactory optional replacement for the platform SQLite open helper; this is
 *   where full-database encryption is plugged in.
 * @param settingsFileName name of the settings DataStore file.
 * @param vaultCipher encrypts the secret vault. The default uses the AndroidKeyStore; tests on
 *   the JVM, where no keystore exists, pass a software cipher.
 */
class DataLayer(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val databaseName: String = AiccDatabase.DEFAULT_NAME,
    private val openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
    private val settingsFileName: String = DataStoreSettingsRepository.DEFAULT_FILE_NAME,
    private val vaultCipher: VaultCipher = AndroidKeystoreVaultCipher(),
) {
    private val appContext = context.applicationContext
    private val settingsJob = SupervisorJob()

    private val lazyDatabase = lazy {
        AiccDatabase.create(appContext, databaseName, openHelperFactory, ioDispatcher)
    }

    val database: AiccDatabase by lazyDatabase

    val agents: AgentRepository by lazy { RoomAgentRepository(database, ioDispatcher) }
    val providers: ProviderRepository by lazy { RoomProviderRepository(database, ioDispatcher) }
    val models: ModelRepository by lazy { RoomModelRepository(database, ioDispatcher) }
    val aiApps: AiAppRepository by lazy { RoomAiAppRepository(database, ioDispatcher) }
    val activityLog: ActivityLog by lazy { RoomActivityLog(database, ioDispatcher) }

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

    companion object {
        /** Path of the vault file, relative to `noBackupFilesDir`. */
        const val VAULT_FILE = "vault/secrets.json"
    }
}
