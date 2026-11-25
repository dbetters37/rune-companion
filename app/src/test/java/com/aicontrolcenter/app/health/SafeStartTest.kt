package com.aicontrolcenter.app.health

import com.aicontrolcenter.core.health.ExitKind
import com.aicontrolcenter.core.health.ExitRecord
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The start ledger and safe start's rules (Phase 6, rule 10) as a state machine, on the JVM. */
class SafeStartTest {
    @get:Rule val folder = TemporaryFolder()

    private fun exit(at: Long, kind: ExitKind) = ExitRecord(at, kind, importance = 400, pssKb = 1, rssKb = 1)

    private val minute = 60_000L

    @Test
    fun `a start that reaches ok leaves safe start off, and two starts in a row without ok turn it on at the third`() {
        var entry = StartLedger.Entry.EMPTY
        entry = SafeStart.atStart(entry, versionCode = 9, now = 1_000, newExits = emptyList())
        assertEquals(1, entry.startsWithoutOk)
        assertEquals(9L, entry.lastStartedVersion)
        entry = SafeStart.atOk(entry, 9, 2_000)
        assertEquals(0, entry.startsWithoutOk)
        assertEquals(9L, entry.lastOkVersion)
        assertFalse(entry.safeStart)

        entry = SafeStart.atStart(entry, 9, 3_000, emptyList()) // never reaches ok
        assertFalse(entry.safeStart)
        entry = SafeStart.atStart(entry, 9, 4_000, emptyList()) // nor this one
        assertFalse(entry.safeStart, "one start without ok before it")
        entry = SafeStart.atStart(entry, 9, 5_000, emptyList())
        assertTrue(entry.safeStart, "two starts in a row without ok before it")
        assertEquals(SafeStartReason.STARTS_WITHOUT_OK, entry.safeStartReason)

        // It stays on, even after a start that reaches ok, until the owner chooses.
        entry = SafeStart.atOk(entry, 9, 6_000)
        entry = SafeStart.atStart(entry, 9, 7_000, emptyList())
        assertTrue(entry.safeStart)
        entry = SafeStart.left(entry)
        assertFalse(entry.safeStart)
        assertNull(entry.safeStartReason)
        assertEquals(0, entry.startsWithoutOk)
    }

    @Test
    fun `three crashes of the main process within 15 minutes turn safe start on, spread out they do not`() {
        val burst = SafeStart.atStart(
            StartLedger.Entry.EMPTY, 9, 20 * minute,
            listOf(exit(1 * minute, ExitKind.CRASH), exit(6 * minute, ExitKind.ANR), exit(15 * minute, ExitKind.CRASH_NATIVE)),
        )
        assertTrue(burst.safeStart)
        assertEquals(SafeStartReason.CRASHES, burst.safeStartReason)
        assertEquals(15 * minute, burst.lastExitSeenAt)

        val spread = SafeStart.atStart(
            StartLedger.Entry.EMPTY, 9, 60 * minute,
            listOf(exit(1 * minute, ExitKind.CRASH), exit(10 * minute, ExitKind.CRASH), exit(30 * minute, ExitKind.CRASH)),
        )
        assertFalse(spread.safeStart)

        val notCrashes = SafeStart.atStart(
            StartLedger.Entry.EMPTY, 9, 10 * minute,
            listOf(exit(1 * minute, ExitKind.LOW_MEMORY), exit(2 * minute, ExitKind.USER_REQUESTED), exit(3 * minute, ExitKind.MEMORY_LIMIT)),
        )
        assertFalse(notCrashes.safeStart, "being closed by Android is no crash")
    }

    @Test
    fun `crashes reported at three separate starts add up across starts`() {
        var entry = StartLedger.Entry.EMPTY
        for ((i, at) in listOf(1L, 4L, 9L).withIndex()) {
            assertFalse(entry.safeStart, "after ${i} crashes")
            entry = SafeStart.atOk(SafeStart.atStart(entry, 9, at * minute + 30_000, listOf(exit(at * minute, ExitKind.CRASH))), 9, at * minute + 40_000)
        }
        assertTrue(entry.safeStart)
        assertEquals(listOf(1 * minute, 4 * minute, 9 * minute), entry.recentCrashes)
    }

    @Test
    fun `the ledger survives a round trip through its file, and a damaged file reads as empty`() {
        val ledger = StartLedger.inFolder(folder.root)
        assertEquals(StartLedger.Entry.EMPTY, ledger.read())
        val entry = StartLedger.Entry(
            lastStartedVersion = 9, lastStartedAt = 1_000, startsWithoutOk = 1, lastOkVersion = 8, lastOkAt = 900, safeStart = true,
            safeStartReason = SafeStartReason.CRASHES, lastExitSeenAt = 800, recentCrashes = listOf(100, 200, 300),
        )
        ledger.update { entry }
        assertEquals(entry, StartLedger.inFolder(folder.root).read())
        java.io.File(folder.root, StartLedger.PATH).writeText("\u0000garbage=\nsafeStart=maybe\nstartsWithoutOk=-4\n")
        assertEquals(StartLedger.Entry.EMPTY, ledger.read())
    }

    @Test
    fun `crash times are kept sorted and bounded`() {
        assertTrue(SafeStart.crashBurst(listOf(30 * minute, 16 * minute, 20 * minute)))
        assertFalse(SafeStart.crashBurst(listOf(1L, 2L)))
        val many = StartLedger.Entry(recentCrashes = (14 downTo 0).map { it * 60 * minute })
        val read = StartLedger.parse(StartLedger.format(many)).recentCrashes
        assertEquals((5..14).map { it * 60 * minute }, read, "the ten newest, oldest first")
        val after = SafeStart.atStart(StartLedger.Entry(recentCrashes = read), 9, 15 * 60 * minute, listOf(exit(15 * 60 * minute, ExitKind.CRASH)))
        assertEquals(10, after.recentCrashes.size)
        assertEquals(15 * 60 * minute, after.recentCrashes.last())
    }
}
