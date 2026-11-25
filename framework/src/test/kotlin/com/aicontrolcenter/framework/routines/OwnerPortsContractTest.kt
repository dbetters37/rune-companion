package com.aicontrolcenter.framework.routines

import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.MemoryRepository
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.pickup.PickUpFolders
import com.aicontrolcenter.core.pickup.PickUpRuleRepository
import com.aicontrolcenter.core.routines.RoutineDraft
import com.aicontrolcenter.core.routines.RoutineRepository
import com.aicontrolcenter.core.routines.RoutineSchedule
import com.aicontrolcenter.framework.pickup.DefaultOwnerPickUpRules
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryMemoryRepository
import com.aicontrolcenter.testkit.InMemoryPickUpRuleRepository
import com.aicontrolcenter.testkit.InMemoryRoutineRepository
import com.aicontrolcenter.testkit.InMemorySharedFolderRepository
import com.aicontrolcenter.testkit.SequentialIdGenerator
import com.aicontrolcenter.testkit.contract.OwnerPickUpRulesContract
import com.aicontrolcenter.testkit.contract.OwnerRoutinesContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** WP17's owner port of routines passes the contract of Appendix A over the in-memory store (Room: the app's tests). */
class DefaultOwnerRoutinesContractTest : OwnerRoutinesContract() {
    override fun createRepository(): RoutineRepository = InMemoryRoutineRepository()

    override fun createOwnerRoutines(repository: RoutineRepository, clock: FakeClock, ids: IdGenerator, zone: ZoneId) =
        DefaultOwnerRoutines(repository, ActivityRecorder(InMemoryActivityLog(), SequentialIdGenerator(), clock), clock, ids) { zone }
}

/** WP17's owner port of folder pick-up passes the contract (F1 and its second review included). */
class DefaultOwnerPickUpRulesContractTest : OwnerPickUpRulesContract() {
    override fun createRepository(): PickUpRuleRepository = InMemoryPickUpRuleRepository()

    override fun createFolders(): SharedFolderRepository = InMemorySharedFolderRepository()

    override fun createMemory(): MemoryRepository = InMemoryMemoryRepository()

    override fun createOwnerPickUpRules(repository: PickUpRuleRepository, folders: PickUpFolders, memory: MemoryRepository, clock: FakeClock, ids: IdGenerator) =
        DefaultOwnerPickUpRules(repository, folders, { memory.getSpace(it) != null }, ActivityRecorder(InMemoryActivityLog(), SequentialIdGenerator(), clock), clock, ids)
}

/** Every change the owner makes to a routine is logged as the owner's action, with the routine's id. */
class OwnerRoutinesLogTest {
    @Test
    fun `making, changing, switching and deleting a routine are logged as the owner's actions`() = runTest {
        val log = InMemoryActivityLog()
        val clock = FakeClock(1_000)
        val owner = DefaultOwnerRoutines(InMemoryRoutineRepository(), ActivityRecorder(log, SequentialIdGenerator(), clock), clock, SequentialIdGenerator()) { ZoneId.of("UTC") }
        val draft = RoutineDraft(title = "Morning news", objectiveText = "Summarise the news", schedule = RoutineSchedule.Daily(7 * 60))
        val routine = (owner.create(draft) as com.aicontrolcenter.core.operator.OwnerResult.Done).value
        owner.change(routine.id, draft.copy(allowScreenActions = true))
        owner.setEnabled(routine.id, false)
        owner.delete(routine.id)
        val events = log.observe(limit = 10).first()
        assertEquals(
            setOf("Routine made: Morning news", "Routine changed: Morning news", "Routine switched off: Morning news", "Routine deleted: Morning news"),
            events.map { it.title }.toSet(),
        )
        assertEquals(4, events.size)
        assertTrue(events.all { it.type == ActivityType.USER_ACTION })
        assertTrue(events.all { it.data.toString().contains(routine.id.value) })
        assertTrue(events.any { it.detail == "It may use other apps' screens." })
    }
}
