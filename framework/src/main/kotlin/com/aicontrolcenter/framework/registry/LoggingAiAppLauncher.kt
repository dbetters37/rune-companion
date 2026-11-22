package com.aicontrolcenter.framework.registry

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.LaunchResult
import com.aicontrolcenter.core.registry.Locality

/**
 * Records every hand-off in the activity log. Text given to another app has left this
 * framework's control, and that app may send it anywhere, so a text hand-off is logged as
 * information leaving the device with the app as its destination.
 */
class LoggingAiAppLauncher(
    private val delegate: AiAppLauncher,
    private val activity: ActivityRecorder,
) : AiAppLauncher {

    override suspend fun launch(app: AiApp): LaunchResult {
        val result = delegate.launch(app)
        when (result) {
            LaunchResult.Started -> activity.record(ActivityType.USER_ACTION, "Opened app", app.label)
            is LaunchResult.NotPossible -> activity.record(ActivityType.USER_ACTION, "Could not open app", "${app.label}: ${result.reason}")
        }
        return result
    }

    override suspend fun handOffText(app: AiApp, text: String): LaunchResult {
        val result = delegate.handOffText(app, text)
        when (result) {
            LaunchResult.Started -> activity.record(
                ActivityType.DATA_EGRESS,
                "Text handed to another app",
                "${app.label} received ${text.length} characters. What it does with them is outside this app's control.",
                egress = EgressInfo(
                    destinationHost = APP_DESTINATION_PREFIX + app.packageName,
                    // Unknown, so assume the widest reach.
                    locality = Locality.CLOUD,
                    purpose = "text handed to the app's own screen",
                    approxBytes = text.toByteArray().size.toLong(),
                ),
            )
            is LaunchResult.NotPossible ->
                activity.record(ActivityType.USER_ACTION, "Could not hand text to app", "${app.label}: ${result.reason}")
        }
        return result
    }

    companion object {
        /** Marks an egress destination that is an installed app rather than a network host. */
        const val APP_DESTINATION_PREFIX = "app:"
    }
}
