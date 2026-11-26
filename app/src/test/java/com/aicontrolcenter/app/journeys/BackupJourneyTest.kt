package com.aicontrolcenter.app.journeys

import android.net.Uri
import android.os.Process
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.MainActivity
import com.aicontrolcenter.app.di.AppGraph
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.PickerResults
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.app.testing.showApp
import com.aicontrolcenter.app.testing.typeFast
import com.aicontrolcenter.app.testing.wp10bGraph
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.data.backup.BackupTexts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup file through its screens (Tier B; rules 8, 21, 27, 30), with the real backup over the test app's stores:
 * Settings › Backup › Save a backup writes a file that another, fresh app opens with the passphrase that was shown; on
 * the fresh app, Restore says a wrong passphrase and a damaged file apart, shows what the backup holds and what changes,
 * restores after one confirmation and starts the app again. No key is in the file and none is restored.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class BackupJourneyTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pickers = PickerResults()
    private var robot: AppRobot? = null
    private val cache get() = ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir

    @After
    fun stop() {
        robot?.close()
        scope.cancel()
        ShadowProcess.clearKilledProcesses()
    }

    /** The robot's test app with its world, shown through a graph that has the real backup. */
    private fun launch(world: suspend TestApp.() -> Unit): Pair<AppRobot, AppGraph> {
        val robot = AppRobot(compose).also { robot = it }
        runBlocking {
            robot.app.graph.start().join()
            robot.app.world()
        }
        val graph = robot.app.wp10bGraph(scope)
        compose.showApp(graph, results = pickers)
        return robot to graph
    }

    private fun AppRobot.openBackup(button: String) = apply {
        openSectionViaMore("Settings")
        compose.onNode(hasContentDescription("Backup") and isHeading()).performScrollTo()
        tap(button)
    }

    private fun passphraseField() = compose.onNode(hasSetTextAction() and hasText("Passphrase of the backup", substring = true))

    /** The generated passphrase as the screen spells it for TalkBack ("Passphrase, 5 groups: 7 K Q 2. M 9 X D. …"). */
    private fun shownPassphrase(): String {
        val node = compose.onNode(hasContentDescription("Passphrase, 5 groups:", substring = true)).fetchSemanticsNode()
        val spoken = node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        return spoken.substringAfter(": ").split(". ").joinToString("-") { it.replace(" ", "") }
    }

    @Test
    fun `a backup saved from Settings opens on a fresh app with the passphrase that was shown, and holds no key`() {
        val (robot, _) = launch { populateWithMemories() }
        robot.openBackup("Save a backup").assertShown(BackupTexts.WHAT_IS_IN).assertShown(BackupTexts.CHOOSE_PLACE)
        val passphrase = shownPassphrase()
        assertTrue(com.aicontrolcenter.core.backup.Passphrases.isGeneratedForm(passphrase), passphrase)
        val file = File(cache, "saved.aiccbackup").apply { delete() }
        pickers.next = Uri.fromFile(file)
        robot.tap("I have written down this passphrase").tap("Choose where to save it")
            .waitUntilShown("Backup saved", timeoutMs = 60_000)
        assertTrue(file.length() > 100, "the file was written")
        assertEquals("AICCBKUP", file.readBytes().copyOf(8).decodeToString())
        val agents = runBlocking { robot.app.agents.getAll() }.size
        robot.assertShown("$agents agents")

        // The file opens on another phone's fresh app with exactly that passphrase, and on nothing else.
        val fresh = TestApp(scope)
        runBlocking { fresh.graph.start().join() }
        val other = fresh.wp10bGraph(scope).ownerBackup!!
        val wrong = runBlocking { other.preview({ file.inputStream() }, "AAAA-AAAA-AAAA-AAAA-AAAA") }
        assertEquals(BackupResult.Failed(BackupProblem.WrongPassphrase), wrong)
        val opened = assertIs<BackupResult.Done<com.aicontrolcenter.data.backup.RestorePreview>>(runBlocking { other.preview({ file.inputStream() }, passphrase.lowercase()) })
        assertEquals(agents, opened.value.summary.counts.agents)
        assertTrue(opened.value.summary.counts.memories > 0)
        assertNull(opened.value.refusal, "a fresh app can take it")
        val bytes = file.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue("sk-ant-sample" !in bytes, "no key in the file, not even in plain sight")
    }

    @Test
    fun `restoring on a fresh app tells a wrong passphrase from a damaged file, then restores after one confirmation and starts again`() {
        // The owner's old phone: its world, saved with an own passphrase.
        val old = TestApp(scope)
        val good = File(cache, "phone.aiccbackup").apply { delete() }
        val passphrase = "copper lantern meadow violin harbour"
        runBlocking {
            old.graph.start().join()
            old.populateWithMemories()
            val saved = old.wp10bGraph(scope).ownerBackup!!.saveWithNotes(passphrase, null, { good.outputStream() })
            assertIs<BackupResult.Done<*>>(saved)
        }
        val damaged = File(cache, "damaged.aiccbackup").apply {
            val bytes = good.readBytes()
            bytes[bytes.size - 20] = (bytes[bytes.size - 20].toInt() xor 0x01).toByte()
            writeBytes(bytes)
        }
        val oldAgents = runBlocking { old.agents.getAll() }.map { it.id }.toSet()

        val (robot, _) = launch { }
        robot.openBackup("Restore from a backup").assertShown(BackupTexts.RESTORE_ONLY_FRESH)

        // A wrong passphrase: said so, nothing restored, typed again from the start.
        pickers.next = Uri.fromFile(good)
        robot.tap("Choose the backup file").waitUntilShown("phone.aiccbackup")
        passphraseField().typeFast(compose, "copper lantern meadow violin harbor")
        robot.tap("Open the backup").waitUntilShown(BackupProblem.WrongPassphrase.text, timeoutMs = 60_000)

        // A damaged file with the right passphrase: said differently.
        pickers.next = Uri.fromFile(damaged)
        robot.tap("Choose another file").waitUntilShown("damaged.aiccbackup")
        passphraseField().typeFast(compose, passphrase)
        robot.tap("Open the backup").waitUntilShown(BackupProblem.Damaged.text, timeoutMs = 60_000)
        assertTrue(BackupProblem.Damaged.text != BackupProblem.WrongPassphrase.text)

        // The good file: what it holds, what changes, what never travels.
        pickers.next = Uri.fromFile(good)
        robot.tap("Choose another file").waitUntilShown("phone.aiccbackup")
        robot.tap("Open the backup").waitUntilShown("What the backup holds".uppercase(), timeoutMs = 60_000)
        robot.assertShown("${oldAgents.size} agents").assertShown(BackupTexts.ALWAYS_OFF).assertShown(BackupTexts.WHAT_IS_NOT)
        assertTrue(runBlocking { robot.app.memories.list(limit = 1) }.isEmpty(), "opening restored nothing")
        assertTrue(runBlocking { robot.app.conversations.observeConversations().first() }.isEmpty())

        robot.tap("Restore this backup")
        compose.onNode(hasText("Restore") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        robot.waitUntilShown("Backup restored", timeoutMs = 60_000)
        assertEquals(oldAgents, runBlocking { robot.app.agents.getAll() }.map { it.id }.toSet(), "the old phone's team replaced the starter team")
        assertEquals(
            runBlocking { old.memories.list(limit = 500) }.filter { it.scope !is com.aicontrolcenter.core.memory.MemoryScope.Task }.map { it.title }.toSet(),
            runBlocking { robot.app.memories.list(limit = 500) }.map { it.title }.toSet(),
            "the old phone's memories are here (an objective's own memories are not in a backup)",
        )
        assertEquals(1, runBlocking { robot.app.conversations.observeConversations().first() }.size, "and its chat")
        assertTrue(runBlocking { robot.app.providers.getAll() }.all { it.apiKeyAlias == null }, "no key comes back (rule 8)")

        // Rule 30: the app starts again, as a new task with no extra.
        compose.onNode(hasText("Start the app again") and hasClickAction()).performScrollTo().performClick()
        val restart = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
            ?: error("no restart")
        assertEquals(MainActivity::class.java.name, restart.component?.className)
        assertNull(restart.extras)
        assertTrue(ShadowProcess.wasKilled(Process.myPid()), "the old process ends")
    }
}
