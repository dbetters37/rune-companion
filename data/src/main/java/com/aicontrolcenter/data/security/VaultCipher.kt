package com.aicontrolcenter.data.security

import java.security.GeneralSecurityException

/**
 * Authenticated encryption for the secret vault. The vault decides what is stored where; a
 * cipher only turns bytes into a sealed blob and back, which keeps the vault logic testable
 * without a device keystore.
 *
 * Implementations must:
 * - return a self-contained blob (it carries whatever [decrypt] needs besides the key, such as
 *   the IV) and never produce the same blob twice for the same input;
 * - authenticate `associatedData` together with the ciphertext, so a blob only opens with the
 *   exact associated data it was sealed with;
 * - be safe to call from several threads.
 */
interface VaultCipher {
    /** @throws GeneralSecurityException if the key is unavailable or encryption fails. */
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /**
     * @throws GeneralSecurityException if [sealed] was modified, was sealed with other
     *   associated data or another key, or is not in this cipher's format.
     */
    @Throws(GeneralSecurityException::class)
    fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray
}
