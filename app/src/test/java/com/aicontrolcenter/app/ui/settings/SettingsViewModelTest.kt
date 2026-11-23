package com.aicontrolcenter.app.ui.settings

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.device.DeviceReport
import com.aicontrolcenter.core.device.LocalModelGuidance
import com.aicontrolcenter.core.settings.FrameworkSettings
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SettingsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.settings(app: TestApp, inspector: DeviceInspector = app.deviceInspector) = SettingsViewModel(
        app.graph.settings, app.graph.activityLog, app.graph.activityRecorder, inspector, appVersion = "0.1.0 (1)",
    ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `local-only mode is stored at once and each change is logged once`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = settings(app)
        assertFalse(viewModel.uiState.value.settings.localOnlyMode)

        viewModel.setLocalOnlyMode(true)
        viewModel.setLocalOnlyMode(true)
        assertTrue(app.settings.get().localOnlyMode)
        assertTrue(viewModel.uiState.value.settings.localOnlyMode)
        assertEquals(listOf("Local-only mode turned on"), app.activityLog.all.map { it.title })

        viewModel.setLocalOnlyMode(false)
        assertFalse(app.settings.get().localOnlyMode)
        assertEquals("Local-only mode turned off", app.activityLog.all.last().title)
    }

    @Test
    fun `the phone operator and screen control switches are stored at once and each change is logged`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = settings(app)
        assertFalse(viewModel.uiState.value.settings.operatorEnabled, "off until the owner turns it on")
        assertFalse(viewModel.uiState.value.settings.accessibilityOperatorEnabled)

        viewModel.setOperatorEnabled(true)
        viewModel.setScreenControlEnabled(true)
        viewModel.setScreenControlEnabled(true)
        assertTrue(app.settings.get().operatorEnabled)
        assertTrue(app.settings.get().accessibilityOperatorEnabled)
        assertEquals(listOf("Phone operator turned on", "Screen control turned on"), app.activityLog.all.map { it.title })

        viewModel.setScreenControlEnabled(false)
        assertFalse(app.settings.get().accessibilityOperatorEnabled)
        assertTrue(app.settings.get().operatorEnabled, "the switches are separate")
        assertEquals("No agent can read or use any app's screen.", app.activityLog.all.last().detail)
    }

    @Test
    fun `every limit is kept inside its range whatever is asked for`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = settings(app)

        viewModel.setLimit(OrchestrationLimit.MAX_CONCURRENT_TASKS, 4)
        assertEquals(4, app.settings.get().maxConcurrentTasks)
        viewModel.setLimit(OrchestrationLimit.MAX_CONCURRENT_TASKS, 99)
        assertEquals(8, app.settings.get().maxConcurrentTasks)
        viewModel.setLimit(OrchestrationLimit.MAX_CONCURRENT_TASKS, -3)
        assertEquals(1, app.settings.get().maxConcurrentTasks)

        viewModel.setLimit(OrchestrationLimit.TASK_TIMEOUT_MINUTES, 45)
        assertEquals(45, viewModel.uiState.value.settings.taskTimeoutMinutes)
        assertEquals("45 min", OrchestrationLimit.TASK_TIMEOUT_MINUTES.format(45))

        val defaults = FrameworkSettings()
        for (limit in OrchestrationLimit.entries) {
            assertTrue(limit.valueIn(defaults) in limit.range, "${limit.name}: the default must be selectable")
            assertEquals(limit.range.last, limit.valueIn(limit.applyTo(defaults, Int.MAX_VALUE)))
            assertEquals(limit.range.first, limit.valueIn(limit.applyTo(defaults, Int.MIN_VALUE)))
            assertEquals(0, (limit.valueIn(defaults) - limit.range.first) % limit.step, "${limit.name}: steps must reach the default")
        }
    }

    @Test
    fun `retention is stored and never negative`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = settings(app)
        viewModel.setActivityRetentionDays(90)
        assertEquals(90, app.settings.get().activityRetentionDays)
        viewModel.setActivityRetentionDays(-5)
        assertEquals(0, app.settings.get().activityRetentionDays)
        assertEquals(listOf("7 days", "30 days", "90 days", "1 year", "Keep everything"), ActivityRetentionChoices.map(::retentionLabel))
    }

    @Test
    fun `clearing the log leaves exactly one entry saying that it was cleared`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        assertTrue(app.activityLog.all.size > 1)
        val viewModel = settings(app)

        viewModel.clearActivityLog()
        assertEquals(listOf("Activity log cleared"), app.activityLog.all.map { it.title })
        assertEquals(ActivityType.USER_ACTION, app.activityLog.all.single().type)
        assertEquals("The activity log was cleared.", viewModel.uiState.value.message?.text)
        viewModel.messageShown()
        assertEquals(null, viewModel.uiState.value.message)
    }

    @Test
    fun `the device report is loaded on demand and a failure offers a retry`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val loaded = assertIs<DeviceReportState.Loaded>(settings(app).uiState.value.device)
        assertEquals("Pixel 9", loaded.report.model)
        assertEquals(LocalModelGuidance.LARGE, loaded.report.localModelGuidance)

        var fail = true
        val flaky = object : DeviceInspector {
            override suspend fun inspect(): DeviceReport =
                if (fail) throw IllegalStateException("activity manager unavailable") else app.deviceInspector.report
        }
        val viewModel = settings(app, flaky)
        assertEquals(DeviceReportState.Failed("activity manager unavailable"), viewModel.uiState.value.device)
        assertEquals("0.1.0 (1)", viewModel.uiState.value.appVersion)

        fail = false
        viewModel.refreshDeviceReport()
        assertIs<DeviceReportState.Loaded>(viewModel.uiState.value.device)
    }
}
