package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

abstract class ActivityLogContract {
    protected abstract fun createLog(): ActivityLog

    @Test
    fun `records every field and returns newest first`() = runTest {
        val log = createLog()
        val first = Samples.event(id = "evt_1", at = 100)
        val second = Samples.event(id = "evt_2", at = 200, egress = false).copy(data = null, agentId = null, taskId = null, rootTaskId = null)
        log.record(first)
        log.record(second)
        assertEquals(listOf(second, first), log.recent())
        assertEquals(listOf(second, first), log.observe().first())
    }

    @Test
    fun `events with the same time are returned newest recorded first`() = runTest {
        val log = createLog()
        log.record(Samples.event(id = "evt_a", at = 100))
        log.record(Samples.event(id = "evt_b", at = 100))
        log.record(Samples.event(id = "evt_c", at = 100))
        assertEquals(listOf("evt_c", "evt_b", "evt_a"), log.recent().map { it.id.value })
        assertEquals(listOf("evt_c", "evt_b"), log.recent(limit = 2).map { it.id.value })
    }

    @Test
    fun `recording an id twice keeps the first event`() = runTest {
        val log = createLog()
        log.record(Samples.event(id = "evt_1", at = 100))
        log.record(Samples.event(id = "evt_1", at = 200).copy(title = "Second"))
        assertEquals(listOf(Samples.event(id = "evt_1", at = 100)), log.recent())
    }

    @Test
    fun `an open observer sees later events`() = runTest {
        val log = createLog()
        val seen = collectIn(backgroundScope, log.observe())
        awaitUntil { seen.isNotEmpty() }
        log.record(Samples.event(id = "evt_1", at = 100))
        awaitUntil { seen.last().size == 1 }
        log.clear()
        awaitUntil { seen.last().isEmpty() }
    }

    @Test
    fun `limit keeps the newest events`() = runTest {
        val log = createLog()
        repeat(5) { log.record(Samples.event(id = "evt_$it", at = 100L + it)) }
        assertEquals(listOf("evt_4", "evt_3"), log.recent(limit = 2).map { it.id.value })
        assertEquals(listOf("evt_4", "evt_3"), log.observe(limit = 2).first().map { it.id.value })
    }

    @Test
    fun `filters by type, agent, task, objective, severity and egress`() = runTest {
        val log = createLog()
        log.record(Samples.event(id = "evt_model", at = 1, type = ActivityType.MODEL_CALL, agentId = "agt_a", taskId = "tsk_1", rootTaskId = "tsk_root", egress = true))
        log.record(Samples.event(id = "evt_tool", at = 2, type = ActivityType.TOOL_CALL, agentId = "agt_b", taskId = "tsk_2", rootTaskId = "tsk_root", egress = false))
        log.record(Samples.event(id = "evt_err", at = 3, type = ActivityType.ERROR, severity = Severity.ERROR, agentId = "agt_a", taskId = "tsk_3", rootTaskId = "tsk_other", egress = false))
        log.record(Samples.event(id = "evt_warn", at = 4, type = ActivityType.TASK, severity = Severity.WARNING, agentId = null, taskId = null, rootTaskId = null, egress = false))

        suspend fun ids(filter: ActivityFilter) = log.recent(filter).map { it.id.value }
        assertEquals(listOf("evt_tool", "evt_model"), ids(ActivityFilter(types = setOf(ActivityType.MODEL_CALL, ActivityType.TOOL_CALL))))
        assertEquals(listOf("evt_err", "evt_model"), ids(ActivityFilter(agentId = AgentId("agt_a"))))
        assertEquals(listOf("evt_tool"), ids(ActivityFilter(taskId = TaskId("tsk_2"))))
        assertEquals(listOf("evt_tool", "evt_model"), ids(ActivityFilter(rootTaskId = TaskId("tsk_root"))))
        assertEquals(listOf("evt_warn", "evt_err"), ids(ActivityFilter(minSeverity = Severity.WARNING)))
        assertEquals(listOf("evt_err"), ids(ActivityFilter(minSeverity = Severity.ERROR)))
        assertEquals(listOf("evt_model"), ids(ActivityFilter(egressOnly = true)))
        assertEquals(listOf("evt_model"), ids(ActivityFilter(agentId = AgentId("agt_a"), types = setOf(ActivityType.MODEL_CALL))))
        assertEquals(listOf("evt_tool"), log.observe(ActivityFilter(agentId = AgentId("agt_b"))).first().map { it.id.value })
    }

    @Test
    fun `prune removes only older events and reports the count`() = runTest {
        val log = createLog()
        log.record(Samples.event(id = "evt_old", at = 100))
        log.record(Samples.event(id = "evt_edge", at = 200))
        log.record(Samples.event(id = "evt_new", at = 300))
        assertEquals(1, log.prune(olderThan = 200))
        assertEquals(listOf("evt_new", "evt_edge"), log.recent().map { it.id.value })
        assertEquals(0, log.prune(olderThan = 200))
    }

    @Test
    fun `clear removes everything`() = runTest {
        val log = createLog()
        log.record(Samples.event())
        log.clear()
        assertTrue(log.recent().isEmpty())
        assertTrue(log.observe().first().isEmpty())
    }
}

abstract class SettingsRepositoryContract {
    protected abstract fun createRepository(): SettingsRepository

    @Test
    fun `starts with defaults`() = runTest {
        val repo = createRepository()
        assertEquals(FrameworkSettings(), repo.get())
        assertEquals(FrameworkSettings(), repo.settings.first())
    }

    @Test
    fun `update applies the transform and is visible everywhere`() = runTest {
        val repo = createRepository()
        val changed = FrameworkSettings(
            localOnlyMode = true, activityRetentionDays = 7, directorAgentId = AgentId("agt_director"),
            maxConcurrentTasks = 4, maxDelegationDepth = 5, maxTasksPerObjective = 12, maxMessagesPerObjective = 99,
            maxModelCallsPerTask = 11, maxToolCallsPerTask = 22, maxRevisionRounds = 3, maxTaskAttempts = 4,
            taskTimeoutMinutes = 6, objectiveTimeoutMinutes = 60, approvalTimeoutMinutes = 9,
            autoResumeInterruptedTasks = true, operatorEnabled = true, accessibilityOperatorEnabled = true,
            onboardingCompleted = true, seedVersion = 3,
        )
        assertEquals(changed, repo.update { changed })
        assertEquals(changed, repo.get())
        assertEquals(changed, repo.settings.first())
    }

    @Test
    fun `an open observer sees later updates and concurrent updates are all applied`() = runTest {
        val repo = createRepository()
        val seen = collectIn(backgroundScope, repo.settings)
        awaitUntil { seen.isNotEmpty() }
        val start = repo.get().maxMessagesPerObjective
        withContext(Dispatchers.Default) {
            (1..50).map { launch { repo.update { it.copy(maxMessagesPerObjective = it.maxMessagesPerObjective + 1) } } }.joinAll()
        }
        assertEquals(start + 50, repo.get().maxMessagesPerObjective)
        awaitUntil { seen.last().maxMessagesPerObjective == start + 50 }
    }

    @Test
    fun `updates compose`() = runTest {
        val repo = createRepository()
        repo.update { it.copy(maxConcurrentTasks = it.maxConcurrentTasks + 1) }
        repo.update { it.copy(maxConcurrentTasks = it.maxConcurrentTasks + 1, directorAgentId = null) }
        assertEquals(FrameworkSettings().maxConcurrentTasks + 2, repo.get().maxConcurrentTasks)
        assertNull(repo.get().directorAgentId)
    }
}

abstract class SecretVaultContract {
    protected abstract fun createVault(): SecretVault

    @Test
    fun `stores, overwrites and deletes secrets`() = runTest {
        val vault = createVault()
        assertNull(vault.get("provider/a"))
        assertFalse(vault.contains("provider/a"))
        vault.put("provider/a", "sk-first")
        vault.put("provider/b", "other")
        assertEquals("sk-first", vault.get("provider/a"))
        assertTrue(vault.contains("provider/a"))
        vault.put("provider/a", "sk-second")
        assertEquals("sk-second", vault.get("provider/a"))
        assertEquals(setOf("provider/a", "provider/b"), vault.aliases())
        vault.delete("provider/a")
        assertNull(vault.get("provider/a"))
        assertEquals(setOf("provider/b"), vault.aliases())
        vault.delete("provider/a")
    }

    @Test
    fun `round-trips long and non-ascii secrets exactly`() = runTest {
        val vault = createVault()
        val long = "k".repeat(5_000) + "-ключ-鍵-🔑\n\twith whitespace "
        vault.put("weird alias/with spaces", long)
        assertEquals(long, vault.get("weird alias/with spaces"))
        vault.put("empty", "")
        assertEquals("", vault.get("empty"))
    }
}
