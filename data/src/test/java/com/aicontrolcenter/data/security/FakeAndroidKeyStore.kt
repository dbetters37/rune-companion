package com.aicontrolcenter.data.security

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * A security provider that registers under the name "AndroidKeyStore", so the production key
 * handling runs unchanged on the JVM. It offers what that code uses: a key store and an AES
 * key generator configured with a [KeyGenParameterSpec]. Keys are ordinary software keys, so
 * the JVM's own AES-GCM works with them.
 *
 * The test scripts its misbehaviour ([failGetKey], [hideKeys], [failGeneration]) and reads
 * back what was done to it ([generated], [deleted], [getKeyCalls]).
 *
 * The provider list is global to the JVM: [install] in a test, [uninstall] after it.
 */
class FakeAndroidKeyStore : Provider(NAME, 1.0, "Test double of the Android keystore") {
    private val storedKeys = Collections.synchronizedMap(LinkedHashMap<String, SecretKey>())
    private val getKeyFailures = ArrayDeque<Exception>()

    /** The spec of every key that was generated, in order. */
    val generated: MutableList<KeyGenParameterSpec> = Collections.synchronizedList(mutableListOf())

    /** Every alias that was deleted, in order. */
    val deleted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    var getKeyCalls = 0
        private set

    /** While true, `getKey` answers null even for an entry that exists, as the real keystore sometimes does. */
    var hideKeys = false

    /** Thrown by the next key generation instead of creating a key. */
    var failGeneration: RuntimeException? = null

    val aliases: Set<String> get() = storedKeys.keys.toSet()

    init {
        putService(FakeService("KeyStore", NAME) { StoreSpi() })
        putService(FakeService("KeyGenerator", "AES") { GeneratorSpi() })
    }

    fun install() {
        check(Security.addProvider(this) != -1) { "an $NAME provider is already installed" }
    }

    fun uninstall() = Security.removeProvider(NAME)

    /** Makes the next [times] calls of `getKey` throw exceptions produced by [failure]. */
    fun failGetKey(times: Int, failure: () -> Exception) {
        repeat(times) { getKeyFailures.addLast(failure()) }
    }

    private inner class FakeService(type: String, algorithm: String, private val create: () -> Any) :
        Service(this@FakeAndroidKeyStore, type, algorithm, create.javaClass.name, null, null) {
        override fun newInstance(constructorParameter: Any?): Any = create()
    }

    private inner class StoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            getKeyCalls++
            getKeyFailures.removeFirstOrNull()?.let { throw it }
            return if (hideKeys) null else storedKeys[alias]
        }

        override fun engineContainsAlias(alias: String): Boolean = alias in storedKeys

        override fun engineDeleteEntry(alias: String) {
            deleted += alias
            storedKeys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(aliases)
        override fun engineSize(): Int = storedKeys.size
        override fun engineIsKeyEntry(alias: String): Boolean = alias in storedKeys
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = null
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) = unsupported()
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = unsupported()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate?) = unsupported()
        override fun engineStore(stream: OutputStream?, password: CharArray?) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("not needed by the code under test")
    }

    private inner class GeneratorSpi : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
            spec = params as KeyGenParameterSpec
        }

        override fun engineGenerateKey(): SecretKey {
            val spec = checkNotNull(spec) { "generateKey() before init(KeyGenParameterSpec)" }
            failGeneration?.let { throw it }
            val key = SecretKeySpec(ByteArray(spec.keySize / 8).also(SecureRandom()::nextBytes), "AES")
            generated += spec
            storedKeys[spec.keystoreAlias] = key
            return key
        }

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("keystore keys need a spec")
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("keystore keys need a spec")
    }

    companion object {
        const val NAME = "AndroidKeyStore"
    }
}
