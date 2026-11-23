package com.aicontrolcenter.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.data.DataLayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's requests and grants in the production database: SQLCipher with a key sealed by
 * the device keystore, on a device or emulator. The JVM tests cover the same repositories on
 * plain SQLite; this checks what only the real configuration can show: that what an agent asked
 * for and why is not readable from the files, and that an answer and the grant it wrote are
 * found again after a restart.
 *
 * Method names are plain so they can be dexed for every supported API level.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedOperatorStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "encrypted-operator-test-${UUID.randomUUID()}.db"
    private val keyFile = File(context.noBackupFilesDir, DataLayer.databaseKeyFile(databaseName))
    private val open = mutableListOf<DataLayer>()

    private val operator = AgentId("agt_operator")
    private val folder = CapabilityConstraint.Folder(FolderId("fld_reports"))
    private val foldersWrite = Capability("folders.write")

    private fun encryptedLayer() = DataLayer(context, databaseName = databaseName).also(open::add)

    private fun request(id: String, at: Long, kind: OwnerRequestKind = OwnerRequestKind.PERMISSION) = OwnerRequest(
        id = RequestId(id), kind = kind, agentId = operator, toolId = ToolId("folders.write"), capability = foldersWrite,
        constraint = folder, risk = if (kind == OwnerRequestKind.APPROVAL) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING,
        summary = "Write \"budget.csv\" in Reports $MARKER", details = listOf("Reports/budget.csv (2 KB)", "Line 1: rent $MARKER"),
        reason = "The owner asked for a budget $MARKER", taskId = TaskId("tsk_1"), rootId = TaskId("tsk_root"),
        conversationId = ConversationId("cnv_1"), createdAt = at, expiresAt = at + 1_800_000, agentName = "Operator",
        toolTitle = "Write in a shared folder", targetLabel = "Reports", delegationChain = listOf(AgentId("agt_director")),
    )

    private fun databaseFiles(): List<File> {
        val main = context.getDatabasePath(databaseName)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-journal")).filter { it.isFile }
    }

    private fun File.contains(text: String): Boolean = String(readBytes(), Charsets.UTF_8).contains(text)

    @After
    fun tearDown() {
        runBlocking { open.forEach { it.close() } }
        context.deleteDatabase(databaseName)
        keyFile.delete()
    }

    @Test
    fun requestsAndGrantsAreStoredEncryptedAndFoundAgainAfterARestart() = runTest {
        val permission = request("req_1", 1_000)
        val approval = request("req_2", 1_100, OwnerRequestKind.APPROVAL)
        val left = request("req_3", 1_200)

        val first = encryptedLayer()
        listOf(permission, approval, left).forEach { first.ownerRequests.create(it) }
        val granted = first.ownerRequests.decide(permission.id, OwnerDecision.Grant(GrantScope.ALWAYS, "Only Reports"), 1_500, GrantId("grt_1"))
        assertIs<DecideResult.Decided>(granted)
        assertIs<DecideResult.Decided>(first.ownerRequests.decide(approval.id, OwnerDecision.Deny("Not today $MARKER"), 1_600, GrantId("grt_2")))
        first.close()

        for (file in databaseFiles()) {
            assertFalse(file.contains(MARKER), "${file.name} contains a request in the clear")
            assertFalse(file.contains("budget"), "${file.name} contains a request in the clear")
        }

        val second = encryptedLayer()
        assertEquals(granted.request, second.ownerRequests.get(permission.id))
        assertEquals(OwnerRequestStatus.DENIED, second.ownerRequests.get(approval.id)?.status)
        assertEquals(listOf(left), second.ownerRequests.observePending().first())
        assertEquals(granted.grant, second.permissions.findByRequest(permission.id))
        val call = GrantQuery(operator, foldersWrite, folder, TaskId("tsk_9"), at = 2_000)
        assertEquals(listOfNotNull(granted.grant), second.permissions.findMatching(call))

        // Start-up: what was left waiting is cancelled; then the owner revokes the grant.
        assertEquals(listOf(left.id), second.ownerRequests.cancelAllPending(3_000, "The app stopped.").map { it.id })
        assertTrue(second.ownerRequests.observePending().first().isEmpty())
        assertEquals(1, second.permissions.revokeAllFor(operator, 3_100))
        assertTrue(second.permissions.findMatching(call.copy(at = 3_200)).isEmpty())
        assertNull(second.permissions.revoke(GrantId("grt_1"), 3_300))
    }

    private companion object {
        const val MARKER = "ZZ-OPERATOR-MARKER-7c41"
    }
}
