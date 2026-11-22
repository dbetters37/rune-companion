package com.aicontrolcenter.data.db

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

/**
 * The part of the SQLCipher glue that runs without the native library. Opening encrypted
 * databases is covered by the instrumented `EncryptedDatabaseTest`.
 */
class SqlCipherTest {

    @Test
    fun `the key is handed to SQLCipher in raw-key notation, so it is used as the AES key itself`() {
        val key = ByteArray(32) { (it * 8 + 1).toByte() }

        val passphrase = SqlCipher.rawKeyPassphrase(key)

        assertEquals(
            "x'0109111921293139414951596169717981899199a1a9b1b9c1c9d1d9e1e9f1f9'",
            String(passphrase, Charsets.US_ASCII),
        )
        // 67 bytes is the length at which SQLCipher recognises a raw key and skips key derivation.
        assertEquals(67, passphrase.size)
        assertNotSame(key, passphrase)
    }

    @Test
    fun `all byte values are written as two lower-case hex digits`() {
        val passphrase = String(SqlCipher.rawKeyPassphrase(ByteArray(32) { if (it == 0) 0x00 else if (it == 1) 0xFF.toByte() else 0x0A }), Charsets.US_ASCII)
        assertEquals("x'00ff" + "0a".repeat(30) + "'", passphrase)
    }

    @Test
    fun `a key of any other length is refused`() {
        for (length in listOf(0, 16, 31, 33, 64)) {
            assertFailsWith<IllegalArgumentException> { SqlCipher.rawKeyPassphrase(ByteArray(length)) }
        }
    }
}
