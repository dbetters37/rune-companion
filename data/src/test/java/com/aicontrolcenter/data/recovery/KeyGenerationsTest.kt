package com.aicontrolcenter.data.recovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.security.AndroidKeystoreVaultCipher
import com.aicontrolcenter.data.security.DatabaseKeyException
import com.aicontrolcenter.data.security.KeyProblem
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Key generations (Phase 6, rule 9): generation 1 is 0.5.2's names exactly; later ones never reuse a name. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KeyGenerationsTest {
    @get:Rule val folder = TemporaryFolder()

    private val root: File get() = folder.root

    @Test
    fun `without a note the app is on generation 1, which keeps every name 0_5_2 used`() {
        val generations = KeyGenerations(root)
        assertEquals(1, generations.current())
        val first = generations.namesOf(1)
        // The names 0.5.2 used (DataLayer.kt, AndroidKeystoreVaultCipher.kt): its data must open unchanged.
        assertEquals(AiccDatabase.DEFAULT_NAME, first.databaseFile)
        assertEquals("aicc.db", first.databaseFile)
        assertEquals(DataLayer.databaseKeyFile("aicc.db"), first.databaseKeyFile)
        assertEquals("database-keys/aicc.db.key", first.databaseKeyFile)
        assertEquals(AndroidKeystoreVaultCipher.DATABASE_KEY_ALIAS, first.databaseKeyAlias)
        assertEquals(DataLayer.VAULT_FILE, first.vaultFile)
        assertEquals(AndroidKeystoreVaultCipher.DEFAULT_KEY_ALIAS, first.vaultKeyAlias)
        assertFalse(File(root, KeyGenerations.FILE_NAME).exists(), "reading writes nothing")
    }

    @Test
    fun `every later generation has names of its own`() {
        val second = KeyGenerations.names("aicc.db", 2)
        assertEquals("aicc.v2.db", second.databaseFile)
        assertEquals("database-keys/aicc.v2.db.key", second.databaseKeyFile)
        assertEquals("aicc.database-key.v2", second.databaseKeyAlias)
        assertEquals("vault/secrets.v2.json", second.vaultFile)
        assertEquals("aicc.secret-vault.v2", second.vaultKeyAlias)
        assertEquals("other.v3", KeyGenerations.names("other", 3).databaseFile)
        val all = (1..5).flatMap { KeyGenerations.names("aicc.db", it).let { n -> listOf(n.databaseFile, n.databaseKeyFile, n.databaseKeyAlias, n.vaultFile, n.vaultKeyAlias) } }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `advancing writes the next number durably, and a new reader sees it`() {
        val generations = KeyGenerations(root)
        assertEquals(2, generations.advance())
        assertEquals("2", File(root, "database-keys/generation").readText().trim())
        assertEquals(2, KeyGenerations(root).current())
        assertEquals(3, KeyGenerations(root).advance())
        assertEquals(3, generations.current())
    }

    @Test
    fun `a damaged note keeps the data closed, and advancing goes above every generation a file shows`() {
        val note = File(root, KeyGenerations.FILE_NAME).apply { parentFile!!.mkdirs(); writeText("not a number") }
        val failure = assertFailsWith<DatabaseKeyException> { KeyGenerations(root).current() }
        assertEquals(KeyProblem.DAMAGED, failure.problem)
        // Files of generations 2 and 4 exist (one in a set-aside folder): the next one must be 5.
        File(root, "database-keys/aicc.v2.db.key").writeText("x")
        File(root, "set-aside-2026-11-01/vault").apply { mkdirs() }.resolve("secrets.v4.json").writeText("{}")
        assertEquals(4, KeyGenerations(root).highestSeen())
        assertEquals(5, KeyGenerations(root).advance())
        assertEquals("5", note.readText().trim())
        for (bad in listOf("0", "-1", "99999999", "", "2 3")) {
            note.writeText(bad)
            assertFailsWith<DatabaseKeyException>("\"$bad\"") { KeyGenerations(root).current() }
        }
    }

    @Test
    fun `another database keeps its own note, so tests never move the app's generation`() {
        val other = KeyGenerations(root, "other.db")
        assertEquals(2, other.advance())
        assertTrue(File(root, "database-keys/other.db.generation").isFile)
        assertEquals(1, KeyGenerations(root).current())
        assertEquals("other.v2.db", other.namesOf(other.current()).databaseFile)
    }
}
