package com.aicontrolcenter.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.aicontrolcenter.data.security.DatabaseKeyStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** The one place that touches the SQLCipher library. */
internal object SqlCipher {
    private const val NATIVE_LIBRARY = "sqlcipher"
    private const val HEX = "0123456789abcdef"

    /**
     * An open helper factory whose databases are encrypted with [key]. Loads the native
     * library first, so this must not run on the JVM (Robolectric has no such library).
     *
     * @param key the 32-byte database key. It is copied; the caller may overwrite it afterwards.
     */
    fun openHelperFactory(key: ByteArray): SupportSQLiteOpenHelper.Factory {
        val passphrase = rawKeyPassphrase(key)
        System.loadLibrary(NATIVE_LIBRARY)
        // The factory keeps the passphrase for as long as it lives: every connection it opens,
        // including the extra ones of write-ahead logging, has to be keyed with it.
        return SupportOpenHelperFactory(passphrase)
    }

    /**
     * SQLCipher's "raw key" notation, `x'<64 hex digits>'`. A passphrase in this form is used
     * directly as the AES key. Any other passphrase is stretched with 256,000 rounds of PBKDF2
     * for every connection that is opened, which costs most of a second on a phone and adds
     * nothing to a key that is already 256 random bits.
     */
    fun rawKeyPassphrase(key: ByteArray): ByteArray {
        require(key.size == DatabaseKeyStore.KEY_BYTES) { "A database key is ${DatabaseKeyStore.KEY_BYTES} bytes long." }
        val passphrase = ByteArray(key.size * 2 + 3)
        passphrase[0] = 'x'.code.toByte()
        passphrase[1] = '\''.code.toByte()
        key.forEachIndexed { index, byte ->
            passphrase[2 + index * 2] = HEX[(byte.toInt() shr 4) and 0x0F].code.toByte()
            passphrase[3 + index * 2] = HEX[byte.toInt() and 0x0F].code.toByte()
        }
        passphrase[passphrase.lastIndex] = '\''.code.toByte()
        return passphrase
    }
}
