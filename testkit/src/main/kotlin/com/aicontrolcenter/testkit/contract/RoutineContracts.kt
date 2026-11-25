package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.RoutineId
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpLimits
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.routines.OwnerRoutines
import com.aicontrolcenter.core.routines.Routine
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.routines.RoutineRun
import com.aicontrolcenter.core.routines.RoutineRunOutcome
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fully populated routines, runs, rules and imports. */
object RoutineSamples {
    fun routine(id: String, at: Long = 1_000) = Routine(
        id = RoutineId(id), title = "Morning news, \"short\" 📰", objectiveText = "Summarise the news\nin five lines",
        options = ObjectiveOptions(reviewResults = false, saveKnowledge = true, maxTasks = 4, routineId = RoutineId(id)),
        schedule = RoutineSchedule.Daily(7 * 60, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)), enabled = true,
        approvalTimeoutMinutes = 120, allowScreenActions = true, createdAt = at, updatedAt = at + 1, nextRunAt = at + 3_600_000,
        consecutiveFailures = 1,
    )

    fun run(id: String, routine: String, dueAt: Long, outcome: RoutineRunOutcome = RoutineRunOutcome.STARTED) = RoutineRun(
        id = RoutineRunId(id), routineId = RoutineId(routine), dueAt = dueAt, outcome = outcome,
        startedAt = if (outcome == RoutineRunOutcome.STARTED) dueAt + 5 else null,
        objectiveId = if (outcome == RoutineRunOutcome.STARTED) TaskId("tsk_$id") else null,
        note = "Started at 7:00, \"on time\"",
    )

    fun rule(id: String, folder: String = "fld_$id", at: Long = 1_000) = PickUpRule(
        id = PickUpRuleId(id), folderId = FolderId(folder), spaceId = SpaceId("spc_inbox"), enabled = true, createdAt = at, updatedAt = at + 1,
        lastRunAt = at + 2, lastResult = "Imported 2 files, \"notes.txt\" and 1 more",
    )

    fun file(rule: String, n: Int, at: Long = 2_000, size: Long = 1_000) = PickedUpFile(
        ruleId = PickUpRuleId(rule), contentSha256 = n.toString(16).padStart(64, '0'), documentName = "note $n, \"draft\".txt",
        sizeBytes = size, importedAt = at, memoryId = MemoryId("mem_$n"),
    )
}

/** Appendix A: what every [RoutineRepository] must do. */
abstract class RoutineRepositoryContract {
    protected abstract fun createRepository(): RoutineRepository

    private val r = RoutineSamples

    @Test
    fun `routines are stored with every field, oldest first, and updated atomically`() = runTest {
        val repo = createRepository()
        val a = r.routine("rtn_a", at = 2_000)
        val b = r.routine("rtn_b", at = 1_000).copy(
            schedule = RoutineSchedule.EveryHours(3, 30, setOf(DayOfWeek.SUNDAY)), approvalTimeoutMinutes = null, nextRunAt = null,
            enabled = false, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES, consecutiveFailures = 3, options = ObjectiveOptions(),
        )
        repo.upsert(a)
        repo.upsert(b)
        assertEquals(a, repo.get(a.id))
        assertEquals(b, repo.get(b.id))
        assertEquals(listOf(b, a), repo.getAll())
        assertEquals(listOf(b, a), repo.observeAll().first())
        assertNull(repo.get(RoutineId("rtn_missing")))
        withContext(Dispatchers.Default) {
            (1..40).map { launch { repo.update(a.id) { it.copy(consecutiveFailures = it.consecutiveFailures + 1) } } }.joinAll()
        }
        assertEquals(41, repo.get(a.id)!!.consecutiveFailures)
        assertNull(repo.update(RoutineId("rtn_missing")) { it })
    }

    @Test
    fun `runs are stored newest first, updated, pruned, and go with their routine`() = runTest {
        val repo = createRepository()
        repo.upsert(r.routine("rtn_a"))
        repo.upsert(r.routine("rtn_b"))
        val old = r.run("rrn_1", "rtn_a", dueAt = 100, outcome = RoutineRunOutcome.MISSED)
        val tap = r.run("rrn_2", "rtn_a", dueAt = 200, outcome = RoutineRunOutcome.WAITING_FOR_TAP)
        val tieLow = r.run("rrn_3", "rtn_a", dueAt = 300)
        val tieHigh = r.run("rrn_4", "rtn_a", dueAt = 300, outcome = RoutineRunOutcome.SKIPPED_STILL_RUNNING)
        val other = r.run("rrn_5", "rtn_b", dueAt = 50, outcome = RoutineRunOutcome.SKIPPED_DAILY_LIMIT)
        listOf(old, tap, tieLow, tieHigh, other).forEach { repo.appendRun(it) }
        repo.appendRun(old.copy(note = "changed"))
        assertEquals(listOf(tieHigh, tieLow, tap, old), repo.runs(RoutineId("rtn_a"), 10))
        assertEquals(listOf(tieHigh, tieLow), repo.observeRuns(RoutineId("rtn_a"), 2).first())
        assertFailsWith<IllegalArgumentException> { repo.appendRun(r.run("rrn_x", "rtn_missing", dueAt = 1)) }
        // A tap starts the run that waited for it.
        val started = repo.updateRun(tap.id) { it.copy(outcome = RoutineRunOutcome.STARTED, startedAt = 250, objectiveId = TaskId("tsk_tap")) }
        assertEquals(RoutineRunOutcome.STARTED, started?.outcome)
        assertEquals(started, repo.runs(RoutineId("rtn_a"), 10).single { it.id == tap.id })
        assertNull(repo.updateRun(RoutineRunId("rrn_missing")) { it })
        assertEquals(2, repo.pruneRuns(beforeMs = 150))
        assertEquals(listOf(tieHigh, tieLow, started), repo.runs(RoutineId("rtn_a"), 10))
        repo.delete(RoutineId("rtn_a"))
        assertNull(repo.get(RoutineId("rtn_a")))
        assertEquals(emptyList(), repo.runs(RoutineId("rtn_a"), 10))
        repo.delete(RoutineId("rtn_a"))
    }

    @Test
    fun `open observers see routines and runs change`() = runTest {
        val repo = createRepository()
        val routines = collectIn(backgroundScope, repo.observeAll())
        val runs = collectIn(backgroundScope, repo.observeRuns(RoutineId("rtn_a"), 5))
        awaitUntil { routines.isNotEmpty() && runs.isNotEmpty() }
        repo.upsert(r.routine("rtn_a"))
        repo.appendRun(r.run("rrn_1", "rtn_a", dueAt = 100))
        awaitUntil { routines.last().size == 1 && runs.last().size == 1 }
        repo.update(RoutineId("rtn_a")) { it.copy(enabled = false, nextRunAt = null) }
        awaitUntil { routines.last().single().enabled == false }
        repo.delete(RoutineId("rtn_a"))
        awaitUntil { routines.last().isEmpty() && runs.last().isEmpty() }
    }
}

/** Appendix A: what the owner's side of routines must do. */
abstract class OwnerRoutinesContract {
    /** The owner's port over [repository], using [clock], [ids] and the owner's [zone]. */
    protected abstract fun createOwnerRoutines(repository: RoutineRepository, clock: FakeClock, ids: IdGenerator, zone: ZoneId): OwnerRoutines

    /** An empty store of routines for [createOwnerRoutines]. */
    protected abstract fun createRepository(): RoutineRepository

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun draft(title: String = "Morning news", schedule: RoutineSchedule = RoutineSchedule.Daily(7 * 60)) =
        RoutineDraft(title = title, objectiveText = "Summarise the news", schedule = schedule)

    @Test
    fun `a new routine is on, due next by its schedule, with screen actions off unless ticked`() = runTest {
        val clock = FakeClock(UsageSamples.at("2026-11-25T06:00"))
        val repo = createRepository()
        val owner = createOwnerRoutines(repo, clock, SequentialIdGenerator(), berlin)
        val routine = assertIs<OwnerResult.Done<Routine>>(owner.create(draft())).value
        assertTrue(routine.enabled)
        assertFalse(routine.allowScreenActions)
        assertEquals(UsageSamples.at("2026-11-25T07:00"), routine.nextRunAt)
        assertEquals(clock.current, routine.createdAt)
        assertEquals(routine, repo.get(routine.id))
        assertEquals(listOf(routine), owner.observeAll().first())
    }

    @Test
    fun `invalid drafts and an eleventh routine are refused`() = runTest {
        val repo = createRepository()
        val owner = createOwnerRoutines(repo, FakeClock(1_000), SequentialIdGenerator(), berlin)
        assertIs<OwnerResult.Refused>(owner.create(draft(title = " ")))
        assertIs<OwnerResult.Refused>(owner.create(draft().copy(objectiveText = "")))
        assertIs<OwnerResult.Refused>(owner.create(draft().copy(approvalTimeoutMinutes = RoutineLimits.MAX_APPROVAL_TIMEOUT_MINUTES + 1)))
        val results = withContext(Dispatchers.Default) {
            (1..RoutineLimits.MAX_ROUTINES + 5).map { n -> async { owner.create(draft(title = "Routine $n")) } }.awaitAll()
        }
        assertEquals(RoutineLimits.MAX_ROUTINES, results.count { it is OwnerResult.Done })
        assertEquals(RoutineLimits.MAX_ROUTINES, repo.getAll().size)
    }

    @Test
    fun `changing, switching off and on, and deleting`() = runTest {
        val clock = FakeClock(UsageSamples.at("2026-11-25T06:00"))
        val repo = createRepository()
        val owner = createOwnerRoutines(repo, clock, SequentialIdGenerator(), berlin)
        val routine = assertIs<OwnerResult.Done<Routine>>(owner.create(draft())).value
        repo.appendRun(RoutineSamples.run("rrn_1", routine.id.value, dueAt = 5))
        clock.current = UsageSamples.at("2026-11-25T08:00")
        val changed = assertIs<OwnerResult.Done<Routine>>(owner.change(routine.id, draft(schedule = RoutineSchedule.EveryHours(2)).copy(allowScreenActions = true))).value
        assertEquals(UsageSamples.at("2026-11-25T10:00"), changed.nextRunAt)
        assertTrue(changed.allowScreenActions)
        assertIs<OwnerResult.Refused>(owner.change(routine.id, draft(title = "")))
        val off = assertIs<OwnerResult.Done<Routine>>(owner.setEnabled(routine.id, false)).value
        assertFalse(off.enabled)
        assertNull(off.nextRunAt)
        // The runner paused it after three failures; switching it on clears that.
        repo.update(routine.id) { it.copy(consecutiveFailures = 3, pausedNote = RoutineLimits.PAUSED_AFTER_FAILURES) }
        clock.current = UsageSamples.at("2026-11-25T10:30")
        val on = assertIs<OwnerResult.Done<Routine>>(owner.setEnabled(routine.id, true)).value
        assertTrue(on.enabled)
        assertEquals(0, on.consecutiveFailures)
        assertNull(on.pausedNote)
        assertEquals(UsageSamples.at("2026-11-25T12:00"), on.nextRunAt)
        assertEquals(1, owner.observeRuns(routine.id, 5).first().size)
        assertTrue(owner.delete(routine.id))
        assertFalse(owner.delete(routine.id))
        assertEquals(emptyList(), repo.runs(routine.id, 5))
        assertIs<OwnerResult.Refused>(owner.setEnabled(routine.id, true))
        assertIs<OwnerResult.Refused>(owner.change(routine.id, draft()))
    }
}

/** Appendix A: what every [PickUpRuleRepository] must do. */
abstract class PickUpRuleRepositoryContract {
    protected abstract fun createRepository(): PickUpRuleRepository

    private val r = RoutineSamples

    @Test
    fun `rules are stored with every field, one per folder, oldest first`() = runTest {
        val repo = createRepository()
        val a = r.rule("pku_a", at = 2_000)
        val b = r.rule("pku_b", at = 1_000).copy(enabled = false, lastRunAt = null, lastResult = null)
        repo.upsert(a)
        repo.upsert(b)
        assertEquals(listOf(b, a), repo.getAll())
        assertEquals(listOf(b, a), repo.observeAll().first())
        assertEquals(a, repo.get(a.id))
        assertFailsWith<IllegalArgumentException> { repo.upsert(r.rule("pku_c", folder = a.folderId.value)) }
        repo.upsert(a.copy(spaceId = SpaceId("spc_other")))
        assertEquals(SpaceId("spc_other"), repo.get(a.id)!!.spaceId, "the same rule may be replaced")
        assertEquals(false, repo.update(a.id) { it.copy(enabled = false) }?.enabled)
        assertFailsWith<IllegalArgumentException> { repo.update(a.id) { it.copy(folderId = b.folderId) } }
        assertNull(repo.update(PickUpRuleId("pku_missing")) { it })
    }

    @Test
    fun `each content is imported once per rule, and the bytes of a day are counted`() = runTest {
        val repo = createRepository()
        repo.upsert(r.rule("pku_a"))
        repo.upsert(r.rule("pku_b"))
        val first = r.file("pku_a", 1, at = 1_000, size = 3_000)
        assertFalse(repo.wasImported(first.ruleId, first.contentSha256))
        assertTrue(repo.recordImport(first))
        assertTrue(repo.wasImported(first.ruleId, first.contentSha256))
        assertFalse(repo.recordImport(first.copy(documentName = "copy.txt", importedAt = 1_500)), "the same content again")
        assertTrue(repo.recordImport(r.file("pku_b", 1, at = 2_000, size = 2_000)), "another rule may import the same content")
        assertTrue(repo.recordImport(r.file("pku_a", 2, at = 3_000, size = 1_000)))
        assertFailsWith<IllegalArgumentException> { repo.recordImport(r.file("pku_missing", 3)) }
        assertEquals(6_000, repo.bytesImportedSince(0))
        assertEquals(3_000, repo.bytesImportedSince(2_000))
        assertEquals(listOf(r.file("pku_a", 2, at = 3_000, size = 1_000), first), repo.observeImports(PickUpRuleId("pku_a"), 10).first())
        assertEquals(1, repo.observeImports(PickUpRuleId("pku_a"), 1).first().size)
        repo.delete(PickUpRuleId("pku_a"))
        assertNull(repo.get(PickUpRuleId("pku_a")))
        assertFalse(repo.wasImported(first.ruleId, first.contentSha256))
        assertEquals(2_000, repo.bytesImportedSince(0))
    }

    @Test
    fun `of concurrent imports of the same content exactly one is recorded`() = runTest {
        val repo = createRepository()
        repo.upsert(r.rule("pku_a"))
        val results = withContext(Dispatchers.Default) {
            (1..20).map { async { repo.recordImport(r.file("pku_a", 7)) } }.awaitAll()
        }
        assertEquals(1, results.count { it })
        assertEquals(1, repo.observeImports(PickUpRuleId("pku_a"), 10).first().size)
    }
}

/** Appendix A: what the owner's side of folder pick-up must do. */
abstract class OwnerPickUpRulesContract {
    /** The owner's port over [repository], checking folders in [folders] and spaces in [memory]. */
    protected abstract fun createOwnerPickUpRules(
        repository: PickUpRuleRepository,
        folders: SharedFolderRepository,
        memory: MemoryRepository,
        clock: FakeClock,
        ids: IdGenerator,
    ): OwnerPickUpRules

    protected abstract fun createRepository(): PickUpRuleRepository

    protected abstract fun createFolders(): SharedFolderRepository

    protected abstract fun createMemory(): MemoryRepository

    private suspend fun world(folderCount: Int = 1): Triple<PickUpRuleRepository, OwnerPickUpRules, FakeClock> {
        val repo = createRepository()
        val folders = createFolders()
        val memory = createMemory()
        repeat(folderCount) { n -> folders.upsert(SharedFolder(FolderId("fld_$n"), "content://tree/$n", "Folder $n", addedAt = n.toLong())) }
        memory.upsertSpace(KnowledgeSpace(id = SpaceId("spc_inbox"), name = "Inbox", description = "", createdAt = 1, updatedAt = 1))
        memory.upsertSpace(KnowledgeSpace(id = SpaceId("spc_other"), name = "Other", description = "", createdAt = 1, updatedAt = 1))
        val clock = FakeClock(5_000)
        return Triple(repo, createOwnerPickUpRules(repo, folders, memory, clock, SequentialIdGenerator()), clock)
    }

    @Test
    fun `a rule is made from an existing folder into an existing space, once per folder`() = runTest {
        val (repo, owner, clock) = world()
        val rule = assertIs<OwnerResult.Done<PickUpRule>>(owner.create(FolderId("fld_0"), SpaceId("spc_inbox"))).value
        assertTrue(rule.enabled)
        assertEquals(clock.current, rule.createdAt)
        assertEquals(rule, repo.get(rule.id))
        assertEquals(listOf(rule), owner.observeAll().first())
        assertIs<OwnerResult.Refused>(owner.create(FolderId("fld_0"), SpaceId("spc_other")), "one rule per folder")
        assertIs<OwnerResult.Refused>(owner.create(FolderId("fld_missing"), SpaceId("spc_inbox")))
        assertIs<OwnerResult.Refused>(owner.create(FolderId("fld_0"), SpaceId("spc_missing")))
    }

    @Test
    fun `the rule cap holds under concurrent creation`() = runTest {
        val (repo, owner, _) = world(folderCount = PickUpLimits.MAX_RULES + 5)
        val results = withContext(Dispatchers.Default) {
            (0 until PickUpLimits.MAX_RULES + 5).map { n -> async { owner.create(FolderId("fld_$n"), SpaceId("spc_inbox")) } }.awaitAll()
        }
        assertEquals(PickUpLimits.MAX_RULES, results.count { it is OwnerResult.Done })
        assertEquals(PickUpLimits.MAX_RULES, repo.getAll().size)
    }

    @Test
    fun `a rule changes space, switches and is deleted with its imports`() = runTest {
        val (repo, owner, _) = world()
        val rule = assertIs<OwnerResult.Done<PickUpRule>>(owner.create(FolderId("fld_0"), SpaceId("spc_inbox"))).value
        assertEquals(SpaceId("spc_other"), assertIs<OwnerResult.Done<PickUpRule>>(owner.changeSpace(rule.id, SpaceId("spc_other"))).value.spaceId)
        assertIs<OwnerResult.Refused>(owner.changeSpace(rule.id, SpaceId("spc_missing")))
        assertFalse(assertIs<OwnerResult.Done<PickUpRule>>(owner.setEnabled(rule.id, false)).value.enabled)
        repo.recordImport(RoutineSamples.file(rule.id.value, 1))
        assertTrue(owner.delete(rule.id))
        assertFalse(owner.delete(rule.id))
        assertEquals(0, repo.bytesImportedSince(0))
        assertIs<OwnerResult.Refused>(owner.setEnabled(rule.id, true))
        assertIs<OwnerResult.Refused>(owner.changeSpace(rule.id, SpaceId("spc_inbox")))
    }
}
