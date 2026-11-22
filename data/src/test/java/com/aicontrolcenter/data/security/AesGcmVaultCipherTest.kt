package com.aicontrolcenter.data.security

import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import kotlin.experimental.xor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The AES-GCM framing shared by the keystore cipher and the software test cipher. */
class AesGcmVaultCipherTest {
    private val cipher = SoftwareVaultCipher()
    private val aad = "alias".toByteArray()

    @Test
    fun `round-trips data of any length, including none`() {
        for (length in listOf(0, 1, 15, 16, 17, 4_096)) {
            val plaintext = ByteArray(length) { (it * 31).toByte() }
            assertContentEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext, aad), aad), "length $length")
        }
    }

    @Test
    fun `a sealed value is format byte, 12-byte IV, ciphertext and 16-byte tag`() {
        val plaintext = "sk-secret".toByteArray()
        val sealed = cipher.encrypt(plaintext, aad)
        assertEquals(1, sealed[0])
        assertEquals(1 + 12 + plaintext.size + 16, sealed.size)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("sk-secret"))
    }

    @Test
    fun `every encryption uses a fresh IV, so equal secrets do not look equal`() {
        val plaintext = "same secret".toByteArray()
        val sealed = List(200) { cipher.encrypt(plaintext, aad) }
        val ivs = sealed.map { it.copyOfRange(1, 13).toList() }.toSet()
        assertEquals(200, ivs.size)
        assertEquals(200, sealed.map { it.toList() }.toSet().size)
    }

    @Test
    fun `a value only opens with the associated data it was sealed with`() {
        val sealed = cipher.encrypt("secret".toByteArray(), "provider/a".toByteArray())
        assertFailsWith<AEADBadTagException> { cipher.decrypt(sealed, "provider/b".toByteArray()) }
        assertFailsWith<AEADBadTagException> { cipher.decrypt(sealed, ByteArray(0)) }
    }

    @Test
    fun `flipping any single bit is detected`() {
        val sealed = cipher.encrypt("secret".toByteArray(), aad)
        for (position in sealed.indices) {
            val tampered = sealed.copyOf().also { it[position] = it[position] xor 0x01 }
            assertFailsWith<GeneralSecurityException>("bit flip at byte $position went unnoticed") {
                cipher.decrypt(tampered, aad)
            }
        }
    }

    @Test
    fun `truncated, empty or foreign input is rejected as a security failure, not a crash`() {
        val sealed = cipher.encrypt("secret".toByteArray(), aad)
        val malformed = listOf(
            ByteArray(0),
            byteArrayOf(1),
            sealed.copyOf(1 + 12 + 15), // shorter than IV plus tag
            sealed.copyOf(sealed.size - 1),
            sealed + 0,
            byteArrayOf(2) + sealed.copyOfRange(1, sealed.size), // unknown format
            ByteArray(64),
        )
        for (input in malformed) {
            assertFailsWith<GeneralSecurityException>("accepted ${input.size} malformed bytes") { cipher.decrypt(input, aad) }
        }
    }

    @Test
    fun `a value sealed with one key does not open with another`() {
        val sealed = cipher.encrypt("secret".toByteArray(), aad)
        assertFailsWith<AEADBadTagException> { SoftwareVaultCipher().decrypt(sealed, aad) }
    }
}
