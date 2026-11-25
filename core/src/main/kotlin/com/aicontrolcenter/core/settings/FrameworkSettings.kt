package com.aicontrolcenter.core.settings

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Framework-wide configuration and safety limits. Persisted locally; never leaves the device. */
@Serializable
data class FrameworkSettings(
    // --- privacy ---
    /** When on, no provider or tool that sends data off this device may be used. */
    val localOnlyMode: Boolean = false,
    /** Days of activity history to keep. 0 keeps everything. */
    val activityRetentionDays: Int = 30,

    // --- orchestration limits (loop and runaway protection) ---
    val directorAgentId: AgentId? = null,
    val maxConcurrentTasks: Int = 2,
    /** Deepest allowed chain of agent-to-agent delegation. */
    val maxDelegationDepth: Int = 3,
    /** Hard cap on tasks created under one objective. */
    val maxTasksPerObjective: Int = 24,
    /** Hard cap on agent-to-agent messages under one objective. */
    val maxMessagesPerObjective: Int = 200,
    val maxModelCallsPerTask: Int = 24,
    val maxToolCallsPerTask: Int = 40,
    val maxRevisionRounds: Int = 2,
    val maxTaskAttempts: Int = 2,
    val taskTimeoutMinutes: Int = 15,
    val objectiveTimeoutMinutes: Int = 90,
    /** How long an agent waits for the user to answer an approval before the action is refused. */
    val approvalTimeoutMinutes: Int = 30,
    /** Re-queue tasks that were running when the app was killed, instead of leaving them paused. */
    val autoResumeInterruptedTasks: Boolean = false,

    // --- phone operator master switches ---
    /** Master switch for every phone-operation tool. Off means no agent can touch the phone. */
    val operatorEnabled: Boolean = false,
    /** Separate switch for accessibility-driven UI operation. */
    val accessibilityOperatorEnabled: Boolean = false,

    // --- first-run bookkeeping ---
    val onboardingCompleted: Boolean = false,
    /** Version of the built-in starter content already applied; lets later versions add to it once. */
    val seedVersion: Int = 0,
    /** Version of the built-in memory content (the starter knowledge space) already applied. */
    val memorySeedVersion: Int = 0,

    // --- Phase 6 (section 4 of the plan). Added last, each with its default, so stored
    // settings without them read as before (the codec reads each field on its own). ---

    /** Keep the phone awake (partial wake lock) while agents work or are about to try again (rule 5). */
    val keepAwakeWhileAgentsWork: Boolean = true,
    /** Notify "Finished" or "Stopped" when an objective ends while the app is not on screen. */
    val notifyWhenWorkEnds: Boolean = true,
    /** Daily limit of model answers (Q2: 200). See [dailyLimits]. */
    val maxModelCallsPerDay: Int = DailyLimits.DEFAULT_MAX_CALLS,
    /** Daily limit of tokens (Q2: 500,000). */
    val maxTokensPerDay: Long = DailyLimits.DEFAULT_MAX_TOKENS,
    /** Daily limit of estimated cost in millionths of the price's currency (Q2: US$1.00), for models with a price; null: no money limit. */
    val maxSpendPerDayMicros: Long? = DailyLimits.DEFAULT_MAX_SPEND_MICROS,
    /** Agents may ask the owner questions while they work (Q3; Tier B: forced off if C1 is switched off). */
    val agentQuestionsEnabled: Boolean = true,
    /** How long a question waits for the owner before the agent decides itself, in minutes ([SettingsBounds.QUESTION_TIMEOUT_MINUTES]). */
    val ownerQuestionTimeoutMinutes: Int = 20,
    /** Days of usage history to keep: 90, 365, 730, or 0 for forever ([SettingsBounds.USAGE_RETENTION_DAYS]). */
    val usageRetentionDays: Int = 730,
    /** "Calm": nothing travels or pulses on screen (rule 17). */
    val calmMotion: Boolean = false,
    /** Master switch for routines (Q1, Appendix A, Tier B): off after installing. */
    val routinesEnabled: Boolean = false,
    /** Master switch for automatic folder pick-up (Q1, Appendix A, Tier B): off after installing. */
    val folderPickUpEnabled: Boolean = false,
) {
    /** Today's limits as the daily limiter applies them (rule 6). */
    val dailyLimits: DailyLimits get() = DailyLimits(maxModelCallsPerDay, maxTokensPerDay, maxSpendPerDayMicros)
}

/** What the owner's settings screens accept for the Phase 6 settings. */
object SettingsBounds {
    val QUESTION_TIMEOUT_MINUTES: IntRange = 5..240

    /** The retention choices offered; 0 keeps everything. */
    val USAGE_RETENTION_DAYS: List<Int> = listOf(90, 365, 730, 0)
}

/**
 * Reading the settings (Phase 6, K16, rule 3). Agent-facing code holds only this, through
 * [ReadOnlySettings]; only the owner's screens and start-up bookkeeping hold the writer
 * ([SettingsRepository]).
 */
interface SettingsView {
    val settings: Flow<FrameworkSettings>

    suspend fun get(): FrameworkSettings
}

/**
 * The settings store, which can also change them. Owner-only from Phase 6 on (rule 3): the
 * architecture tests forbid it, and its [update], anywhere an agent can reach.
 */
interface SettingsRepository : SettingsView {
    override val settings: Flow<FrameworkSettings>

    override suspend fun get(): FrameworkSettings

    /** Atomically applies [transform] and returns the new settings. */
    suspend fun update(transform: (FrameworkSettings) -> FrameworkSettings): FrameworkSettings
}

/**
 * A read-only view of [source]: the only settings object agent-facing code may hold (rule 3).
 * It cannot be cast to a writer, and it offers no way to change anything, whatever [source] is.
 * The app's architecture test lists it among the views it does not walk into.
 */
class ReadOnlySettings(private val source: SettingsView) : SettingsView {
    override val settings: Flow<FrameworkSettings> get() = source.settings

    override suspend fun get(): FrameworkSettings = source.get()
}
