package com.aicontrolcenter.data.security

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM sealing with whatever key [key] supplies. The production key comes from the
 * AndroidKeyStore ([AndroidKeystoreVaultCipher]); tests supply a software key, so this exact
 * framing code runs in both.
 *
 * Blob layout: `[format = 1][12-byte IV][ciphertext + 128-bit tag]`.
 *
 * The IV is never chosen here. The cipher is initialised without one and the provider
 * generates a fresh random IV per encryption; that is the only mode the AndroidKeyStore
 * accepts for keys that require randomised encryption, and it rules out IV reuse by
 * construction.
 */
internal class AesGcmVaultCipher(private val key: () -> SecretKey) : VaultCipher {

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val body = cipher.doFinal(plaintext)
        val iv = cipher.iv
        if (iv == null || iv.size != IV_BYTES) {
            throw GeneralSecurityException("The cipher did not supply a $IV_BYTES-byte IV.")
        }
        return ByteArray(HEADER_BYTES + body.size).also { sealed ->
            sealed[0] = FORMAT_V1
            iv.copyInto(sealed, destinationOffset = 1)
            body.copyInto(sealed, destinationOffset = HEADER_BYTES)
        }
    }

    override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray {
        if (sealed.size < HEADER_BYTES + TAG_BYTES || sealed[0] != FORMAT_V1) {
            throw GeneralSecurityException("Not a sealed vault value in a known format.")
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed, HEADER_BYTES, sealed.size - HEADER_BYTES)
    }

    internal companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_V1: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val HEADER_BYTES = 1 + IV_BYTES
    }
}
