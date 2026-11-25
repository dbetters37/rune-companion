package com.aicontrolcenter.core.backup

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.DailyLimits
import kotlinx.serialization.Serializable

/*
 * Which settings a backup carries and how a restore uses them (Phase 6, rule 8; security review of
 * wave 1, F2). The allow-list is structural: the file's manifest holds a [BackupSettings] object
 * and nothing else of the settings, and [RestoreSettings.merge] is the only way its values reach
 * the app. A restore never relaxes protection: local-only mode stays on if it is on, daily limits
 * only get stricter, and every switch that lets work happen while nobody watches (routines,
 * folder pick-up, the phone operator, screen control, resuming interrupted work by itself) is off
 * after a restore, whatever the file says.
 */

/**
 * The settings a backup file holds: the `"settings"` object of its manifest record (format v1,
 * `phase6-contracts.md` section 4), exactly these keys. Any other key in the file is ignored; a
 * missing key reads as the default here. Written by [of] on save.
 */
@Serializable
data class BackupSettings(
    val localOnlyMode: Boolean = false,
    /** The Director's agent id on the phone that saved it; used only if that agent is restored too. */
    val directorAgentId: String? = null,
    val maxModelCallsPerDay: Int = DailyLimits.DEFAULT_MAX_CALLS,
    val maxTokensPerDay: Long = DailyLimits.DEFAULT_MAX_TOKENS,
    /** Null: the file's phone had no money limit. */
    val maxSpendPerDayMicros: Long? = DailyLimits.DEFAULT_MAX_SPEND_MICROS,
    val notifyWhenWorkEnds: Boolean = true,
    val calmMotion: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val seedVersion: Int = 0,
    val memorySeedVersion: Int = 0,
) {
    companion object {
        /** What a save writes for [settings]: these fields and no other. */
        fun of(settings: FrameworkSettings): BackupSettings = BackupSettings(
            localOnlyMode = settings.localOnlyMode,
            directorAgentId = settings.directorAgentId?.value,
            maxModelCallsPerDay = settings.maxModelCallsPerDay,
            maxTokensPerDay = settings.maxTokensPerDay,
            maxSpendPerDayMicros = settings.maxSpendPerDayMicros,
            notifyWhenWorkEnds = settings.notifyWhenWorkEnds,
            calmMotion = settings.calmMotion,
            onboardingCompleted = settings.onboardingCompleted,
            seedVersion = settings.seedVersion,
            memorySeedVersion = settings.memorySeedVersion,
        )

        /** The manifest's keys, in the order a save writes them (pinned by the format tests). */
        val KEYS: List<String> = listOf(
            "localOnlyMode", "directorAgentId", "maxModelCallsPerDay", "maxTokensPerDay", "maxSpendPerDayMicros",
            "notifyWhenWorkEnds", "calmMotion", "onboardingCompleted", "seedVersion", "memorySeedVersion",
        )
    }
}

/**
 * A setting the file has that a restore does not take as it is, in plain words: shown on the
 * inspect screen. For the Director, [inFile] and [afterRestore] are agents' names (or "an agent not
 * in this backup"), never ids; the agents themselves are [agentInFile] and [agentAfterRestore].
 */
data class SettingKeptBack(
    val name: String,
    val inFile: String,
    val afterRestore: String,
    val agentInFile: AgentId? = null,
    val agentAfterRestore: AgentId? = null,
)

/** The outcome of [RestoreSettings.merge]. */
data class RestoredSettings(
    /** The settings to store. */
    val settings: FrameworkSettings,
    /** Every setting that changes on this phone (rule 8: "the screen lists every setting that would change"). */
    val changes: List<SettingChange>,
    /** Every value of the file the restore does not take as it is, and what the setting is instead. */
    val keptBack: List<SettingKeptBack>,
)

/** How a restore combines the app's settings with a file's (rule 8; security review of wave 1, F2). Pure. */
object RestoreSettings {
    /**
     * The settings after restoring [file] into an app whose settings are [current] and which will
     * then hold the agents [agentsAfterRestore] (the file's and any starter agent kept: id to name;
     * the names are what the inspect screen shows for the Director).
     *
     * - Local-only mode: on if it is on now or in the file.
     * - Daily limits: the file's values are first brought into the ranges the Settings screen
     *   accepts ([DailyLimits.CALLS_RANGE], [DailyLimits.TOKENS_RANGE],
     *   [DailyLimits.SPEND_RANGE_MICROS]; a file without a money limit keeps none), then the
     *   stricter of now and the file is kept, limit by limit ([DailyLimits.stricter]).
     * - The Director: the file's, if that agent exists after the restore; else the current one, if
     *   it still exists; else none.
     * - Seed bookkeeping (`seedVersion`, `memorySeedVersion`) from the file, which describes the
     *   restored team and spaces; "getting started" done if it is done now or in the file.
     * - From the file as it is: "Notify when work ends" and "Calm".
     * - Forced off, whatever the file says: routines, folder pick-up, the phone operator, screen
     *   control and resuming interrupted tasks by themselves. The owner switches them on again.
     * - Every other setting keeps its current value (agents' questions included).
     */
    fun merge(current: FrameworkSettings, file: BackupSettings, agentsAfterRestore: Map<AgentId, String>): RestoredSettings {
        val fileLimits = DailyLimits(
            maxCalls = file.maxModelCallsPerDay.coerceIn(DailyLimits.CALLS_RANGE),
            maxTokens = file.maxTokensPerDay.coerceIn(DailyLimits.TOKENS_RANGE),
            maxSpendMicros = file.maxSpendPerDayMicros?.coerceIn(DailyLimits.SPEND_RANGE_MICROS),
        )
        val limits = current.dailyLimits.stricter(fileLimits)
        val fileDirector = file.directorAgentId?.let(::AgentId)
        val director = when {
            fileDirector != null && fileDirector in agentsAfterRestore.keys -> fileDirector
            current.directorAgentId != null && current.directorAgentId in agentsAfterRestore.keys -> current.directorAgentId
            else -> null
        }
        val merged = current.copy(
            localOnlyMode = current.localOnlyMode || file.localOnlyMode,
            directorAgentId = director,
            maxModelCallsPerDay = limits.maxCalls ?: current.maxModelCallsPerDay,
            maxTokensPerDay = limits.maxTokens ?: current.maxTokensPerDay,
            maxSpendPerDayMicros = limits.maxSpendMicros,
            notifyWhenWorkEnds = file.notifyWhenWorkEnds,
            calmMotion = file.calmMotion,
            onboardingCompleted = current.onboardingCompleted || file.onboardingCompleted,
            seedVersion = file.seedVersion.coerceAtLeast(0),
            memorySeedVersion = file.memorySeedVersion.coerceAtLeast(0),
            autoResumeInterruptedTasks = false,
            operatorEnabled = false,
            accessibilityOperatorEnabled = false,
            routinesEnabled = false,
            folderPickUpEnabled = false,
        )
        val names = Names(agentsAfterRestore)
        return RestoredSettings(merged, changes(current, merged, names), keptBack(file, merged, names))
    }

    /** What the inspect screen says for an agent that is not among the agents after the restore. */
    const val UNKNOWN_AGENT: String = "an agent not in this backup"

    /** How an agent is named on the inspect screen: by its name, never by its id. */
    private class Names(private val agents: Map<AgentId, String>) {
        fun of(id: AgentId?): String = when (id) {
            null -> "None chosen"
            else -> agents[id]?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_AGENT
        }
    }

    /** One setting as the owner reads it: its name and how a value is said. The Director is said apart, by name. */
    private class Shown(val name: String, val value: (FrameworkSettings) -> String)

    private fun onOff(value: Boolean) = if (value) "On" else "Off"

    private fun calls(value: Long) = "%,d model answers".format(java.util.Locale.ROOT, value)

    private fun tokens(value: Long) = "%,d tokens".format(java.util.Locale.ROOT, value)

    private fun spend(value: Long?) = value?.let { DailyLimits.money(it, com.aicontrolcenter.core.usage.ModelPrice.DEFAULT_CURRENCY) } ?: "No limit"

    /** The settings a restore can change, in the order the inspect screen lists them. */
    private val shown: List<Shown> = listOf(
        Shown("Local-only mode") { onOff(it.localOnlyMode) },
        Shown("Daily limit of model answers") { calls(it.maxModelCallsPerDay.toLong()) },
        Shown("Daily limit of tokens") { tokens(it.maxTokensPerDay) },
        Shown("Daily spending limit") { spend(it.maxSpendPerDayMicros) },
        Shown("Notify when work ends") { onOff(it.notifyWhenWorkEnds) },
        Shown("Calm") { onOff(it.calmMotion) },
        Shown("Getting started") { if (it.onboardingCompleted) "Done" else "Not done" },
        Shown("Starter team version") { it.seedVersion.toString() },
        Shown("Starter knowledge version") { it.memorySeedVersion.toString() },
        Shown("Phone operator") { onOff(it.operatorEnabled) },
        Shown("Screen control") { onOff(it.accessibilityOperatorEnabled) },
        Shown("Resume interrupted tasks") { onOff(it.autoResumeInterruptedTasks) },
        Shown("Routines") { onOff(it.routinesEnabled) },
        Shown("Folder pick-up") { onOff(it.folderPickUpEnabled) },
    )

    private fun changes(current: FrameworkSettings, merged: FrameworkSettings, names: Names): List<SettingChange> = buildList {
        for (s in shown) {
            val from = s.value(current)
            val to = s.value(merged)
            if (to != from) add(SettingChange(s.name, from, to))
            // The Director comes after the limits, as before.
            if (s.name == "Daily spending limit" && current.directorAgentId != merged.directorAgentId) {
                add(SettingChange(DIRECTOR, names.of(current.directorAgentId), names.of(merged.directorAgentId), current.directorAgentId, merged.directorAgentId))
            }
        }
    }

    private const val DIRECTOR = "Director"

    private fun keptBack(file: BackupSettings, merged: FrameworkSettings, names: Names): List<SettingKeptBack> = buildList {
        if (file.maxModelCallsPerDay.toLong() != merged.maxModelCallsPerDay.toLong()) {
            add(SettingKeptBack("Daily limit of model answers", calls(file.maxModelCallsPerDay.toLong()), calls(merged.maxModelCallsPerDay.toLong())))
        }
        if (file.maxTokensPerDay != merged.maxTokensPerDay) {
            add(SettingKeptBack("Daily limit of tokens", tokens(file.maxTokensPerDay), tokens(merged.maxTokensPerDay)))
        }
        if (file.maxSpendPerDayMicros != merged.maxSpendPerDayMicros) {
            add(SettingKeptBack("Daily spending limit", spend(file.maxSpendPerDayMicros), spend(merged.maxSpendPerDayMicros)))
        }
        if (file.localOnlyMode != merged.localOnlyMode) add(SettingKeptBack("Local-only mode", onOff(file.localOnlyMode), onOff(merged.localOnlyMode)))
        if (file.directorAgentId != null && file.directorAgentId != merged.directorAgentId?.value) {
            val inFile = AgentId(file.directorAgentId)
            add(SettingKeptBack(DIRECTOR, names.of(inFile), names.of(merged.directorAgentId), inFile, merged.directorAgentId))
        }
    }

    /**
     * The switches a restore always leaves off, in plain words, for the inspect screen's line "After
     * the restore these are off; switch them on again in Settings if you want them".
     */
    val ALWAYS_OFF_AFTER_RESTORE: List<String> = listOf("Phone operator", "Screen control", "Resume interrupted tasks", "Routines", "Folder pick-up")
}
