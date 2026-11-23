package com.aicontrolcenter.core.settings

import com.aicontrolcenter.core.AgentId
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
)

interface SettingsRepository {
    val settings: Flow<FrameworkSettings>

    suspend fun get(): FrameworkSettings

    /** Atomically applies [transform] and returns the new settings. */
    suspend fun update(transform: (FrameworkSettings) -> FrameworkSettings): FrameworkSettings
}
