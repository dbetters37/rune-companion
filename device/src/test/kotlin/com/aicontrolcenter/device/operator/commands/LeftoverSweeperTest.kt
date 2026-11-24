package com.aicontrolcenter.device.operator.commands

import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * G2: the sweep that stops whatever a shell command left running. Every test here uses a process
 * table in a temporary folder and a kill function that only records ([FakeProc]); none looks at
 * or signals a real process.
 */
class LeftoverSweeperTest {
    private val proc = FakeProc()
    private val uid = FakeProc.UID

    @Test
    fun `everything under the app's user id is killed, except the app and what the zygote started`() {
        proc.add(4100, FakeProc.APP, uid) // a child the command left: "sleep 300 &" before the group was killed
        proc.add(4200, 1, uid) // an orphan in a session of its own: "setsid sleep 300 &", a double fork
        proc.add(4201, 4200, uid) // and what that started in turn
        proc.add(4300, FakeProc.ZYGOTE, uid) // another process of this app, started by the zygote
        proc.add(4400, FakeProc.ZYGOTE, 10_200) // another app
        proc.add(4500, 1, 10_200) // another app's command
        proc.add(4600, 1, 0) // a system daemon
        proc.add(4700, FakeProc.APP, 99_001) // a process of another user id that this app started

        val swept = proc.sweeper().sweep()

        assertEquals(Sweep.Done(stopped = 3, left = 0), swept)
        assertEquals(setOf(4100, 4200, 4201), proc.killed.toSet())
        assertEquals(setOf(1, FakeProc.ZYGOTE, FakeProc.APP, 4300, 4400, 4500, 4600, 4700), proc.pids())
    }

    @Test
    fun `with nothing left nothing is killed`() {
        proc.add(4300, FakeProc.ZYGOTE, uid)

        assertEquals(Sweep.Done(stopped = 0, left = 0), proc.sweeper().sweep())
        assertTrue(proc.killed.isEmpty())
    }

    @Test
    fun `a process that has ended and only waits to be collected is not chased`() {
        proc.add(4100, FakeProc.APP, uid, state = "Z (zombie)")
        proc.add(4101, 1, uid, state = "X (dead)")

        assertEquals(Sweep.Done(stopped = 0, left = 0), proc.sweeper().sweep())
        assertTrue(proc.killed.isEmpty())
    }

    @Test
    fun `the sweep is repeated until none is left`() {
        proc.add(4100, 1, uid)
        // Killing one starts the next, three times over: what a process that restarts itself would do.
        val next = AtomicInteger(4101)
        proc.onKill = { pid ->
            proc.remove(pid)
            if (next.get() <= 4103) proc.add(next.getAndIncrement(), 1, uid)
        }
        var pauses = 0

        val swept = proc.sweeper(pause = { pauses++ }).sweep()

        assertEquals(Sweep.Done(stopped = 4, left = 0), swept)
        assertEquals(listOf(4100, 4101, 4102, 4103), proc.killed.toList())
        assertEquals(4, pauses, "it waits a moment after each round of kills")
    }

    @Test
    fun `the sweep is bounded, and says what it could not stop`() {
        proc.add(4100, 1, uid)
        proc.add(4101, 1, uid)
        proc.onKill = { } // they do not die

        val swept = proc.sweeper(maxRounds = 5).sweep()

        assertEquals(Sweep.Done(stopped = 2, left = 2), swept)
        assertEquals(10, proc.killed.size, "five rounds, two kills each")
    }

    @Test
    fun `a kill that fails does not stop the sweep`() {
        proc.add(4100, 1, uid)
        proc.add(4101, 1, uid)
        proc.onKill = { pid -> if (pid == 4100 && proc.killed.count { it == 4100 } == 1) throw SecurityException("no") else proc.remove(pid) }

        assertEquals(Sweep.Done(stopped = 2, left = 0), proc.sweeper().sweep())
    }

    @Test
    fun `without a table that can be read, nothing is killed and the sweep says so`() {
        proc.add(4100, 1, uid)

        // No such folder.
        val missing = ProcessSweeper(ProcFolderTable(File(proc.folder, "nowhere")), proc.kill, ownPid = FakeProc.APP, ownUid = uid, pause = {})
        assertIs<Sweep.NotPossible>(missing.sweep())
        // The app does not find itself in the table.
        val notListed = ProcessSweeper(proc.table(), proc.kill, ownPid = 7777, ownUid = uid, pause = {})
        assertIs<Sweep.NotPossible>(notListed.sweep())
        // The app's entry is another user's: the table and the app disagree.
        val otherUser = ProcessSweeper(proc.table(), proc.kill, ownPid = FakeProc.APP, ownUid = 10_999, pause = {})
        assertIs<Sweep.NotPossible>(otherUser.sweep())
        // The app was started by init itself: an orphan could not be told from an app process.
        proc.add(4900, 1, uid)
        val startedByInit = ProcessSweeper(proc.table(), proc.kill, ownPid = 4900, ownUid = uid, pause = {})
        assertIs<Sweep.NotPossible>(startedByInit.sweep())

        assertTrue(proc.killed.isEmpty())
    }

    @Test
    fun `the table reads only numbered folders with a status it understands`() {
        File(proc.folder, "self").mkdirs()
        File(proc.folder, "self/status").writeText(FakeProc.statusText(4000, 500, uid))
        File(proc.folder, "cpuinfo").writeText("x")
        File(proc.folder, "5000").mkdirs() // no status: it ended meanwhile
        File(proc.folder, "5001").mkdirs()
        File(proc.folder, "5001/status").writeText("Name:\tx\nState:\tR (running)\n") // no PPid, no Uid
        File(proc.folder, "5002").mkdirs()
        File(proc.folder, "5002/status").writeText("Name:\tx\nState:\tR (running)\nPPid:\t1\nUid:\t10123\t0\t0\t0\n") // the real uid counts

        val entries = proc.table().read()!!.sortedBy { it.pid }

        assertEquals(listOf(ProcessEntry(1, 0, 0), ProcessEntry(500, 1, 0), ProcessEntry(4000, 500, uid), ProcessEntry(5002, 1, uid)), entries)
    }

    // --- the production wiring ---

    @Test
    fun `the production sweeper does nothing on the JVM`() {
        // The real factory, as PhoneOperatorLayer calls it. On this JVM it must be the sweeper that looks at nothing
        // and kills nothing; it returns before it evaluates anything of Android or of /proc.
        val real = AndroidLeftovers.create()
        assertSame(LeftoverSweeper.None, real)
        assertIs<Sweep.NotPossible>(real.sweep())

        // The same decision with everything it reads passed in: this runtime's own name, and an application uid.
        var tableBuilt = false
        val here = AndroidLeftovers.create(
            vmName = System.getProperty("java.vm.name"), uid = uid, pid = FakeProc.APP,
            table = { tableBuilt = true; proc.table() }, kill = proc.kill,
        )
        proc.add(4100, 1, uid)
        assertSame(LeftoverSweeper.None, here)
        assertIs<Sweep.NotPossible>(here.sweep())
        assertFalse(tableBuilt, "the table is not even built")
        assertTrue(proc.killed.isEmpty())
        assertTrue(4100 in proc.pids())
    }

    @Test
    fun `the production sweeper exists only in an Android app process`() {
        assertFalse(AndroidLeftovers.allowed("OpenJDK 64-Bit Server VM", uid))
        assertFalse(AndroidLeftovers.allowed(System.getProperty("java.vm.name"), uid), "this JVM is not Android's runtime")
        assertFalse(AndroidLeftovers.allowed(null, uid))
        assertFalse(AndroidLeftovers.allowed("Dalvik", 0), "root: everything could be signalled")
        assertFalse(AndroidLeftovers.allowed("Dalvik", 1000), "the system uid")
        assertFalse(AndroidLeftovers.allowed("Dalvik", 2000), "adb's shell uid")
        assertFalse(AndroidLeftovers.allowed("Dalvik", 9999))
        assertFalse(AndroidLeftovers.allowed("Dalvik", null))
        assertTrue(AndroidLeftovers.allowed("Dalvik", 10_000))
        assertTrue(AndroidLeftovers.allowed("Dalvik", 1_010_123), "an app of a second user or a work profile")

        for ((vm, id) in listOf("Dalvik" to 0, "Dalvik" to 2000, "OpenJDK 64-Bit Server VM" to uid, "dalvik" to uid)) {
            assertSame(LeftoverSweeper.None, AndroidLeftovers.create(vm, id, FakeProc.APP, { error("no table off Android") }, { error("no kill off Android") }), "$vm $id")
        }
        // On a phone, as an app: the sweeper over the table and the kill it was given (here still the fake ones).
        proc.add(4100, 1, uid)
        val onPhone = AndroidLeftovers.create("Dalvik", uid, FakeProc.APP, { proc.table() }, proc.kill)
        assertIs<ProcessSweeper>(onPhone)
    }
}
