package com.aicontrolcenter.app.ui.questions

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.SAMPLE_QUESTION
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.sampleQuestion
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.framework.operator.DefaultOwnerRequests
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The question screen's view model (rule 13, rule 32) over a real AppGraph: the owner's answer goes through the owner's
 * request port and is refused for keys and stored provider keys every time, and for codes until the second tap.
 */
class QuestionViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.screen(app: TestApp, id: String = "req_question") =
        QuestionViewModel(RequestId(id), app.graph.ownerQuestions, app.graph.registryDirectory, app.clock, { ZoneOffset.UTC }, ticker = flowOf(Unit))
            .also { it.uiState.activeIn(backgroundScope) }

    private suspend fun TestApp.researcher(): AgentProfile {
        graph.start().join()
        return agentNamed("Researcher").profile
    }

    /** Types [text] and lets the snapshot system tell the view model, as a frame does in the app. */
    private fun QuestionViewModel.type(text: String) {
        answer.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `a question is shown as the agent's words, never as a permission`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current))
        val state = screen(app).uiState.value
        val question = assertNotNull(state.question)
        assertEquals("A question from Researcher", question.title)
        assertEquals(SAMPLE_QUESTION, question.question)
        assertEquals(listOf("Berlin", "Paris, in “France”"), question.options)
        assertTrue(question.isOpen)
        assertTrue(state.canAnswer)
        assertEquals("Waiting for your answer", question.status.text)
        assertEquals("This is not a permission. Your answer is only text for the agent.", QuestionTexts.NOT_A_PERMISSION)
        assertTrue(question.whereAnswerGoes.isNotBlank())
        assertNull(state.hint)
        assertNull(state.refusal)
    }

    @Test
    fun `the owner's words are sent as the answer, and nothing else changes`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current))
        app.raise(sampleApproval(agent.id, id = "req_other", createdAt = app.clock.current, minutes = 240))
        val vm = screen(app)
        vm.type("Berlin, \"please\" 🙂\nthanks")
        vm.send()

        val stored = assertNotNull(app.ownerRequestStore.get(RequestId("req_question")))
        assertEquals(OwnerRequestStatus.APPROVED, stored.status)
        assertEquals("Berlin, \"please\" 🙂\nthanks", stored.answer)
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_other"))!!.status, "an answer approves nothing else (rule 2)")
        val state = vm.uiState.value
        assertFalse(state.canAnswer)
        assertEquals("Answered", state.question?.status?.text)
        assertEquals("Berlin, \"please\" 🙂\nthanks", state.question?.answer)
        assertEquals("", vm.answer.text.toString())
        assertTrue(state.message?.text.orEmpty().startsWith("Sent."))
    }

    @Test
    fun `a key is never sent, and there is no second chance`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current))
        val vm = screen(app)
        vm.type("my key is sk-ant-api03-abcdefghijklmnopqrstuvwx")
        assertEquals(AnswerHint.KEY, vm.uiState.value.hint, "said before anything is sent")
        vm.send()

        val state = vm.uiState.value
        assertEquals(DefaultOwnerRequests.KEY_REFUSED, state.refusal)
        assertFalse(state.sendAnywayOffered)
        assertTrue(state.canAnswer)
        vm.sendAnyway()
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_question"))!!.status)
        assertNull(app.ownerRequestStore.get(RequestId("req_question"))!!.answer)
    }

    @Test
    fun `a provider key stored on this phone is never sent, even when it looks like plain words`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.vault.put("provider/prv_home/1", "ollama kitchen server phrase")
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current))
        val vm = screen(app)
        vm.type("It is ollama kitchen server phrase")
        assertNull(vm.uiState.value.hint, "only the app's own check knows the stored keys")
        vm.send()

        assertEquals(DefaultOwnerRequests.KEY_REFUSED, vm.uiState.value.refusal)
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_question"))!!.status)
    }

    @Test
    fun `a code needs the second tap, for exactly the same text`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current, question = "What is the code from the text message?", options = emptyList()))
        val vm = screen(app)
        vm.type("482913")
        assertEquals(AnswerHint.CODE, vm.uiState.value.hint)
        vm.send()
        assertEquals(QuestionTexts.CODE_WARNING, vm.uiState.value.refusal)
        assertTrue(QuestionTexts.CODE_WARNING.endsWith("tap “Send it anyway”."), "curly quotes, as the rest of the screen (D3A-12e)")
        assertTrue(vm.uiState.value.sendAnywayOffered)
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_question"))!!.status)

        // Changed after the warning: the second tap no longer applies.
        vm.type("482914")
        assertFalse(vm.uiState.value.sendAnywayOffered)
        assertNull(vm.uiState.value.refusal)
        vm.sendAnyway()
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_question"))!!.status)

        vm.type("482913")
        vm.send()
        assertTrue(vm.uiState.value.sendAnywayOffered)
        vm.sendAnyway()
        val stored = app.ownerRequestStore.get(RequestId("req_question"))!!
        assertEquals(OwnerRequestStatus.APPROVED, stored.status)
        assertEquals("482913", stored.answer)
    }

    @Test
    fun `decide yourself closes the question without words`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current))
        val vm = screen(app)
        vm.decideYourself()
        val stored = app.ownerRequestStore.get(RequestId("req_question"))!!
        assertEquals(OwnerRequestStatus.APPROVED, stored.status)
        assertNull(stored.answer)
        assertEquals("You let it decide", vm.uiState.value.question?.status?.text)
        assertEquals("You let Researcher decide itself.", vm.uiState.value.question?.outcome)
    }

    @Test
    fun `use this answer only fills the field`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current))
        val vm = screen(app)
        vm.useSuggestion(1)
        assertEquals("Paris, in “France”", vm.answer.text.toString())
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_question"))!!.status, "nothing is sent")
        vm.useSuggestion(7)
        assertEquals("Paris, in “France”", vm.answer.text.toString())
    }

    @Test
    fun `an empty answer is not sent`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current))
        val vm = screen(app)
        vm.type("   ")
        vm.send()
        assertEquals("Write an answer, or choose “Let Researcher decide”.", vm.uiState.value.refusal)
        assertEquals(OwnerRequestStatus.PENDING, app.ownerRequestStore.get(RequestId("req_question"))!!.status)
    }

    @Test
    fun `a question nothing waits for is closed when it is shown, and cannot be answered`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current), waiting = false)
        val state = screen(app).uiState.value
        assertEquals(OwnerRequestStatus.CANCELLED, app.ownerRequestStore.get(RequestId("req_question"))!!.status)
        assertFalse(state.canAnswer)
        assertEquals("Withdrawn", state.question?.status?.text)
        assertTrue(state.question?.outcome.orEmpty().startsWith("This question was withdrawn before you answered"))
    }

    @Test
    fun `a question that ran out of time says the agent decided itself`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current - 30 * 60_000L, minutes = 20))
        val vm = screen(app)
        val state = vm.uiState.value
        assertFalse(state.canAnswer)
        assertEquals("No answer in time", state.question?.status?.text)
        vm.type("Berlin")
        vm.send()
        assertNotNull(vm.uiState.value.refusal, "a late answer is refused in the app's words")
        assertNull(app.ownerRequestStore.get(RequestId("req_question"))!!.answer)
    }

    @Test
    fun `an approval is not shown as a question`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleApproval(agent.id, id = "req_approval", createdAt = app.clock.current, minutes = 240))
        val state = screen(app, "req_approval").uiState.value
        assertTrue(state.notFound)
        assertNull(state.question)
    }

    @Test
    fun `hidden characters in the question are shown and warned about`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = app.researcher()
        app.raise(sampleQuestion(agent.id, createdAt = app.clock.current, question = "Open Invoice‮fdp.exe now?", options = emptyList()))
        val question = assertNotNull(screen(app).uiState.value.question)
        assertTrue("⟨hidden: reverses text⟩" in question.question, question.question)
        assertNotNull(question.hiddenWarning)
    }
}
