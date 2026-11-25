package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.CapabilityRequester
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.FolderTrees
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerOperatorApps
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpSafety
import com.aicontrolcenter.core.requestId
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.tools.CapabilityRegistry
import com.aicontrolcenter.testkit.InMemoryActivityLog
import com.aicontrolcenter.testkit.InMemoryPickUpRuleRepository
import com.aicontrolcenter.testkit.OperatorWorld
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.contract.CapabilityRequesterContract
import com.aicontrolcenter.testkit.contract.OwnerPortsContract
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The framework's implementation of an operator port over an [OperatorWorld], with its log. */
private class Built(world: OperatorWorld) {
    val log = InMemoryActivityLog()
    val activity = ActivityRecorder(log, world.ids, world.clock)
    val capabilities = CapabilityRegistry(world.capabilities.values.toList())
    val facts = OperatorFacts(world.agents, world.settings, capabilities, world.folders.asDirectory(), world.apps.asAuthorisedApps(), world.screenStopped) { world.protectedPackages }
    val waiters = RequestWaiters()
}

/** The testkit's contract for [CapabilityRequester], run against [DefaultCapabilityRequester]. */
class DefaultCapabilityRequesterTest : CapabilityRequesterContract() {
    override fun createRequester(world: OperatorWorld): CapabilityRequester {
        val built = Built(world)
        return DefaultCapabilityRequester(built.facts, world.requests, world.grants, built.waiters, built.activity, world.ids, world.clock)
    }
}

/** The testkit's contract for the owner-only ports, run against the framework's implementations. */
class DefaultOwnerPortsTest : OwnerPortsContract() {
    override fun createGrants(world: OperatorWorld): OwnerGrants {
        val built = Built(world)
        return DefaultOwnerGrants(
            world.grants, world.agents, built.capabilities, world.folders, world.apps, { world.protectedPackages }, world.requests, built.waiters,
            built.activity, world.ids, world.clock,
        )
    }

    override fun createFolders(world: OperatorWorld): OwnerFolders =
        DefaultOwnerFolders(world.gateway, world.folders, world.grants, world.requests, Built(world).activity, world.ids, world.clock)

    override fun createApps(world: OperatorWorld): OwnerOperatorApps =
        DefaultOwnerOperatorApps(world.apps, world.grants, { world.protectedPackages }, world.requests, Built(world).activity, world.clock)
}

/** What the framework adds to the contracts: answering through [DefaultOwnerRequests], and the log of every change. */
class OwnerServicesTest {
    private fun request(world: OperatorWorld, kind: OwnerRequestKind, expiresIn: Long = 30 * 60_000L) = OwnerRequest(
        id = world.ids.requestId(), kind = kind, agentId = Samples.agent(id = "agt_op").id, capability = OperatorCapabilities.FOLDERS_READ,
        risk = RiskLevel.SAFE, summary = "Read Reports", createdAt = world.clock.now(), expiresAt = world.clock.now() + expiresIn,
        toolTitle = "Read a file",
    )

    @Test
    fun `answering is one atomic decision - a grant stored with it, an approval never granted, a second answer refused`() = runTest {
        val world = OperatorWorld()
        val built = Built(world)
        val owner = DefaultOwnerRequests(world.requests, built.waiters, built.facts, built.activity, world.ids, world.clock)
        val permission = request(world, OwnerRequestKind.PERMISSION).also { built.waiters.register(it.id); world.requests.create(it) }
        val approval = request(world, OwnerRequestKind.APPROVAL).also { built.waiters.register(it.id); world.requests.create(it) }

        assertIs<DecideResult.NotAccepted>(owner.grant(approval.id, GrantScope.ALWAYS), "an approval is yes or no, never \"always\"")
        assertIs<DecideResult.NotAccepted>(owner.approve(permission.id), "a permission is answered with a scope")
        val granted = assertIs<DecideResult.Decided>(owner.grant(permission.id, GrantScope.ALWAYS, "  Fine.  "))
        assertEquals(granted.grant, world.grants.findByRequest(permission.id))
        assertEquals("Fine.", granted.request.decisionNote, "the note is trimmed")
        assertIs<DecideResult.AlreadyClosed>(owner.deny(permission.id))
        val approved = assertIs<DecideResult.Decided>(owner.approve(approval.id, "   "))
        assertNull(approved.grant)
        assertNull(approved.request.decisionNote, "a blank note is no note")
        assertEquals(DecideResult.NotFound, owner.deny(world.ids.requestId()))
        assertEquals(
            listOf("Owner gave permission always: Read a file", "Owner approved: Read a file"),
            built.log.all.map { it.title },
        )
        assertEquals(listOf(ActivityType.PERMISSION, ActivityType.APPROVAL), built.log.all.map { it.type })
    }

    @Test
    fun `pending hides requests past their expiry, and a late answer only expires them`() = runTest {
        val world = OperatorWorld()
        val built = Built(world)
        val owner = DefaultOwnerRequests(world.requests, built.waiters, built.facts, built.activity, world.ids, world.clock)
        val soon = request(world, OwnerRequestKind.PERMISSION, expiresIn = 1_000).also { built.waiters.register(it.id); world.requests.create(it) }
        val later = request(world, OwnerRequestKind.PERMISSION).also { built.waiters.register(it.id); world.requests.create(it) }
        assertEquals(listOf(soon.id, later.id), owner.pending.first().map { it.id })
        world.clock.advance(1_000)
        assertEquals(listOf(later.id), owner.pending.first().map { it.id })
        assertIs<DecideResult.Expired>(owner.grant(soon.id, GrantScope.ALWAYS))
        assertEquals(OwnerRequestStatus.EXPIRED, world.requests.get(soon.id)?.status)
        assertTrue(world.grants.observeAll().first().isEmpty())
        assertEquals(listOf(later.id), owner.pending.first().map { it.id })
    }

    @Test
    fun `making a folder writable switches off folder pick-up from it and from folders around or inside it`() = runTest {
        // Second review of F1: the rule does not wait for a run to notice; the owner sees why at once.
        val world = OperatorWorld()
        val built = Built(world)
        val rules = InMemoryPickUpRuleRepository()
        val folders = DefaultOwnerFolders(world.gateway, world.folders, world.grants, world.requests, built.activity, world.ids, world.clock, rules)
        fun tree(path: String) = "content://${FolderTrees.EXTERNAL_STORAGE}/tree/primary%3A" + path.replace("/", "%2F")
        suspend fun share(path: String): SharedFolder {
            world.gateway.pickable[tree(path)] = path
            return assertIs<OwnerResult.Done<SharedFolder>>(folders.add(tree(path))).value
        }
        val inbox = share("Inbox")
        val notes = share("Docs/Notes")
        val apart = share("Music")
        for (folder in listOf(inbox, notes, apart)) assertEquals(world.clock.current, folders.setReadOnly(folder.id, true)!!.readOnlySince)
        for ((n, folder) in listOf(inbox, notes, apart).withIndex()) {
            rules.upsert(
                PickUpRule(
                    PickUpRuleId("pku_$n"), folder.id, SpaceId("spc_inbox"), enabled = true, createdAt = 1, updatedAt = 1,
                    folderReadOnlySince = world.folders.get(folder.id)!!.readOnlySince,
                ),
            )
        }
        // The owner makes Inbox writable: its rule is off at once, with the reason as its last result; the others run on.
        assertNull(folders.setReadOnly(inbox.id, false)!!.readOnlySince)
        assertEquals(false to PickUpSafety.RUN_FOLDER_NOT_READ_ONLY, rules.get(PickUpRuleId("pku_0"))!!.let { it.enabled to it.lastResult })
        assertTrue(rules.get(PickUpRuleId("pku_1"))!!.enabled && rules.get(PickUpRuleId("pku_2"))!!.enabled)
        // The owner shares "Docs", which holds Notes: Notes' rule is off too; Music is apart and stays on.
        share("Docs")
        assertEquals(false to PickUpSafety.RUN_FOLDER_OVERLAPS_WRITABLE, rules.get(PickUpRuleId("pku_1"))!!.let { it.enabled to it.lastResult })
        assertTrue(rules.get(PickUpRuleId("pku_2"))!!.enabled)
        assertTrue(built.log.all.any { it.title == "Folder pick-up switched off: Inbox" })
    }

    @Test
    fun `every change the owner makes to grants, folders and apps is logged`() = runTest {
        val world = OperatorWorld()
        val built = Built(world)
        val agent = Samples.agent(id = "agt_op", name = "Operator")
        world.agents.upsert(agent)
        val grants = DefaultOwnerGrants(
            world.grants, world.agents, built.capabilities, world.folders, world.apps, { world.protectedPackages }, world.requests, built.waiters,
            built.activity, world.ids, world.clock,
        )
        val folders = DefaultOwnerFolders(world.gateway, world.folders, world.grants, world.requests, built.activity, world.ids, world.clock)
        val apps = DefaultOwnerOperatorApps(world.apps, world.grants, { world.protectedPackages }, world.requests, built.activity, world.clock)

        world.gateway.pickable["content://tree/reports"] = "Reports"
        val folder = assertIs<com.aicontrolcenter.core.operator.OwnerResult.Done<com.aicontrolcenter.core.operator.SharedFolder>>(folders.add("content://tree/reports")).value
        val grant = assertIs<com.aicontrolcenter.core.operator.OwnerResult.Done<com.aicontrolcenter.core.operator.PermissionGrant>>(
            grants.grant(agent.id, OperatorCapabilities.FOLDERS_READ, CapabilityConstraint.Folder(folder.id)),
        ).value
        folders.setReadOnly(folder.id, true)
        grants.revoke(grant.id)
        apps.authorise("com.example.notes", "Notes", "ab".repeat(32))
        apps.withdraw("com.example.notes")
        folders.remove(folder.id)
        assertEquals(
            listOf(
                "Folder shared with agents: Reports", "Owner gave permission always: Read a shared folder", "Folder made read-only: Reports",
                "Owner revoked a permission: Read a shared folder", "App authorised for screen control: Notes",
                "App withdrawn from screen control: Notes", "Folder removed: Reports",
            ),
            built.log.all.map { it.title },
        )
    }
}
