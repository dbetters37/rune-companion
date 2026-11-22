package com.aicontrolcenter.app.ui.activity

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.testkit.Samples
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

class ActivityViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val day = 24L * 60 * 60 * 1000

    private fun TestScope.activity(app: TestApp, limit: Int = ActivityViewModel.DEFAULT_LIMIT) =
        ActivityViewModel(app.graph.activityLog, app.graph.registryDirectory, app.clock, ZoneOffset.UTC, limit)
            .also { it.uiState.activeIn(backgroundScope) }

    /** Five events over three days: today 12:00 and 09:30, yesterday, and two a week ago at the same instant. */
    private suspend fun TestApp.recordSampleEvents() {
        val now = TestApp.START_TIME
        activityLog.record(Samples.event("evt_old_1", at = now - 7 * day, type = ActivityType.SYSTEM, egress = false, agentId = null))
        activityLog.record(Samples.event("evt_old_2", at = now - 7 * day, type = ActivityType.REGISTRY, egress = false, agentId = null))
        activityLog.record(Samples.event("evt_yesterday", at = now - day, type = ActivityType.ERROR, severity = Severity.ERROR, egress = false))
        activityLog.record(Samples.event("evt_morning", at = now - 9_000_000, type = ActivityType.PERMISSION, severity = Severity.WARNING, egress = false))
        activityLog.record(Samples.event("evt_noon", at = now, type = ActivityType.MODEL_CALL, egress = true))
    }

    @Test
    fun `events are grouped by day, newest first, with the latest recorded first inside one instant`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleEvents()
        val state = activity(app).uiState.value

        assertFalse(state.isLoading)
        assertEquals(listOf("Today", "Yesterday", "Saturday, 7 November 2026"), state.groups.map { it.heading })
        assertEquals(listOf("evt_noon", "evt_morning"), state.groups[0].rows.map { it.id })
        assertEquals(listOf("12:00", "09:30"), state.groups[0].rows.map { it.time })
        assertEquals(listOf("evt_old_2", "evt_old_1"), state.groups[2].rows.map { it.id })
        assertEquals(5, state.eventCount)
        assertFalse(state.truncated)
        assertFalse(state.filter.isActive)
    }

    @Test
    fun `type, severity and left-the-device filters combine and can be cleared`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleEvents()
        val viewModel = activity(app)
        fun ids() = viewModel.uiState.value.groups.flatMap { g -> g.rows.map { it.id } }

        viewModel.toggleType(ActivityType.ERROR)
        viewModel.toggleType(ActivityType.PERMISSION)
        assertEquals(listOf("evt_morning", "evt_yesterday"), ids())

        viewModel.setMinSeverity(Severity.ERROR)
        assertEquals(listOf("evt_yesterday"), ids())

        viewModel.toggleType(ActivityType.ERROR)
        assertEquals(emptyList(), ids(), "permission events only, but none of them is an error")
        assertTrue(viewModel.uiState.value.filter.isActive)

        viewModel.clearFilters()
        assertEquals(5, ids().size)

        viewModel.setEgressOnly(true)
        assertEquals(listOf("evt_noon"), ids())

        viewModel.setEgressOnly(false)
        viewModel.setMinSeverity(Severity.WARNING)
        assertEquals(listOf("evt_morning", "evt_yesterday"), ids())
    }

    @Test
    fun `the log is live and says when more exists than is loaded`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleEvents()
        val viewModel = activity(app, limit = 5)
        assertTrue(viewModel.uiState.value.truncated)

        app.graph.activityRecorder.record(ActivityType.USER_ACTION, "Something new")
        val rows = viewModel.uiState.value.groups.flatMap { it.rows }
        assertEquals("Something new", rows.first().event.title)
        assertEquals(5, rows.size)

        app.activityLog.clear()
        assertEquals(emptyList(), viewModel.uiState.value.groups)
        assertFalse(viewModel.uiState.value.truncated)
    }

    @Test
    fun `the inspector shows every recorded field, including where information went`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.providers.upsert(Samples.provider())
        app.agents.upsert(Samples.agent())
        app.recordSampleEvents()
        val viewModel = activity(app)
        val row = viewModel.uiState.value.groups[0].rows.first()
        assertEquals("Researcher", row.agentName)

        viewModel.select(row.event)
        val detail = assertNotNull(viewModel.uiState.value.selected)
        val fields = detail.fields.associate { it.label to it.value }
        assertEquals("14 Nov 2026, 12:00:00", fields["When"])
        assertEquals("Model call", fields["Type"])
        assertEquals("Researcher (agent)", fields["Caused by"])
        assertEquals("agt_sample", fields["Agent id"])
        assertEquals("tsk_child", fields["Task"])
        assertEquals("tsk_root", fields["Objective"])
        assertEquals("evt_noon", fields["Event id"])
        assertEquals(mapOf("tokens" to "120", "model" to "gemma3:4b"), detail.data.associate { it.label to it.value })

        val egress = assertNotNull(detail.egress).associate { it.label to it.value }
        assertEquals("192.168.1.20", egress["Destination"])
        assertEquals("Home server", egress["Provider"])
        assertEquals("prompt and conversation", egress["What was sent"])
        assertEquals("2.0 KB", egress["Approximate size"])
        assertTrue(assertNotNull(egress["Where that is"]).startsWith("Local network"))

        // The open event stays readable after the log is cleared underneath it.
        app.activityLog.clear()
        assertEquals("evt_noon", viewModel.uiState.value.selected?.event?.id?.value)
        viewModel.closeDetail()
        assertNull(viewModel.uiState.value.selected)
    }

    @Test
    fun `an event caused by nobody or by a deleted agent says so`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleEvents()
        val viewModel = activity(app)
        val groups = viewModel.uiState.value.groups

        viewModel.select(groups[0].rows.first().event)
        assertEquals("An agent that no longer exists", viewModel.uiState.value.selected?.fields?.first { it.label == "Caused by" }?.value)

        viewModel.select(groups[2].rows.first().event)
        val detail = assertNotNull(viewModel.uiState.value.selected)
        assertEquals("You or the system", detail.fields.first { it.label == "Caused by" }.value)
        assertNull(detail.egress)
    }
}
