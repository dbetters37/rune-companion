package com.aicontrolcenter.app.ui.settings

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.settings.SettingsRepository

/**
 * The phone operator's master switch as the owner flips it: in Settings, or with "Turn on" where work would need it
 * (New objective, a chat, a task or objective whose phone tool was refused). Owner UI only: it holds the settings
 * writer, so nothing an agent reaches may hold it (the architecture tests walk every agent-facing root).
 */
class PhoneOperatorSwitch(private val settings: SettingsRepository, private val activity: ActivityRecorder) {
    /** Off: every phone tool is refused before anyone is asked. Records the change in the activity log. */
    suspend fun set(enabled: Boolean) {
        if (settings.get().operatorEnabled == enabled) return
        settings.update { it.copy(operatorEnabled = enabled) }
        activity.record(
            ActivityType.USER_ACTION,
            if (enabled) TURNED_ON else TURNED_OFF,
            if (enabled) "Agents may use their phone tools, each with your permission." else "No agent can use the phone's files, apps, web pages or scripts.",
        )
    }

    companion object {
        const val TURNED_ON: String = "Phone operator turned on"
        const val TURNED_OFF: String = "Phone operator turned off"
    }
}
