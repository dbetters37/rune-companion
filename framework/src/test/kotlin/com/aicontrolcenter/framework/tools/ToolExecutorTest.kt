package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolAuthorizer
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.LambdaTool
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.llm.EchoGuard
import com.aicontrolcenter.framework.llm.PromptedToolsClient
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutorTest {
    private val world = TestWorld(toolLimits = ToolLimits(timeoutMs = 5_000, maxOutputChars = 100))

    private fun args(vararg members: Pair<String, JsonElement>) = JsonObject(mapOf(*members))

    private fun call(tool: Tool, arguments: JsonObject = args()) = ToolCallRequest("call_1", tool.spec.functionName, arguments)

    /** A context whose agent has exactly [tools] switched on. */
    private suspend fun context(vararg tools: Tool) = ToolContext(world.agent(model = null, tools = tools.map { it.spec.id }.toSet()))

    private fun toolEvents() = world.events(ActivityType.TOOL_CALL)

    @Test
    fun `an allowed call runs the tool and is logged without the values of its arguments`() = runTest {
        val echo = world.register(LambdaTool("echo") { ToolOutcome.success("You said: " + it.getValue("text").jsonPrimitive.content) })
        val context = context(echo)

        val execution = world.executor.execute(call(echo, args("text" to JsonPrimitive("private words"))), context)

        assertEquals(ToolCallEnd.OK, execution.end)
        assertEquals(ToolOutcome.success("You said: private words"), execution.outcome)
        assertEquals(echo.spec, execution.spec)
        val event = toolEvents().single()
        assertEquals("Tool used: Test echo", event.title)
        assertEquals(Severity.INFO, event.severity)
        assertEquals(context.agent.id, event.agentId)
        val data = event.data!!
        assertEquals("test_echo", data.getValue("function").jsonPrimitive.content)
        assertEquals("test.echo", data.getValue("toolId").jsonPrimitive.content)
        assertEquals("OK", data.getValue("outcome").jsonPrimitive.content)
        assertEquals("SAFE", data.getValue("risk").jsonPrimitive.content)
        assertEquals("text", data.getValue("argumentNames").jsonPrimitive.content)
        assertEquals("You said: private words".length, data.getValue("outputChars").jsonPrimitive.int)
        assertFalse("private words" in world.logText(), "what an agent passes to or gets from a tool stays out of the log")
    }

    @Test
    fun `a tool that does not exist is answered with the tools that do`() = runTest {
        val context = ToolContext(world.agent(model = null, tools = setOf(ClockTool.ID, TeamListTool.ID)))

        val execution = world.executor.execute(ToolCallRequest("call_1", "delete_everything", args()), context)

        assertEquals(ToolCallEnd.REJECTED, execution.end)
        assertNull(execution.spec)
        assertFalse(execution.outcome.ok)
        assertTrue("delete_everything" in execution.outcome.content)
        assertTrue("clock_now, team_list_agents" in execution.outcome.content)
        assertEquals("Tool call rejected: \"delete_everything\"", toolEvents().single().title)
    }

    @Test
    fun `arguments that do not fit the schema are explained and the tool is not run`() = runTest {
        val strict = world.register(LambdaTool("strict", required = listOf("text")) { ToolOutcome.success("ran") })
        val context = context(strict)

        val missing = world.executor.execute(call(strict), context)
        val wrongType = world.executor.execute(call(strict, args("text" to JsonPrimitive(5))), context)
        val unknown = world.executor.execute(call(strict, args("text" to JsonPrimitive("a"), "extra" to JsonPrimitive(true))), context)
        val fitting = world.executor.execute(call(strict, args("text" to JsonPrimitive("a"), "count" to JsonPrimitive(2))), context)

        assertEquals(List(3) { ToolCallEnd.REJECTED }, listOf(missing, wrongType, unknown).map { it.end })
        assertTrue("required argument \"text\" is missing" in missing.outcome.content)
        assertTrue("\"text\" must be a string" in wrongType.outcome.content)
        assertTrue("no argument called \"extra\"" in unknown.outcome.content)
        assertEquals(ToolCallEnd.OK, fitting.end)
        assertEquals(1, strict.calls.size, "only the well-formed call reached the tool")
        assertEquals(Severity.WARNING, toolEvents().first().severity)
    }

    @Test
    fun `a call that is not authorised is denied with the reason and never runs`() = runTest {
        val tool = world.register(LambdaTool("quiet") { ToolOutcome.success("ran") })
        val risky = world.register(LambdaTool("wipe", assessed = RiskLevel.CONSEQUENTIAL) { ToolOutcome.success("ran") })

        val notSwitchedOn = world.executor.execute(call(tool), context())
        // A consequential call waits for the owner's approval; the owner says no.
        val riskyContext = context(risky)
        val pending = async { world.executor.execute(call(risky), riskyContext) }
        runCurrent()
        world.ownerRequests.deny(world.ownerRequests.pending.first().single().id)
        val consequential = pending.await()

        assertEquals(listOf(ToolCallEnd.DENIED, ToolCallEnd.DENIED), listOf(notSwitchedOn.end, consequential.end))
        assertTrue("not switched on" in notSwitchedOn.outcome.content)
        assertTrue("did not approve" in consequential.outcome.content, consequential.outcome.content)
        assertTrue(tool.calls.isEmpty() && risky.calls.isEmpty())
        assertEquals(listOf("Tool call denied: Test quiet", "Tool call denied: Test wipe"), toolEvents().map { it.title })
        assertEquals("CONSEQUENTIAL", toolEvents().last().data!!.getValue("risk").jsonPrimitive.content, "the risk of this call, not of a typical one")
    }

    @Test
    fun `a tool the agent does not have is refused before its arguments are looked at or its assessment runs`() = runTest {
        val assessed = ArrayList<JsonObject>()
        val strict = world.register(object : Tool {
            private val shape = LambdaTool("strict", required = listOf("text")) { ToolOutcome.success("ran") }
            override val spec = shape.spec

            override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
                assessed += arguments
                return shape.assess(arguments, context)
            }

            override suspend fun execute(arguments: JsonObject, context: ToolContext) = shape.execute(arguments, context)
        })
        val without = context()

        val badArguments = world.executor.execute(call(strict, args("extra" to JsonPrimitive(1))), without)
        val unreadable = world.executor.execute(call(strict).copy(argumentsError = "not JSON"), without)
        val fine = world.executor.execute(call(strict, args("text" to JsonPrimitive("a"))), without)

        val authorizer = DefaultToolAuthorizer(world.settings, world.capabilities)
        val expected = (authorizer.authorize(strict.spec, ActionAssessment(RiskLevel.SAFE, ""), without) as Authorization.Denied).reason
        for (execution in listOf(badArguments, unreadable, fine)) {
            assertEquals(ToolCallEnd.DENIED, execution.end)
            assertEquals(expected, execution.outcome.content, "the same words as the authorizer, and nothing about the tool's arguments")
        }
        assertTrue(assessed.isEmpty(), "no assessment ran for a tool the agent does not have")
        assertEquals(List(3) { "Tool call denied: Test strict" }, toolEvents().map { it.title })
    }

    @Test
    fun `a call whose arguments could not be read is answered with the tool and the reason, and never runs`() = runTest {
        val echo = world.register(LambdaTool("echo") { ToolOutcome.success("ran") })

        val execution = world.executor.execute(call(echo).copy(argumentsError = "The arguments were not valid JSON: unexpected '}' at 14."), context(echo))

        assertEquals(ToolCallEnd.REJECTED, execution.end)
        assertFalse(execution.outcome.ok)
        val text = execution.outcome.content
        assertTrue("test_echo" in text && "unexpected '}' at 14" in text && "JSON object" in text, text)
        assertTrue(echo.calls.isEmpty())
        assertEquals("Tool call rejected: Test echo", toolEvents().single().title)
    }

    @Test
    fun `a call the model repeated from a tool result is answered as such and never runs`() = runTest {
        val echo = world.register(LambdaTool("echo") { ToolOutcome.success("ran") })

        val execution = world.executor.execute(call(echo).copy(argumentsError = EchoGuard.REJECTION), context(echo))

        assertEquals(ToolCallEnd.REJECTED, execution.end)
        val text = execution.outcome.content
        assertTrue("test_echo" in text && "repeated content from a tool result" in text && "never choose an action" in text, text)
        assertFalse("could not be read" in text, "it is not about unreadable arguments: $text")
        assertTrue(echo.calls.isEmpty())
    }

    @Test
    fun `a call without arguments refused as an echo is told how to write it if meant, and written so it runs`() = runTest {
        val clock = world.register(LambdaTool("clock") { ToolOutcome.success("noon") })
        val name = clock.spec.functionName
        val tools = listOf(ToolDefinition(name, "Tells the time.", buildJsonObject { put("type", "object") }))
        // A tool listing that happens to hold the call {"name": "test_clock"}, written without arguments.
        val history = listOf(
            ChatMessage.user("What time is it?"),
            ChatMessage.assistant("", listOf(ToolCallRequest("c0", "list_tools", JsonObject(emptyMap())))),
            ChatMessage.toolResult("c0", "list_tools", "Tools: [{\"name\": \"$name\"}]"),
        )
        val replies = listOf("<tool_call>{\"name\": \"$name\"}</tool_call>", "<tool_call>{\"name\": \"$name\", \"arguments\": {}}</tool_call>")
        val model = PromptedToolsClient(ScriptedLlmClient("tiny") { _, index -> ScriptStep.Reply(replies[index]) })

        val refused = model.chat(ChatRequest(messages = history, tools = tools)).message.toolCalls.single()
        assertEquals(EchoGuard.REJECTION_WITHOUT_ARGUMENTS, refused.argumentsError)
        val answer = world.executor.execute(refused, context(clock))
        assertEquals(ToolCallEnd.REJECTED, answer.end)
        val text = answer.outcome.content
        assertTrue("repeated content from a tool result" in text && "<tool_call>" in text && "\"arguments\": {}" in text, text)
        assertTrue(clock.calls.isEmpty())

        // The answer is itself a tool result now; the form it describes is still not an echo of it.
        val next = history + ChatMessage.assistant("", listOf(refused)) + ChatMessage.toolResult(refused.id, name, text, isError = true)
        val meant = model.chat(ChatRequest(messages = next, tools = tools)).message.toolCalls.single()
        assertNull(meant.argumentsError)
        assertEquals(ToolCallEnd.OK, world.executor.execute(meant, context(clock)).end)
        assertEquals(1, clock.calls.size)

        // A refused call with arguments is offered no such way: its arguments came from the tool result.
        val withArguments = world.executor.execute(call(clock).copy(argumentsError = EchoGuard.REJECTION), context(clock)).outcome.content
        assertFalse("<tool_call>" in withArguments, withArguments)
    }

    @Test
    fun `an authorizer that fails denies the call instead of letting it through`() = runTest {
        val tool = world.register(LambdaTool("quiet") { ToolOutcome.success("ran") })
        val brokenAuthorizer = ToolAuthorizer { _, _, _ -> error("permission store unavailable") }
        val executor = ToolExecutor(world.toolRegistry, brokenAuthorizer, world.activity, world.clock)

        val execution = executor.execute(call(tool), context(tool))

        assertEquals(ToolCallEnd.DENIED, execution.end)
        assertTrue("could not be checked" in execution.outcome.content)
        assertTrue(tool.calls.isEmpty())
    }

    @Test
    fun `a tool that throws or reports failure becomes a failed outcome the model can read`() = runTest {
        val broken = world.register(LambdaTool("broken") { throw IllegalStateException("disk full") })
        val unhappy = world.register(LambdaTool("unhappy") { ToolOutcome.failure("Nothing matches that search.") })
        val context = context(broken, unhappy)

        val thrown = world.executor.execute(call(broken), context)
        val reported = world.executor.execute(call(unhappy), context)

        assertEquals(ToolCallEnd.FAILED, thrown.end)
        assertFalse(thrown.outcome.ok)
        assertTrue("test_broken failed" in thrown.outcome.content && "disk full" in thrown.outcome.content)
        assertEquals(ToolCallEnd.FAILED, reported.end)
        assertEquals(ToolOutcome.failure("Nothing matches that search."), reported.outcome)
        assertEquals(listOf("Tool failed: Test broken", "Tool failed: Test unhappy"), toolEvents().map { it.title })
        assertTrue(toolEvents().all { it.severity == Severity.WARNING })
    }

    @Test
    fun `a tool that runs too long is stopped at the time limit`() = runTest {
        world.time.source = testTimeSource
        var finished = false
        val slow = world.register(
            LambdaTool("slow") {
                delay(60_000)
                finished = true
                ToolOutcome.success("done")
            },
        )

        val execution = world.executor.execute(call(slow), context(slow))

        assertEquals(ToolCallEnd.TIMED_OUT, execution.end)
        assertFalse(execution.outcome.ok)
        assertTrue("did not finish within 5 seconds" in execution.outcome.content)
        assertEquals(5_000, currentTime)
        assertFalse(finished)
        assertEquals("Tool timed out: Test slow", toolEvents().single().title)
    }

    @Test
    fun `a tool that bounds its own running time is not cut at the executor's time limit`() = runTest {
        val inner = LambdaTool("waits_for_agent") {
            delay(60_000)
            ToolOutcome.success("the teammate finished")
        }
        val waiting = object : SelfLimitingTool {
            override val spec = inner.spec

            override suspend fun execute(arguments: JsonObject, context: ToolContext) = inner.execute(arguments, context)
        }
        world.register(waiting)

        val execution = world.executor.execute(call(waiting), context(waiting))

        assertEquals(ToolCallEnd.OK, execution.end)
        assertEquals("the teammate finished", execution.outcome.content)
        assertEquals(60_000, currentTime)
    }

    @Test
    fun `oversized output is cut at the limit and says so`() = runTest {
        val verbose = world.register(LambdaTool("verbose") { ToolOutcome.success("x".repeat(250)) })
        val emoji = world.register(LambdaTool("emoji") { ToolOutcome.success("x".repeat(99) + "😀" + "y".repeat(50)) })
        val brief = world.register(LambdaTool("brief") { ToolOutcome.success("x".repeat(100)) })
        val context = context(verbose, emoji, brief)

        val cut = world.executor.execute(call(verbose), context).outcome
        val cutBeforePair = world.executor.execute(call(emoji), context).outcome
        val whole = world.executor.execute(call(brief), context).outcome

        assertTrue(cut.ok)
        assertEquals("x".repeat(100) + "\n\n[Output truncated: the first 100 of 250 characters are shown.]", cut.content)
        assertTrue(cutBeforePair.content.startsWith("x".repeat(99) + "\n\n[Output truncated: the first 99 of"), "a surrogate pair is never split")
        assertEquals("x".repeat(100), whole.content)
    }

    @Test
    fun `a call that is cancelled while running is still logged`() = runTest {
        val hanging = world.register(LambdaTool("hanging") { awaitCancellation() })
        val context = context(hanging)

        val job = launch { world.executor.execute(call(hanging), context) }
        runCurrent()
        job.cancelAndJoin()

        val event = toolEvents().single()
        assertEquals("Tool call cancelled: Test hanging", event.title)
        assertEquals("CANCELLED", event.data!!.getValue("outcome").jsonPrimitive.content)
        assertEquals(1, hanging.calls.size)
    }
}
