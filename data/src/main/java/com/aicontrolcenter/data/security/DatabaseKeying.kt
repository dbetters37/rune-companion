package com.aicontrolcenter.data.security

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.aicontrolcenter.data.db.SqlCipher

/**
 * How the data layer seals keys and opens a keyed database (Phase 6, WP2). The app uses [Device]: keystore entries and
 * SQLCipher. Tests of `:data` on the JVM, where neither the keystore's hardware nor SQLCipher's native library exists,
 * use [PlainForTests] with a fake "AndroidKeyStore" provider, so every step of the key handling (generations, sealed
 * keys, the vault, Start over) runs as in the app; only the final open uses plain SQLite.
 */
internal interface DatabaseKeying {
    /** The cipher that seals with the keystore entry [alias]. */
    fun cipher(alias: String): VaultCipher

    /** An open helper factory for a database keyed with [key] (the caller wipes [key] afterwards). */
    fun openHelperFactory(key: ByteArray): SupportSQLiteOpenHelper.Factory

    /** The app's: the AndroidKeyStore and SQLCipher. */
    object Device : DatabaseKeying {
        override fun cipher(alias: String): VaultCipher = AndroidKeystoreVaultCipher(alias)

        override fun openHelperFactory(key: ByteArray): SupportSQLiteOpenHelper.Factory = SqlCipher.openHelperFactory(key)
    }

    /** JVM tests only: the keystore cipher (over whatever provider is installed), and plain SQLite that ignores the key. */
    object PlainForTests : DatabaseKeying {
        override fun cipher(alias: String): VaultCipher = AndroidKeystoreVaultCipher(alias)

        override fun openHelperFactory(key: ByteArray): SupportSQLiteOpenHelper.Factory {
            require(key.size == DatabaseKeyStore.KEY_BYTES) { "A database key is ${DatabaseKeyStore.KEY_BYTES} bytes long." }
            return FrameworkSQLiteOpenHelperFactory()
        }
    }
}
