package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenPreview
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecution
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.AppCall
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.ScreenCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round 2 of review 5, where the three sets of fixes meet. Each set (FILES F1-F6, SCREEN S1-S7,
 * BROKER B1-B4) was built and tested on its own branch; these tests run two or more of the fixes
 * together through the real tools, the real permission broker and the real tool executor, on one
 * clock, so that a fix of one set cannot quietly undo a fix of another:
 *
 *  - the workspace tools hold the kept versions (F1, F2) and the hand-over of a file (B1);
 *  - the wider bulk count (F6) raises approvals that meet the refusal wait (B2) and the stored
 *    deadlines (B3);
 *  - the refusal of an action on a window too large to fingerprint (S1) meets the wait for the
 *    app after an approval, the stored deadlines (B3) and the rule that no request is left open;
 *  - local-only mode (B1) meets the order in which a screen tool checks things (S1, S6);
 *  - the marks for typographic quotes (S5) meet the preview texts the other sets added.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Round2MeetingPointsTest {
    /** The real tools over one workspace, behind the real broker and executor, all on the broker fixture's clock. */
    private class World(
        val b: BrokerFixture,
        val f: OperatorFixture,
        val workspace: LocalWorkspace,
        val tools: Map<String, Tool>,
        val agent: AgentProfile,
        val executor: ToolExecutor,
    ) {
        private var calls = 0

        suspend fun call(name: String, vararg args: Pair<String, Any?>, task: String? = "tsk_work"): ToolExecution =
            executor.execute(ToolCallRequest("c${++calls}", name, json(*args)), b.context(task = task, who = agent))

        suspend fun grantAlways(capability: Capability, constraint: CapabilityConstraint = CapabilityConstraint.None) =
            b.grants.insert(PermissionGrant(GrantId("grt_${capability.id}"), agent.id, capability, constraint, GrantScope.ALWAYS, grantedAt = 1))

        suspend fun trash(): List<TrashItem> = (workspace.listTrash() as DocResult.Ok).value

        fun kept(item: TrashItem): String = File(f.root, ".trash/${item.id}/${item.originalPath.substringAfterLast('/')}").readText()

        suspend fun deadline(task: String): Long = b.tasks.get(TaskId(task))!!.deadlineAt!! - START

        suspend fun egress() = b.events(ActivityType.DATA_EGRESS)
    }

    private suspend fun TestScope.world(vararg toolIds: String, screen: FakeScreen? = null): World {
        val b = BrokerFixture(this).setUp()
        val f = OperatorFixture(screen)
        val agent = b.agent.copy(toolIds = b.agent.toolIds + toolIds.map(::ToolId))
        b.agents.upsert(agent)
        b.agent = agent
        // As the app wires them: one clock for the broker, the tools and the workspace, the owner's settings read from the
        // store the broker reads, and hand-overs written to the activity log. The workspace reads on the test's own thread,
        // so that the executor's time limit (virtual time) cannot run out while a real thread reads a file.
        val base = f.dependencies
        val workspace = LocalWorkspace(FakeWorkspaceRoot(f.root), b.clock, io = EmptyCoroutineContext)
        val d = OperatorToolDependencies(
            workspace = workspace, sharedFolders = base.sharedFolders,
            folders = base.folders, media = base.media, apps = base.apps, web = base.web, screen = base.screen,
            authorisedApps = base.authorisedApps, settings = { b.settings.get() }, protectedPackages = base.protectedPackages,
            shell = base.shell, termux = base.termux, clock = b.clock, ownPackage = base.ownPackage, limits = base.limits,
            egress = OperatorEgress.logTo(b.activity),
        )
        val tools = OperatorToolset.create(d).associateBy { it.spec.id.value }
        val registry = DefaultToolRegistry().also { r -> toolIds.forEach { r.register(tools.getValue(it)) } }
        return World(b, f, workspace, tools, agent, ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = testTimeSource, tasks = b.tasks))
    }

    /** Authorises [pkg] for screen control where the broker looks and where the tools look. */
    private suspend fun World.authorise(pkg: String, label: String, policy: ScreenActionPolicy = ScreenActionPolicy.ASK_EVERY_ACTION) {
        b.apps.upsert(OperatorApp(pkg, label, 20, "cd".repeat(32), policy))
        f.authorise(pkg, label, policy)
    }

    private fun text(text: String, key: String) = FakeControl(role = "TextView", text = text, clickable = false, nodeKey = key)

    // ---- FILES and BROKER in the workspace tools: kept versions (F1, F2) and the hand-over of a file (B1) ----

    @Test
    fun `local-only mode refuses the export but not the edit, a kept version is never handed over, and an export hands over the file as it is now`() = runTest {
        val w = world("workspace.replace_text", "workspace.export")
        w.grantAlways(OperatorCapabilities.WORKSPACE_WRITE)
        w.grantAlways(OperatorCapabilities.APPS_COMPOSE)
        w.f.apps.installed += InstalledAppInfo("com.example.clouddrive", "Cloud Drive")
        val original = "the owner's draft, version one"
        val edited = "the owner's draft, version two, checked by the agent"
        w.f.put("notes.md", original)

        // Local-only mode stops what leaves the phone, not work inside it: the edit runs and keeps the version it replaces.
        w.b.settings.update { it.copy(localOnlyMode = true) }
        val edit = w.call("workspace_replace_text", "path" to "notes.md", "find" to "version one", "replace" to "version two, checked by the agent")
        assertEquals(ToolCallEnd.OK, edit.end, edit.outcome.content)
        assertEquals(edited, w.f.file("notes.md").readText())
        val kept = w.trash().single()
        assertEquals(original, w.kept(kept), "the version from before the edit is in the trash")

        val refused = w.call("workspace_export", "path" to "notes.md", "app" to "com.example.clouddrive")
        assertEquals(ToolCallEnd.DENIED, refused.end, refused.outcome.content)
        assertTrue("Local-only mode is on" in refused.outcome.content, refused.outcome.content)

        // The kept version is reached only through the trash and restore, never by its place on disk: in neither mode is it handed over.
        val inTrash = ".trash/${kept.id}/notes.md"
        assertNotEquals(ToolCallEnd.OK, w.call("workspace_export", "path" to inTrash).end)
        w.b.settings.update { it.copy(localOnlyMode = false) }
        val trashed = w.call("workspace_export", "path" to inTrash, "app" to "com.example.clouddrive")
        assertNotEquals(ToolCallEnd.OK, trashed.end, trashed.outcome.content)
        assertEquals(emptyList<AppCall>(), w.f.apps.calls.toList(), "nothing reached another app")
        assertEquals(0, w.egress().size)
        assertEquals(0, w.b.allRequests().size, "and the owner was asked nothing")

        // Outside local-only mode the file goes out as it is now, and the log says which file and how much, never what is in it.
        val shared = w.call("workspace_export", "path" to "notes.md", "app" to "com.example.clouddrive")
        assertEquals(ToolCallEnd.OK, shared.end, shared.outcome.content)
        val handed = assertIs<AppCall.ShareFile>(w.f.apps.calls.single())
        assertEquals("notes.md", handed.file.path.value)
        assertEquals(edited, handed.file.file.readText(), "the live file, not the version in the trash")
        val entry = w.egress().single()
        assertTrue("\"notes.md\"" in entry.detail && "${edited.length} bytes" in entry.detail && "Cloud Drive" in entry.detail, entry.detail)
        assertEquals(edited.length.toLong(), entry.egress?.approxBytes)
        assertFalse("draft" in entry.detail || "checked" in entry.detail, entry.detail)
        assertEquals(original, w.kept(w.trash().single()), "handing the file over changed nothing in the trash")
    }

    // ---- FILES and BROKER at the broker: the objective's bulk count (F6), "Let it ask again" (B2), stored deadlines (B3), the kept version (F1) ----

    @Test
    fun `an edit that fills the objective's count is refused, opened again by the owner, approved, keeps the version, and every wait is in the stored deadlines once`() = runTest {
        val w = world("workspace.replace_text")
        val limit = ConsequentialControls.BULK_FILE_LIMIT
        w.b.runningTask("tsk_lead", deadlineAt = START + 60 * MINUTE, status = TaskStatus.WAITING_FOR_AGENT)
        w.b.runningTask("tsk_work", deadlineAt = START + 20 * MINUTE, parent = "tsk_lead")

        // Another agent of the same objective has replaced as many files as the bulk rule lets pass unasked (F6 counts per objective).
        val writer = ToolContext(Samples.agent(id = "agt_writer", name = "Writer"), TaskId("tsk_writing"), TaskId("tsk_root"))
        val write = w.tools.getValue("workspace.write")
        repeat(limit) { i ->
            w.f.put("old-$i.md", "owner text $i")
            val args = json("path" to "old-$i.md", "content" to "rewritten", "overwrite" to true)
            val assessed = write.assess(args, writer)
            assertRisk(RiskLevel.MODIFYING, assessed)
            assertTrue(write.execute(args, writer, assessed).ok)
        }
        w.f.put("plan.md", "the owner's plan")
        fun edit(path: String = "plan.md") =
            async { w.call("workspace_replace_text", "path" to path, "find" to "owner's", "replace" to "agent's") }.also { runCurrent() }
        suspend fun pending(): OwnerRequest = w.b.pending()

        // The agent has no permission to change the workspace yet. The owner takes a minute, and says no.
        val first = edit()
        val refusal = pending()
        assertEquals(OwnerRequestKind.PERMISSION, refusal.kind)
        advanceTimeBy(MINUTE)
        w.b.owner.deny(refusal.id)
        assertEquals(ToolCallEnd.DENIED, first.await().end)
        assertEquals(21 * MINUTE, w.deadline("tsk_work"), "the owner's minute is in the stored deadline")

        // B2: asked again inside the ten minutes, the call is refused unasked; nothing is changed, kept or counted.
        val unasked = edit().await()
        assertEquals(ToolCallEnd.DENIED, unasked.end)
        assertTrue("not asked again" in unasked.outcome.content && "Let it ask again" in unasked.outcome.content, unasked.outcome.content)
        assertTrue(w.b.owner.pending.first().isEmpty())
        assertEquals("the owner's plan", w.f.file("plan.md").readText())
        assertEquals(21 * MINUTE, w.deadline("tsk_work"), "a call refused unasked waited for nobody")

        // The owner changes their mind on the refused request: that gives nothing, the next call is put to them as usual.
        assertIs<OwnerResult.Done<OwnerRequest>>(w.b.owner.letAskAgain(refusal.id))
        assertTrue(w.b.grants.observeAll().first().isEmpty())
        val second = edit()
        val permission = pending()
        assertEquals(OwnerRequestKind.PERMISSION, permission.kind)
        advanceTimeBy(2 * MINUTE)
        w.b.owner.grant(permission.id, GrantScope.TASK)
        runCurrent()

        // F6: with the permission given, the edit still asks, because the objective's agents together are at the limit.
        val approval = pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        assertEquals(RiskLevel.CONSEQUENTIAL, approval.risk)
        assertTrue(approval.details.any { "more than $limit existing files" in it && "by the agents working on this objective" in it }, approval.details.toString())
        assertTrue(WorkspaceReplaceText.KEPT_IN_TRASH in approval.details, approval.details.toString())
        assertEquals("the owner's plan", w.f.file("plan.md").readText(), "nothing is changed while the owner is asked")
        advanceTimeBy(3 * MINUTE)
        w.b.owner.approve(approval.id)
        val done = second.await()
        assertEquals(ToolCallEnd.OK, done.end, done.outcome.content)

        // F1: the edit ran and the owner's version is in the trash.
        assertEquals("the agent's plan", w.f.file("plan.md").readText())
        assertEquals("the owner's plan", w.kept(w.trash().single { it.originalPath == "plan.md" }))
        // B3: one minute, two minutes and three minutes of the owner's time, each in the stored deadlines once,
        // for the task and for the task that delegated to it.
        assertEquals(26 * MINUTE, w.deadline("tsk_work"))
        assertEquals(66 * MINUTE, w.deadline("tsk_lead"))
        assertEquals(TaskStatus.RUNNING, w.b.tasks.get(TaskId("tsk_work"))?.status)
        assertTrue(w.b.owner.pending.first().isEmpty() && w.b.waiters.waiting.value.isEmpty())
        // The approved edit counts too: the next one asks again, for the same reason.
        w.f.put("next.md", "the owner's next file")
        val next = edit(path = "next.md")
        assertEquals(OwnerRequestKind.APPROVAL, pending().kind)
        next.cancel()
    }

    // ---- SCREEN and BROKER: a window too large to fingerprint (S1), the wait for the app after an approval, stored deadlines (B3) ----

    @Test
    fun `an approved tap whose window outgrew the fingerprint while the owner answered is refused once the app is back, nothing is left open, and the wait is stored once`() = runTest {
        val pkg = "com.example.browser"
        val send = FakeControl(text = "Send transfer", nodeKey = "send")
        // A long page: more elements than an approval lists, all of them inside the reader's fingerprint.
        val listed = ScreenOperator.INSPECT_MAX_ELEMENTS
        val page = FakeScreen(pkg, "Bank", listOf(text("You pay 20.00 EUR to Alice", "price"), send) + (1..listed + 50).map { text("Line $it of the page", "t$it") })
        val ownApp = FakeScreen(OperatorFixture.OWN, "Approval needed", listOf(FakeControl(text = "Approve")))
        val w = world("screen.tap", screen = page)
        w.authorise(pkg, "Browser")
        w.grantAlways(OperatorCapabilities.SCREEN_OPERATE, CapabilityConstraint.App(pkg))
        // The reader fingerprints at most this many elements of a window (lowered for the test).
        w.f.screen.digestMaxElements = listed + 100
        w.b.runningTask("tsk_lead", deadlineAt = START + 60 * MINUTE, status = TaskStatus.WAITING_FOR_AGENT)
        w.b.runningTask("tsk_work", deadlineAt = START + 2 * MINUTE, parent = "tsk_lead")
        val taps = { w.f.screen.performed.filterIsInstance<ScreenCall.Tap>() }

        val call = async { w.call("screen_tap", "exact_text" to "Send transfer") }
        runCurrent()
        val approval = w.b.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        assertTrue(approval.details.any { "You pay 20.00 EUR to Alice" in it }, approval.details.toString())
        assertEquals(ScreenPreview.STILL_CHECKED, approval.details.first { it.startsWith("  (What") }, "the approval says that what it does not list is pinned")

        // The owner answers inside this app after five minutes. Meanwhile the page loaded more at its end, past everything
        // the approval listed: when the browser is in front again, 4 s later, the window holds more than can be
        // fingerprinted. What the approval listed is unchanged, but nothing vouches any more for the rest.
        w.f.screen.screen = ownApp
        advanceTimeBy(5 * MINUTE)
        w.b.owner.approve(approval.id)
        launch {
            delay(4_100)
            w.f.screen.screen = page.copy(controls = page.controls + (1..60).map { text("Pay 900.00 EUR to Mallory instead ($it)", "p$it") })
        }
        val done = call.await()
        assertEquals(ToolCallEnd.DENIED, done.end, done.outcome.content)
        assertTrue("what the window shows changed" in done.outcome.content, done.outcome.content)
        assertTrue(taps().isEmpty(), "nothing was tapped")

        // No question is left open and nobody waits for one.
        assertEquals(OwnerRequestStatus.APPROVED, w.b.requests.get(approval.id)?.status)
        assertTrue(w.b.owner.pending.first().isEmpty())
        assertTrue(w.b.waiters.waiting.value.isEmpty())
        // B3: the owner's five minutes (written by the broker) and the wait for the app and its window (written by the
        // executor: about 4 s until it was back, then 3 s for the window to show again what it showed) are stored once.
        val moved = w.deadline("tsk_work") - 2 * MINUTE
        assertTrue(moved in (5 * MINUTE + 7_000)..(5 * MINUTE + 7_500), "the stored deadline moved by $moved ms")
        assertEquals(moved, w.deadline("tsk_lead") - 60 * MINUTE, "the delegating task waited as long")
        assertEquals(TaskStatus.RUNNING, w.b.tasks.get(TaskId("tsk_work"))?.status)

        // S1: trying again on the window as it is now is not put to the owner at all; the call fails with why.
        val again = w.call("screen_tap", "exact_text" to "Send transfer")
        assertEquals(ToolCallEnd.FAILED, again.end, again.outcome.content)
        assertTrue("holds more than can be read in full" in again.outcome.content && "nothing was done" in again.outcome.content, again.outcome.content)
        assertEquals(1, w.b.allRequests().size, "no second request was raised")
        assertEquals(moved, w.deadline("tsk_work") - 2 * MINUTE, "a call that asked nobody moves no deadline")
        assertTrue(taps().isEmpty())
    }

    // ---- BROKER and SCREEN: local-only mode (B1) against the order of a screen tool's checks (S1, S6) ----

    @Test
    fun `in local-only mode a screen action is refused at once, before the service state is waited for or an oversized window is read, and reading stays`() = runTest {
        val pkg = "com.example.browser"
        val feed = FakeScreen(pkg, "Feed", (1..60).map { text("Post $it", "p$it") } + FakeControl(text = "Send transfer", nodeKey = "send"))
        val w = world("screen.tap", "screen.type", "screen.scroll", "screen.back", "screen.home", "screen.read", "apps.share_text", screen = feed)
        w.authorise(pkg, "Browser")
        w.authorise("com.android.launcher3", "Home")
        w.grantAlways(OperatorCapabilities.SCREEN_OPERATE, CapabilityConstraint.App(pkg))
        w.grantAlways(OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App(pkg))
        w.grantAlways(OperatorCapabilities.APPS_COMPOSE)
        w.f.screen.digestMaxElements = 50
        w.b.settings.update { it.copy(localOnlyMode = true) }
        // The accessibility service's state is not known yet: a screen tool would wait up to 3 s for it.
        w.f.screen.setState(ScreenServiceState.CHECKING)

        val acting = listOf(
            "screen_tap" to arrayOf<Pair<String, Any?>>("exact_text" to "Send transfer"),
            "screen_type" to arrayOf<Pair<String, Any?>>("exact_text" to "Send transfer", "text" to "x"),
            "screen_scroll" to arrayOf<Pair<String, Any?>>("direction" to "down"),
            "screen_back" to emptyArray(),
            "screen_home" to emptyArray(),
        )
        for ((name, args) in acting) {
            val done = w.call(name, *args)
            assertNotEquals(ToolCallEnd.OK, done.end, name)
            assertTrue("Local-only mode is on" in done.outcome.content, "$name: ${done.outcome.content}")
            assertFalse("holds more than can be read" in done.outcome.content || "still looking up" in done.outcome.content, "$name: ${done.outcome.content}")
        }
        assertEquals(0L, currentTime, "refused without waiting for the service state")
        assertTrue(w.f.screen.inspected.isEmpty() && w.f.screen.calls.isEmpty(), "nothing of the screen was looked at")
        // A hand-over is refused by the gate in the same mode, and for none of these was the owner asked.
        val share = w.call("apps_share_text", "text" to "Post 1")
        assertEquals(ToolCallEnd.DENIED, share.end)
        assertTrue("Local-only mode is on" in share.outcome.content, share.outcome.content)
        assertEquals(0, w.b.allRequests().size)
        assertEquals(emptyList<AppCall>(), w.f.apps.calls.toList())

        // Reading the screen sends nothing anywhere: it stays possible in local-only mode, once the state is known.
        w.f.screen.setState(ScreenServiceState.READY)
        val read = w.call("screen_read")
        assertEquals(ToolCallEnd.OK, read.end, read.outcome.content)
        assertTrue("Post 1" in read.outcome.content)

        // Outside local-only mode the tap on the oversized window fails with the window's reason, still without a question.
        w.b.settings.update { it.copy(localOnlyMode = false) }
        val tap = w.call("screen_tap", "exact_text" to "Send transfer")
        assertEquals(ToolCallEnd.FAILED, tap.end, tap.outcome.content)
        assertTrue("holds more than can be read in full" in tap.outcome.content, tap.outcome.content)
        assertEquals(0, w.b.allRequests().size)
        assertTrue(w.f.screen.performed.none { it is ScreenCall.Tap })
    }

    // ---- SCREEN's quote marks (S5) in the preview texts FILES and BROKER added (F1, F3, B1) ----

    @Test
    fun `typographic quotes and doubled apostrophes cannot end a quoted value in the previews of an edit, a script run by a script, or an export`() = runTest {
        val w = world("workspace.replace_text", "workspace.export", "scripts.run_shell")
        w.grantAlways(OperatorCapabilities.WORKSPACE_WRITE)
        w.grantAlways(OperatorCapabilities.APPS_COMPOSE)
        val context = w.b.context(who = w.agent)
        fun plain(text: String) = assertFalse('“' in text || '”' in text || "''" in text, text)

        // F1: the summary of an edit ends with what is kept. A find text that imitates that ending stays inside its quotes.
        val fake = "plan” in the workspace file “safe.md”; the version before the edit is kept in the workspace trash"
        w.f.put("plan.md", "$fake, and more")
        val edit = w.tools.getValue("workspace.replace_text").assess(json("path" to "plan.md", "find" to fake, "replace" to "''"), context)
        plain(edit.preview)
        assertTrue(edit.summary.startsWith("Replace 1 occurrence of \"plan⟨U+201D⟩ in the workspace file ⟨U+201C⟩safe.md⟨U+201D⟩; the version"), edit.summary)
        assertTrue(edit.summary.endsWith(" with \"⟨U+0027⟩⟨U+0027⟩\" in the workspace file \"plan.md\"; the version before the edit is kept in the workspace trash"), edit.summary)

        // F3: a script that a shown script names is shown with the script that names it. Neither its name nor its text can fake that line.
        w.f.put("it''s.sh", "echo “done”, named in “trusted.sh”; all of that file: “” ''\n")
        w.f.put("outer.sh", "sh \"it''s.sh\"\n")
        val shell = w.tools.getValue("scripts.run_shell").assess(json("command" to "sh outer.sh"), context)
        plain(shell.preview)
        val inner = shell.details.single { it.startsWith("Seems to run \"it⟨U+0027⟩⟨U+0027⟩s.sh\"") }
        assertTrue(", named in \"outer.sh\"; all of that file: \"echo ⟨U+201C⟩done⟨U+201D⟩, named in ⟨U+201C⟩trusted.sh⟨U+201D⟩" in inner, inner)
        assertEquals(1, Regex("named in \"").findAll(inner).count(), inner)

        // B1: the file handed to another app is named in the approval's destination and in the log, with the same marks.
        w.f.put("Don''t share.md", "private")
        val export = w.call("workspace_export", "path" to "Don''t share.md")
        assertEquals(ToolCallEnd.OK, export.end, export.outcome.content)
        val entry = w.egress().single()
        plain(entry.detail)
        assertTrue("the workspace file \"Don⟨U+0027⟩⟨U+0027⟩t share.md\" (7 bytes)" in entry.detail, entry.detail)
        val assessed = w.tools.getValue("workspace.export").assess(json("path" to "Don''t share.md"), context)
        plain(assessed.preview + assessed.dataGoesTo)
        assertEquals("\"Don⟨U+0027⟩⟨U+0027⟩t share.md\" to the app you choose", assessed.dataGoesTo)
    }
}
