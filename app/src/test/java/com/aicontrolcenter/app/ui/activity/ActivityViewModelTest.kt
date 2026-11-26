package com.aicontrolcenter.app.ui.activity

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.EventId
import com.aicontrolcenter.core.activity.ActivityCursor
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import kotlinx.coroutines.flow.Flow
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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

    private fun TestScope.activity(
        app: TestApp,
        pageSize: Int = ActivityViewModel.PAGE_SIZE,
        agent: AgentId? = null,
        text: String? = null,
        log: ActivityLog = app.graph.activityLog,
    ) = ActivityViewModel(log, app.graph.registryDirectory, app.clock, { ZoneOffset.UTC }, pageSize, agent, text)
        .also { it.uiState.activeIn(backgroundScope) }

    /** Types into the search field as the keyboard does, then lets the snapshot change reach the view model. */
    private fun TestScope.typeSearch(viewModel: ActivityViewModel, text: String) {
        viewModel.search.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
        runCurrent()
    }

    private fun ActivityViewModel.ids() = uiState.value.groups.flatMap { g -> g.rows.map { it.id } }

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
        assertFalse(state.canShowOlder)
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
    fun `the newest page is live and says when older events exist`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleEvents()
        val viewModel = activity(app, pageSize = 5)
        assertTrue(viewModel.uiState.value.canShowOlder, "a full page may have older events behind it")

        app.graph.activityRecorder.record(ActivityType.USER_ACTION, "Something new")
        val rows = viewModel.uiState.value.groups.flatMap { it.rows }
        assertEquals("Something new", rows.first().event.title)
        assertEquals(5, rows.size)

        app.activityLog.clear()
        assertEquals(emptyList(), viewModel.uiState.value.groups)
        assertFalse(viewModel.uiState.value.canShowOlder)
    }

    @Test
    fun `Show older reads the next page after the last event shown, until the end of the log (D41)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        repeat(7) { app.activityLog.record(Samples.event("old_$it", at = TestApp.START_TIME - (7 - it) * 60_000L, egress = false)) }
        val pages = CountingPages(app.activityLog)
        val viewModel = activity(app, pageSize = 3, log = pages)
        assertEquals(listOf("old_6", "old_5", "old_4"), viewModel.ids())
        assertTrue(viewModel.uiState.value.canShowOlder)

        viewModel.showOlder()
        assertEquals(listOf("old_6", "old_5", "old_4", "old_3", "old_2", "old_1"), viewModel.ids())
        assertEquals(ActivityCursor(TestApp.START_TIME - 3 * 60_000L, EventId("old_4")), pages.cursors.last(), "the page after the last event shown")
        assertTrue(viewModel.uiState.value.canShowOlder)

        viewModel.showOlder()
        assertEquals((6 downTo 0).map { "old_$it" }, viewModel.ids())
        assertFalse(viewModel.uiState.value.canShowOlder, "a short page is the end of the log")
        assertTrue(pages.limits.all { it == 3 }, "every read is one page: ${pages.limits}")

        // While older pages are shown the list holds still and counts what arrived since.
        app.graph.activityRecorder.record(ActivityType.USER_ACTION, "Something new")
        assertEquals((6 downTo 0).map { "old_$it" }, viewModel.ids())
        assertEquals(1, viewModel.uiState.value.newer)
        assertFalse(viewModel.uiState.value.newerCapped)

        viewModel.showNewest()
        assertEquals("Something new", viewModel.uiState.value.groups.first().rows.first().event.title)
        assertEquals(3, viewModel.uiState.value.eventCount)
        assertEquals(0, viewModel.uiState.value.newer)
    }

    @Test
    fun `a filter or a search starts again from the newest page`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        repeat(5) { app.activityLog.record(Samples.event("old_$it", at = TestApp.START_TIME - (5 - it) * 60_000L, egress = false)) }
        val viewModel = activity(app, pageSize = 2)
        viewModel.showOlder()
        assertEquals(4, viewModel.uiState.value.eventCount)

        viewModel.setMinSeverity(Severity.WARNING)
        assertEquals(emptyList(), viewModel.ids())
        viewModel.setMinSeverity(Severity.INFO)
        assertEquals(listOf("old_4", "old_3"), viewModel.ids(), "the pages read under the old filter are gone")
    }

    @Test
    fun `the search finds a text in titles and details, ignoring case, and keeps the other filters`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.activityRecorder.record(ActivityType.MODEL_CALL, "Model call", "Scripted model via Test server: 3 messages")
        app.graph.activityRecorder.record(ActivityType.TOOL_CALL, "Web page read", "news.example.org")
        app.graph.activityRecorder.record(ActivityType.ERROR, "Model call failed", "Grok via xAI: could not connect", severity = Severity.ERROR)
        val viewModel = activity(app)

        typeSearch(viewModel, "  SCRIPTED model ")
        assertEquals(listOf("Model call"), viewModel.uiState.value.groups.flatMap { g -> g.rows.map { it.event.title } })
        assertEquals("SCRIPTED model", viewModel.uiState.value.filter.text)
        assertTrue(viewModel.uiState.value.filter.isActive)

        typeSearch(viewModel, "model call")
        assertEquals(2, viewModel.uiState.value.eventCount)
        viewModel.setMinSeverity(Severity.ERROR)
        assertEquals(listOf("Model call failed"), viewModel.uiState.value.groups.flatMap { g -> g.rows.map { it.event.title } })

        viewModel.clearFilters()
        Snapshot.sendApplyNotifications()
        runCurrent()
        assertEquals("", viewModel.search.text.toString(), "Clear filters empties the search too")
        assertEquals(3, viewModel.uiState.value.eventCount)
        assertFalse(viewModel.uiState.value.filter.isActive)
    }

    @Test
    fun `a usage row opens the log on one agent or on one model's calls`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.agents.upsert(Samples.agent())
        app.graph.activityRecorder.record(ActivityType.MODEL_CALL, "Model call", "Scripted model via Test server", agentId = AgentId("agt_sample"))
        app.graph.activityRecorder.record(ActivityType.MODEL_CALL, "Model call", "Grok 4 via xAI", agentId = AgentId("agt_other"))
        app.graph.activityRecorder.record(ActivityType.REGISTRY, "Provider added", "xAI")

        val byAgent = activity(app, agent = AgentId("agt_sample"))
        assertEquals(listOf("Scripted model via Test server"), byAgent.uiState.value.groups.flatMap { g -> g.rows.map { it.event.detail } })
        assertEquals("Researcher", byAgent.uiState.value.agentName)
        byAgent.clearAgent()
        assertEquals(3, byAgent.uiState.value.eventCount)

        val byModel = activity(app, text = "Grok 4 via xAI")
        assertEquals("Grok 4 via xAI", byModel.search.text.toString(), "the search field shows what the log is searched for")
        assertEquals(listOf("Grok 4 via xAI"), byModel.uiState.value.groups.flatMap { g -> g.rows.map { it.event.detail } })

        // In place, from the Usage tab.
        byModel.showOnly(AgentId("agt_sample"), null)
        assertEquals("", byModel.search.text.toString())
        assertEquals(1, byModel.uiState.value.eventCount)
        assertEquals(AgentId("agt_sample"), byModel.uiState.value.filter.agentId)
    }

    /** Counts what each page read asks for, so the test can check that "Show older" reads one page, after the right event. */
    private class CountingPages(private val log: ActivityLog) : ActivityLog by log {
        val cursors = java.util.concurrent.CopyOnWriteArrayList<ActivityCursor>()
        val limits = java.util.concurrent.CopyOnWriteArrayList<Int>()

        override fun observePage(filter: ActivityFilter, before: ActivityCursor?, limit: Int): Flow<List<ActivityEvent>> {
            before?.let(cursors::add)
            limits += limit
            return log.observePage(filter, before, limit)
        }
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
        assertEquals("2.0\u00A0KB", egress["Approximate size"])
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
