package com.aicontrolcenter.core.backup

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which settings a restore brings back, and how (rule 8; security review of wave 1, F2). */
class RestoreSettingsTest {
    private val director = AgentId("agt_director")
    private val restoredDirector = AgentId("agt_lead")

    /** A fresh app: the starter team is there, nothing switched on. */
    private val fresh = FrameworkSettings(directorAgentId = director, seedVersion = 4, memorySeedVersion = 1)

    /** A file from a phone where the owner had switched on everything that works while nobody watches. */
    private val everythingOn = BackupSettings.of(
        FrameworkSettings(
            localOnlyMode = false, directorAgentId = restoredDirector, maxModelCallsPerDay = 1_000, maxTokensPerDay = 9_000_000,
            maxSpendPerDayMicros = null, autoResumeInterruptedTasks = true, operatorEnabled = true, accessibilityOperatorEnabled = true,
            notifyWhenWorkEnds = false, calmMotion = true, onboardingCompleted = true, seedVersion = 3, memorySeedVersion = 1,
            agentQuestionsEnabled = true, routinesEnabled = true, folderPickUpEnabled = true, maxConcurrentTasks = 8,
            approvalTimeoutMinutes = 240, usageRetentionDays = 0, keepAwakeWhileAgentsWork = false,
        ),
    )

    @Test
    fun `switches that let work happen while nobody watches are off after a restore, whatever the file says`() {
        val restored = RestoreSettings.merge(fresh.copy(routinesEnabled = true, operatorEnabled = true), everythingOn, setOf(director, restoredDirector)).settings
        assertFalse(restored.routinesEnabled)
        assertFalse(restored.folderPickUpEnabled)
        assertFalse(restored.operatorEnabled)
        assertFalse(restored.accessibilityOperatorEnabled)
        assertFalse(restored.autoResumeInterruptedTasks)
    }

    @Test
    fun `daily limits are brought into range, then the stricter of now and the file is kept`() {
        // A looser file never loosens: no money limit and higher limits in the file keep today's.
        val looser = RestoreSettings.merge(fresh, everythingOn, setOf(director)).settings
        assertEquals(DailyLimits.DEFAULT, looser.dailyLimits)
        // A stricter file tightens.
        val stricter = RestoreSettings.merge(fresh, BackupSettings(maxModelCallsPerDay = 50, maxTokensPerDay = 20_000, maxSpendPerDayMicros = 250_000), setOf(director)).settings
        assertEquals(DailyLimits(50, 20_000, 250_000), stricter.dailyLimits)
        // Values outside what Settings accepts are brought into range first: 0 calls would refuse every model call.
        val broken = RestoreSettings.merge(fresh, BackupSettings(maxModelCallsPerDay = 0, maxTokensPerDay = -5, maxSpendPerDayMicros = -1), setOf(director)).settings
        assertEquals(DailyLimits(DailyLimits.CALLS_RANGE.first, DailyLimits.TOKENS_RANGE.first, DailyLimits.SPEND_RANGE_MICROS.first), broken.dailyLimits)
        // A current "no money limit" takes the file's limit.
        val noLimitNow = RestoreSettings.merge(fresh.copy(maxSpendPerDayMicros = null), BackupSettings(maxSpendPerDayMicros = 2_000_000), setOf(director)).settings
        assertEquals(2_000_000, noLimitNow.maxSpendPerDayMicros)
        assertNull(RestoreSettings.merge(fresh.copy(maxSpendPerDayMicros = null), BackupSettings(maxSpendPerDayMicros = null), setOf(director)).settings.maxSpendPerDayMicros)
    }

    @Test
    fun `local-only mode stays on if it is on now or in the file`() {
        assertTrue(RestoreSettings.merge(fresh.copy(localOnlyMode = true), everythingOn, setOf(director)).settings.localOnlyMode)
        assertTrue(RestoreSettings.merge(fresh, everythingOn.copy(localOnlyMode = true), setOf(director)).settings.localOnlyMode)
        assertFalse(RestoreSettings.merge(fresh, everythingOn, setOf(director)).settings.localOnlyMode)
    }

    @Test
    fun `the Director is the file's only if that agent is restored`() {
        assertEquals(restoredDirector, RestoreSettings.merge(fresh, everythingOn, setOf(director, restoredDirector)).settings.directorAgentId)
        assertEquals(director, RestoreSettings.merge(fresh, everythingOn, setOf(director)).settings.directorAgentId, "else the current one")
        assertNull(RestoreSettings.merge(fresh, everythingOn, emptySet()).settings.directorAgentId, "else none")
    }

    @Test
    fun `seed bookkeeping and two display choices come from the file, everything else keeps its current value`() {
        val current = fresh.copy(agentQuestionsEnabled = false, maxConcurrentTasks = 2, approvalTimeoutMinutes = 30, usageRetentionDays = 365, activityRetentionDays = 7)
        val restored = RestoreSettings.merge(current, everythingOn, setOf(director, restoredDirector)).settings
        assertEquals(3, restored.seedVersion)
        assertEquals(1, restored.memorySeedVersion)
        assertTrue(restored.onboardingCompleted)
        assertFalse(restored.notifyWhenWorkEnds)
        assertTrue(restored.calmMotion)
        // Kept as they are now.
        assertFalse(restored.agentQuestionsEnabled, "questions keep their current value")
        assertEquals(2, restored.maxConcurrentTasks)
        assertEquals(30, restored.approvalTimeoutMinutes)
        assertEquals(365, restored.usageRetentionDays)
        assertEquals(7, restored.activityRetentionDays)
        assertTrue(restored.keepAwakeWhileAgentsWork)
        assertEquals(current.ownerQuestionTimeoutMinutes, restored.ownerQuestionTimeoutMinutes)
        // Exactly the allow-listed fields can differ from the current settings.
        val allowed = setOf(
            "localOnlyMode", "directorAgentId", "maxModelCallsPerDay", "maxTokensPerDay", "maxSpendPerDayMicros", "notifyWhenWorkEnds", "calmMotion",
            "onboardingCompleted", "seedVersion", "memorySeedVersion", "autoResumeInterruptedTasks", "operatorEnabled", "accessibilityOperatorEnabled",
            "routinesEnabled", "folderPickUpEnabled",
        )
        val json = Json { encodeDefaults = true }
        val before = json.encodeToJsonElement(FrameworkSettings.serializer(), current).jsonObject
        val after = json.encodeToJsonElement(FrameworkSettings.serializer(), restored).jsonObject
        assertTrue(before.keys.filter { before[it] != after[it] }.all { it in allowed }, "changed: ${before.keys.filter { before[it] != after[it] }}")
    }

    @Test
    fun `every change and every value not taken from the file is listed for the owner`() {
        val result = RestoreSettings.merge(fresh.copy(operatorEnabled = true), everythingOn, setOf(director))
        val changed = result.changes.associateBy { it.name }
        assertEquals(SettingChange("Phone operator", "On", "Off"), changed["Phone operator"])
        assertEquals(SettingChange("Calm", "Off", "On"), changed["Calm"])
        assertEquals(SettingChange("Notify when work ends", "On", "Off"), changed["Notify when work ends"])
        assertTrue("Daily limit of model answers" !in changed, "today's limit stays")
        val kept = result.keptBack.associateBy { it.name }
        assertEquals(SettingKeptBack("Daily limit of model answers", "1,000 model answers", "200 model answers"), kept["Daily limit of model answers"])
        assertEquals(SettingKeptBack("Daily spending limit", "No limit", "US$1.00"), kept["Daily spending limit"])
        assertEquals("agt_lead", kept["Director"]?.inFile)
        assertEquals(listOf("Phone operator", "Screen control", "Resume interrupted tasks", "Routines", "Folder pick-up"), RestoreSettings.ALWAYS_OFF_AFTER_RESTORE)
        assertEquals(emptyList(), RestoreSettings.merge(fresh, BackupSettings.of(fresh), setOf(director)).changes, "the same settings change nothing")
    }

    @Test
    fun `the file holds exactly the allow-listed settings`() {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val written = json.encodeToJsonElement(BackupSettings.serializer(), everythingOn).jsonObject
        assertEquals(BackupSettings.KEYS, written.keys.toList())
        // Unknown keys in a file are ignored, missing ones read as the defaults.
        val read = Json { ignoreUnknownKeys = true }.decodeFromString(BackupSettings.serializer(), """{"operatorEnabled":true,"routinesEnabled":true,"maxModelCallsPerDay":10}""")
        assertEquals(BackupSettings(maxModelCallsPerDay = 10), read)
    }
}
