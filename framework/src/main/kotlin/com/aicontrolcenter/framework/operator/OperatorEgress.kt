package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.registry.LoggingAiAppLauncher

/**
 * Where the operator tools log what may leave the phone, at the moment they hand it over: a
 * shell or Termux command (which can use the internet), a link or address opened in another app.
 * Web page reads are logged by the web reader itself, request by request.
 */
fun interface OperatorEgress {
    /** Logs one hand-over by [context]'s agent: [title] and [detail] for the owner, [egress] for the data-transfer views. */
    suspend fun handedOver(context: ToolContext, title: String, detail: String, egress: EgressInfo)

    /** Destinations and constructors. */
    companion object {
        /** Logs nothing (tests that do not look at the log). */
        val NONE = OperatorEgress { _, _, _, _ -> }

        /** The destination of a command that can reach the network: not known in advance. */
        const val ANY_HOST = "any address the command connects to"

        /** [egress] for a hand-over to the app [packageName]. */
        fun toApp(packageName: String, purpose: String) =
            EgressInfo(destinationHost = LoggingAiAppLauncher.APP_DESTINATION_PREFIX + packageName, locality = Locality.CLOUD, purpose = purpose)

        /** [egress] for something sent to [host], or to an unknown address when null. */
        fun toHost(host: String?, purpose: String) = EgressInfo(destinationHost = host ?: ANY_HOST, locality = Locality.CLOUD, purpose = purpose)

        /** Writes each hand-over as a DATA_EGRESS entry of [activity]. A log that cannot be written never fails the call. */
        fun logTo(activity: ActivityRecorder) = OperatorEgress { context, title, detail, egress ->
            try {
                activity.record(
                    ActivityType.DATA_EGRESS, title, detail, agentId = context.agent.id, taskId = context.taskId,
                    rootTaskId = context.rootTaskId, egress = egress,
                )
            } catch (e: Exception) {
                rethrowIfCancelled(e)
            }
        }
    }
}
