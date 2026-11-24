package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.grantId
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.CapabilityRequester
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.testkit.OperatorWorld
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A world with one agent, one folder ("Reports") and one authorised app ("Notes"), as both suites below start from. */
private suspend fun OperatorWorld.furnished(): Triple<AgentProfile, SharedFolder, OperatorApp> {
    val agent = Samples.agent(id = "agt_op", name = "Operator").copy(toolIds = setOf(ToolId("permissions.request")))
    agents.upsert(agent)
    gateway.grant("content://tree/reports", "Reports")
    val folder = SharedFolder(FolderId("fld_reports"), "content://tree/reports", "Reports", 10)
    folders.upsert(folder)
    val app = OperatorApp("com.example.notes", "Notes", 20, "ab".repeat(32))
    apps.upsert(app)
    return Triple(agent, folder, app)
}

/**
 * What every [CapabilityRequester] must do. Subclasses build the implementation under test over
 * the [OperatorWorld] they are given (its repositories, settings, Stop flag, protected packages,
 * clock and ids).
 */
abstract class CapabilityRequesterContract {
    protected abstract fun createRequester(world: OperatorWorld): CapabilityRequester

    private val folders = OperatorCapabilities.FOLDERS_READ

    private fun context(agent: AgentProfile, task: String? = "tsk_1") =
        ToolContext(agent, task?.let(::TaskId), TaskId("tsk_root"), listOf(AgentId("agt_director")), conversationId = null)

    @Test
    fun `an ask raises a permission request with the agent's words, and grants nothing`() = runTest {
        val world = OperatorWorld()
        val (agent, folder, _) = world.furnished()
        val requester = createRequester(world)
        val reason = "I need \"Reports\" to total Q1,\nas you asked 🙂"
        val raised = assertIs<AskResult.Raised>(requester.ask(context(agent), folders, CapabilityConstraint.Folder(folder.id), reason))
        val request = world.requests.get(raised.requestId)!!
        assertEquals(OwnerRequestKind.PERMISSION, request.kind)
        assertNull(request.toolId)
        assertEquals(folders, request.capability)
        assertEquals(CapabilityConstraint.Folder(folder.id), request.constraint)
        assertEquals(reason, request.reason)
        assertEquals(TaskId("tsk_1"), request.taskId)
        assertEquals(TaskId("tsk_root"), request.rootId)
        assertEquals(listOf(AgentId("agt_director")), request.delegationChain)
        assertEquals("Operator", request.agentName)
        assertEquals("Reports", request.targetLabel)
        assertEquals(world.clock.current + 30 * 60_000L, request.expiresAt)
        assertTrue(request.risk >= RiskLevel.SAFE)
        assertTrue(world.grants.observeAll().first().isEmpty())
    }

    @Test
    fun `one pending ask per capability and target, and a few per agent`() = runTest {
        val world = OperatorWorld()
        val (agent, folder, app) = world.furnished()
        val requester = createRequester(world)
        val first = assertIs<AskResult.Raised>(requester.ask(context(agent), folders, CapabilityConstraint.Folder(folder.id), "To read it."))
        assertEquals(AskResult.AlreadyPending(first.requestId), requester.ask(context(agent, task = "tsk_2"), folders, CapabilityConstraint.Folder(folder.id), "Again."))
        assertIs<AskResult.Raised>(requester.ask(context(agent), OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App(app.packageName), "To read notes."))
        assertIs<AskResult.Raised>(requester.ask(context(agent), OperatorCapabilities.WEB_READ, CapabilityConstraint.None, "To read a page."))
        assertEquals(CapabilityRequester.MAX_PENDING_ASKS_PER_AGENT, world.requests.observePending().first().size)
        assertIs<AskResult.Refused>(requester.ask(context(agent), OperatorCapabilities.MEDIA_READ, CapabilityConstraint.None, "To list photos."))
        assertEquals(CapabilityRequester.MAX_PENDING_ASKS_PER_AGENT, world.requests.observePending().first().size)
    }

    @Test
    fun `nothing the broker would refuse is put to the owner`() = runTest {
        val world = OperatorWorld()
        val (agent, folder, app) = world.furnished()
        val requester = createRequester(world)
        val ok = "A reason."
        suspend fun refused(capability: Capability, constraint: CapabilityConstraint, reason: String = ok, who: AgentProfile = agent) =
            assertIs<AskResult.Refused>(requester.ask(context(who), capability, constraint, reason), "$capability $constraint")
        refused(Capability("made.up"), CapabilityConstraint.None)
        refused(folders, CapabilityConstraint.None)
        refused(OperatorCapabilities.WEB_READ, CapabilityConstraint.Folder(folder.id))
        refused(folders, CapabilityConstraint.Folder(FolderId("fld_gone")))
        refused(OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App("com.unknown.app"))
        refused(OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App(OperatorWorld.OWN_PACKAGE))
        refused(OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App("com.android.settings"))
        refused(OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App("com.oem.securitycenter"))
        refused(folders, CapabilityConstraint.Folder(folder.id), reason = "  ")
        refused(folders, CapabilityConstraint.Folder(folder.id), reason = "x".repeat(CapabilityRequester.MAX_REASON_CHARS + 1))
        world.screenStopped.value = true
        refused(OperatorCapabilities.SCREEN_OPERATE, CapabilityConstraint.App(app.packageName))
        world.screenStopped.value = false
        world.settings.update { it.copy(accessibilityOperatorEnabled = false) }
        refused(OperatorCapabilities.SCREEN_OPERATE, CapabilityConstraint.App(app.packageName))
        world.settings.update { it.copy(accessibilityOperatorEnabled = true, localOnlyMode = true) }
        refused(OperatorCapabilities.WEB_READ, CapabilityConstraint.None)
        world.settings.update { it.copy(localOnlyMode = false, operatorEnabled = false) }
        refused(folders, CapabilityConstraint.Folder(folder.id))
        world.settings.update { it.copy(operatorEnabled = true) }
        world.agents.upsert(agent.copy(enabled = false))
        refused(folders, CapabilityConstraint.Folder(folder.id))
        world.agents.delete(agent.id)
        refused(folders, CapabilityConstraint.Folder(folder.id))
        assertTrue(world.requests.observeRecent(100).first().isEmpty())
    }

    @Test
    fun `an ask that needs no answer says so`() = runTest {
        val world = OperatorWorld()
        val (agent, folder, _) = world.furnished()
        val requester = createRequester(world)
        assertEquals(AskResult.AlreadyGranted, requester.ask(context(agent), OperatorCapabilities.WORKSPACE_READ, CapabilityConstraint.None, "To read."))
        assertEquals(AskResult.AlreadyGranted, requester.ask(context(agent), Capabilities.BASIC_INFO, CapabilityConstraint.None, "To read."))
        val constraint = CapabilityConstraint.Folder(folder.id)
        world.grants.insert(PermissionGrant(world.ids.grantId(), agent.id, folders, constraint, GrantScope.TASK, TaskId("tsk_1"), grantedAt = 1))
        assertEquals(AskResult.AlreadyGranted, requester.ask(context(agent), folders, constraint, "To read."))
        assertIs<AskResult.Raised>(requester.ask(context(agent, task = "tsk_2"), folders, constraint, "To read."))
        assertTrue(world.requests.observePending().first().single().taskId == TaskId("tsk_2"))
    }
}

/**
 * What every implementation of the owner-only ports [OwnerGrants], [OwnerFolders] and
 * [OwnerOperatorApps] must do. Subclasses build them over the [OperatorWorld] they are given.
 */
abstract class OwnerPortsContract {
    protected abstract fun createGrants(world: OperatorWorld): OwnerGrants

    protected abstract fun createFolders(world: OperatorWorld): OwnerFolders

    protected abstract fun createApps(world: OperatorWorld): OwnerOperatorApps

    @Test
    fun `the owner grants directly, always, only what can be granted`() = runTest {
        val world = OperatorWorld()
        val (agent, folder, app) = world.furnished()
        val grants = createGrants(world)
        val onFolder = assertIs<OwnerResult.Done<PermissionGrant>>(grants.grant(agent.id, OperatorCapabilities.FOLDERS_WRITE, CapabilityConstraint.Folder(folder.id))).value
        assertEquals(GrantScope.ALWAYS, onFolder.scope)
        assertNull(onFolder.taskId)
        assertEquals(onFolder, world.grants.get(onFolder.id))
        assertEquals(OwnerResult.Done(onFolder), grants.grant(agent.id, OperatorCapabilities.FOLDERS_WRITE, CapabilityConstraint.Folder(folder.id)))
        assertIs<OwnerResult.Done<*>>(grants.grant(agent.id, OperatorCapabilities.SCREEN_OPERATE, CapabilityConstraint.App(app.packageName)))
        for ((capability, constraint) in listOf(
            Capability("made.up") to CapabilityConstraint.None,
            OperatorCapabilities.WORKSPACE_READ to CapabilityConstraint.None,
            OperatorCapabilities.FOLDERS_READ to CapabilityConstraint.None,
            OperatorCapabilities.WEB_READ to CapabilityConstraint.Folder(folder.id),
            OperatorCapabilities.FOLDERS_READ to CapabilityConstraint.Folder(FolderId("fld_gone")),
            OperatorCapabilities.SCREEN_READ to CapabilityConstraint.App("com.unknown.app"),
            OperatorCapabilities.SCREEN_READ to CapabilityConstraint.App(OperatorWorld.OWN_PACKAGE),
        )) {
            assertIs<OwnerResult.Refused>(grants.grant(agent.id, capability, constraint), "$capability $constraint")
        }
        assertIs<OwnerResult.Refused>(grants.grant(AgentId("agt_gone"), OperatorCapabilities.WEB_READ))
        assertEquals(2, world.grants.observeAll().first().size)
        assertTrue(grants.revoke(onFolder.id))
        assertTrue(!grants.revoke(onFolder.id))
        assertEquals(1, grants.revokeAll(agent.id))
        assertTrue(grants.observeAll().first().isEmpty())
    }

    @Test
    fun `adding and removing a folder keeps access, storage and grants in step`() = runTest {
        val world = OperatorWorld()
        val (agent, folder, _) = world.furnished()
        val owner = createFolders(world)
        world.gateway.pickable["content://tree/photos"] = "Photos"
        val photos = assertIs<OwnerResult.Done<SharedFolder>>(owner.add("content://tree/photos")).value
        assertEquals("Photos", photos.displayName)
        assertEquals(photos, world.folders.getByTreeUri("content://tree/photos"))
        assertTrue("content://tree/photos" in world.gateway.granted)
        assertEquals(OwnerResult.Done(photos), owner.add("content://tree/photos"))
        assertIs<OwnerResult.Refused>(owner.add("content://tree/not-picked"))
        assertNull(world.folders.getByTreeUri("content://tree/not-picked"))
        val onReports = PermissionGrant(GrantId("grt_reports"), agent.id, OperatorCapabilities.FOLDERS_READ, CapabilityConstraint.Folder(folder.id), GrantScope.ALWAYS, grantedAt = 1)
        val onPhotos = onReports.copy(id = GrantId("grt_photos"), constraint = CapabilityConstraint.Folder(photos.id))
        listOf(onReports, onPhotos).forEach { world.grants.insert(it) }
        assertEquals(folder.copy(readOnly = true), owner.setReadOnly(folder.id, true))
        assertEquals(true, world.folders.get(folder.id)?.readOnly)
        assertNull(owner.setReadOnly(FolderId("fld_gone"), true))
        assertTrue(owner.remove(folder.id))
        assertNull(world.folders.get(folder.id))
        assertTrue(folder.treeUri !in world.gateway.granted)
        assertTrue(world.grants.get(onReports.id)?.revokedAt != null)
        assertNull(world.grants.get(onPhotos.id)?.revokedAt)
        assertTrue(!owner.remove(folder.id))
        assertEquals(listOf(photos), owner.observeAll().first())
    }

    @Test
    fun `authorising and withdrawing an app keeps storage and grants in step`() = runTest {
        val world = OperatorWorld()
        val (agent, _, app) = world.furnished()
        val owner = createApps(world)
        for (bad in listOf(OperatorWorld.OWN_PACKAGE, "com.android.settings", "com.oem.securitycenter", "not a package")) {
            assertIs<OwnerResult.Refused>(owner.authorise(bad, "x", null), bad)
            assertNull(world.apps.get(bad))
        }
        val mail = assertIs<OwnerResult.Done<OperatorApp>>(owner.authorise("com.example.mail", "Mail", "cd".repeat(32))).value
        assertEquals("cd".repeat(32), world.apps.get("com.example.mail")?.signingCertSha256)
        val onNotes = PermissionGrant(GrantId("grt_notes"), agent.id, OperatorCapabilities.SCREEN_READ, CapabilityConstraint.App(app.packageName), GrantScope.ALWAYS, grantedAt = 1)
        val onMail = onNotes.copy(id = GrantId("grt_mail"), constraint = CapabilityConstraint.App(mail.packageName))
        listOf(onNotes, onMail).forEach { world.grants.insert(it) }
        // Same name, different signer: a different app, so earlier grants do not carry over.
        assertIs<OwnerResult.Done<*>>(owner.authorise("com.example.mail", "Mail", "ef".repeat(32)))
        assertTrue(world.grants.get(onMail.id)?.revokedAt != null)
        assertTrue(owner.withdraw(app.packageName))
        assertNull(world.apps.get(app.packageName))
        assertTrue(world.grants.get(onNotes.id)?.revokedAt != null)
        assertTrue(!owner.withdraw(app.packageName))
        assertEquals(listOf("com.example.mail"), owner.observeAll().first().map { it.packageName })
    }

    @Test
    fun `an app asks before every action until the owner chooses otherwise, and a different signer starts over`() = runTest {
        val world = OperatorWorld()
        val owner = createApps(world)
        val mail = assertIs<OwnerResult.Done<OperatorApp>>(owner.authorise("com.example.mail", "Mail", "cd".repeat(32))).value
        assertEquals(ScreenActionPolicy.ASK_EVERY_ACTION, mail.policy)
        assertEquals(ScreenActionPolicy.ASK_EVERY_ACTION, world.apps.get("com.example.mail")?.policy)
        val relaxed = owner.setPolicy("com.example.mail", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
        assertEquals(ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY, relaxed?.policy)
        assertEquals(ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY, world.apps.get("com.example.mail")?.policy)
        assertNull(owner.setPolicy("com.example.missing", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY))
        assertNull(world.apps.get("com.example.missing"))
        // Authorising the same app again keeps the owner's choice.
        assertEquals(
            ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY,
            assertIs<OwnerResult.Done<OperatorApp>>(owner.authorise("com.example.mail", "Mail", "cd".repeat(32))).value.policy,
        )
        // Another signer is another app: it asks every time again.
        assertEquals(
            ScreenActionPolicy.ASK_EVERY_ACTION,
            assertIs<OwnerResult.Done<OperatorApp>>(owner.authorise("com.example.mail", "Mail", "ef".repeat(32))).value.policy,
        )
        assertEquals(ScreenActionPolicy.ASK_EVERY_ACTION, world.apps.get("com.example.mail")?.policy)
    }
}
