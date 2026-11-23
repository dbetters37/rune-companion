package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ConstraintKind
import com.aicontrolcenter.core.tools.RiskLevel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorModelTest {
    private val agent = AgentId("agt_op")
    private val folder = CapabilityConstraint.Folder(FolderId("fld_1"))
    private val app = CapabilityConstraint.App("com.example.notes")

    private fun request(kind: OwnerRequestKind = OwnerRequestKind.PERMISSION, taskId: String? = "tsk_1") = OwnerRequest(
        id = RequestId("req_1"), kind = kind, agentId = agent, capability = OperatorCapabilities.FOLDERS_READ, constraint = folder,
        risk = RiskLevel.SAFE, summary = "Read Reports", taskId = taskId?.let(::TaskId), createdAt = 1_000, expiresAt = 2_000,
    )

    private fun query(constraint: CapabilityConstraint = folder, task: String? = "tsk_1", at: Long = 1_500) =
        GrantQuery(agent, OperatorCapabilities.FOLDERS_READ, constraint, task?.let(::TaskId), at)

    @Test
    fun `waiting for the owner is active and waiting, like waiting for an agent`() {
        assertTrue(TaskStatus.WAITING_FOR_OWNER.isActive)
        assertFalse(TaskStatus.WAITING_FOR_OWNER.isFinished)
        assertTrue(TaskStatus.WAITING_FOR_OWNER.isWaiting && TaskStatus.WAITING_FOR_AGENT.isWaiting)
        assertFalse(TaskStatus.RUNNING.isWaiting)
        // Appended: earlier stored names and positions are unchanged.
        assertEquals(TaskStatus.entries.last(), TaskStatus.WAITING_FOR_OWNER)
        assertEquals(TaskStatus.INTERRUPTED.ordinal + 1, TaskStatus.WAITING_FOR_OWNER.ordinal)
    }

    @Test
    fun `constraints round-trip through their stored form and refuse junk`() {
        for (c in listOf(CapabilityConstraint.None, folder, app, CapabilityConstraint.App("android"))) {
            assertEquals(c, CapabilityConstraint.parse(c.stored))
            assertEquals(c, Json.decodeFromString<CapabilityConstraint>(Json.encodeToString<CapabilityConstraint>(c)))
        }
        assertEquals("", CapabilityConstraint.None.stored)
        assertEquals("folder:fld_1", folder.stored)
        assertEquals("app:com.example.notes", app.stored)
        assertEquals(ConstraintKind.APP, app.kind)
        for (junk in listOf("x", "folder:", "app:", "app:1com.x", "app:com..x", "app:com.x/y", "folder:a b", "APP:com.x")) {
            assertFailsWith<IllegalArgumentException>(junk) { CapabilityConstraint.parse(junk) }
        }
    }

    @Test
    fun `a grant covers only the same agent, capability and constraint`() {
        val always = PermissionGrant(GrantId("g"), agent, OperatorCapabilities.FOLDERS_READ, folder, GrantScope.ALWAYS, grantedAt = 1_000)
        assertTrue(always.covers(query()))
        assertTrue(always.covers(query(task = null)))
        assertTrue(always.covers(query(task = "tsk_other")))
        assertFalse(always.covers(query(constraint = CapabilityConstraint.Folder(FolderId("fld_2")))))
        assertFalse(always.covers(query(constraint = CapabilityConstraint.None)))
        assertFalse(always.copy(agentId = AgentId("agt_other")).covers(query()))
        assertFalse(always.copy(capability = OperatorCapabilities.FOLDERS_WRITE).covers(query()))
        // A grant without a constraint never covers a constrained call.
        val unconstrained = always.copy(constraint = CapabilityConstraint.None)
        assertFalse(unconstrained.covers(query()))
        assertTrue(unconstrained.covers(query(constraint = CapabilityConstraint.None)))
    }

    @Test
    fun `task grants cover only their task, and once grants cover no call at all`() {
        val task = PermissionGrant(GrantId("t"), agent, OperatorCapabilities.FOLDERS_READ, folder, GrantScope.TASK, TaskId("tsk_1"), grantedAt = 1_000)
        assertTrue(task.covers(query()))
        assertFalse(task.covers(query(task = "tsk_2")))
        assertFalse(task.covers(query(task = null)))
        assertFalse(task.copy(revokedAt = 1_200).covers(query()))
        assertFalse(task.copy(expiresAt = 1_500).covers(query()))
        // A ONCE grant belongs to its request's own call and never covers any other.
        val once = PermissionGrant(GrantId("o"), agent, OperatorCapabilities.FOLDERS_READ, folder, GrantScope.ONCE, null, RequestId("req_1"), grantedAt = 1_000, expiresAt = 1_600)
        assertTrue(once.isActiveAt(1_500))
        assertFalse(once.covers(query(task = null)))
        assertFalse(once.covers(query(task = "tsk_1")))
    }

    @Test
    fun `grants refuse inconsistent scopes`() {
        assertFailsWith<IllegalArgumentException> { PermissionGrant(GrantId("g"), agent, Capability("x"), scope = GrantScope.TASK, grantedAt = 1) }
        assertFailsWith<IllegalArgumentException> { PermissionGrant(GrantId("g"), agent, Capability("x"), scope = GrantScope.ALWAYS, taskId = TaskId("t"), grantedAt = 1) }
        assertFailsWith<IllegalArgumentException> { PermissionGrant(GrantId("g"), agent, Capability("x"), scope = GrantScope.ONCE, requestId = RequestId("r"), grantedAt = 1) }
        assertFailsWith<IllegalArgumentException> { PermissionGrant(GrantId("g"), agent, Capability("x"), scope = GrantScope.ONCE, grantedAt = 1, expiresAt = 9) }
        assertFailsWith<IllegalArgumentException> { PermissionGrant(GrantId("g"), agent, Capability("x"), scope = GrantScope.ALWAYS, grantedAt = 1, consumedAt = 2) }
    }

    @Test
    fun `preference puts reusable grants before once grants`() {
        val once = PermissionGrant(GrantId("a"), agent, Capability("x"), scope = GrantScope.ONCE, requestId = RequestId("r"), grantedAt = 1, expiresAt = 9)
        val task = PermissionGrant(GrantId("b"), agent, Capability("x"), scope = GrantScope.TASK, taskId = TaskId("t"), grantedAt = 5)
        val always = PermissionGrant(GrantId("c"), agent, Capability("x"), scope = GrantScope.ALWAYS, grantedAt = 7)
        val olderAlways = always.copy(id = GrantId("d"), grantedAt = 6)
        assertEquals(listOf(olderAlways, always, task, once), listOf(once, task, always, olderAlways).sortedWith(PermissionGrant.PREFERENCE))
    }

    @Test
    fun `an approval request cannot carry a scope, by construction or by answer`() {
        val approval = request(OwnerRequestKind.APPROVAL)
        assertEquals(emptyList(), approval.offeredScopes)
        assertFailsWith<IllegalArgumentException> { approval.copy(status = OwnerRequestStatus.APPROVED, decidedAt = 1_100, decisionScope = GrantScope.ALWAYS) }
        assertNotNull(approval.problemWith(OwnerDecision.Grant(GrantScope.ALWAYS)))
        assertNotNull(approval.problemWith(OwnerDecision.Grant(GrantScope.ONCE)))
        assertNull(approval.problemWith(OwnerDecision.Approve()))
        assertNull(approval.problemWith(OwnerDecision.Deny("no")))
        assertFailsWith<IllegalArgumentException> { approval.grantFor(GrantScope.ONCE, GrantId("g"), 1_100) }
        // A plain approval does not answer a permission request.
        assertNotNull(request().problemWith(OwnerDecision.Approve()))
    }

    @Test
    fun `task scope is offered only for calls inside a task`() {
        assertEquals(listOf(GrantScope.ONCE, GrantScope.TASK, GrantScope.ALWAYS), request().offeredScopes)
        val chat = request(taskId = null)
        assertEquals(listOf(GrantScope.ONCE, GrantScope.ALWAYS), chat.offeredScopes)
        assertNotNull(chat.problemWith(OwnerDecision.Grant(GrantScope.TASK)))
        assertNull(chat.problemWith(OwnerDecision.Grant(GrantScope.ALWAYS)))
    }

    @Test
    fun `request invariants hold`() {
        val r = request()
        assertFailsWith<IllegalArgumentException> { r.copy(expiresAt = r.createdAt) }
        assertFailsWith<IllegalArgumentException> { r.copy(status = OwnerRequestStatus.APPROVED, decidedAt = 1_100) }
        assertFailsWith<IllegalArgumentException> { r.copy(status = OwnerRequestStatus.DENIED) }
        assertFailsWith<IllegalArgumentException> { r.copy(decidedAt = 1_100) }
        assertFailsWith<IllegalArgumentException> { r.copy(status = OwnerRequestStatus.DENIED, decidedAt = 1_100, decisionScope = GrantScope.ONCE) }
        r.copy(status = OwnerRequestStatus.APPROVED, decidedAt = 1_100, decisionScope = GrantScope.TASK)
        assertTrue(r.isExpiredAt(2_000))
        assertFalse(r.isExpiredAt(1_999))
    }

    @Test
    fun `grantFor writes the scope rules in one place`() {
        val r = request()
        val once = r.grantFor(GrantScope.ONCE, GrantId("g1"), 1_200)
        assertEquals(PermissionGrant(GrantId("g1"), agent, r.capability, folder, GrantScope.ONCE, TaskId("tsk_1"), r.id, 1_200, 1_200 + PermissionGrant.ONCE_LIFETIME_MS), once)
        assertEquals(TaskId("tsk_1"), r.grantFor(GrantScope.TASK, GrantId("g2"), 1_200).taskId)
        val always = r.grantFor(GrantScope.ALWAYS, GrantId("g3"), 1_200)
        assertNull(always.taskId)
        assertNull(always.expiresAt)
        assertFailsWith<IllegalArgumentException> { request(taskId = null).grantFor(GrantScope.TASK, GrantId("g4"), 1_200) }
    }

    @Test
    fun `operator capabilities are unique, and only reading the workspace is granted by default`() {
        val all = OperatorCapabilities.all
        assertEquals(14, all.size)
        assertEquals(all.size, all.map { it.capability }.toSet().size)
        assertTrue(all.none { it.capability in Capabilities.builtIn.map { b -> b.capability } })
        assertEquals(listOf(OperatorCapabilities.WORKSPACE_READ), all.filter { it.grantedByDefault }.map { it.capability })
        assertEquals(setOf(OperatorCapabilities.FOLDERS_READ, OperatorCapabilities.FOLDERS_WRITE), all.filter { it.constrainedBy == ConstraintKind.FOLDER }.map { it.capability }.toSet())
        assertEquals(setOf(OperatorCapabilities.SCREEN_READ, OperatorCapabilities.SCREEN_OPERATE), all.filter { it.constrainedBy == ConstraintKind.APP }.map { it.capability }.toSet())
        assertTrue(all.all { it.title.isNotBlank() && it.description.endsWith(".") })
    }

    @Test
    fun `consequential words match words, their forms, prefixes and compounds`() {
        val hits = mapOf(
            "Buy now" to "buy", "PAY" to "pay", "Payment" to "pay", "Paid" to "pay", "Place order" to "order", "Check out" to "checkout",
            "checkout" to "checkout", "Subscribe" to "subscribe", "Subscription" to "subscribe", "Send" to "send", "Sending…" to "send",
            "Sent" to "send", "Post" to "post", "Share" to "share", "Sharing" to "share", "Publish" to "publish", "Reply all" to "reply",
            "Replies" to "reply", "Call" to "call", "Transferring" to "transfer", "Install" to "install", "Uninstall" to "uninstall",
            "Delete" to "delete", "Deletion" to "delete", "Remove" to "remove", "Removal" to "remove", "Confirm" to "confirm",
            "Confirmation" to "confirm", "Accept" to "accept", "Sign in" to "sign", "Signature" to "sign", "btn_buy" to "buy",
            "sendButton" to "send", "Purchases" to "purchase", "Purchasing" to "purchase",
            // Prefixes and compounds: missing one would let a buy, pay or send button run without approval.
            "Repayment" to "pay", "Resend" to "send", "Unsubscribe" to "subscribe", "Autopay" to "pay", "Prepay" to "pay",
            "Prepaid" to "pay", "Reorder" to "order", "Repost" to "post", "paynow" to "pay", "buynow" to "buy", "Signup" to "sign",
            // Accepted false positives: one extra confirmation each.
            "Signal" to "sign", "Recall" to "call", "Postpone" to "post", "Payload" to "pay",
        )
        for ((text, stem) in hits) assertEquals(stem, ConsequentialControls.matchedWord(text), text)
        for (text in listOf(null, "", "  ", "Cancel", "Next", "Add to cart", "Ordinary", "Settings", "Display", "Calendar", "Messages", "Search", "Save", "Back")) {
            assertNull(ConsequentialControls.matchedWord(text), text.toString())
        }
    }

    @Test
    fun `a control matches by text, description or view id`() {
        val base = ScreenElement(1, 1, "com.shop", "Button", bounds = ScreenBounds(0, 0, 10, 10))
        assertNull(ConsequentialControls.matchedWord(base))
        assertEquals("buy", ConsequentialControls.matchedWord(base.copy(text = "Buy")))
        assertEquals("pay", ConsequentialControls.matchedWord(base.copy(description = "Pay with card")))
        assertEquals("checkout", ConsequentialControls.matchedWord(base.copy(resourceId = "com.shop:id/btnCheckOut")))
        // The package part of a view id is not a label.
        assertNull(ConsequentialControls.matchedWord(base.copy(resourceId = "com.sendit:id/next")))
        // A clickable row whose words sit on child views.
        assertEquals("buy", ConsequentialControls.matchedWord(base.copy(role = "LinearLayout", label = "Wireless earbuds · Buy now")))
        assertNull(ConsequentialControls.consequentialReason(base.copy(text = "Next", clickable = true)))
        assertNotNull(ConsequentialControls.consequentialReason(base.copy(text = "Pay", clickable = true)))
        // An icon-only button cannot be shown to the owner for what it does.
        assertTrue(ConsequentialControls.isUnlabelled(base.copy(resourceId = "com.shop:id/fab")))
        assertNotNull(ConsequentialControls.consequentialReason(base.copy(clickable = true, resourceId = "com.shop:id/fab")))
        assertNull(ConsequentialControls.consequentialReason(base.copy(clickable = false)))
    }

    @Test
    fun `protected packages include the system screens and this app`() {
        val list = ConsequentialControls.PROTECTED_PACKAGES
        for (p in listOf("com.android.settings", "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "android")) {
            assertTrue(p in list, p)
        }
        assertTrue(list.all { CapabilityConstraint.isValidPackageName(it) })
        assertTrue(ConsequentialControls.isProtectedPackage("com.aicontrolcenter.app"))
        assertTrue(ConsequentialControls.isProtectedPackage("com.aicontrolcenter.app.debug"))
        assertTrue(ConsequentialControls.isProtectedPackage("com.aicontrolcenter"))
        assertTrue(ConsequentialControls.isProtectedPackage("org.renamed.build", ownPackage = "org.renamed.build"))
        assertFalse(ConsequentialControls.isProtectedPackage("com.example.notes", ownPackage = "com.aicontrolcenter.app"))
        assertFalse(ConsequentialControls.isProtectedPackage("com.aicontrolcenterx"))
        // Handlers resolved on the device are added to the static floor, never replacing it.
        val device = ProtectedPackages("com.aicontrolcenter.app", resolved = setOf("com.samsung.android.permissioncontroller"))
        assertTrue(device.isProtected("com.samsung.android.permissioncontroller"))
        assertTrue(device.isProtected("com.android.settings"))
        assertTrue(device.isProtected("com.aicontrolcenter.app"))
        assertFalse(device.isProtected("com.example.notes"))
        assertTrue(ConsequentialControls.PROTECTED_HANDLER_ACTIONS.containsAll(listOf("android.settings.SETTINGS", "android.intent.action.INSTALL_PACKAGE")))
    }

    @Test
    fun `relative paths normalise and refuse escapes`() {
        assertEquals(RelativePath.ROOT, RelativePath.of(""))
        assertEquals(RelativePath.ROOT, RelativePath.of("./"))
        assertEquals("a/b", RelativePath.of("a//./b/").value)
        assertEquals(listOf("notes", "2024 plan.md"), RelativePath.of("notes/2024 plan.md").segments)
        assertEquals("2024 plan.md", RelativePath.of("notes/2024 plan.md").name)
        assertEquals("notes", RelativePath.of("notes/2024 plan.md").parent?.value)
        assertEquals(RelativePath.ROOT, RelativePath.of("notes").parent)
        assertNull(RelativePath.ROOT.parent)
        assertEquals("a/b", RelativePath.of("a").child("b").value)
        assertEquals("…", RelativePath.of("…").value)
        val bad = listOf(
            "/etc/passwd", "..", "../x", "a/../b", "a/..", "a\\b", "a\u0000b", "a\nb", "a\u202Eb", "x\u200Fy",
            "a\u061Cb", "a\u200Bb", "a\u200Db", "a\u2060b", "a\u00A0b", "a\u2028b", "a\uDB40\uDC41b", " lead.txt", "trail.txt ", "dir /x",
            "a/".repeat(33), "x".repeat(1025), "é".repeat(128),
        )
        for (raw in bad) assertIs<RelativePath.Check.Invalid>(RelativePath.parse(raw), raw.take(30))
        for (name in listOf("", ".", "..", "a/b")) assertFailsWith<IllegalArgumentException>(name) { RelativePath.ROOT.child(name) }
    }

    @Test
    fun `screen elements are the same control when their identity matches, wherever they moved`() {
        val e = ScreenElement(3, 7, "com.shop", "Button", text = "Next", resourceId = "com.shop:id/next", bounds = ScreenBounds(0, 0, 10, 10))
        assertTrue(e.sameControlAs(e.copy(snapshotId = 4, number = 2, bounds = ScreenBounds(0, 50, 10, 60), focused = true)))
        assertFalse(e.sameControlAs(e.copy(text = "Pay")))
        assertFalse(e.sameControlAs(e.copy(packageName = "com.other")))
        assertFalse(e.sameControlAs(e.copy(resourceId = null)))
        // Repeated controls differ by where they are and by node identity.
        assertFalse(e.sameControlAs(e.copy(context = "Row: Invoice 42")))
        assertFalse(e.sameControlAs(e.copy(nodeKey = "0/3/1")))
        assertFalse(e.sameControlAs(e.copy(label = "Next page")))
    }
}
