package com.aicontrolcenter.app.operator

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.EgressOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Writes one activity entry for every web request that left the phone for an agent's page
 * read, including requests whose read then failed or whose redirect was refused (those results
 * cannot list the hosts reached). The web reader calls [sent] on its network thread, with the
 * [EgressOrigin] of the tool call that read the page, so the entry names the agent, the task and
 * the objective; the entry is written in [scope], so the read never waits for the log.
 */
class EgressLog(private val activity: ActivityRecorder, private val scope: CoroutineScope) {
    fun sent(egress: EgressInfo, origin: EgressOrigin?) {
        scope.launch {
            try {
                activity.record(
                    ActivityType.DATA_EGRESS,
                    "Web page requested from ${egress.destinationHost}",
                    "An agent read a web page. The site received the page address and standard request headers, " +
                        "with no cookies or logins.",
                    agentId = origin?.agentId,
                    taskId = origin?.taskId,
                    rootTaskId = origin?.rootTaskId,
                    egress = egress,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The request already left; a log that cannot be written must not fail the read.
            }
        }
    }
}
