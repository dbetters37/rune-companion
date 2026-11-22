package com.aicontrolcenter.data.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Stand-in for [AndroidKeystoreVaultCipher] on the JVM, where no AndroidKeyStore exists. It
 * runs the production AES-GCM framing ([AesGcmVaultCipher]) with an ordinary in-memory key, so
 * only the origin of the key differs from the real thing. Test-only: an exportable key in
 * process memory is exactly what the keystore cipher exists to avoid.
 */
class SoftwareVaultCipher(key: SecretKey = newKey()) : VaultCipher by AesGcmVaultCipher(FixedKey(key)) {

    private class FixedKey(private val key: SecretKey) : VaultKeySource {
        override fun keyForEncryption(): SecretKey = key
        override fun keyForDecryption(): SecretKey = key
    }

    companion object {
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
