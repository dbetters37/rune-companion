package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Wave-2a merge (WP15's hand-off item 3): since lean requests, a task's model calls carry the task transcript's
 * conversation id as well as the task and the objective. The usage source is decided by the task first, then the
 * conversation: such a call is OBJECTIVE work, filed under the task and the objective, never under a chat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageSourceTest {
    private val ask = ChatRequest(messages = listOf(ChatMessage.user("Find three note apps.")))

    @Test
    fun `a task's call that also names its transcript is objective work, never chat`() = runTest {
        val metered = MeteredWorld(this)
        val model = metered.cloud(ScriptedLlmClient.replies("Found three."))
        val context = CallContext(
            agentId = AgentId("agt_1"), taskId = TaskId("tsk_2"), rootTaskId = TaskId("tsk_1"), conversationId = ConversationId("cnv_task"),
        )

        metered.gateway.chat(model.id, ask, context)

        assertEquals(setOf(UsageSource.OBJECTIVE), metered.usage.hourRows.keys.map { it[3] }.toSet())
        assertEquals(1, metered.usage.workUsage(WorkKind.TASK, "tsk_2").single().calls)
        assertEquals(1, metered.usage.workUsage(WorkKind.OBJECTIVE, "tsk_1").single().calls)
        assertEquals(emptyList(), metered.usage.workUsage(WorkKind.CHAT, "cnv_task"))
    }

    @Test
    fun `an objective's call without a task is objective work too, and only a call with a conversation alone is chat`() = runTest {
        val metered = MeteredWorld(this)
        val model = metered.cloud(ScriptedLlmClient.replies("Planned.", "Hello."))
        metered.gateway.chat(model.id, ask, CallContext(agentId = AgentId("agt_1"), rootTaskId = TaskId("tsk_1"), conversationId = ConversationId("cnv_plan")))
        assertEquals(setOf(UsageSource.OBJECTIVE), metered.usage.hourRows.keys.map { it[3] }.toSet())

        metered.gateway.chat(model.id, ask, CallContext(agentId = AgentId("agt_1"), conversationId = ConversationId("cnv_chat")))

        assertEquals(setOf(UsageSource.OBJECTIVE, UsageSource.CHAT), metered.usage.hourRows.keys.map { it[3] }.toSet())
        assertEquals(emptyList(), metered.usage.workUsage(WorkKind.CHAT, "cnv_plan"))
        assertEquals(1, metered.usage.workUsage(WorkKind.CHAT, "cnv_chat").single().calls)
    }
}
