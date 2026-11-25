package com.aicontrolcenter.core

import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.LiveTurn
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.ExitHistory
import com.aicontrolcenter.core.health.ExitKind
import com.aicontrolcenter.core.health.NetworkStatus
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.core.health.StorageLevel
import com.aicontrolcenter.core.health.TimedWaits
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.DetectedServer
import com.aicontrolcenter.core.llm.ModelSyncReport
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.OwnerKeyCheck
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.llm.ProviderPreset
import com.aicontrolcenter.core.llm.ProviderValidationIssue
import com.aicontrolcenter.core.operator.ActiveWindow
import com.aicontrolcenter.core.operator.CommandSlot
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.FrontState
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.ScreenControl
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.ReadOnlySettings
import com.aicontrolcenter.core.settings.SettingsBounds
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.OwnerConversation
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tools.PreparedAction
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.UsageLedger
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.UsageTotals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6: every addition is additive with a safe default, so existing implementations keep working unchanged. */
class Phase6DefaultsTest {
    @Test
    fun `settings gain the Phase 6 defaults, and the read-only view cannot write`() = runTest {
        val defaults = FrameworkSettings()
        assertTrue(defaults.keepAwakeWhileAgentsWork && defaults.notifyWhenWorkEnds && defaults.agentQuestionsEnabled)
        assertEquals(listOf(200, 20, 730), listOf(defaults.maxModelCallsPerDay, defaults.ownerQuestionTimeoutMinutes, defaults.usageRetentionDays))
        assertEquals(500_000L, defaults.maxTokensPerDay)
        assertEquals(1_000_000L, defaults.maxSpendPerDayMicros)
        assertFalse(defaults.calmMotion || defaults.routinesEnabled || defaults.folderPickUpEnabled, "routines and pick-up are off after installing")
        assertTrue(defaults.ownerQuestionTimeoutMinutes in SettingsBounds.QUESTION_TIMEOUT_MINUTES)
        assertTrue(defaults.usageRetentionDays in SettingsBounds.USAGE_RETENTION_DAYS)

        val writer = object : SettingsRepository {
            val state = MutableStateFlow(FrameworkSettings())
            override val settings: Flow<FrameworkSettings> get() = state
            override suspend fun get() = state.value
            override suspend fun update(transform: (FrameworkSettings) -> FrameworkSettings) = transform(state.value).also { state.value = it }
        }
        val view: SettingsView = ReadOnlySettings(writer)
        writer.update { it.copy(calmMotion = true) }
        assertTrue(view.get().calmMotion)
        assertTrue(view.settings.first().calmMotion)
        assertFalse(view is SettingsRepository)
        assertTrue(ReadOnlySettings::class.java.methods.none { it.name.startsWith("update") }, "no way to write")
        assertTrue(ReadOnlySettings::class.java.declaredFields.all { Modifier.isPrivate(it.modifiers) })
    }

    @Test
    fun `the shell slot is held by one token at a time`() {
        var running = false
        val slot = CommandSlot { running }
        assertNull(slot.reserve("call_1"))
        assertNull(slot.reserve("call_1"), "asking again is harmless")
        assertEquals(Workspace.COMMAND_SLOT_TAKEN, slot.reserve("call_2"))
        slot.release("call_2")
        assertEquals("call_1", slot.holder(), "only the holder releases")
        slot.release("call_1")
        assertNull(slot.holder())
        running = true
        assertEquals(Workspace.COMMAND_SLOT_TAKEN, slot.reserve("call_3"), "not while a command runs")
        running = false
        assertNull(slot.reserve("call_3"))
        val prepared = object : PreparedAction {
            override suspend fun changedSince(): String? = null
        }
        runTest { assertNull(prepared.beforeOwnerAsked()) }
        prepared.callEnded()
    }

    @Test
    fun `screen defaults know nothing hidden and nothing switched off`() = runTest {
        val control = object : ScreenControl {
            override val stopped: StateFlow<Boolean> = MutableStateFlow(false)
            override fun stop() {}
            override fun resume() {}
        }
        assertFalse(control.switchedOffByAndroid.value)
        assertFalse(control.switchedOffByAndroid is MutableStateFlow<*>, "the default cannot be switched by a cast")
        val operator = com.aicontrolcenter.testsupport.MinimalScreen(ActiveWindow("com.example", null))
        assertEquals(FrontState.App(ActiveWindow("com.example", null)), operator.frontState())
        assertEquals(FrontState.None, com.aicontrolcenter.testsupport.MinimalScreen(null).frontState())
        operator.holdSession("req_1", "Operator", "com.example")
        operator.releaseSession("req_1")
        assertTrue(
            ConsequentialControls.PROTECTED_HANDLER_ACTIONS.containsAll(
                listOf(
                    "android.intent.action.OPEN_DOCUMENT", "android.intent.action.OPEN_DOCUMENT_TREE",
                    "android.intent.action.CREATE_DOCUMENT", "android.provider.action.PICK_IMAGES",
                ),
            ),
        )
    }

    @Test
    fun `orchestrator, chat and provider defaults change nothing`() = runTest {
        val orchestrator = object : Orchestrator {
            var recovered = 0
            override val running: StateFlow<Set<TaskId>> = MutableStateFlow(emptySet())
            override val assignments: StateFlow<Map<AgentId, TaskId>> = MutableStateFlow(emptyMap())
            override suspend fun startObjective(text: String, options: ObjectiveOptions): TaskRecord = error("unused")
            override suspend fun pause(rootId: TaskId) {}
            override suspend fun resume(rootId: TaskId) {}
            override suspend fun cancel(rootId: TaskId) {}
            override suspend fun cancelTask(taskId: TaskId) {}
            override suspend fun retryTask(taskId: TaskId) {}
            override suspend fun delete(rootId: TaskId) {}
            override suspend fun recoverInterrupted(): List<TaskId> = listOf(TaskId("tsk_${++recovered}"))
        }
        assertEquals(listOf(TaskId("tsk_1")), orchestrator.recoverInterrupted("Android closed the app to free memory", resumeAllowed = false))
        assertNull(orchestrator.timedWaits.nextDueAt.value)

        val chat = object : AgentChat {
            override fun observeConversations(agentId: AgentId?): Flow<List<Conversation>> = flowOf(emptyList())
            override fun observeEntries(id: ConversationId): Flow<List<ConversationEntry>> = flowOf(emptyList())
            override val liveTurns: StateFlow<Map<ConversationId, LiveTurn>> = MutableStateFlow(emptyMap())
            override suspend fun startConversation(agentId: AgentId, title: String?): Conversation = error("unused")
            override suspend fun rename(id: ConversationId, title: String) {}
            override suspend fun delete(id: ConversationId) {}
            override suspend fun send(id: ConversationId, text: String): TurnResult = error("unused")
            override suspend fun cancel(id: ConversationId) {}
        }
        assertIs<TurnResult.Failed>(chat.sendAgain(ConversationId("cnv_1")))
        assertEquals(0, chat.recoverInterruptedTurns())
        assertNull(CallContext().conversationId)

        val providers = object : ProviderManager {
            override val presets: List<ProviderPreset> = emptyList()
            override suspend fun validate(draft: ProviderDraft, editing: ProviderId?): List<ProviderValidationIssue> = emptyList()
            override suspend fun addProvider(draft: ProviderDraft): ModelProvider = error("unused")
            override suspend fun updateProvider(id: ProviderId, draft: ProviderDraft): ModelProvider = error("unused")
            override suspend fun setProviderEnabled(id: ProviderId, enabled: Boolean) {}
            override suspend fun removeProvider(id: ProviderId) {}
            override suspend fun checkProvider(id: ProviderId): ProviderHealth = error("unused")
            override suspend fun checkAllProviders() {}
            override suspend fun refreshModels(id: ProviderId): ModelSyncReport = error("unused")
            override suspend fun addModel(providerId: ProviderId, modelName: String, displayName: String?, capabilities: ModelCapabilities?): ModelDescriptor = error("unused")
            override suspend fun updateModel(id: ModelId, displayName: String, capabilities: ModelCapabilities, notes: String) {}
            override suspend fun setModelEnabled(id: ModelId, enabled: Boolean) {}
            override suspend fun removeModel(id: ModelId) {}
            override suspend fun hasApiKey(id: ProviderId): Boolean = false
            override suspend fun detectLocalServers(): List<DetectedServer> = emptyList()
            override suspend fun ensureBuiltIns() {}
        }
        // Security review of wave 1, F3: the re-checks and the key check are their own ports, not the owner's provider manager.
        assertTrue(ProviderRechecks::class.java.isAssignableFrom(providers.javaClass).not())
        assertTrue(OwnerKeyCheck::class.java.isAssignableFrom(providers.javaClass).not())
        assertEquals(emptyList(), ProviderManager::class.java.methods.map { it.name.substringBefore('-') }.filter { it in setOf("recheckIfStale", "getTimedWaits", "matchesStoredKey") })
        assertNull(ProviderRechecks.None.recheckIfStale(ProviderId("p"), 60_000))
        assertNull(ProviderRechecks.None.timedWaits.nextDueAt.value)
        assertFalse(OwnerKeyCheck.None.matchesStoredKey("sk-anything"))
        assertEquals(emptyList(), ProviderRechecks::class.java.methods.map { it.name }.filter { it.startsWith("add") || it.startsWith("update") || it.startsWith("remove") || it.startsWith("matches") })
    }

    @Test
    fun `health, usage and notes have inert stand-ins`() = runTest {
        assertEquals(emptyList(), ExitHistory.None.newSince(0))
        assertTrue(NetworkStatus.AlwaysOn.available.value)
        TimedWaits.None.wake()
        assertEquals(UsageTotals(), UsageLedger.None.totalsSince(0))
        assertEquals(UsageTotals(), UsageReports.None.observeTotals(com.aicontrolcenter.core.usage.UsageRange.ALL).first())
        assertEquals(emptyList(), ModelPrices.None.getAll())
        assertIs<OwnerResult.Refused>(OwnerConversation.Unavailable.addNote(TaskId("tsk_root"), null, "Hello"))
        assertNull(OwnerConversation.textProblem("Please focus on prices."))
        assertTrue(OwnerConversation.textProblem(" ") != null && OwnerConversation.textProblem("x".repeat(1_001)) != null)
        assertEquals(StorageLevel.UNKNOWN, StorageLevel.of(null))
        assertEquals(StorageLevel.CRITICAL, StorageLevel.of(99L * 1024 * 1024))
        assertEquals(StorageLevel.LOW, StorageLevel.of(100L * 1024 * 1024))
        assertEquals(StorageLevel.LOW, StorageLevel.of(499L * 1024 * 1024))
        assertEquals(StorageLevel.OK, StorageLevel.of(500L * 1024 * 1024))
        assertTrue(BackgroundState.UNKNOWN.allClear)
        assertFalse(BackgroundState(true, batteryOptimised = true, backgroundRestricted = false, standbyBucket = StandbyBucket.ACTIVE).allClear)
        assertTrue(BackgroundState(true, false, false, StandbyBucket.RARE).standbyWarning)
        assertEquals("Android closed the app to free memory", ExitKind.LOW_MEMORY.cause)
        assertTrue(ExitKind.entries.all { it.cause.isNotBlank() })
        assertTrue(DatabaseState.entries.all { it.ownerText.isNotBlank() })
        assertFalse(DatabaseState.OK.needsRecovery)
        assertTrue(OwnerRecovery.confirms("  start over "))
        assertFalse(OwnerRecovery.confirms("start"))
    }
}
