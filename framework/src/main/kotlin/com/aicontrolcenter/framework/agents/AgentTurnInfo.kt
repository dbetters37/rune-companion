package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What the runner knows about the turn a tool call belongs to: the registered model, the model
 * name the provider last reported and the conversation, when there is one.
 *
 * [com.aicontrolcenter.core.tools.ToolContext] (frozen in :core) carries only the agent and the
 * task, but a tool that records provenance, such as memory saving, needs the model too. The
 * runner therefore puts this element into the coroutine context of every tool call, where a
 * tool reads it with [currentAgentTurn]. Tools called outside a runner simply find nothing.
 */
class AgentTurnInfo(
    val modelId: ModelId,
    /** Null until the first reply of the turn arrived; tools run only after one, so it is normally set. */
    val modelName: String?,
    val conversationId: ConversationId?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentTurnInfo>
}

/** The turn the current tool call belongs to, or null outside an agent run. */
suspend fun currentAgentTurn(): AgentTurnInfo? = currentCoroutineContext()[AgentTurnInfo]
