package com.aicontrolcenter.data.backup

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.MemoryPolicy
import com.aicontrolcenter.core.backup.BackupFormat
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.core.backup.BackupRecord
import com.aicontrolcenter.core.backup.BackupRecords
import com.aicontrolcenter.core.backup.BackupSettings
import com.aicontrolcenter.core.backup.EntryAuthor
import com.aicontrolcenter.core.backup.LocalMaintenanceGate
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.backup.RestoreSettings
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.testkit.SequentialIdGenerator
import com.aicontrolcenter.testkit.contract.MemorySamples
import com.aicontrolcenter.testkit.contract.TaskSamples
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Save and restore over in-memory stores (JVM): the whole app round trip, rule 8's exclusions in the decrypted body,
 * the restore rules (keys, locality, headers, AI apps, memories, spaces, settings), the passphrase gate, the fresh-app
 * rule, the maintenance gate, free space, cancelling during the key derivation and hostile strings. All-or-nothing in
 * one Room transaction is `RoomBackupRoundTripTest`'s (Robolectric).
 */
class RoomOwnerBackupTest {
    private fun phone(): MemoryApp = MemoryApp().also { app ->
        runBlocking {
            BackupPhone.fill(app.agents, app.providers, app.models, app.prices, app.aiApps, app.memories, app.conversations)
            app.settings.update { it.copy(directorAgentId = BackupPhone.ada, seedVersion = 4, memorySeedVersion = 1) }
        }
        app.toolTitles.use(BackupPhone.titles)
    }

    private fun fresh(): MemoryApp = MemoryApp().also { app ->
        runBlocking { BackupPhone.fillFresh(app.agents, app.providers, app.models, app.aiApps, app.memories) }
    }

    private fun savedPhone(app: MemoryApp = phone()): ByteArray = runBlocking { app.saveToBytes() }.let { (result, bytes) ->
        result.done()
        bytes
    }

    @Test
    fun `a whole app survives a save and a restore into a fresh app`() = runBlocking<Unit> {
        val source = phone()
        val (saved, file) = source.saveToBytes()
        val outcome = saved.done()
        assertEquals(2, outcome.report.counts.agents)
        assertEquals(3, outcome.report.counts.providers, "the provider with a key in its address is left out")
        assertEquals(3, outcome.report.counts.models, "and its model with it")
        assertEquals(2, outcome.report.counts.memories, "the task's memory is left out")
        assertEquals(1, outcome.report.counts.memoryLinks, "and its link with it")
        assertEquals(3, outcome.report.counts.conversationEntries, "the system message and the tool result are left out")
        assertEquals(file.size.toLong(), outcome.report.sizeBytes)
        assertEquals(listOf(BackupRecords.PROVIDER_LEFT_OUT, BackupRecords.HEADERS_LEFT_OUT), outcome.notes)

        val target = fresh()
        val restored = target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        assertEquals(outcome.report.counts, restored.report.counts)
        assertEquals(0, restored.report.droppedMemories)

        // Agents: the file's, and only those (the starter team is replaced); stats kept; spaces they name are in the file.
        assertEquals(source.agents.getAll(), target.agents.getAll())
        assertEquals(BackupPhone.adaStats, target.agents.getStats(BackupPhone.ada))
        // Providers: no key alias, status unknown, only the two allowed headers; the built-in simulator kept.
        val xai = target.providers.get(ProviderId("prv_xai"))!!
        assertNull(xai.apiKeyAlias)
        assertEquals(ConnectionStatus.UNKNOWN, xai.status)
        assertNull(xai.statusMessage)
        assertEquals(mapOf("X-Title" to "AI Control Center", "HTTP-Referer" to "https://example.org"), xai.extraHeaders)
        assertEquals(setOf("prv_xai", "prv_home", "prv_simulator"), target.providers.getAll().map { it.id.value }.toSet())
        assertEquals(source.models.getAll().filter { it.providerId.value != "prv_leaky" }.toSet(), target.models.getAll().toSet())
        assertEquals(BackupPhone.price.copy(updatedAt = target.clock.now()), target.prices.get(ModelId("mdl_grok")))
        // The AI app: the file's (the scan's entry of the same app replaced), without this phone's install details.
        val app = target.aiApps.getAll().single()
        assertEquals("app_chat", app.id.value)
        assertFalse(app.verifiedByUser)
        assertFalse(app.accessibilityAuthorized)
        assertNull(app.signingCertSha256)
        assertNull(app.firstInstallTime)
        // Spaces: the file's (the seeded one replaced); memories with their ids, scopes, provenance and history; the link.
        assertEquals(listOf(BackupPhone.space), target.memories.observeSpaces().first())
        assertEquals(source.memories.getAll(BackupPhone.memoryIds).toSet(), target.memories.list().toSet())
        assertEquals(listOf("lnk_1"), target.memories.links().map { it.id.value })
        // The chat: the owner's words and the agent's reply text with a line per tool, nothing else.
        assertEquals(listOf(BackupPhone.conversation), target.conversations.observeConversations().first())
        val entries = target.conversations.getEntries(BackupPhone.chat)
        assertEquals(BackupPhone.restoredTexts, entries.map { it.message.role to it.message.text })
        assertTrue(entries.all { it.message.toolCalls.isEmpty() && it.message.toolResult == null && it.message.reasoning == null && it.message.images.isEmpty() })
        assertEquals(listOf(null, "grok-4-fast", "grok-4-fast"), entries.map { it.modelName })
        assertEquals(listOf(3_001L, 3_002L, 3_004L), entries.map { it.at })
        // Settings through RestoreSettings: the Director is the file's.
        assertEquals(BackupPhone.ada, target.settings.get().directorAgentId)
        assertEquals(4, target.settings.get().seedVersion)
    }

    @Test
    fun `the decrypted body holds none of rule 8's exclusions`() {
        val body = BackupFiles.plaintext(savedPhone()).decodeToString()
        for (secret in listOf(
            BackupPhone.KEY_ALIAS, "apiKeyAlias\":\"", BackupPhone.HEADER_SECRET, "X-Token", BackupPhone.URL_KEY, BackupPhone.SCREEN_SECRET,
            BackupPhone.TASK_MEMORY_TEXT, BackupPhone.CERT.repeat(16), BackupPhone.REASONING_SECRET, BackupPhone.SYSTEM_SECRET, "com.bank",
            "lnk_task", "\"lastCheckedAt\":1200", "\"statusMessage\":\"Online\"",
        )) {
            assertFalse(secret in body, "\"$secret\" is in the file")
        }
        assertTrue("A tool was used here: Read the screen. Its result is not in the backup." in body)
        assertTrue("\"apiKeyAlias\":null" in body)
        // Nothing of grants, requests, folders, authorised apps, the activity log, usage, routines or objectives: the
        // record types are format v1's only.
        val types = Regex("\"type\":\"([a-zA-Z]+)\"").findAll(body).map { it.groupValues[1] }.toSet()
        assertTrue(BackupRecords.TYPES.containsAll(types - setOf("agent", "shared")), types.toString())
    }

    @Test
    fun `a save takes the generated passphrase it showed, or a strong own one, and nothing else`() = runBlocking<Unit> {
        val app = phone()
        var opened = 0
        val open: suspend () -> OutputStream = {
            opened++
            ByteArrayOutputStream()
        }
        // A weak passphrase, also when the screen claims it generated it.
        for ((typed, shown) in listOf("password" to null, "aaaa bbbb" to "aaaa bbbb", "Summer2024!Summer2024!" to null)) {
            assertIs<BackupProblem.WeakPassphrase>(app.backup.saveWithNotes(typed, shown, open).problem(), typed)
        }
        assertEquals(0, opened, "nothing was written for a refused passphrase")
        // The generated one, typed as the owner might: lower case, spaces instead of dashes.
        app.backup.saveWithNotes("7kq2 m9xd 4rtb 8hjn 3vcp", BackupFiles.PASSPHRASE, open).done()
        // An own passphrase of four real words.
        app.backup.saveWithNotes("violet harbour lantern quietly", null, open).done()
        assertEquals(2, opened)
        // The log says what was saved, with counts, and never the passphrase.
        val log = app.activity.recent()
        assertTrue(log.all { it.type == ActivityType.USER_ACTION })
        assertTrue(log.none { "violet" in it.detail || "7KQ2" in it.detail.uppercase() })
        assertEquals(2, log.count { it.title == BackupTexts.LOG_SAVED && "2 agents" in it.detail })
    }

    @Test
    fun `the restore grants nothing and takes the farther locality, whatever the file says`() = runBlocking<Unit> {
        val lying = ModelProvider(
            id = ProviderId("prv_p1"), name = "Mine", kind = ProviderKind.OPENAI_COMPATIBLE, baseUrl = "https://other.example/v1",
            apiKeyAlias = "provider/prv_p1/1", extraHeaders = mapOf("x-title" to "ok", "Authorization" to "Bearer sk-live-1234567890abcdef"),
            locality = Locality.ON_DEVICE, status = ConnectionStatus.ONLINE, createdAt = 1, updatedAt = 1,
        )
        val app = Samples.aiApp().copy(signingCertSha256 = "ab".repeat(32), verifiedByUser = true, accessibilityAuthorized = true, firstInstallTime = 9)
        val file = BackupFiles.craft(BackupFiles.framed(listOf(BackupRecord.Provider(lying), BackupRecord.App(app))))
        val target = fresh()
        target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        val stored = target.providers.get(ProviderId("prv_p1"))!!
        assertNull(stored.apiKeyAlias)
        assertEquals(ConnectionStatus.UNKNOWN, stored.status)
        assertEquals(Locality.CLOUD, stored.locality)
        assertEquals(mapOf("x-title" to "ok"), stored.extraHeaders)
        val storedApp = target.aiApps.getAll().single()
        assertFalse(storedApp.verifiedByUser || storedApp.accessibilityAuthorized)
        assertNull(storedApp.signingCertSha256)
    }

    @Test
    fun `memories whose agent or space is not in the file are left out and counted, agents keep only the file's spaces`() = runBlocking<Unit> {
        val ada = Samples.agent(id = "agt_a", name = "A", modelId = null).copy(
            templateId = null, memory = MemoryPolicy(readableSpaces = setOf(SpaceId("spc_in"), SpaceId("spc_gone")), writableSpaces = setOf(SpaceId("spc_gone"))),
        )
        val body = listOf(
            BackupRecord.Agent(ada),
            BackupRecord.Price(ModelId("mdl_x"), inputPerMTokMicros = -5, outputPerMTokMicros = 1),
            BackupRecord.Space(KnowledgeSpace(SpaceId("spc_in"), "In", "", 1, 1)),
            BackupRecord.Memory(MemorySamples.memory("mem_a", scope = MemoryScope.Agent(AgentId("agt_a")))),
            BackupRecord.Memory(MemorySamples.memory("mem_in", scope = MemoryScope.Shared(SpaceId("spc_in")))),
            BackupRecord.Memory(MemorySamples.memory("mem_nobody", scope = MemoryScope.Agent(AgentId("agt_gone")))),
            BackupRecord.Memory(MemorySamples.memory("mem_gone", scope = MemoryScope.Shared(SpaceId("spc_gone")))),
            BackupRecord.Memory(MemorySamples.memory("mem_task", scope = MemoryScope.Task(com.aicontrolcenter.core.TaskId("tsk"), AgentId("agt_a")))),
            BackupRecord.Link(MemorySamples.link("lnk_kept", "mem_a", "mem_in")),
            BackupRecord.Link(MemorySamples.link("lnk_dropped", "mem_a", "mem_gone")),
        )
        val file = BackupFiles.craft(BackupFiles.framed(body))
        val target = fresh()
        val preview = target.backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        assertEquals(5, preview.summary.counts.memories, "the file holds five")
        assertTrue(BackupTexts.droppedMemories(3) in preview.notes)
        assertTrue(BackupTexts.droppedPrices(1) in preview.notes)
        val outcome = target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        assertEquals(3, outcome.report.droppedMemories)
        assertEquals(2, outcome.report.counts.memories)
        assertEquals(1, outcome.report.counts.memoryLinks)
        assertEquals(0, outcome.report.counts.modelPrices)
        assertEquals(setOf("mem_a", "mem_in"), target.memories.list().map { it.id.value }.toSet())
        // Memory ids and scopes as they were.
        assertEquals(MemoryScope.Agent(AgentId("agt_a")), target.memories.get(MemoryId("mem_a"))!!.scope)
        val policy = target.agents.get(AgentId("agt_a"))!!.memory
        assertEquals(setOf(SpaceId("spc_in")), policy.readableSpaces)
        assertEquals(emptySet(), policy.writableSpaces)
        assertNull(target.prices.get(ModelId("mdl_x")))
    }

    @Test
    fun `settings come only through RestoreSettings, never relaxing protection`() = runBlocking<Unit> {
        val target = fresh()
        target.settings.update {
            it.copy(
                localOnlyMode = true, operatorEnabled = true, accessibilityOperatorEnabled = true, autoResumeInterruptedTasks = true,
                routinesEnabled = true, folderPickUpEnabled = true, maxModelCallsPerDay = 50, maxTokensPerDay = 900_000,
                maxSpendPerDayMicros = null, agentQuestionsEnabled = false, approvalTimeoutMinutes = 7,
            )
        }
        val fileSettings = BackupSettings(
            localOnlyMode = false, directorAgentId = "agt_a", maxModelCallsPerDay = 10_000_000, maxTokensPerDay = 100_000,
            maxSpendPerDayMicros = 2_000_000, notifyWhenWorkEnds = false, calmMotion = true, seedVersion = 3, memorySeedVersion = 1,
        )
        val agent = Samples.agent(id = "agt_a", name = "Director Ada", modelId = null).copy(templateId = null)
        val file = BackupFiles.craft(BackupFiles.framed(listOf(BackupRecord.Agent(agent)), settings = fileSettings))
        val before = target.settings.get()
        val preview = target.backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        val expected = RestoreSettings.merge(before, fileSettings, mapOf(AgentId("agt_a") to "Director Ada"))
        assertEquals(expected.changes, preview.summary.settingChanges)
        assertEquals(expected.keptBack, preview.keptBack)
        assertEquals(RestoreSettings.ALWAYS_OFF_AFTER_RESTORE, preview.alwaysOff)
        // The Director by name, never by id.
        val director = preview.summary.settingChanges.single { it.name == "Director" }
        assertEquals("Director Ada", director.to)
        assertFalse(preview.summary.settingChanges.any { "agt_" in it.from || "agt_" in it.to })

        target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        val after = target.settings.get()
        assertEquals(expected.settings, after)
        assertTrue(after.localOnlyMode, "local-only stays on")
        assertEquals(50, after.maxModelCallsPerDay, "the stricter limit")
        assertEquals(100_000, after.maxTokensPerDay, "the stricter limit")
        assertEquals(2_000_000, after.maxSpendPerDayMicros, "a money limit where there was none")
        assertFalse(after.operatorEnabled || after.accessibilityOperatorEnabled || after.autoResumeInterruptedTasks || after.routinesEnabled || after.folderPickUpEnabled)
        assertFalse(after.agentQuestionsEnabled, "other settings keep their current value")
        assertEquals(7, after.approvalTimeoutMinutes)
        assertEquals(AgentId("agt_a"), after.directorAgentId)
    }

    @Test
    fun `a restore writes only into a fresh app`() = runBlocking<Unit> {
        val file = savedPhone()
        val makers: List<Pair<String, suspend (MemoryApp) -> Unit>> = listOf(
            "an objective" to { it.tasks.upsert(TaskSamples.objective()) },
            "a chat" to { it.conversations.upsertConversation(Conversation(ConversationId("cnv"), AgentId("agt_fresh_0"), "Hi", 1, 1)) },
            "a memory" to { it.memories.upsert(MemorySamples.memory("mem_own", scope = MemoryScope.Shared(SpaceId("spc_seeded")))) },
            "an own agent" to { it.agents.upsert(Samples.agent(id = "agt_own", name = "Mine", modelId = null).copy(templateId = null)) },
            "a second agent of one template" to { it.agents.upsert(Samples.agent(id = "agt_twin", name = "Twin", modelId = null).copy(templateId = "director")) },
            "an own provider" to { it.providers.upsert(Samples.provider(id = "prv_own")) },
            "an AI app the owner confirmed" to { it.aiApps.upsert(Samples.aiApp(id = "app_mine", packageName = "com.mine.ai")) },
        )
        for ((what, make) in makers) {
            val target = fresh()
            make(target)
            val agentsBefore = target.agents.getAll()
            val preview = target.backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
            assertEquals(BackupProblem.NotEmpty, preview.refusal, what)
            assertEquals(BackupProblem.NotEmpty, target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).problem(), what)
            assertEquals(agentsBefore, target.agents.getAll(), "nothing changed: $what")
            assertEquals(1, target.activity.recent().count { it.title == BackupTexts.LOG_NOT_RESTORED })
        }
        // A fresh app is fine, and so is a brand-new one with nothing at all.
        assertNull(fresh().backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done().refusal)
        MemoryApp().backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
    }

    @Test
    fun `a restore runs alone, needs twice the file's size free, and a save waits for it`() = runBlocking<Unit> {
        val file = savedPhone()
        val gate = LocalMaintenanceGate()
        val target = MemoryApp(gate = gate)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async { gate.runExclusive { started.complete(Unit); release.await() } }
        started.await()
        assertEquals(BackupProblem.Busy(MaintenanceGate.RESTORING), target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).problem())
        assertEquals(BackupProblem.Busy(MaintenanceGate.RESTORING), target.saveToBytes().first.problem())
        assertEquals(BackupProblem.Busy(MaintenanceGate.RESTORING), target.backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done().refusal)
        release.complete(Unit)
        holder.await()

        target.free = 2 * file.size.toLong() - 1
        assertEquals(BackupProblem.NotEnoughSpace(2 * file.size.toLong()), target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).problem())
        assertTrue(target.agents.getAll().isEmpty())
        assertFalse(gate.busy.value)
        target.free = 2 * file.size.toLong()
        target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        assertEquals(2, target.agents.getAll().size)
    }

    @Test
    fun `a wrong passphrase restores nothing, and the right one typed loosely opens the file`() = runBlocking<Unit> {
        val file = savedPhone()
        val target = fresh()
        val before = target.agents.getAll()
        assertEquals(BackupProblem.WrongPassphrase, target.backup.restoreWithNotes(BackupFiles.opener(file), "7KQ2-M9XD-4RTB-8HJN-3VCQ").problem())
        assertEquals(before, target.agents.getAll())
        // Case, spaces, dashes, O for 0 and I or L for 1 do not matter.
        target.backup.restoreWithNotes(BackupFiles.opener(file), " 7kq2m9xd 4rtb–8hjn 3vcp ").done()
    }

    @Test
    fun `cancelling a save or a restore while the key is derived writes nothing`() = runBlocking<Unit> {
        val source = phone()
        // The most work a file may ask for, so the derivation is still running when it is cancelled.
        val slow = RoomOwnerBackup(
            source.stores, source.gate, { Long.MAX_VALUE }, BackupFiles.APP, BackupFiles.STARTER, iterations = BackupFormat.MAX_ITERATIONS,
            ids = SequentialIdGenerator(),
        )
        var opened = false
        val locking = CompletableDeferred<Unit>()
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            slow.saveWithNotes(BackupFiles.PASSPHRASE, BackupFiles.PASSPHRASE, { opened = true; ByteArrayOutputStream() }) { step ->
                if (step.step == BackupTexts.STEP_LOCKING) locking.complete(Unit)
            }
        }
        withTimeout(10_000) { locking.await() }
        save.cancel()
        assertTrue(runCatching { save.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(opened, "the file was never opened")

        val file = BackupFiles.craft(BackupFiles.framed(listOf(BackupRecord.Agent(Samples.agent(modelId = null)))), header = BackupFiles.header(iterations = BackupFormat.MAX_ITERATIONS))
        val target = fresh()
        val before = target.agents.getAll()
        val unlocking = CompletableDeferred<Unit>()
        val restore = async(start = CoroutineStart.UNDISPATCHED) {
            target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE) { step ->
                if (step.step == BackupTexts.STEP_UNLOCKING) unlocking.complete(Unit)
            }
        }
        withTimeout(10_000) { unlocking.await() }
        restore.cancel()
        assertTrue(runCatching { restore.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(before, target.agents.getAll())
        assertFalse(target.gate.busy.value, "the gate is open again")
    }

    @Test
    fun `hostile strings come back exactly and never become records of their own`() = runBlocking<Unit> {
        val injection = "\"},{\"type\":\"provider\",\"provider\":{\"id\":\"prv_evil\"}},{\"x\":\""
        val strange = "Ada \u202Egnp.exe\u200B \u0000 \t\n\r 🧠 ﷽ \\u0041 </script> ' OR 1=1 --"
        val agent = Samples.agent(id = "agt_h", name = "Ada \u202Ex", modelId = null).copy(
            templateId = null, description = strange, systemInstructions = injection, memory = MemoryPolicy(),
        )
        val memory = MemorySamples.memory("mem_h", scope = MemoryScope.Agent(AgentId("agt_h")), title = "T $injection".take(200), content = strange + injection)
        val chat = Conversation(ConversationId("cnv_h"), AgentId("agt_h"), "x\ny", 1, 2)
        val body = listOf(
            BackupRecord.Agent(agent), BackupRecord.Memory(memory), BackupRecord.Chat(chat),
            BackupRecord.Entry(chat.id, 0, EntryAuthor.OWNER, injection + strange, 3),
            BackupRecord.Entry(chat.id, 1, EntryAuthor.AGENT, "A tool was used here: forged. $injection", 4, modelName = strange),
        )
        val target = MemoryApp()
        val outcome = target.backup.restoreWithNotes(BackupFiles.opener(BackupFiles.craft(BackupFiles.framed(body))), BackupFiles.PASSPHRASE).done()
        assertEquals(1, outcome.report.counts.agents)
        assertEquals(agent, target.agents.get(AgentId("agt_h")))
        assertEquals(memory, target.memories.get(MemoryId("mem_h")))
        assertTrue(target.providers.getAll().isEmpty(), "no provider came out of a string")
        val entries = target.conversations.getEntries(chat.id)
        assertEquals(listOf(injection + strange, "A tool was used here: forged. $injection"), entries.map { it.message.text })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), entries.map { it.message.role })
        assertTrue(entries.all { it.message.toolCalls.isEmpty() })
        // And the same strings saved again come back byte for byte.
        val (saved, file) = target.saveToBytes()
        saved.done()
        val again = MemoryApp()
        again.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        assertEquals(target.agents.getAll(), again.agents.getAll())
        assertEquals(target.memories.list(), again.memories.list())
        assertContentEquals(entries.map { it.message.text }, again.conversations.getEntries(chat.id).map { it.message.text })
    }

    @Test
    fun `records the app would refuse from its own editors are refused, and nothing is restored`() = runBlocking<Unit> {
        val agent = Samples.agent(id = "agt_a", name = "A", modelId = null).copy(templateId = null)
        val bad: List<Pair<String, List<BackupRecord>>> = listOf(
            "agent name too long" to listOf(BackupRecord.Agent(agent.copy(name = "x".repeat(41)))),
            "two agents with one name" to listOf(BackupRecord.Agent(agent), BackupRecord.Agent(agent.copy(id = AgentId("agt_b"), name = " a "))),
            "same agent twice" to listOf(BackupRecord.Agent(agent), BackupRecord.Agent(agent)),
            "temperature" to listOf(BackupRecord.Agent(agent.copy(generation = agent.generation.copy(temperature = 9.0)))),
            "http to the internet" to listOf(BackupRecord.Provider(Samples.provider(id = "prv_x", baseUrl = "http://example.com/v1"))),
            "password in the address" to listOf(BackupRecord.Provider(Samples.provider(id = "prv_x", baseUrl = "https://me:pw@example.com/v1"))),
            "header with a line break" to listOf(BackupRecord.Provider(Samples.provider(id = "prv_x").copy(extraHeaders = mapOf("X-Title" to "a\r\nHost: evil")))),
            "a model without its provider" to listOf(BackupRecord.Model(Samples.model(id = "mdl_x", providerId = "prv_missing"))),
            "memory content too long" to listOf(BackupRecord.Agent(agent), BackupRecord.Memory(MemorySamples.memory("m", scope = MemoryScope.Agent(agent.id), content = "x".repeat(20_001)))),
            "memory importance" to listOf(BackupRecord.Agent(agent), BackupRecord.Memory(MemorySamples.memory("m", scope = MemoryScope.Agent(agent.id)).copy(importance = 1.5))),
            "a chat of no agent" to listOf(BackupRecord.Chat(Conversation(ConversationId("c"), AgentId("agt_none"), "t", 1, 1))),
            "a message of no chat" to listOf(BackupRecord.Agent(agent), BackupRecord.Entry(ConversationId("c"), 0, EntryAuthor.OWNER, "hi", 1)),
            "messages out of order" to listOf(
                BackupRecord.Agent(agent), BackupRecord.Chat(Conversation(ConversationId("c"), agent.id, "t", 1, 1)),
                BackupRecord.Entry(ConversationId("c"), 2, EntryAuthor.OWNER, "a", 1), BackupRecord.Entry(ConversationId("c"), 1, EntryAuthor.OWNER, "b", 1),
            ),
            "an AI app with a bad package" to listOf(BackupRecord.App(Samples.aiApp(packageName = "not a package"))),
            "an id with spaces" to listOf(BackupRecord.Agent(agent.copy(id = AgentId("agt one")))),
        )
        for ((what, body) in bad) {
            val target = fresh()
            val before = target.agents.getAll()
            val file = BackupFiles.craft(BackupFiles.framed(body))
            assertIs<BackupProblem.Invalid>(target.backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE).problem(), what)
            assertIs<BackupProblem.Invalid>(target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).problem(), what)
            assertEquals(before, target.agents.getAll(), what)
            assertTrue(target.providers.getAll().all { it.id.value == "prv_simulator" }, what)
        }
    }

    @Test
    fun `too many agents is too large`() = runBlocking<Unit> {
        val body = (0..BackupFormat.MAX_AGENTS).map { BackupRecord.Agent(Samples.agent(id = "agt_$it", name = "Agent $it", modelId = null).copy(templateId = null)) }
        val file = BackupFiles.craft(BackupFiles.framed(body))
        assertEquals(BackupProblem.TooLarge, MemoryApp().backup.preview(BackupFiles.opener(file), BackupFiles.PASSPHRASE).problem())
    }

    @Test
    fun `progress is reported in steps the owner reads`() = runBlocking<Unit> {
        val steps = mutableListOf<BackupProgress>()
        val out = ByteArrayOutputStream()
        phone().backup.saveWithNotes(BackupFiles.PASSPHRASE, BackupFiles.PASSPHRASE, { out }) { steps += it }.done()
        assertEquals(listOf(BackupTexts.STEP_GATHERING, BackupTexts.STEP_LOCKING, BackupTexts.STEP_WRITING), steps.map { it.step }.distinct())
        assertEquals(steps.last().total, steps.last().done)
        steps.clear()
        fresh().backup.restoreWithNotes(BackupFiles.opener(out.toByteArray()), BackupFiles.PASSPHRASE) { steps += it }.done()
        assertEquals(listOf(BackupTexts.STEP_UNLOCKING, BackupTexts.STEP_CHECKING, BackupTexts.STEP_RESTORING), steps.map { it.step }.distinct())
        assertEquals(steps.last().total, steps.last().done)
    }

    @Test
    fun `daily limits from the file are brought into the settings ranges before the stricter one is taken`() = runBlocking<Unit> {
        val target = MemoryApp()
        val file = BackupFiles.craft(BackupFiles.framed(emptyList(), settings = BackupSettings(maxModelCallsPerDay = 0, maxTokensPerDay = -5, maxSpendPerDayMicros = 0)))
        target.backup.restoreWithNotes(BackupFiles.opener(file), BackupFiles.PASSPHRASE).done()
        val limits = target.settings.get().dailyLimits
        assertEquals(DailyLimits.CALLS_RANGE.first, limits.maxCalls)
        assertEquals(DailyLimits.TOKENS_RANGE.first, limits.maxTokens)
        assertEquals(DailyLimits.SPEND_RANGE_MICROS.first, limits.maxSpendMicros)
    }
}
