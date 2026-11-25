package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6, the phone operator's rough edges: D5 (a screen call right after opening an app waits
 * for it), D6 (a window Android does not let the app read is named as such), D8 (a private
 * address is refused before anyone is asked), and a routine's screen call refused before its tool
 * looks at the screen (F12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneOperatorFixesTest {
    private val notes = "com.example.notes"

    // ------------------------------------------------------------------ D5

    @Test
    fun `a screen call right after the agent opened an app waits for it to come to the front`() = runTest {
        val f = OperatorFixture()
        f.authorise(notes, "Notes")
        f.apps.installed += InstalledAppInfo(notes, "Notes")
        // This app is still in front while Android brings Notes up.
        f.screen.screen = FakeScreen(OperatorFixture.OWN, "AI Control Center")
        assertTrue(f.tool("apps.open").let { tool -> tool.execute(json("package" to notes), f.context, tool.assess(json("package" to notes), f.context)) }.ok)
        launch {
            delay(1_200)
            f.screen.screen = FakeScreen(notes, "Notes", listOf(FakeControl(text = "New note")))
        }
        val read = f.tool("screen.read").assess(json(), f.context)
        assertEquals("Read the screen of \"Notes\" ($notes)", read.summary)
        assertEquals(CapabilityConstraint.App(notes), read.constraint)
        assertTrue(testScheduler.currentTime in 1_200..1_500, "it waited about as long as the app took: ${testScheduler.currentTime}")
    }

    @Test
    fun `an agent that opened nothing is not kept waiting, and the wait is bounded`() = runTest {
        val f = OperatorFixture()
        f.authorise(notes, "Notes")
        f.apps.installed += InstalledAppInfo(notes, "Notes")
        f.screen.screen = FakeScreen(OperatorFixture.OWN, "AI Control Center")
        // Another agent opened Notes: this one is told at once what is in front.
        val other = ToolContext(Samples.agent(id = "agt_other", name = "Other"))
        f.tool("apps.open").let { it.execute(json("package" to notes), other, it.assess(json("package" to notes), other)) }
        val now = f.tool("screen.read").assess(json(), f.context)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(CapabilityConstraint.App(OperatorFixture.OWN), now.constraint, "this app is in front, and it is protected")
        // The agent that opened it waits at most screenOpenWaitMs when Notes never shows.
        f.tool("screen.read").assess(json(), other)
        assertTrue(testScheduler.currentTime in OperatorLimits().screenOpenWaitMs..OperatorLimits().screenOpenWaitMs + OperatorLimits().screenPollMs)
    }

    @Test
    fun `a link or a page opened by the agent is waited for until another app is in front`() = runTest {
        val f = OperatorFixture()
        f.authorise("com.android.chrome", "Chrome")
        f.screen.screen = FakeScreen(OperatorFixture.OWN, "AI Control Center")
        val open = f.tool("browser.open")
        assertTrue(open.execute(json("url" to "https://example.com/"), f.context, open.assess(json("url" to "https://example.com/"), f.context)).ok)
        launch {
            delay(700)
            f.screen.screen = FakeScreen("com.android.chrome", "Example")
        }
        assertEquals(CapabilityConstraint.App("com.android.chrome"), f.tool("screen.read").assess(json(), f.context).constraint)
        assertTrue(testScheduler.currentTime in 700..1_000)
    }

    // ------------------------------------------------------------------ D6

    @Test
    fun `a window Android does not let the app read is named as such and nothing is read`() = runTest {
        val f = OperatorFixture(FakeScreen(notes, "Notes"))
        f.authorise(notes, "Notes")
        f.screen.frontHidden = true
        assertEquals(ScreenTools.HIDDEN_WINDOW, f.rejected("screen.read"))
        assertTrue(f.screen.inspected.isEmpty() && f.screen.calls.isEmpty())
    }

    // ------------------------------------------------------------------ D8

    @Test
    fun `a private or local address is refused before the owner is asked or anything is read`() = runTest {
        val b = BrokerFixture(this).setUp()
        val f = OperatorFixture()
        val tool = f.tool("web.read_page")
        val registry = DefaultToolRegistry().also { it.register(tool) }
        val executor = ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = testTimeSource, tasks = b.tasks)
        val agent = b.agent.copy(toolIds = setOf(tool.spec.id))
        b.agents.upsert(agent)
        for (url in listOf(
            "http://192.168.1.10/", "http://router.local/", "http://2130706433/", "http://0177.0.0.1/", "http://[::ffff:127.0.0.1]/",
            "http://localhost./", "http://router/", "http://[::127.0.0.1]/", "http://[2002:7f00:1::]/", "http://10.0.0.1/admin",
        )) {
            val done = executor.execute(ToolCallRequest("c", "web_read_page", json("url" to url)), b.context(who = agent))
            assertEquals(ToolCallEnd.FAILED, done.end, url)
            assertTrue("The page was not read: " in done.outcome.content, "$url: ${done.outcome.content}")
        }
        assertTrue(b.allRequests().isEmpty(), "the owner was never asked")
        assertTrue(f.web.requests.isEmpty(), "nothing was read")
        // A public name still goes on to the broker as before.
        f.web.page("https://example.com/", "<p>Hello</p>")
        b.grants.insert(PermissionGrant(GrantId("grt_w"), agent.id, OperatorCapabilities.WEB_READ, CapabilityConstraint.None, GrantScope.ALWAYS, grantedAt = 1))
        assertTrue(executor.execute(ToolCallRequest("c", "web_read_page", json("url" to "https://example.com/")), b.context(who = agent)).outcome.ok)
    }

    // ------------------------------------------------------------------ F12: refused before the tool looks

    @Test
    fun `a routine's screen call without the owner's tick is refused before the window is read`() = runTest {
        val b = BrokerFixture(this).setUp()
        val f = OperatorFixture(FakeScreen(notes, "Notes", listOf(FakeControl(text = "Send"))))
        f.authorise(notes, "Notes")
        val tool = f.tool("screen.read")
        val registry = DefaultToolRegistry().also { it.register(tool) }
        val executor = ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = testTimeSource, tasks = b.tasks)
        val agent = b.agent.copy(toolIds = setOf(tool.spec.id))
        b.agents.upsert(agent)
        b.tasks.upsert(
            TaskRecord(
                id = TaskId("tsk_routine"), rootId = TaskId("tsk_routine"), kind = TaskKind.OBJECTIVE, title = "Every morning", instructions = "Do it.",
                requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = START, updatedAt = START,
                options = ObjectiveOptions(screenActionsAllowed = false),
            ),
        )
        val context = ToolContext(agent, TaskId("tsk_w"), TaskId("tsk_routine"))
        val done = executor.execute(ToolCallRequest("c", "screen_read", json()), context)
        assertEquals(ToolCallEnd.DENIED, done.end)
        assertEquals(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED, done.outcome.content)
        assertTrue(f.screen.inspected.isEmpty() && f.screen.calls.isEmpty(), "not even the window in front was looked at")
        assertTrue(b.allRequests().isEmpty())
    }
}
