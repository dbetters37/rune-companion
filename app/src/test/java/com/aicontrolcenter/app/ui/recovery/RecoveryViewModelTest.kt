package com.aicontrolcenter.app.ui.recovery

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.core.health.DatabaseState
import com.aicontrolcenter.core.health.OwnerRecovery
import com.aicontrolcenter.core.health.RecoveryResult
import com.aicontrolcenter.testkit.FakeOwnerRecovery
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Recovery screen's view model (R5, rules 9, 27, 30): plain words for each reason, Start over only after the typed
 * word, a refusal said as it is, the word forgotten, and nothing at all without a recovery port.
 */
class RecoveryViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `every reason has its own sentence and what to try first, in plain words`() {
        val needing = DatabaseState.entries.filter { it.needsRecovery }
        for (state in needing) {
            val ui = RecoveryViewModel(state, FakeOwnerRecovery(state)).uiState.value
            assertEquals(state.ownerText, ui.reason)
            assertTrue(ui.advice.isNotBlank())
            assertTrue(ui.canStartOver)
            assertFalse(listOf("Exception", "SQLite", "keystore", "null", "_").any { it in ui.advice }, "$state: ${ui.advice}")
        }
        assertEquals(needing.size - 1, needing.map { RecoveryViewModel.adviceFor(it) }.distinct().size, "missing and damaged keys share their advice")
        assertTrue("restart the phone" in RecoveryViewModel.adviceFor(DatabaseState.KEYSTORE_UNAVAILABLE))
        assertTrue("update" in RecoveryViewModel.adviceFor(DatabaseState.MIGRATION_FAILED))
        assertTrue(RecoveryViewModel.WHAT_START_OVER_DOES.first().contains("Nothing is deleted"))
    }

    @Test
    fun `Start over waits for the typed word, ignoring case and spaces at the ends`() = runTest(mainRule.dispatcher) {
        val recovery = FakeOwnerRecovery(DatabaseState.KEY_DAMAGED)
        val viewModel = RecoveryViewModel(DatabaseState.KEY_DAMAGED, recovery)
        viewModel.startOver()
        assertEquals(0, recovery.startOvers.get(), "nothing without the word")
        viewModel.confirmWord.setTextAndPlaceCursorAtEnd("START")
        assertFalse(viewModel.wordTyped())
        viewModel.startOver()
        assertEquals(0, recovery.startOvers.get())

        viewModel.confirmWord.setTextAndPlaceCursorAtEnd("  start over ")
        assertTrue(viewModel.wordTyped())
        viewModel.startOver()
        assertEquals(1, recovery.startOvers.get())
        assertTrue(viewModel.uiState.value.restartNow, "done: the app starts again (rule 30)")
        assertEquals("", viewModel.confirmWord.text.toString(), "the word is forgotten")
        viewModel.confirmWord.setTextAndPlaceCursorAtEnd(OwnerRecovery.CONFIRM_WORD)
        viewModel.startOver()
        assertEquals(1, recovery.startOvers.get(), "once")
    }

    @Test
    fun `a refusal is said as Start over said it, and changes nothing`() = runTest(mainRule.dispatcher) {
        val recovery = FakeOwnerRecovery(DatabaseState.NOT_A_DATABASE, result = RecoveryResult.Refused("Your settings could not be changed, so nothing was set aside. Try again."))
        val viewModel = RecoveryViewModel(DatabaseState.NOT_A_DATABASE, recovery)
        viewModel.confirmWord.setTextAndPlaceCursorAtEnd(OwnerRecovery.CONFIRM_WORD)
        viewModel.startOver()
        assertEquals("Your settings could not be changed, so nothing was set aside. Try again.", viewModel.uiState.value.refusal)
        assertFalse(viewModel.uiState.value.restartNow)
        assertFalse(viewModel.uiState.value.startingOver)
        assertEquals(OwnerRecovery.CONFIRM_WORD, viewModel.confirmWord.text.toString(), "kept, to try again")
    }

    @Test
    fun `a Start over that throws says it did not work, in plain words`() = runTest(mainRule.dispatcher) {
        val recovery = object : OwnerRecovery {
            override suspend fun state(): DatabaseState = DatabaseState.OTHER
            override suspend fun startOver(): RecoveryResult = throw IllegalStateException("java.io.IOException: EACCES")
        }
        val viewModel = RecoveryViewModel(DatabaseState.OTHER, recovery)
        viewModel.confirmWord.setTextAndPlaceCursorAtEnd(OwnerRecovery.CONFIRM_WORD)
        viewModel.startOver()
        assertEquals(RecoveryViewModel.DID_NOT_WORK, viewModel.uiState.value.refusal)
    }

    @Test
    fun `without a recovery port there is no Start over, and Try again is remembered`() = runTest(mainRule.dispatcher) {
        val viewModel = RecoveryViewModel(DatabaseState.OTHER, null)
        assertFalse(viewModel.uiState.value.canStartOver)
        viewModel.confirmWord.setTextAndPlaceCursorAtEnd(OwnerRecovery.CONFIRM_WORD)
        viewModel.startOver()
        assertFalse(viewModel.uiState.value.restartNow)
        assertFalse(viewModel.uiState.value.stillNotOpening)
        viewModel.triedAgain()
        assertTrue(viewModel.uiState.value.stillNotOpening)
        assertNull(viewModel.uiState.value.refusal)
    }
}
