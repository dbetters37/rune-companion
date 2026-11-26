package com.aicontrolcenter.app.screenshots

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.app.testing.AppRobot
import com.aicontrolcenter.app.testing.ScriptedBackup
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.TestBackgroundScreens
import com.aicontrolcenter.app.testing.showApp
import com.aicontrolcenter.app.testing.wp10bGraph
import com.aicontrolcenter.app.ui.backup.BackupRestoreScreen
import com.aicontrolcenter.app.ui.backup.BackupRestoreViewModel
import com.aicontrolcenter.app.ui.backup.BackupSaveScreen
import com.aicontrolcenter.app.ui.backup.BackupSaveViewModel
import com.aicontrolcenter.app.ui.recovery.RecoveryScreen
import com.aicontrolcenter.app.ui.recovery.RecoveryViewModel
import com.aicontrolcenter.app.ui.theme.AppTheme
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.core.backup.BackupProblem
import com.aicontrolcenter.core.backup.BackupProgress
import com.aicontrolcenter.core.backup.BackupResult
import com.aicontrolcenter.core.backup.RestoreReport
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.data.backup.BackupTexts
import com.aicontrolcenter.data.backup.RestoreOutcome
import com.aicontrolcenter.data.backup.SavePassphrase
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import java.util.Random
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Pictures of WP10b's screens (wave 3, part 2): Settings › Working in the background, Questions from agents, Appearance
 * and Backup; Save a backup (generated passphrase, own passphrase with its reason, the key step, saved); Restore a backup
 * (empty, the summary, a wrong passphrase, restored); the Recovery screen. Dark, light, wide (900 dp), 360 dp and font
 * scale 2.0. Review material, recorded into `build/outputs/roborazzi/` (`ScreenshotTest`'s KDoc), not golden files.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class WP10bScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var robot: AppRobot? = null

    @After
    fun stop() {
        robot?.close()
        scope.cancel()
    }

    private fun shoot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/p6_wp10b_$name.png")

    private val held = BackgroundState(notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = false, standbyBucket = StandbyBucket.RARE)

    /** Settings with the real sections, the background check holding the app back, and the Backup section shown. */
    private fun settings(dark: Boolean = true, state: BackgroundState = held, wide: Boolean = false): AppRobot {
        val robot = AppRobot(compose).also { robot = it }
        runBlocking { robot.app.graph.start().join() }
        robot.app.backgroundHealth.current.value = state
        compose.showApp(robot.app.wp10bGraph(scope, screens = TestBackgroundScreens()), darkTheme = dark)
        if (wide) return robot.openSection("Settings")
        // The sheet of all sections scrolls at large font sizes: bring "Settings" into view first.
        robot.openMore()
        compose.onAllNodes(androidx.compose.ui.test.hasText("Settings") and androidx.compose.ui.test.hasClickAction(), useUnmergedTree = false)
            .let { nodes -> if (nodes.fetchSemanticsNodes().isEmpty()) compose.onAllNodes(androidx.compose.ui.test.hasText("Settings")) else nodes }
            .onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        return robot
    }

    private fun heading(title: String) {
        compose.onNode(hasContentDescription(title) and isHeading()).performScrollTo()
        compose.waitForIdle()
    }

    private fun show(dark: Boolean = true, calm: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalCalmMotion provides calm) { AppTheme(darkTheme = dark) { content() } } }
        compose.waitForIdle()
    }

    private fun saver(backup: ScriptedBackup = ScriptedBackup()) = BackupSaveViewModel(backup, work = EmptyCoroutineContext, passphrase = SavePassphrase(Random(11)))

    private fun restorer(backup: ScriptedBackup = ScriptedBackup()) =
        BackupRestoreViewModel(backup, TestApp(scope).graph.registryDirectory, work = EmptyCoroutineContext, zone = { ZoneOffset.UTC })

    // --- Settings ---

    @Test
    fun settingsBackground() {
        settings()
        heading("Working in the background")
        shoot("settings_background_1")
        robot!!.scrollTo("Check again")
        shoot("settings_background_2_switches")
    }

    @Test
    fun settingsBackgroundAllClearLight() {
        settings(dark = false, state = BackgroundState(notificationsAllowed = true, batteryOptimised = false, backgroundRestricted = false, standbyBucket = StandbyBucket.ACTIVE))
        heading("Working in the background")
        shoot("settings_background_clear_light")
    }

    @Test
    fun settingsQuestionsCalmBackup() {
        settings()
        heading("Questions from agents")
        shoot("settings_questions")
        heading("Backup")
        shoot("settings_backup")
        heading("Appearance")
        shoot("settings_appearance")
    }

    @Test
    @Config(qualifiers = WIDE_WP10B)
    fun settingsWide() {
        settings(wide = true)
        heading("Working in the background")
        shoot("settings_background_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10B, fontScale = 2.0f)
    fun settings360LargeFont() {
        settings()
        heading("Working in the background")
        shoot("settings_background_360_font2")
        heading("Questions from agents")
        shoot("settings_questions_360_font2")
    }

    @Test
    @Config(qualifiers = NARROW_WP10B)
    fun settings360() {
        settings()
        heading("Backup")
        shoot("settings_backup_360")
    }

    // --- Save a backup ---

    @Test
    fun saveGenerated() {
        show { BackupSaveScreen(saver(), suggestedName = "b.aiccbackup", onBack = {}) }
        shoot("save_1_generated")
    }

    @Test
    fun saveGeneratedLight() {
        show(dark = false) { BackupSaveScreen(saver(), suggestedName = "b.aiccbackup", onBack = {}) }
        shoot("save_generated_light")
    }

    @Test
    fun saveOwnWithReason() {
        val viewModel = saver().apply {
            useOwn(true)
            ownPassphrase.setTextAndPlaceCursorAtEnd("summer2024")
            ownPassphraseAgain.setTextAndPlaceCursorAtEnd("summer")
        }
        show { BackupSaveScreen(viewModel, suggestedName = "b.aiccbackup", onBack = {}) }
        shoot("save_2_own_with_reason")
    }

    @Test
    fun saveKeyStepCalmAndSaved() {
        val backup = ScriptedBackup().apply {
            hold = CompletableDeferred()
            heldProgress = BackupProgress(BackupTexts.STEP_LOCKING, 0, null)
        }
        val viewModel = saver(backup).apply { setWroteItDown(true) }
        show(calm = true) { BackupSaveScreen(viewModel, suggestedName = "b.aiccbackup", onBack = {}) }
        viewModel.save(open = { java.io.ByteArrayOutputStream() })
        compose.waitForIdle()
        shoot("save_3_key_step_calm")
        backup.hold?.complete(Unit)
        compose.waitForIdle()
        shoot("save_4_saved")
    }

    @Test
    @Config(qualifiers = NARROW_WP10B, fontScale = 2.0f)
    fun save360LargeFont() {
        show { BackupSaveScreen(saver(), suggestedName = "b.aiccbackup", onBack = {}) }
        shoot("save_generated_360_font2")
    }

    @Test
    @Config(qualifiers = WIDE_WP10B)
    fun saveWide() {
        show { BackupSaveScreen(saver(), suggestedName = "b.aiccbackup", onBack = {}) }
        shoot("save_generated_wide")
    }

    // --- Restore a backup ---

    @Test
    fun restoreSteps() {
        val backup = ScriptedBackup().apply {
            onPreview = { typed -> if (typed == "wrong") BackupResult.Failed(BackupProblem.WrongPassphrase) else BackupResult.Done(ScriptedBackup.samplePreview()) }
            onRestore = {
                BackupResult.Done(RestoreOutcome(RestoreReport(com.aicontrolcenter.core.backup.BackupCounts(agents = 6, memories = 2), 0, emptyList()), listOf(BackupTexts.SETTINGS_NOT_RESTORED)))
            }
        }
        val viewModel = restorer(backup)
        show { BackupRestoreScreen(viewModel, onBack = {}, restart = {}) }
        shoot("restore_1_empty")
        viewModel.fileChosen(open = { java.io.ByteArrayInputStream(ByteArray(4)) }, name = { "phone.aiccbackup" })
        viewModel.passphrase.setTextAndPlaceCursorAtEnd("wrong")
        viewModel.openBackup()
        compose.waitForIdle()
        shoot("restore_2_wrong_passphrase")
        viewModel.passphrase.setTextAndPlaceCursorAtEnd("right")
        viewModel.openBackup()
        compose.waitForIdle()
        shoot("restore_3_summary_top")
        compose.onAllNodes(androidx.compose.ui.test.hasText("Restore this backup") and androidx.compose.ui.test.hasClickAction()).onFirst().performScrollTo()
        compose.waitForIdle()
        shoot("restore_4_summary_changes")
        viewModel.restore()
        compose.waitForIdle()
        shoot("restore_5_restored")
    }

    @Test
    @Config(qualifiers = NARROW_WP10B, fontScale = 2.0f)
    fun restoreSummary360LargeFont() {
        val viewModel = restorer()
        show(dark = false) { BackupRestoreScreen(viewModel, onBack = {}, restart = {}) }
        viewModel.fileChosen(open = { java.io.ByteArrayInputStream(ByteArray(4)) }, name = { "phone.aiccbackup" })
        viewModel.passphrase.setTextAndPlaceCursorAtEnd("right")
        viewModel.openBackup()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Settings that change on this phone")).performScrollTo()
        compose.waitForIdle()
        shoot("restore_summary_360_font2_light")
    }

    @Test
    @Config(qualifiers = WIDE_WP10B)
    fun restoreWide() {
        val viewModel = restorer()
        show { BackupRestoreScreen(viewModel, onBack = {}, restart = {}) }
        viewModel.fileChosen(open = { java.io.ByteArrayInputStream(ByteArray(4)) }, name = { "phone.aiccbackup" })
        viewModel.passphrase.setTextAndPlaceCursorAtEnd("right")
        viewModel.openBackup()
        compose.waitForIdle()
        shoot("restore_summary_wide")
    }

    // --- Recovery ---

    private fun recovery(state: DatabaseState, word: String = "") = RecoveryViewModel(state, FakeOwnerRecovery(state)).apply {
        if (word.isNotEmpty()) confirmWord.setTextAndPlaceCursorAtEnd(word)
    }

    @Test
    fun recoveryKeyDamaged() {
        val viewModel = recovery(DatabaseState.KEY_DAMAGED, word = "START OVER")
        show { RecoveryScreen(viewModel, onTryAgain = {}, restart = {}, close = {}) }
        shoot("recovery_1_key_damaged")
        compose.onNode(hasContentDescription("Start over") and isHeading()).performScrollTo()
        compose.waitForIdle()
        shoot("recovery_2_start_over")
    }

    @Test
    fun recoveryKeystoreLight() {
        show(dark = false) { RecoveryScreen(recovery(DatabaseState.KEYSTORE_UNAVAILABLE), onTryAgain = {}, restart = {}, close = {}) }
        shoot("recovery_keystore_light")
    }

    @Test
    @Config(qualifiers = WIDE_WP10B)
    fun recoveryWide() {
        show { RecoveryScreen(recovery(DatabaseState.NOT_A_DATABASE), onTryAgain = {}, restart = {}, close = {}) }
        shoot("recovery_wide")
    }

    @Test
    @Config(qualifiers = NARROW_WP10B, fontScale = 2.0f)
    fun recovery360LargeFont() {
        show { RecoveryScreen(recovery(DatabaseState.MIGRATION_FAILED), onTryAgain = {}, restart = {}, close = {}) }
        shoot("recovery_360_font2")
    }

    @Test
    @Config(qualifiers = NARROW_WP10B)
    fun recovery360() {
        show { RecoveryScreen(recovery(DatabaseState.KEY_MISSING), onTryAgain = {}, restart = {}, close = {}) }
        shoot("recovery_360")
    }
}

private const val WIDE_WP10B = "w900dp-h600dp-xhdpi"
private const val NARROW_WP10B = "w360dp-h740dp-xhdpi"
