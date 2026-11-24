package com.aicontrolcenter.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MessageId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.ReviewVerdict
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.TaskUsage
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tasks and agent messages in the production database: SQLCipher with a key sealed by the device
 * keystore, on a device or emulator. The JVM tests cover the same repositories on plain SQLite;
 * this checks what only the real configuration can show: that objectives, results and messages
 * are not readable from the files, and that a restart finds the running work again.
 *
 * Method names are plain so they can be dexed for every supported API level.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedTaskStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "encrypted-task-test-${UUID.randomUUID()}.db"
    private val keyFile = File(context.noBackupFilesDir, DataLayer.databaseKeyFile(databaseName))
    private val open = mutableListOf<DataLayer>()

    private val director = AgentId("agt_director")
    private val researcher = AgentId("agt_researcher")

    private fun encryptedLayer() = DataLayer(context, databaseName = databaseName).also(open::add)

    private val objective = TaskRecord(
        id = TaskId("tsk_root"), rootId = TaskId("tsk_root"), kind = TaskKind.OBJECTIVE, title = "Compare heat pumps",
        instructions = "Research heat pumps for the cellar, compare them and write a plan. $MARKER", requestedBy = TaskRequester.Owner,
        assignee = director, status = TaskStatus.RUNNING, statusDetail = "Planning",
        options = ObjectiveOptions(reviewResults = true, saveKnowledge = true, maxTasks = 6),
        createdAt = 1_000, updatedAt = 1_001, startedAt = 1_002, deadlineAt = 91_000,
    )

    private val research = TaskRecord(
        id = TaskId("tsk_research"), rootId = objective.id, parentId = objective.id, kind = TaskKind.WORK, title = "Find models",
        instructions = "List heat pumps with prices", completionCriteria = listOf("Three models", "Prices in EUR"),
        assignee = researcher, requestedBy = TaskRequester.Agent(director), status = TaskStatus.RUNNING,
        dependsOn = listOf(TaskId("tsk_scope")), delegationChain = listOf(director), order = 1, attempt = 1, needsReview = true,
        reviews = listOf(ReviewVerdict(AgentId("agt_reviewer"), ReviewDecision.REVISE, "Prices missing", listOf("Add prices"), 1_500)),
        usage = TaskUsage(modelCalls = 2, toolCalls = 1, inputTokens = 900, outputTokens = 200, messagesSent = 1),
        conversationId = ConversationId("cnv_research"), createdAt = 1_100, updatedAt = 1_200, startedAt = 1_150, deadlineAt = 61_100,
    )

    private fun message(id: String, at: Long, from: Participant, to: Participant, type: MessageType, body: String) =
        AgentMessage(MessageId(id), objective.id, research.id, from, to, type, body, hop = 1, at = at)

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
    fun tasksAndMessagesAreStoredEncryptedAndFoundAgainAfterARestart(): Unit = runTest {
        val assignment = message("msg_1", 1_100, Participant.Agent(director), Participant.Agent(researcher), MessageType.ASSIGNMENT, "Find heat pumps")
        val findings = message("msg_2", 1_300, Participant.Agent(researcher), Participant.Agent(director), MessageType.FINDINGS, "Found three $MARKER")
        val status = message("msg_3", 1_300, Participant.System, Participant.Owner, MessageType.STATUS, "Research is under way")

        val first = encryptedLayer()
        first.tasks.upsertAll(listOf(research, objective))
        listOf(status, findings, assignment, findings).forEach { first.messages.append(it) }
        first.close()

        for (file in databaseFiles()) {
            assertFalse(file.contains(MARKER), "${file.name} contains task or message content in the clear")
            assertFalse(file.contains("heat pumps"), "${file.name} contains task content in the clear")
        }

        val second = encryptedLayer()
        assertEquals(listOf(objective), second.tasks.observeObjectives().first())
        assertEquals(listOf(objective, research), second.tasks.getTree(objective.id))
        assertEquals(listOf(objective, research), second.tasks.withStatus(setOf(TaskStatus.RUNNING, TaskStatus.WAITING_FOR_AGENT)))
        assertEquals(listOf(assignment, findings, status), second.messages.list(objective.id))
        assertEquals(listOf(status, findings), second.messages.observeRecent(2).first())
        assertEquals(3, second.messages.count(objective.id))

        val done = second.tasks.update(research.id) {
            it.copy(status = TaskStatus.COMPLETED, result = "Three models compared", usage = it.usage + TaskUsage(modelCalls = 1), finishedAt = 2_000)
        }
        assertEquals(done, second.tasks.get(research.id))
        assertEquals(3, done?.usage?.modelCalls)
        assertEquals(listOf(objective), second.tasks.withStatus(setOf(TaskStatus.RUNNING)))

        second.tasks.deleteTree(objective.id)
        second.messages.deleteFor(objective.id)
        assertNull(second.tasks.get(objective.id))
        assertTrue(second.tasks.getTree(objective.id).isEmpty())
        assertEquals(0, second.messages.count(objective.id))
    }

    private companion object {
        const val MARKER = "ZZ-TASK-MARKER-5e2a"
    }
}
