package com.aicontrolcenter.app.ui.tasks

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.ui.settings.PhoneOperatorSwitch
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPERATOR-OFF (c): a phone tool of an objective refused because Phone operator is off is said on the objective, with
 * "Turn on" through the owner's switch, while the switch is still off; a tool call that ran, or a line that only
 * mentions the words, is not taken for a refusal.
 */
class OperatorRefusedTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val root = TaskId("obj_operator")

    private fun TestScope.screen(app: TestApp) = ObjectiveViewModel(
        root, app.graph.tasks, app.graph.messages, app.graph.registryDirectory, app.graph.orchestrator, app.graph.activityLog,
        app.graph.settingsView, app.clock, ZoneOffset.UTC, ticker = flowOf(Unit),
        operatorSwitch = PhoneOperatorSwitch(app.graph.settings, app.graph.activityRecorder),
    ).also { it.uiState.activeIn(backgroundScope) }

    private suspend fun TestApp.objective() {
        graph.start().join()
        val now = clock.current
        tasks.upsert(
            TaskRecord(
                id = root, rootId = root, parentId = null, kind = TaskKind.OBJECTIVE, title = "Read the news", instructions = "Read the news.",
                assignee = director().profile.id, requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING,
                createdAt = now, updatedAt = now, startedAt = now,
            ),
        )
    }

    private suspend fun TestApp.toolCall(id: String, outcome: String, detail: String, refusal: String? = null) = activityLog.record(
        ActivityEvent(
            id = EventId(id), at = clock.current, type = ActivityType.TOOL_CALL, title = "Tool call denied: Read a web page", detail = detail,
            severity = Severity.WARNING, rootTaskId = root,
            data = buildJsonObject {
                put("outcome", outcome)
                if (refusal != null) put("refusal", refusal)
            },
        ),
    )

    @Test
    fun `a call refused for another reason whose own words name the switch is not taken for a refusal`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective()
        val vm = screen(app)
        // The tool row's summary holds the agent's own arguments; the refusal itself is the owner's own "No".
        app.toolCall("evt_words", "DENIED", "Write \"${OperatorGate.OPERATOR_OFF}.md\" — The owner said no.")
        app.toolCall("evt_failed", "FAILED", "${OperatorGate.OPERATOR_OFF}, so this was made up by a script.")
        assertFalse(vm.uiState.value.operatorRefused, "only the gate's own refusal code counts")
    }

    @Test
    fun `a phone tool refused because Phone operator is off is said on the objective, and Turn on switches it on`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.objective()
        val vm = screen(app)
        assertFalse(vm.uiState.value.operatorRefused)

        app.toolCall("evt_ok", "OK", "Read https://example.org — ${OperatorGate.OPERATOR_OFF} is what the page said")
        assertFalse(vm.uiState.value.operatorRefused, "a call that ran is no refusal, whatever its line says")

        app.toolCall("evt_denied", "DENIED", "${OperatorGate.OPERATOR_OFF}, so \"Read a web page\" cannot be used.", refusal = OperatorGate.OPERATOR_OFF_CODE)
        assertTrue(vm.uiState.value.operatorRefused)
        assertTrue(vm.uiState.value.canTurnOnOperator)

        vm.turnOnOperator()
        assertTrue(app.settings.get().operatorEnabled)
        assertTrue(app.activityLog.recent().any { it.type == ActivityType.USER_ACTION && it.title == PhoneOperatorSwitch.TURNED_ON })
        assertFalse(vm.uiState.value.operatorRefused, "once it is on, the line goes")
    }
}
