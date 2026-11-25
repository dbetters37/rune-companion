package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.health.ExitKind
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamFixture.Companion.MINUTE_MS
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WP7 item 8 (R1 consumer, K14; rule 10) and the secfix rule on objective options (F12): the recovery at start-up says
 * why the app stopped when the app knows it; with safe start ([resumeAllowed] false) nothing goes on by itself, whatever
 * the setting says; and an objective's options, all of them, survive the crash, the recovery and the resume unchanged.
 */
class RecoveryCauseTest {

    /** Starts an objective whose task never ends, kills the "app" while it runs, and returns the team, a fresh orchestrator and the objective. */
    private suspend fun TestScope.crashed(autoResume: Boolean, options: ObjectiveOptions = ObjectiveOptions()): Triple<TeamFixture, DefaultOrchestrator, com.aicontrolcenter.core.TaskId> {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = autoResume) }.setUp()
        var hold = true
        team.script.work = { if (hold) awaitCancellation() else ScriptStep.Reply("Done") }
        val app = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val objective = team.newOrchestrator(app).startObjective("Research note apps", options)
        team.settle(MINUTE_MS)
        app.cancel() // the app is killed: nothing gets to record that the work stopped
        team.settle(MINUTE_MS)
        hold = false
        return Triple(team, team.newOrchestrator(), objective.id)
    }

    @Test
    fun `the interrupted texts say why the app stopped when the app knows it`() = runTest {
        val (team, after, id) = crashed(autoResume = false)
        assertEquals(listOf(id), after.recoverInterrupted(ExitKind.LOW_MEMORY.cause, resumeAllowed = true))
        val objective = team.objective(id)
        assertEquals(TaskStatus.INTERRUPTED, objective.status)
        assertEquals("Interrupted: Android closed the app to free memory. Resume it to continue.", objective.statusDetail)
        val work = team.work(id).single()
        assertEquals("Interrupted: Android closed the app to free memory.", work.statusDetail)
        assertEquals("Interrupted 1 time(s): Android closed the app to free memory while this was running.", work.error)
        val told = team.messagesOf(id, MessageType.STATUS).filter { it.from == Participant.System }.map { it.body }
        assertTrue(told.first().startsWith("Interrupted: Android closed the app to free memory."), told.first())
    }

    @Test
    fun `without a cause the general sentence is used, and the old entry point is the new one with no cause`() = runTest {
        val (team, after, id) = crashed(autoResume = false)
        assertEquals(listOf(id), after.recoverInterrupted())
        assertEquals("Interrupted: the app stopped while this was running. Resume it to continue.", team.objective(id).statusDetail)
        assertEquals("Interrupted 1 time(s): the app stopped while this was running.", team.work(id).single().error)
    }

    @Test
    fun `with safe start nothing resumes by itself, whatever the setting says, and the owner can resume`() = runTest {
        val (team, after, id) = crashed(autoResume = true)
        assertEquals(listOf(id), after.recoverInterrupted(ExitKind.CRASH.cause, resumeAllowed = false))
        team.settle()
        val objective = team.objective(id)
        assertEquals(TaskStatus.INTERRUPTED, objective.status)
        assertEquals("Interrupted: the app stopped because of an error. Resume it to continue.", objective.statusDetail)
        assertTrue(after.running.value.isEmpty())
        assertTrue(team.messagesOf(id, MessageType.STATUS).none { it.body == "Resumed automatically after the interruption." })

        after.resume(id)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(id).status)
    }

    @Test
    fun `with safe start an objective with only bookkeeping left is not finished by itself either`() = runTest {
        val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = true) }.setUp()
        val objective = team.start()
        team.settle()
        // As if the app died right before the objective's last write: its work is done, it is stored as running.
        val done = team.objective(objective.id)
        team.tasks.upsert(done.copy(status = TaskStatus.RUNNING, finishedAt = null))
        val after = team.newOrchestrator()
        after.recoverInterrupted(null, resumeAllowed = false)
        team.settle()
        assertEquals(TaskStatus.INTERRUPTED, team.objective(objective.id).status, "the owner decides")
        after.resume(objective.id)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
    }

    @Test
    fun `an objective's options, all of them, are kept through the crash, the recovery and the resume`() = runTest {
        val options = ObjectiveOptions(reviewResults = false, saveKnowledge = true, maxTasks = 3, screenActionsAllowed = false, approvalTimeoutMinutes = 90)
        val (team, after, id) = crashed(autoResume = false, options = options)
        assertEquals(options, team.objective(id).options, "as started")
        after.recoverInterrupted(ExitKind.ANR.cause, resumeAllowed = true)
        assertEquals(options, team.objective(id).options, "after the recovery")
        after.resume(id)
        assertEquals(options, team.objective(id).options, "after the resume")
        team.settle()
        val done = team.objective(id)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(options, done.options, "after it ended")
        // A routine's options, with its own screen choice, likewise (they fail closed if rebuilt without it).
        val routine = ObjectiveOptions(routineId = com.aicontrolcenter.core.RoutineId("rtn_1"), screenActionsAllowed = true, approvalTimeoutMinutes = 15)
        val (team2, after2, id2) = crashed(autoResume = true, options = routine)
        after2.recoverInterrupted(null, resumeAllowed = true)
        team2.settle()
        assertEquals(TaskStatus.COMPLETED, team2.objective(id2).status)
        assertEquals(routine, team2.objective(id2).options)
    }
}
