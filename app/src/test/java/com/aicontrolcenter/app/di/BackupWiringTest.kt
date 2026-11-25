package com.aicontrolcenter.app.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.di.wiring.BackupWiring
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.data.BackupStores
import com.aicontrolcenter.data.DataLayer
import com.aicontrolcenter.data.DatabaseEncryption
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backup port is built from the stores a backup holds and nothing else (rule 8; security review of wave 1, F13):
 * the key vault, the grants, the requests to the owner, the shared folders and the authorised screen apps cannot be
 * reached from what `BackupWiring.production` gets, so rule 8's exclusions do not rest on the backup code alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupWiringTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val data = DataLayer(context, databaseName = "backup-wiring-test.db", databaseEncryption = DatabaseEncryption.None)

    @After
    fun close() = runBlocking { data.close() }

    private val forbidden: List<Class<*>> = listOf(
        SecretVault::class.java, PermissionRepository::class.java, OwnerRequestRepository::class.java,
        SharedFolderRepository::class.java, OperatorAppRepository::class.java,
    )

    private val walk = AgentFacingWalk(ownerOnly = forbidden, checkActions = false)

    @Test
    fun `nothing the backup gets reaches the vault, the grants, the requests, the folders or the authorised apps`() {
        // As production builds them: every other store of the data layer is in use too, so a path to one would be seen.
        listOf(data.secretVault, data.permissions, data.ownerRequests, data.sharedFolders, data.operatorApps).forEach { it.hashCode() }
        val result = walk.walk(data.backupStores, "backup stores")
        assertEquals(emptyList(), result.violations)
        assertEquals(emptyList(), result.unreadable)
        assertTrue(result.objects > 20, "the walk went into the stores (${result.objects} objects)")
        // The walk finds them where they are.
        for (store in listOf<Any>(data.secretVault, data.permissions, data.ownerRequests, data.sharedFolders, data.operatorApps)) {
            assertTrue(walk.walk(object { val held: Any = store }, "holder").violations.isNotEmpty(), store.javaClass.simpleName)
        }
        assertTrue(walk.walk(object { val whole: Any = data }, "the whole data layer").violations.isNotEmpty(), "the data layer itself would reach them")
    }

    @Test
    fun `the backup wiring takes the backup's stores, never the data layer or a context`() {
        val production = BackupWiring::class.java.methods.single { it.name == "production" }
        val types = production.parameterTypes.toList()
        assertTrue(BackupStores::class.java in types)
        for (wide in listOf(DataLayer::class.java, android.content.Context::class.java) + forbidden) {
            assertTrue(types.none { wide.isAssignableFrom(it) }, "production takes no ${wide.simpleName}")
        }
        val fieldTypes = BackupStores::class.java.declaredFields.map { it.type }
        for (wide in listOf(DataLayer::class.java, android.content.Context::class.java) + forbidden) {
            assertTrue(fieldTypes.none { wide.isAssignableFrom(it) || it.isAssignableFrom(wide) && it != Any::class.java }, "BackupStores holds no ${wide.simpleName}")
        }
    }
}
