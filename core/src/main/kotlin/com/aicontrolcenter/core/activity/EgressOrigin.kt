package com.aicontrolcenter.core.activity

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Who a request that leaves the phone is made for: the agent, task and objective of the tool
 * call that makes it. A tool puts it into the coroutine context of a gateway call whose requests
 * the gateway reports one by one (the web page reader reports each request it writes, on its
 * own threads); the gateway reads it when the call starts and hands it back with every report,
 * so each DATA_EGRESS entry can name the agent and the task.
 */
class EgressOrigin(
    val agentId: AgentId?,
    val taskId: TaskId?,
    val rootTaskId: TaskId?,
) : AbstractCoroutineContextElement(Key) {
    /** The context key. */
    companion object Key : CoroutineContext.Key<EgressOrigin> {
        /** The origin of the current coroutine, or null outside a tool call that set one. */
        suspend fun current(): EgressOrigin? = currentCoroutineContext()[Key]
    }

    override fun toString(): String = "EgressOrigin(agent=${agentId?.value}, task=${taskId?.value}, root=${rootTaskId?.value})"
}
