package com.aicontrolcenter.data.settings

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.settings.FrameworkSettings
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How stored settings text is read when parts of it are not what this build expects. */
class SettingsCodecTest {
    private val customised = FrameworkSettings(
        localOnlyMode = true, activityRetentionDays = 0, directorAgentId = AgentId("agt_director"),
        maxConcurrentTasks = 5, maxDelegationDepth = 4, maxTasksPerObjective = 10, maxMessagesPerObjective = 50,
        maxModelCallsPerTask = 8, maxToolCallsPerTask = 9, maxRevisionRounds = 1, maxTaskAttempts = 3,
        taskTimeoutMinutes = 5, objectiveTimeoutMinutes = 45, approvalTimeoutMinutes = 2,
        autoResumeInterruptedTasks = true, operatorEnabled = true, accessibilityOperatorEnabled = true,
        onboardingCompleted = true, seedVersion = 2,
    )

    @Test
    fun `what is encoded decodes to the same settings, completely`() {
        for (settings in listOf(FrameworkSettings(), customised)) {
            val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))
            assertEquals(settings, decoded.settings)
            assertTrue(decoded.complete)
        }
    }

    @Test
    fun `nothing stored means defaults, with local-only mode off`() {
        val decoded = SettingsCodec.decode(null)
        assertEquals(FrameworkSettings(), decoded.settings)
        assertFalse(decoded.settings.localOnlyMode)
        assertTrue(decoded.complete)
    }

    @Test
    fun `unknown and missing fields are not damage`() {
        val decoded = SettingsCodec.decode("""{"localOnlyMode":true,"fromTheFuture":{"nested":[1,2]}}""")
        assertEquals(FrameworkSettings(localOnlyMode = true), decoded.settings)
        assertTrue(decoded.complete)
    }

    @Test
    fun `each field that cannot be read falls back on its own`() {
        val stored = SettingsCodec.encode(customised)
            .replace("\"maxConcurrentTasks\":5", "\"maxConcurrentTasks\":\"many\"")
            .replace("\"directorAgentId\":\"agt_director\"", "\"directorAgentId\":{\"id\":7}")
            .replace("\"onboardingCompleted\":true", "\"onboardingCompleted\":null")

        val decoded = SettingsCodec.decode(stored)

        assertEquals(setOf("maxConcurrentTasks", "directorAgentId", "onboardingCompleted"), decoded.unreadableFields)
        assertFalse(decoded.complete)
        val defaults = FrameworkSettings()
        assertEquals(
            customised.copy(
                maxConcurrentTasks = defaults.maxConcurrentTasks, directorAgentId = defaults.directorAgentId,
                onboardingCompleted = defaults.onboardingCompleted,
            ),
            decoded.settings,
        )
    }

    @Test
    fun `any single field may be unreadable without costing the others, least of all local-only mode`() {
        val defaults = FrameworkSettings()
        val fields = SettingsCodec.encode(customised).removeSurrounding("{", "}").split(",")
        assertEquals(19, fields.size)
        for (broken in fields) {
            val name = broken.substringBefore(':').trim('"')
            val stored = "{" + fields.joinToString(",") { if (it == broken) "\"$name\":[\"unreadable\"]" else it } + "}"

            val decoded = SettingsCodec.decode(stored)

            assertEquals(setOf(name), decoded.unreadableFields)
            val expected = when (name) {
                // Unknown, so on: see SettingsCodec.
                "localOnlyMode" -> customised
                "activityRetentionDays" -> customised.copy(activityRetentionDays = defaults.activityRetentionDays)
                "directorAgentId" -> customised.copy(directorAgentId = defaults.directorAgentId)
                "maxConcurrentTasks" -> customised.copy(maxConcurrentTasks = defaults.maxConcurrentTasks)
                "maxDelegationDepth" -> customised.copy(maxDelegationDepth = defaults.maxDelegationDepth)
                "maxTasksPerObjective" -> customised.copy(maxTasksPerObjective = defaults.maxTasksPerObjective)
                "maxMessagesPerObjective" -> customised.copy(maxMessagesPerObjective = defaults.maxMessagesPerObjective)
                "maxModelCallsPerTask" -> customised.copy(maxModelCallsPerTask = defaults.maxModelCallsPerTask)
                "maxToolCallsPerTask" -> customised.copy(maxToolCallsPerTask = defaults.maxToolCallsPerTask)
                "maxRevisionRounds" -> customised.copy(maxRevisionRounds = defaults.maxRevisionRounds)
                "maxTaskAttempts" -> customised.copy(maxTaskAttempts = defaults.maxTaskAttempts)
                "taskTimeoutMinutes" -> customised.copy(taskTimeoutMinutes = defaults.taskTimeoutMinutes)
                "objectiveTimeoutMinutes" -> customised.copy(objectiveTimeoutMinutes = defaults.objectiveTimeoutMinutes)
                "approvalTimeoutMinutes" -> customised.copy(approvalTimeoutMinutes = defaults.approvalTimeoutMinutes)
                "autoResumeInterruptedTasks" -> customised.copy(autoResumeInterruptedTasks = defaults.autoResumeInterruptedTasks)
                "operatorEnabled" -> customised.copy(operatorEnabled = defaults.operatorEnabled)
                "accessibilityOperatorEnabled" -> customised.copy(accessibilityOperatorEnabled = defaults.accessibilityOperatorEnabled)
                "onboardingCompleted" -> customised.copy(onboardingCompleted = defaults.onboardingCompleted)
                "seedVersion" -> customised.copy(seedVersion = defaults.seedVersion)
                else -> error("FrameworkSettings has a field this test does not know: $name")
            }
            assertEquals(expected, decoded.settings, "with $name unreadable")
        }
    }

    @Test
    fun `an unreadable local-only switch comes back on, whatever the default is`() {
        for (stored in listOf("""{"localOnlyMode":"yes"}""", """{"localOnlyMode":null,"seedVersion":2}""", """{"localOnlyMode":0}""")) {
            val decoded = SettingsCodec.decode(stored)
            assertTrue(decoded.settings.localOnlyMode, stored)
            assertEquals(setOf("localOnlyMode"), decoded.unreadableFields)
        }
        assertEquals(2, SettingsCodec.decode("""{"localOnlyMode":null,"seedVersion":2}""").settings.seedVersion)
    }

    @Test
    fun `text that is not a settings object at all gives defaults with local-only mode on`() {
        for (stored in listOf("", "not json", "[1,2,3]", "42", "null", "{\"localOnlyMode\": tru")) {
            val decoded = SettingsCodec.decode(stored)
            assertEquals(FrameworkSettings(localOnlyMode = true), decoded.settings, "for '$stored'")
            assertEquals(setOf(SettingsCodec.WHOLE_VALUE), decoded.unreadableFields)
            assertFalse(decoded.complete)
        }
    }
}
