package com.aicontrolcenter.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.security.SecretVaultException
import com.aicontrolcenter.testkit.contract.SecretVaultContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException
import java.util.Base64
import kotlin.experimental.xor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EncryptedFileSecretVaultContractTest : SecretVaultContract() {
    @get:Rule val folder = TemporaryFolder()

    override fun createVault(): SecretVault =
        EncryptedFileSecretVault(File(folder.newFolder(), "secrets.json"), SoftwareVaultCipher())
}

/** Vault behaviour beyond the port contract: persistence, tamper detection, concurrency, failures. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EncryptedFileSecretVaultTest {
    @get:Rule val folder = TemporaryFolder()

    private val cipher = SoftwareVaultCipher()
    private val file: File by lazy { File(folder.newFolder("vault"), "secrets.json") }

    /** A new vault object over the same file and key, as after an app restart. */
    private fun vault(cipher: VaultCipher = this.cipher) = EncryptedFileSecretVault(file, cipher)

    private fun storedEntries(): Map<String, String> =
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("entries").jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    private fun rewriteEntries(change: (Map<String, String>) -> Map<String, String>) {
        val entries = change(storedEntries()).mapValues { JsonPrimitive(it.value) }
        file.writeText(JsonObject(mapOf("version" to JsonPrimitive(1), "entries" to JsonObject(entries))).toString())
    }

    @Test
    fun `secrets are still there in a new vault over the same file`() = runTest {
        vault().apply {
            put("provider/a", "sk-alpha")
            put("provider/b", "sk-beta")
            put("provider/gone", "sk-gone")
            delete("provider/gone")
        }

        val reopened = vault()
        assertEquals(setOf("provider/a", "provider/b"), reopened.aliases())
        assertEquals("sk-alpha", reopened.get("provider/a"))
        assertEquals("sk-beta", reopened.get("provider/b"))
        assertFalse(reopened.contains("provider/gone"))
    }

    @Test
    fun `the file holds ciphertext only and no temporary files are left behind`() = runTest {
        val secret = "sk-live-0123456789abcdef"
        vault().put("provider/a", secret)

        val raw = file.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(secret), "plaintext secret found in the vault file")
        val sealed = Base64.getDecoder().decode(storedEntries().getValue("provider/a"))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(secret), "plaintext secret found in the sealed value")
        assertEquals(1, Json.parseToJsonElement(file.readText()).jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(listOf("secrets.json"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `storing the same secret twice produces different ciphertext`() = runTest {
        val vault = vault()
        vault.put("provider/a", "same")
        val first = storedEntries().getValue("provider/a")
        vault.put("provider/a", "same")
        vault.put("provider/b", "same")
        val entries = storedEntries()
        assertEquals(3, setOf(first, entries.getValue("provider/a"), entries.getValue("provider/b")).size)
    }

    @Test
    fun `modified ciphertext is rejected and other secrets stay readable`() = runTest {
        vault().apply {
            put("provider/a", "sk-alpha")
            put("provider/b", "sk-beta")
        }
        rewriteEntries { entries ->
            val sealed = Base64.getDecoder().decode(entries.getValue("provider/a"))
            sealed[sealed.size / 2] = sealed[sealed.size / 2] xor 0x40
            entries + ("provider/a" to Base64.getEncoder().encodeToString(sealed))
        }

        val reopened = vault()
        val failure = assertFailsWith<SecretVaultException> { reopened.get("provider/a") }
        // Coroutines may wrap an exception in a copy of itself when it crosses dispatchers, so
        // the whole cause chain is searched for the cipher's authentication failure.
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is GeneralSecurityException })
        assertFalse("sk-alpha" in failure.message.orEmpty())
        assertEquals("sk-beta", reopened.get("provider/b"))

        // The damaged entry is still listed, and storing the secret again repairs it.
        assertTrue(reopened.contains("provider/a"))
        reopened.put("provider/a", "sk-alpha-2")
        assertEquals("sk-alpha-2", vault().get("provider/a"))
    }

    @Test
    fun `ciphertext moved to another alias is rejected`() = runTest {
        vault().apply {
            put("provider/cheap", "sk-cheap")
            put("provider/expensive", "sk-expensive")
        }
        rewriteEntries { entries ->
            mapOf(
                "provider/cheap" to entries.getValue("provider/expensive"),
                "provider/expensive" to entries.getValue("provider/cheap"),
            )
        }

        val reopened = vault()
        assertFailsWith<SecretVaultException> { reopened.get("provider/cheap") }
        assertFailsWith<SecretVaultException> { reopened.get("provider/expensive") }
    }

    @Test
    fun `ciphertext copied under a new alias is rejected`() = runTest {
        vault().put("provider/a", "sk-alpha")
        rewriteEntries { entries -> entries + ("provider/copy" to entries.getValue("provider/a")) }

        val reopened = vault()
        assertFailsWith<SecretVaultException> { reopened.get("provider/copy") }
        assertEquals("sk-alpha", reopened.get("provider/a"))
    }

    @Test
    fun `an entry that is not valid Base64 is rejected`() = runTest {
        vault().put("provider/a", "sk-alpha")
        rewriteEntries { it + ("provider/a" to "***not base64***") }
        assertFailsWith<SecretVaultException> { vault().get("provider/a") }
    }

    @Test
    fun `when the key is lost, old secrets fail to decrypt and new ones can be stored`() = runTest {
        vault().put("provider/a", "sk-alpha")

        val afterKeyLoss = vault(SoftwareVaultCipher())
        assertFailsWith<SecretVaultException> { afterKeyLoss.get("provider/a") }
        assertEquals(setOf("provider/a"), afterKeyLoss.aliases())
        afterKeyLoss.put("provider/a", "sk-re-entered")
        assertEquals("sk-re-entered", afterKeyLoss.get("provider/a"))
        afterKeyLoss.delete("provider/a")
        assertNull(afterKeyLoss.get("provider/a"))
    }

    @Test
    fun `concurrent puts all end up in the vault and on disk`() = runTest {
        val vault = vault()
        val writers = 64
        coroutineScope {
            repeat(writers) { n ->
                launch(Dispatchers.Default) { vault.put("provider/$n", "secret-$n") }
            }
        }
        val expected = (0 until writers).associate { "provider/$it" to "secret-$it" }
        assertEquals(expected.keys, vault.aliases())

        val reopened = vault()
        assertEquals(expected, reopened.aliases().associateWith { reopened.get(it) })
    }

    @Test
    fun `concurrent puts and deletes leave exactly the surviving aliases`() = runTest {
        val vault = vault()
        repeat(20) { vault.put("old/$it", "x") }
        coroutineScope {
            repeat(20) { n ->
                launch(Dispatchers.Default) { vault.delete("old/$n") }
                launch(Dispatchers.Default) { vault.put("new/$n", "secret-$n") }
            }
        }
        val expected = (0 until 20).map { "new/$it" }.toSet()
        assertEquals(expected, vault.aliases())
        assertEquals(expected, vault().aliases())
    }

    @Test
    fun `reading an empty vault or deleting a missing alias does not create the file`() = runTest {
        val vault = vault()
        assertNull(vault.get("nothing"))
        assertFalse(vault.contains("nothing"))
        assertEquals(emptySet(), vault.aliases())
        vault.delete("nothing")
        assertFalse(file.exists())
    }

    private fun setAsideFiles(): List<File> =
        file.parentFile!!.listFiles { candidate -> candidate.name.startsWith("secrets.json.corrupt-") }!!.toList()

    @Test
    fun `a damaged vault file is set aside and the vault starts empty`() = runTest {
        vault().put("provider/a", "sk-alpha")
        val damaged = "{\"version\":1,\"entries\":{\"provider/a\":\"AAAA"
        file.writeText(damaged)

        val reopened = vault()
        assertEquals(emptySet(), reopened.aliases())
        assertNull(reopened.get("provider/a"))
        assertEquals(listOf(damaged), setAsideFiles().map { it.readText() }, "the damaged file should be kept aside")

        reopened.put("provider/a", "sk-new")
        assertEquals("sk-new", vault().get("provider/a"))
    }

    @Test
    fun `every damaged file is kept - a second incident does not overwrite the first copy`() = runTest {
        val incidents = listOf("not json", "[1,2,3]", "{\"entries\":{}}", "{\"version\":\"1\",\"entries\":{}}", "{\"version\":0}", "")
        for (damaged in incidents) {
            file.writeText(damaged)
            assertEquals(emptySet(), vault().aliases(), "content '$damaged'")
            assertFalse(file.exists())
        }
        assertEquals(incidents.sorted(), setAsideFiles().map { it.readText() }.sorted())
    }

    @Test
    fun `entries of the wrong shape under the current version count as damage, not as an empty vault`() = runTest {
        file.writeText("""{"version":1,"entries":{"provider/a":{"ct":"AAAA"}}}""")
        assertEquals(emptySet(), vault().aliases())
        assertEquals(1, setAsideFiles().size)
    }

    @Test
    fun `a vault written by a newer format is refused and left untouched`() = runTest {
        // A newer format is expected to change the shape of the entries; neither a v1-shaped
        // nor a differently shaped file may be mistaken for damage and moved away.
        val newerFiles = listOf(
            """{"version":2,"entries":{"provider/a":{"ct":"AAAA","iv":"BBBB"}}}""",
            """{"version":2,"entries":{"provider/a":"AAAA"},"somethingNew":true}""",
            """{"version":3,"secrets":[{"alias":"provider/a","sealed":"AAAA"}]}""",
            """{"version":4294967297}""",
        )
        for (newer in newerFiles) {
            file.writeText(newer)

            val vault = vault()
            val failure = assertFailsWith<SecretVaultException> { vault.aliases() }
            assertTrue("cannot read" in failure.message.orEmpty(), failure.message)
            assertFailsWith<SecretVaultException> { vault.get("provider/a") }
            assertFailsWith<SecretVaultException> { vault.put("provider/b", "sk-beta") }
            assertFailsWith<SecretVaultException> { vault.delete("provider/a") }
            assertEquals(newer, file.readText())
            assertEquals(listOf("secrets.json"), file.parentFile!!.list()!!.toList())
        }
    }

    @Test
    fun `a vault file that exists but cannot be opened is an error, never an empty vault`() = runTest {
        vault().apply {
            put("provider/a", "sk-alpha")
            put("provider/b", "sk-beta")
        }
        // A directory in the file's place cannot be opened as a file, whoever runs the test.
        val kept = File(file.path + ".kept")
        assertTrue(file.renameTo(kept))
        assertTrue(file.mkdir())

        val vault = vault()
        assertFailsWith<SecretVaultException> { vault.aliases() }
        assertFailsWith<SecretVaultException> { vault.get("provider/a") }
        assertFailsWith<SecretVaultException> { vault.contains("provider/a") }
        assertFailsWith<SecretVaultException> { vault.put("provider/c", "sk-gamma") }
        assertFailsWith<SecretVaultException> { vault.delete("provider/a") }

        // Once the file can be opened again the same vault object sees every secret, and a new
        // one is added to them instead of replacing them.
        assertTrue(file.delete())
        assertTrue(kept.renameTo(file))
        assertEquals(setOf("provider/a", "provider/b"), vault.aliases())
        vault.put("provider/c", "sk-gamma")
        assertEquals(setOf("provider/a", "provider/b", "provider/c"), storedEntries().keys)
        assertEquals("sk-alpha", vault().get("provider/a"))
    }

    @Test
    fun `a write that cannot be committed fails the put and is not reported as stored`() = runTest {
        val vault = vault()
        vault.put("provider/a", "sk-alpha")

        // The new content can be written next to the file, but the final rename cannot succeed
        // while a non-empty directory occupies the file's name.
        val kept = File(file.path + ".kept")
        assertTrue(file.renameTo(kept))
        assertTrue(file.mkdir())
        File(file, "occupied").writeText("x")

        assertFailsWith<SecretVaultException> { vault.put("provider/b", "sk-beta") }
        assertFalse(File(file.path + ".tmp").exists(), "the side file of the failed write was left behind")
        // The vault does not go on believing in a write that never happened.
        assertFailsWith<SecretVaultException> { vault.contains("provider/b") }

        assertTrue(file.deleteRecursively())
        assertTrue(kept.renameTo(file))
        assertEquals(setOf("provider/a"), vault.aliases())
        assertNull(vault.get("provider/b"))
        vault.put("provider/b", "sk-beta")
        assertEquals(mapOf("provider/a" to "sk-alpha", "provider/b" to "sk-beta"), vault().run { aliases().associateWith { get(it) } })
    }

    @Test
    fun `a side file left by an interrupted first write does not turn into vault content`() = runTest {
        File(file.path + ".tmp").writeText("""{"version":1,"entries":{"provider/a":"AAAA"}}""")

        val vault = vault()
        assertEquals(emptySet(), vault.aliases())
        vault.put("provider/b", "sk-beta")
        assertEquals(setOf("provider/b"), vault().aliases())
        assertEquals(listOf("secrets.json"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `a cipher failure while storing is reported, stores nothing and does not leak the secret`() = runTest {
        val failing = object : VaultCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("keystore unavailable")

            override fun decrypt(sealed: ByteArray, associatedData: ByteArray): ByteArray =
                throw GeneralSecurityException("keystore unavailable")
        }
        val vault = vault(failing)

        val failure = assertFailsWith<SecretVaultException> { vault.put("provider/a", "sk-top-secret") }
        assertFalse("sk-top-secret" in failure.toString())
        assertFalse(vault.contains("provider/a"))
        assertFalse(file.exists())
    }

    @Test
    fun `a failed write is reported and the vault keeps its previous content`() = runTest {
        val vault = vault()
        vault.put("provider/a", "sk-alpha")

        // Occupy the name of the side file that an atomic write creates first, so the write
        // cannot even start. (Unlike file permissions, this also stops a root user.)
        val blocker = File(file.path + ".tmp").apply { assertTrue(mkdir()) }
        assertFailsWith<SecretVaultException> { vault.put("provider/b", "sk-beta") }
        assertFailsWith<SecretVaultException> { vault.delete("provider/a") }

        assertEquals(setOf("provider/a"), vault.aliases())
        assertEquals("sk-alpha", vault.get("provider/a"))
        assertEquals(setOf("provider/a"), storedEntries().keys)

        // Once the obstacle is gone the same vault object works again. The failed writes did
        // not remove what they found in their way.
        assertTrue(blocker.isDirectory)
        assertTrue(blocker.delete())
        vault.put("provider/b", "sk-beta")
        assertEquals(setOf("provider/a", "provider/b"), vault().aliases())
    }

    @Test
    fun `an unusable vault location is reported as a vault failure`() = runTest {
        val notADirectory = folder.newFile("occupied")
        val vault = EncryptedFileSecretVault(File(notADirectory, "secrets.json"), cipher)
        assertFailsWith<SecretVaultException> { vault.put("provider/a", "sk-alpha") }
        // Nothing can be known about a vault whose location cannot even be looked at.
        assertFailsWith<SecretVaultException> { vault.contains("provider/a") }
        assertTrue(notADirectory.isFile)
    }
}
