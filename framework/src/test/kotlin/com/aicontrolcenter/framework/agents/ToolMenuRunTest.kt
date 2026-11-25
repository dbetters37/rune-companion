package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.llm.TokenEstimator
import com.aicontrolcenter.framework.operator.OperatorFixture
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecution
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lean requests in a run (Phase 6, WP15, rule 28 and rule 2): the Operator is offered its
 * always-on tools and `tools_open`; opening a family adds that family's tools for the rest of
 * the run; a call to an enabled tool that is not offered yet runs as usual and opens its family;
 * nothing the model writes gives the agent a tool it does not have, and every call is authorised
 * exactly as before.
 *
 * The workspace tools read and write a real folder on Dispatchers.IO, so these runs use real time
 * (a virtual clock would jump to the run's time limit while a file is being written).
 */
class ToolMenuRunTest {
    private val world = TestWorld()
    private val operatorTools: OperatorFixture = registerOperatorTools(world)

    /** An agent made from the Operator template (all its tools unless [toolIds] says otherwise), with the phone operator switched on. */
    private suspend fun operator(
        toolIds: Set<ToolId> = AgentTemplates.byId(AgentTemplates.OPERATOR)!!.draft.toolIds,
        name: String = "Operator",
    ): AgentProfile {
        world.settings.update { it.copy(operatorEnabled = true) }
        val created = world.agentManager.create(AgentTemplates.byId(AgentTemplates.OPERATOR)!!.draft.copy(toolIds = toolIds, name = name))
        return world.agents.get(created.id)!!
    }

    private fun call(id: String, name: String, vararg args: Pair<String, Any?>) = ToolCallRequest(id, name, json(*args))

    private fun open(id: String, family: String) = ToolCallRequest(id, ToolMenu.FUNCTION_NAME, family(family))

    /** Keeps the executions a run reports. */
    private class Executions : AgentRunListener {
        val all = ArrayList<ToolExecution>()

        override suspend fun onToolFinished(execution: ToolExecution) {
            all += execution
        }
    }

    @Test
    fun `a scripted model opens the workspace family and then writes a file`() = runBlocking<Unit> {
        val agent = operator()
        world.grants.insert(PermissionGrant(GrantId("grt_ws"), agent.id, OperatorCapabilities.WORKSPACE_WRITE, scope = GrantScope.ALWAYS, grantedAt = 1))
        val client = ScriptedLlmClient { request, index ->
            when (index) {
                0 -> ScriptStep.Calls(listOf(open("c1", "workspace")))
                1 -> ScriptStep.Calls(listOf(call("c2", "workspace_write", "path" to "notes/plan.md", "content" to "Buy milk.")))
                else -> ScriptStep.Reply("Written.")
            }
        }
        val model = world.serve(client)
        val listener = Executions()

        val result = world.runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("Write my plan."))), listener)

        assertEquals(RunEnd.COMPLETED, result.end, result.notice)
        assertEquals("Buy milk.", operatorTools.file("notes/plan.md").readText())
        val (first, second) = client.requests
        assertFalse("workspace_write" in namesIn(first), "not offered before the family is opened")
        assertEquals(listOf("clock_now", "memory_search", ToolMenu.FUNCTION_NAME), namesIn(first))
        val workspace = AgentTemplates.operatorToolIds.filter { ToolMenu.familyOf(it) == "workspace" }
            .map { world.toolRegistry.byId(it)!!.spec.functionName }.sorted()
        assertEquals(namesIn(first) + workspace, namesIn(second), "the family is appended after what was offered")
        val opened = resultOf(result.produced, "c1")
        assertTrue(opened.startsWith("Opened the workspace tools: workspace_append,"), opened)
        assertEquals(1, result.toolCalls, "opening a family is not a tool call")
        assertEquals(listOf(ToolMenu.SPEC, world.toolRegistry.byFunctionName("workspace_write")!!.spec), listener.all.map { it.spec })
        assertEquals(listOf(ToolCallEnd.OK, ToolCallEnd.OK), listener.all.map { it.end })
        val logged = world.events(ActivityType.TOOL_CALL).map { it.data!!["function"].toString().trim('"') }
        assertEquals(listOf("workspace_write"), logged, "opening a family touches nothing the executor logs")
    }

    @Test
    fun `a call to an enabled tool that is not offered yet runs as usual and opens its family`() = runBlocking<Unit> {
        val agent = operator()
        operatorTools.put("todo.txt", "milk")
        val client = ScriptedLlmClient { _, index ->
            if (index == 0) ScriptStep.Calls(listOf(call("c1", "workspace_list"))) else ScriptStep.Reply("One file.")
        }
        val model = world.serve(client)

        val result = world.runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("What is in the workspace?"))))

        assertEquals(RunEnd.COMPLETED, result.end)
        assertTrue("todo.txt" in resultOf(result.produced, "c1"), resultOf(result.produced, "c1"))
        assertEquals(1, result.toolCalls)
        assertTrue(world.events(ActivityType.TOOL_CALL).single().title.startsWith("Tool used:"), "run and logged by the executor as before")
        val (first, second) = client.requests
        assertFalse("workspace_list" in namesIn(first))
        assertTrue("workspace_list" in namesIn(second) && "workspace_write" in namesIn(second), "its family is offered from the next call")
    }

    @Test
    fun `tools_open never gives a family the agent does not have, and such a tool stays refused as before`() = runBlocking<Unit> {
        val withoutScreen = AgentTemplates.byId(AgentTemplates.OPERATOR)!!.draft.toolIds.filterNot { ToolMenu.familyOf(it) == "screen" }.toSet()
        val agent = operator(withoutScreen)
        val client = ScriptedLlmClient { _, index ->
            when (index) {
                0 -> ScriptStep.Calls(listOf(open("c1", "screen"), open("c2", "admin"), open("c3", "screen_read")))
                1 -> ScriptStep.Calls(listOf(call("c4", "screen_read")))
                else -> ScriptStep.Reply("I cannot see the screen.")
            }
        }
        val model = world.serve(client)

        val result = world.runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("What is on my screen?"))))

        assertEquals(RunEnd.COMPLETED, result.end)
        for (id in listOf("c1", "c2", "c3")) {
            val refusal = resultOf(result.produced, id)
            assertTrue(refusal.startsWith("There is no tool family"), refusal)
            assertFalse("screen" in refusal.substringAfter("Families you can open:"), refusal)
        }
        val refused = result.produced.mapNotNull { it.toolResult }.single { it.callId == "c4" }
        assertTrue(refused.isError)
        assertEquals(ToolCallEnd.DENIED.name, world.events(ActivityType.TOOL_CALL).single().data!!["outcome"].toString().trim('"'))
        assertTrue(operatorTools.screen.calls.isEmpty(), "the screen was never touched")
        val offered = client.requests.flatMap { namesIn(it) }.toSet()
        assertTrue(offered.none { it.startsWith("screen_") }, "no screen tool was ever offered: $offered")
        assertEquals(1, client.requests.map { namesIn(it) }.distinct().size, "the offer never changed")
        val menu = client.requests.first().tools.single { it.name == ToolMenu.FUNCTION_NAME }
        assertFalse("\n- screen:" in menu.description, menu.description)
    }

    @Test
    fun `a tool reached through the menu is authorised exactly as without one`() = runBlocking<Unit> {
        // The same call, by an agent offered a menu and by one offered its only tool directly: the broker asks the same question.
        suspend fun requestRaisedBy(agent: AgentProfile, opens: Boolean): OwnerRequest {
            val client = ScriptedLlmClient { _, index ->
                when {
                    opens && index == 0 -> ScriptStep.Calls(listOf(open("c1", "workspace")))
                    index <= (if (opens) 1 else 0) -> ScriptStep.Calls(listOf(call("c2", "workspace_write", "path" to "a.txt", "content" to "A")))
                    else -> ScriptStep.Reply("Asked.")
                }
            }
            val model = world.serve(client)
            val run = async { world.runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("Write a.txt")))) }
            val pending = world.requests.observePending().first { it.isNotEmpty() }.single()
            world.ownerRequests.deny(pending.id)
            assertEquals(RunEnd.COMPLETED, run.await().end)
            assertTrue(namesIn(client.requests.first()).contains(ToolMenu.FUNCTION_NAME) == opens)
            return pending
        }
        val withMenu = requestRaisedBy(operator(), opens = true)
        val direct = requestRaisedBy(operator(setOf(ToolId("workspace.write")), name = "Writer of files"), opens = false)

        assertEquals(OwnerRequestKind.PERMISSION, withMenu.kind)
        for (field in listOf<(OwnerRequest) -> Any?>({ it.kind }, { it.capability }, { it.constraint }, { it.risk }, { it.summary }, { it.toolId }, { it.details })) {
            assertEquals(field(direct), field(withMenu))
        }
        assertFalse(operatorTools.file("a.txt").exists(), "refused by the owner both times")
    }

    @Test
    fun `families the conversation already used are open from the first call of the next turn`() = runBlocking<Unit> {
        val agent = operator()
        val earlier = listOf(
            ChatMessage.user("Write my plan."),
            ChatMessage.assistant("", listOf(open("c1", "folders"))),
            ChatMessage.toolResult("c1", ToolMenu.FUNCTION_NAME, "Opened the folders tools."),
            ChatMessage.assistant("", listOf(call("c2", "workspace_write", "path" to "p.md", "content" to "x"))),
            ChatMessage.toolResult("c2", "workspace_write", "Written."),
            // A tool the agent does not have, in the history: it opens nothing.
            ChatMessage.assistant("", listOf(call("c3", "admin_wipe"))),
            ChatMessage.toolResult("c3", "admin_wipe", "There is no tool called \"admin_wipe\".", isError = true),
            ChatMessage.assistant("Done."),
            ChatMessage.user("Now read it back."),
        )
        val client = ScriptedLlmClient.replies("It says x.")
        val model = world.serve(client)

        world.runner.run(AgentRunRequest(agent, model.id, earlier))

        val names = namesIn(client.requests.single())
        val folders = names.filter { it.startsWith("folders_") }
        val workspace = names.filter { it.startsWith("workspace_") }
        assertTrue(folders.isNotEmpty() && workspace.isNotEmpty(), names.toString())
        assertEquals(listOf("clock_now", "memory_search", ToolMenu.FUNCTION_NAME) + folders + workspace, names, "in the order they were first used")
        assertFalse(names.any { it.startsWith("screen_") || it.startsWith("apps_") })
    }

    @Test
    fun `the estimate is the first request as it is sent, and every call names the chat it belongs to`() = runBlocking<Unit> {
        val agent = operator()
        val client = ScriptedLlmClient.replies("Hello.")
        val model = world.serve(client)
        val gateway = ClientGateway(client)
        val runner = AgentRunner(gateway, world.toolRegistry, world.executor, world.agents, world.settings, world.monitor, world.clock, timeSource = world.time)
        val request = AgentRunRequest(
            agent, model.id, listOf(ChatMessage.user("Hi.")), conversationId = ConversationId("cnv_1"),
            taskId = TaskId("tsk_2"), rootTaskId = TaskId("tsk_1"), purpose = "a chat",
        )

        runner.run(request)

        val sent = client.requests.single()
        assertEquals(
            TokenEstimator.tokens(sent.messages, sent.tools),
            runner.estimatePromptTokens(request),
        )
        val context = gateway.contexts.single()
        assertEquals(ConversationId("cnv_1"), context.conversationId)
        assertEquals(agent.id, context.agentId)
        assertEquals(TaskId("tsk_2"), context.taskId)
        assertEquals(TaskId("tsk_1"), context.rootTaskId)
    }

    @Test
    fun `without a menu, tools_open is a tool like any unknown one and opens nothing`() = runBlocking<Unit> {
        val model = world.serve(
            ScriptedLlmClient { _, index -> if (index == 0) ScriptStep.Calls(listOf(open("c1", "workspace"))) else ScriptStep.Reply("Ok.") },
        )
        val agent = world.agent(model, setOf(ToolId("clock.now"), ToolId("workspace.read")))

        val result = world.runner.run(AgentRunRequest(agent, model.id, listOf(ChatMessage.user("Hi."))))

        val answer = resultOf(result.produced, "c1")
        assertTrue(answer.startsWith("There is no tool called \"tools_open\""), answer)
        assertEquals(ToolCallEnd.REJECTED.name, world.events(ActivityType.TOOL_CALL).single().data!!["outcome"].toString().trim('"'))
        assertNotNull(result.produced.firstOrNull { it.role == ChatRole.TOOL })
    }
}
