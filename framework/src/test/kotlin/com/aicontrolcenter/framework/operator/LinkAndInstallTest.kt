package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.AppActions
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.core.tasks.withPausableTimeout
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolAuthorizer
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.SelfLimitingTool
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.AppCall
import com.aicontrolcenter.testkit.FakeClock
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.SequentialIdGenerator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Links other apps act on, and installs that wait for the owner on Android's own confirmation. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkAndInstallTest {
    private val f = OperatorFixture()

    @Test
    fun `a link that is not a web page or a map asks every time and names the app that opens it`() = runTest {
        f.apps.handledSchemes += listOf("upi", "skype", "zoomus", "sip", "whatsapp")
        f.apps.linkApps["upi"] = listOf(InstalledAppInfo("com.example.pay", "Pay"))
        for (link in listOf("skype:echo123?call", "zoomus://zoom.us/join?confno=123456789", "sip:alice@example.org", "upi://pay?pa=x@bank&am=500", "whatsapp://send?phone=15550100&text=hi")) {
            val a = f.assess("apps.open_link", "link" to link)
            assertRisk(RiskLevel.CONSEQUENTIAL, a)
            assertTrue(a.details.any { it.startsWith("Opens in: ") }, a.preview)
        }
        val pay = f.assess("apps.open_link", "link" to "upi://pay?pa=x@bank&am=500")
        assertEquals("\"Pay\" (com.example.pay)", pay.dataGoesTo, "the payment app, not the link's \"host\" (pay)")
        assertTrue(pay.summary.endsWith("in \"Pay\" (com.example.pay)"), pay.summary)

        // Web addresses and map links stay as they were.
        assertRisk(RiskLevel.MODIFYING, f.assess("apps.open_link", "link" to "https://example.com"))
        assertRisk(RiskLevel.MODIFYING, f.assess("apps.open_link", "link" to "geo:48.85,2.35"))
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("apps.open_link", "link" to "market://details?id=com.example"))
    }

    @Test
    fun `a link whose app changed after approval is not opened, and an opened link is logged`() = runTest {
        f.apps.handledSchemes += "upi"
        f.apps.linkApps["upi"] = listOf(InstalledAppInfo("com.example.pay", "Pay"))
        val tool = f.tool("apps.open_link")
        val args = OperatorFixture.json("link" to "upi://pay?pa=x@bank&am=500")
        val assessment = tool.assess(args, f.context)
        f.apps.linkApps["upi"] = listOf(InstalledAppInfo("com.evil.pay", "Pay"))
        assertNotNull(assessment.prepared!!.changedSince())
        assertTrue(f.apps.calls.none { it is AppCall.OpenLink })

        f.apps.linkApps["upi"] = listOf(InstalledAppInfo("com.example.pay", "Pay"))
        assertTrue(f.ok("apps.open_link", "link" to "upi://pay?pa=x@bank&am=500").ok)
        assertEquals("app:com.example.pay", f.egress.last().second)
        f.ok("browser.open", "url" to "https://news.example.org/today")
        assertEquals("news.example.org", f.egress.last().second)
    }

    /** The owner reads Android's install dialog for [ownerMs], then confirms; the wait is the owner's, as the gateway marks it. */
    private class SlowConfirm(private val inner: AppActions, private val ownerMs: Long) : AppActions by inner {
        override suspend fun install(apk: WorkspaceFile, waitMs: Long): AppActionResult {
            WaitClock.pausedWhile { delay(ownerMs) }
            return AppActionResult.Installed("com.example.notes")
        }

        override suspend fun uninstall(packageName: String, waitMs: Long): AppActionResult {
            WaitClock.pausedWhile { delay(ownerMs) }
            return AppActionResult.Uninstalled(packageName)
        }
    }

    private fun TestScope.executorWith(apps: AppActions): ToolExecutor {
        val d = f.dependencies
        // File work on the test dispatcher, so virtual time moves only when the test is idle.
        val workspace = LocalWorkspace(FakeWorkspaceRoot(f.root), f.clock, io = EmptyCoroutineContext)
        val deps = OperatorToolDependencies(
            workspace = workspace, sharedFolders = d.sharedFolders, folders = d.folders, media = d.media, apps = apps, web = d.web,
            screen = d.screen, authorisedApps = d.authorisedApps, settings = d.settings, protectedPackages = d.protectedPackages, shell = d.shell,
            termux = d.termux, clock = d.clock, ownPackage = d.ownPackage, limits = d.limits,
        )
        val registry = DefaultToolRegistry()
        OperatorToolset.create(deps).forEach(registry::register)
        val clock = FakeClock()
        // As AppGraph builds it (default limits); the owner already approved.
        return ToolExecutor(registry, ToolAuthorizer { _, _, _ -> Authorization.Allowed }, ActivityRecorder(InMemoryActivityLog(), SequentialIdGenerator(), clock), clock, timeSource = testTimeSource)
    }

    @Test
    fun `an install the owner confirms after a minute and a half is reported as installed, not timed out`() = runTest {
        f.put("notes.apk", "PK not really an apk")
        val executor = executorWith(SlowConfirm(f.apps, ownerMs = 90_000))
        val agent = Samples.agent(id = "agt_operator", name = "Operator").copy(toolIds = setOf(ToolId("apps.install"), ToolId("apps.uninstall")))
        val install = executor.execute(ToolCallRequest("c1", "apps_install", buildJsonObject { put("path", "notes.apk") }), ToolContext(agent))
        assertEquals(ToolCallEnd.OK, install.end, install.outcome.content)
        assertTrue("installed" in install.outcome.content, install.outcome.content)
        val remove = executor.execute(ToolCallRequest("c2", "apps_uninstall", buildJsonObject { put("package", "com.example.notes") }), ToolContext(agent))
        assertEquals(ToolCallEnd.OK, remove.end, remove.outcome.content)
        assertTrue(f.tool("apps.install") is SelfLimitingTool && f.tool("apps.uninstall") is SelfLimitingTool)
    }

    @Test
    fun `the owner's time on Android's confirmation does not count against the task's limit`() = runTest {
        f.put("notes.apk", "PK not really an apk")
        val executor = executorWith(SlowConfirm(f.apps, ownerMs = 90_000))
        val agent = Samples.agent(id = "agt_operator", name = "Operator").copy(toolIds = setOf(ToolId("apps.install")))
        val started = currentTime
        // A task with one minute left: the confirmation takes a minute and a half of the owner's time.
        val result = withPausableTimeout(60_000, testTimeSource) {
            executor.execute(ToolCallRequest("c1", "apps_install", buildJsonObject { put("path", "notes.apk") }), ToolContext(agent))
        }
        assertEquals(ToolCallEnd.OK, assertNotNull(result, "the task's limit ran out during the owner's confirmation").end)
        assertTrue(currentTime - started >= 90_000)
    }

    @Test
    fun `an install that never ends is cut at its own limit and reported as unknown`() = runTest {
        f.put("notes.apk", "PK not really an apk")
        val hanging = object : AppActions by f.apps {
            override suspend fun install(apk: WorkspaceFile, waitMs: Long): AppActionResult {
                delay(Long.MAX_VALUE / 4)
                return AppActionResult.Installed("never")
            }
        }
        val executor = executorWith(hanging)
        val agent = Samples.agent(id = "agt_operator", name = "Operator").copy(toolIds = setOf(ToolId("apps.install")))
        val result = executor.execute(ToolCallRequest("c1", "apps_install", buildJsonObject { put("path", "notes.apk") }), ToolContext(agent))
        assertEquals(ToolCallEnd.FAILED, result.end)
        assertTrue("may still be open" in result.outcome.content, result.outcome.content)
    }
}
