package com.aicontrolcenter.app.ui.tools

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.raise
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DeviceAccessState
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.tools.CapabilityConstraint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Tools section's five view models over a real AppGraph with the starter team. */
class ToolsViewModelsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private suspend fun TestScope.started(): TestApp = TestApp(backgroundScope).also { it.graph.start().join() }

    private suspend fun TestApp.id(name: String): AgentId = agentNamed(name).profile.id

    private fun TestApp.familyOf(): (ToolId) -> ToolFamily? = { id -> graph.toolRegistry.byId(id)?.spec?.let(ToolFamily::of) }

    // --- Tools ---

    private fun TestScope.catalog(app: TestApp) =
        ToolsCatalogViewModel(app.graph.registryDirectory, app.graph.toolRegistry.all().map { it.spec }, app.graph.capabilities::info)
            .also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `every phone operator tool has plain words for the owner, and every family holds what its category says`() {
        val missing = OperatorTools.all.map { it.id.value }.filter { it !in PLAIN_WORDS }
        assertEquals(emptyList(), missing)
        assertEquals(OperatorTools.all.map { it.id.value }.toSet(), PLAIN_WORDS.keys, "no words for a tool that does not exist")
    }

    @Test
    fun `the catalogue lists every tool by family, with its risk, the data it sends, its permission and its agents`() = runTest(mainRule.dispatcher) {
        val app = started()
        val state = catalog(app).uiState.value

        assertEquals(app.graph.toolRegistry.all().size, state.toolCount)
        assertEquals(ToolFamily.entries.filter { f -> state.families.any { it.family == f } }, state.families.map { it.family }, "catalogue order")
        val workspace = state.families.first { it.family == ToolFamily.WORKSPACE }
        val write = workspace.tools.first { it.id == ToolId("workspace.write") }
        assertEquals("Creates a text file in the workspace, or replaces one. A replaced file goes to the workspace trash.", write.plainWords)
        assertEquals("Makes changes", write.risk.text)
        assertEquals("Change the workspace", write.permission)
        assertEquals("Needs your permission", write.permissionNote)
        assertEquals(listOf("Operator"), write.agents.map { it.name }, "only the Operator has the phone tools switched on")
        assertEquals("Allowed for every agent", workspace.tools.first { it.id == ToolId("workspace.read") }.permissionNote)

        val web = state.families.first { it.family == ToolFamily.WEB }.tools.first { it.id == ToolId("web.read_page") }
        assertTrue(web.sendsDataOffPhone)
        assertEquals("Needs your permission, folder by folder", state.families.first { it.family == ToolFamily.FOLDERS }.tools.first().permissionNote)
        assertEquals("Needs your permission, app by app", state.families.first { it.family == ToolFamily.SCREEN }.tools.first().permissionNote)
        assertEquals("Hard to undo", state.families.first { it.family == ToolFamily.SCRIPTS }.tools.first { it.id == ToolId("scripts.run_shell") }.risk.text)
        // Built-in tools keep the first sentence of their description.
        assertTrue(state.families.first { it.family == ToolFamily.MEMORY }.tools.all { it.plainWords.isNotBlank() })
    }

    @Test
    fun `the photo, install and Termux families show what Android must allow, read again on return`() = runTest(mainRule.dispatcher) {
        val app = started()
        val states = mutableMapOf(
            DeviceAccess.MEDIA to DeviceAccessState.SELECTED_ONLY,
            DeviceAccess.INSTALL_UNKNOWN_APPS to DeviceAccessState.NOT_ALLOWED,
            DeviceAccess.TERMUX_RUN_COMMAND to DeviceAccessState.NOT_INSTALLED,
        )
        val vm = ToolsCatalogViewModel(app.graph.registryDirectory, app.graph.toolRegistry.all().map { it.spec }, app.graph.capabilities::info) { states.getValue(it) }
            .also { it.uiState.activeIn(backgroundScope) }
        fun access(family: ToolFamily) = vm.uiState.value.families.first { it.family == family }.access

        val media = access(ToolFamily.MEDIA).single()
        assertEquals("Allowed for the photos and videos you selected only.", media.status)
        assertEquals("Change selection", media.requestLabel)
        val install = access(ToolFamily.APPS).single()
        assertEquals(DeviceAccess.INSTALL_UNKNOWN_APPS, install.access)
        assertNull(install.requestLabel, "only Android's settings page turns it on")
        assertEquals("Open Android's setting", install.settingsLabel)
        val termux = access(ToolFamily.SCRIPTS).single()
        assertEquals("Termux is not installed, so Termux commands cannot run.", termux.status)
        assertNull(termux.requestLabel)
        assertTrue(access(ToolFamily.WORKSPACE).isEmpty())

        // The owner allowed installs in Android's settings and came back.
        states[DeviceAccess.INSTALL_UNKNOWN_APPS] = DeviceAccessState.ALLOWED
        vm.refreshAccess()
        assertEquals(DeviceAccessState.ALLOWED, access(ToolFamily.APPS).single().state)

        // Android's dialog was answered no: the owner is told where else to allow it.
        states[DeviceAccess.MEDIA] = DeviceAccessState.NOT_ALLOWED
        vm.permissionAnswered(DeviceAccess.MEDIA)
        assertEquals("Allow photos and videos", access(ToolFamily.MEDIA).single().requestLabel)
        assertTrue("App info" in vm.uiState.value.message!!.text)
        vm.messageShown()
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun `a family says when a switch in Settings or local-only mode blocks it`() = runTest(mainRule.dispatcher) {
        val app = started()
        val vm = catalog(app)
        fun blocked(family: ToolFamily) = vm.uiState.value.families.first { it.family == family }.blockedBecause

        assertEquals("Phone operator is off in Settings, so no agent can use these tools.", blocked(ToolFamily.WORKSPACE))
        assertNull(blocked(ToolFamily.MEMORY))
        assertNull(blocked(ToolFamily.INFORMATION), "asking for a permission is not a phone tool")

        app.settings.update { it.copy(operatorEnabled = true) }
        assertNull(blocked(ToolFamily.WORKSPACE))
        assertEquals("Screen control is off in Settings, so no agent can use these tools.", blocked(ToolFamily.SCREEN))

        app.settings.update { it.copy(accessibilityOperatorEnabled = true, localOnlyMode = true) }
        assertNull(blocked(ToolFamily.SCREEN))
        assertEquals("Local-only mode is on, so these are blocked: they send a web address off this phone.", blocked(ToolFamily.WEB))
        assertTrue("shell and Termux commands are blocked" in blocked(ToolFamily.SCRIPTS)!!)
        assertTrue("links are not opened" in blocked(ToolFamily.APPS)!!)
    }

    // --- Permissions ---

    private fun TestScope.permissions(app: TestApp, agent: AgentId? = null) =
        PermissionsViewModel(
            app.graph.ownerGrants, app.graph.registryDirectory, app.graph.ownerFolders, app.graph.ownerOperatorApps,
            app.graph.capabilities::info, app.graph.capabilities.all(), app.clock, agent, ZoneOffset.UTC,
        ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `permissions are listed per agent with scope, target and origin, and can be narrowed to one agent`() = runTest(mainRule.dispatcher) {
        val app = started()
        val operator = app.id("Operator")
        val researcher = app.id("Researcher")
        app.folderAccess.grant("content://tree/documents", "Documents")
        val folder = (app.graph.ownerFolders.add("content://tree/documents") as com.aicontrolcenter.core.operator.OwnerResult.Done).value
        app.graph.ownerGrants.grant(operator, OperatorCapabilities.FOLDERS_READ, CapabilityConstraint.Folder(folder.id))
        app.graph.ownerGrants.grant(operator, OperatorCapabilities.WEB_READ)
        app.permissions.insert(
            PermissionGrant(GrantId("grt_task"), researcher, OperatorCapabilities.WEB_READ, scope = GrantScope.TASK, taskId = TaskId("tsk_1"), requestId = RequestId("req_1"), grantedAt = app.clock.current),
        )
        val vm = permissions(app)
        val state = vm.uiState.value

        assertEquals(listOf("Operator", "Researcher"), state.groups.map { it.agent.name }, "only agents with a grant")
        val operatorGrants = state.groups.first().grants
        assertEquals(listOf("Read a shared folder", "Read web pages"), operatorGrants.map { it.permission })
        assertEquals("Folder “Documents”", operatorGrants.first().target)
        assertEquals("Always", operatorGrants.first().scope)
        assertTrue(operatorGrants.first().granted.endsWith("by you, directly"))
        val taskGrant = state.groups.last().grants.single()
        assertEquals("For the task", taskGrant.scope)
        assertTrue(taskGrant.granted.endsWith("in answer to a request"))
        assertTrue("Read the workspace" in state.allowedForEveryone && "Basic information" in state.allowedForEveryone)

        vm.select(researcher)
        assertEquals(listOf("Researcher"), vm.uiState.value.groups.map { it.agent.name })
        vm.select(app.id("Writer"))
        assertEquals(emptyList(), vm.uiState.value.groups.single().grants, "an agent without grants still shows, with none")
    }

    @Test
    fun `revoking one permission, then all of an agent's, ends them at once`() = runTest(mainRule.dispatcher) {
        val app = started()
        val operator = app.id("Operator")
        app.graph.ownerGrants.grant(operator, OperatorCapabilities.WEB_READ)
        app.graph.ownerGrants.grant(operator, OperatorCapabilities.APPS_OPEN)
        app.graph.ownerGrants.grant(operator, OperatorCapabilities.SCRIPTS_JS)
        val vm = permissions(app, operator)
        val web = vm.uiState.value.groups.single().grants.first { it.permission == "Read web pages" }

        vm.revoke(web.id)
        assertEquals("Removed. The agent asks you again the next time it needs this.", vm.uiState.value.message?.text)
        assertEquals(2, vm.uiState.value.groups.single().grants.size)
        assertTrue(app.permissions.observeAll().first().none { it.capability == OperatorCapabilities.WEB_READ })

        vm.revoke(web.id)
        assertEquals("That permission had already ended. Nothing changed.", vm.uiState.value.message?.text)

        vm.revokeAll(operator)
        assertEquals("2 permissions removed. The agent asks you again when it needs one.", vm.uiState.value.message?.text)
        assertEquals(emptyList(), vm.uiState.value.groups.single().grants)
    }

    @Test
    fun `a one-time grant past its ten minutes is shown greyed with the reason`() = runTest(mainRule.dispatcher) {
        val app = started()
        val operator = app.id("Operator")
        val long = app.clock.current - 60 * 60_000L
        app.permissions.insert(
            PermissionGrant(
                GrantId("grt_once"), operator, OperatorCapabilities.WEB_READ, scope = GrantScope.ONCE, requestId = RequestId("req_once"),
                grantedAt = long, expiresAt = long + PermissionGrant.ONCE_LIFETIME_MS,
            ),
        )
        val grant = permissions(app).uiState.value.groups.single().grants.single()
        assertEquals("Just once", grant.scope)
        assertFalse(grant.isActive)
        assertEquals("Not used within 10 minutes, so it no longer counts", grant.inactiveReason)
    }

    // --- Folders ---

    private fun TestScope.folders(app: TestApp) =
        FoldersViewModel(app.graph.ownerFolders, app.graph.ownerGrants, app.graph.registryDirectory, app.familyOf(), app.clock, ZoneOffset.UTC)
            .also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `a picked folder is kept, a refused one is not, and cancelling the picker does nothing`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.folderAccess.pickable["content://tree/documents"] = "Documents"
        val vm = folders(app)

        vm.add(null)
        assertNull(vm.uiState.value.message)
        vm.add("content://tree/unknown")
        assertEquals("That folder cannot be used.", vm.uiState.value.message?.text)
        assertEquals(emptyList(), vm.uiState.value.folders)

        vm.add("content://tree/documents")
        assertEquals("“Documents” is shared. No agent may use it until you choose who below.", vm.uiState.value.message?.text)
        val folder = vm.uiState.value.folders.single()
        assertEquals("Documents", folder.name)
        assertFalse(folder.readOnly)
        assertTrue(folder.agents.all { it.level == AccessLevel.NONE }, "picking a folder gives nobody access")
        assertTrue("content://tree/documents" in app.folderAccess.granted, "Android's access is kept")
    }

    @Test
    fun `choosing who may use a folder gives and revokes standing grants narrowed to it`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.folderAccess.pickable["content://tree/documents"] = "Documents"
        val vm = folders(app)
        vm.add("content://tree/documents")
        val folderId = vm.uiState.value.folders.single().id
        val operator = app.id("Operator")
        val constraint = CapabilityConstraint.Folder(folderId)
        suspend fun capabilities() = app.permissions.observeAll().first().filter { it.agentId == operator && it.constraint == constraint }.map { it.capability }.toSet()

        vm.setAccess(folderId, operator, AccessLevel.READ)
        assertEquals("May now read this folder.", vm.uiState.value.message?.text)
        assertEquals(setOf(OperatorCapabilities.FOLDERS_READ), capabilities())
        assertTrue(app.permissions.observeAll().first().all { it.scope == GrantScope.ALWAYS })

        vm.setAccess(folderId, operator, AccessLevel.CHANGE)
        assertEquals(setOf(OperatorCapabilities.FOLDERS_READ, OperatorCapabilities.FOLDERS_WRITE), capabilities())
        val access = vm.uiState.value.folders.single().agents
        assertEquals(AccessLevel.CHANGE, access.first { it.agent.name == "Operator" }.level)
        assertTrue(access.first { it.agent.name == "Operator" }.hasTools)
        assertFalse(access.first { it.agent.name == "Writer" }.hasTools, "the Writer has no folder tools switched on")

        // A grant for one task, from an answer to a request, also ends when access is taken away.
        app.permissions.insert(
            PermissionGrant(GrantId("grt_task"), operator, OperatorCapabilities.FOLDERS_WRITE, constraint, GrantScope.TASK, TaskId("tsk_1"), RequestId("req_1"), app.clock.current),
        )
        assertEquals("Also allowed for a task in progress (see Allowed).", vm.uiState.value.folders.single().agents.first { it.agent.name == "Operator" }.note)
        vm.setAccess(folderId, operator, AccessLevel.READ)
        assertEquals(setOf(OperatorCapabilities.FOLDERS_READ), capabilities())
        vm.setAccess(folderId, operator, AccessLevel.NONE)
        assertEquals(emptySet(), capabilities())
        assertEquals("No access any more. Its permissions for this folder were removed.", vm.uiState.value.message?.text)
    }

    @Test
    fun `read-only and removal go through the owner's folder service, which narrows first`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.folderAccess.pickable["content://tree/documents"] = "Documents"
        val vm = folders(app)
        vm.add("content://tree/documents")
        val folderId = vm.uiState.value.folders.single().id
        vm.setAccess(folderId, app.id("Operator"), AccessLevel.CHANGE)

        vm.setReadOnly(folderId, true)
        assertTrue(vm.uiState.value.folders.single().readOnly)
        assertEquals("“Documents” is read-only: no agent can change anything in it.", vm.uiState.value.message?.text)

        vm.remove(folderId)
        assertEquals("Folder removed. Every permission for it was removed first.", vm.uiState.value.message?.text)
        assertEquals(emptyList(), vm.uiState.value.folders)
        assertEquals(emptyList(), app.permissions.observeAll().first())
        assertTrue("releaseAccess content://tree/documents" in app.folderAccess.calls)
        assertTrue(app.activityLog.all.any { it.title == "Folder removed: Documents" })

        vm.setAccess(folderId, app.id("Operator"), AccessLevel.READ)
        assertEquals("That folder was removed.", vm.uiState.value.message?.text, "the folder service refuses a removed folder")
    }

    // --- Screen control ---

    private fun TestScope.screen(app: TestApp, refreshed: MutableList<Unit> = mutableListOf()) =
        ScreenControlViewModel(
            serviceState = app.graph.screenSessions.state,
            control = app.graph.screenControl,
            settings = app.settings,
            apps = app.graph.ownerOperatorApps,
            grants = app.graph.ownerGrants,
            directory = app.graph.registryDirectory,
            installed = app.graph.installedApps,
            isProtected = app.graph::isProtectedPackage,
            familyOfTool = app.familyOf(),
            refreshService = { refreshed += Unit },
            clock = app.clock,
            zone = ZoneOffset.UTC,
            registry = app.graph.aiAppRegistry,
        ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `the service state is told in plain words, with the steps and buttons that fit it`() {
        val off = serviceStatusOf(ScreenServiceState.NOT_ENABLED)
        assertEquals("Off", off.label.text)
        assertTrue(off.offerAccessibilitySettings && !off.offerAppInfo)
        assertTrue(off.steps.any { "AI Control Center operator" in it })

        val restricted = serviceStatusOf(ScreenServiceState.RESTRICTED)
        assertEquals("Blocked by restricted settings", restricted.label.text)
        assertTrue(restricted.offerAppInfo && restricted.offerAccessibilitySettings)
        assertTrue(restricted.steps.any { "Allow restricted settings" in it })

        val protection = serviceStatusOf(ScreenServiceState.ADVANCED_PROTECTION)
        assertEquals("Off: Advanced Protection", protection.label.text)
        assertTrue(protection.steps.isEmpty() && !protection.offerAccessibilitySettings, "nothing the owner can switch here")

        assertEquals("Ready", serviceStatusOf(ScreenServiceState.READY).label.text)
        assertEquals("Starting", serviceStatusOf(ScreenServiceState.STARTING).label.text)
        // Round 2, S6: until Android's settings have been read in the background, the screen says so and offers no steps.
        val checking = serviceStatusOf(ScreenServiceState.CHECKING)
        assertEquals("Checking", checking.label.text)
        assertTrue(checking.steps.isEmpty() && !checking.offerAccessibilitySettings && !checking.offerAppInfo)
        assertEquals(ScreenServiceState.CHECKING, ScreenControlUiState().service.state, "before the first state arrives the screen claims nothing")
        assertEquals("AB:CD:EF:01 … 89:AB", ScreenControlViewModel.fingerprint("abcdef01" + "23".repeat(26) + "89ab"))
    }

    @Test
    fun `the tab follows the service and the switches, reads the service again when shown, and Stop and Resume are the owner's`() = runTest(mainRule.dispatcher) {
        val app = started()
        val refreshed = mutableListOf<Unit>()
        app.screen.setState(ScreenServiceState.RESTRICTED)
        val vm = screen(app, refreshed)
        assertEquals(1, refreshed.size, "read again when the tab is created")
        assertEquals(ScreenServiceState.RESTRICTED, vm.uiState.value.service.state)
        assertFalse(vm.uiState.value.operatorEnabled)
        vm.refresh()
        assertEquals(2, refreshed.size)

        app.screen.setState(ScreenServiceState.READY)
        app.settings.update { it.copy(operatorEnabled = true, accessibilityOperatorEnabled = true) }
        assertTrue(vm.uiState.value.ready)

        vm.stop()
        assertTrue(app.screen.stopped.value, "Stop goes to the screen operator's own switch")
        assertTrue(vm.uiState.value.stopped)
        assertFalse(vm.uiState.value.ready)
        vm.resume()
        assertFalse(app.screen.stopped.value)
        assertEquals("Resumed. Agents can use the authorised apps again, with your permission.", vm.uiState.value.message?.text)
    }

    @Test
    fun `authorising an installed app records its certificate, and protected apps and unreadable certificates are refused`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.installSampleApps()
        app.certificates["org.example.notes"] = "12".repeat(32)
        val vm = screen(app)

        vm.openPicker()
        val picker = assertNotNull(vm.uiState.value.picker)
        assertEquals(listOf("Notes", "Settings", "Terminal"), picker.apps.map { it.label })
        val settings = picker.apps.first { it.packageName == "com.android.settings" }
        assertFalse(settings.selectable)
        assertEquals("Never operated: it can change the phone's security", settings.note)

        vm.authorise("org.example.notes", "Notes")
        assertNull(vm.uiState.value.picker, "the picker closes")
        assertEquals("12".repeat(32), app.operatorApps.get("org.example.notes")?.signingCertSha256)
        val shown = vm.uiState.value.apps.single()
        assertTrue(shown.inEffect)
        assertEquals("Checked: this exact app. A look-alike app with the same name is not trusted.", shown.certificate)
        assertEquals("Signing certificate fingerprint: 12:12:12:12 … 12:12", shown.fingerprint)

        vm.openPicker()
        assertEquals("Authorised", vm.uiState.value.picker?.apps?.first { it.packageName == "org.example.notes" }?.note)
        vm.authorise("org.example.terminal", "Terminal")
        assertEquals(
            "Terminal could not be checked (its signing certificate could not be read), so it was not authorised. Is it still installed?",
            vm.uiState.value.message?.text,
        )
        assertNull(app.operatorApps.get("org.example.terminal"))
        vm.authorise("com.android.settings", "Settings")
        assertEquals("Settings is never operated by agents: it can change the phone's security.", vm.uiState.value.message?.text)
    }

    /** Review 5, P4: every authorised app asks before every action until the owner chooses to be asked less. */
    @Test
    fun `an authorised app asks before every action until the owner chooses fewer questions, and can go back`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.installSampleApps()
        app.certificates["org.example.notes"] = "12".repeat(32)
        val vm = screen(app)
        vm.authorise("org.example.notes", "Notes")
        assertEquals(ScreenActionPolicy.ASK_EVERY_ACTION, vm.uiState.value.apps.single().policy)
        assertEquals(ScreenActionPolicy.ASK_EVERY_ACTION, app.operatorApps.get("org.example.notes")?.policy)

        vm.setPolicy("org.example.notes", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
        assertEquals("Only actions recognised as buying, sending, deleting and similar ask you first in Notes now.", vm.uiState.value.message?.text)
        assertEquals(ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY, vm.uiState.value.apps.single().policy)
        assertEquals(ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY, app.operatorApps.get("org.example.notes")?.policy)

        vm.setPolicy("org.example.notes", ScreenActionPolicy.ASK_EVERY_ACTION)
        assertEquals("Every action in Notes asks you first now.", vm.uiState.value.message?.text)
        assertEquals(ScreenActionPolicy.ASK_EVERY_ACTION, app.operatorApps.get("org.example.notes")?.policy)
        vm.setPolicy("org.example.gone", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
        assertEquals("That app is not authorised any more.", vm.uiState.value.message?.text)
        // The owner is told plainly what the fewer questions can miss.
        val relaxed = ScreenPolicyText.description(ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY, "Notes")
        assertTrue("can be missed" in relaxed && "Other actions in Notes run without asking" in relaxed, relaxed)
        assertTrue("Every tap, typing, scroll, Back and Home" in ScreenPolicyText.description(ScreenActionPolicy.ASK_EVERY_ACTION, "Notes"))
    }

    @Test
    fun `who may see or use an app is kept as standing grants for that app, and withdrawing revokes them`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.installSampleApps()
        app.certificates["org.example.notes"] = "12".repeat(32)
        val vm = screen(app)
        vm.authorise("org.example.notes", "Notes")
        val operator = app.id("Operator")

        vm.setAccess("org.example.notes", operator, AccessLevel.CHANGE)
        val grants = app.permissions.observeAll().first()
        assertEquals(setOf(OperatorCapabilities.SCREEN_READ, OperatorCapabilities.SCREEN_OPERATE), grants.map { it.capability }.toSet())
        assertTrue(grants.all { it.constraint == CapabilityConstraint.App("org.example.notes") && it.scope == GrantScope.ALWAYS })
        assertEquals(AccessLevel.CHANGE, vm.uiState.value.apps.single().agents.first { it.agent.name == "Operator" }.level)

        vm.withdraw("org.example.notes")
        assertEquals("Removed. Every permission for it was removed too, and no agent can operate it.", vm.uiState.value.message?.text)
        assertEquals(emptyList(), app.permissions.observeAll().first())
        assertEquals(emptyList(), vm.uiState.value.apps)
    }

    @Test
    fun `authorising an AI app here keeps the registry's display flag in step, and withdrawing clears it`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.installSampleApps()
        app.graph.aiAppRegistry.discover()
        val vm = screen(app)

        vm.authorise("com.example.chatbot", "Example Chatbot")
        assertTrue(app.appNamed("Example Chatbot").accessibilityAuthorized)
        vm.withdraw("com.example.chatbot")
        assertFalse(app.appNamed("Example Chatbot").accessibilityAuthorized)
        assertNull(app.operatorApps.get("com.example.chatbot"))
    }

    @Test
    fun `an app authorised without a certificate is shown as not in effect`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.operatorApps.upsert(com.aicontrolcenter.core.operator.OperatorApp("org.example.notes", "Notes", app.clock.current, signingCertSha256 = null))
        val shown = screen(app).uiState.value.apps.single()
        assertFalse(shown.inEffect)
        assertEquals("Not checked when it was authorised, so agents cannot use it. Authorise it again to check it.", shown.certificate)
        assertNull(shown.fingerprint)
    }

    // --- Requests ---

    private fun TestScope.requests(app: TestApp) =
        RequestsViewModel(app.graph.ownerRequests, app.graph.registryDirectory, app.graph.capabilities::info, app.clock, ZoneOffset.UTC, flowOf(Unit))
            .also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `B2 - in the history a refused permission offers to let its agent ask again, and doing so ends the wait`() = runTest(mainRule.dispatcher) {
        val app = started()
        val operator = app.id("Operator")
        app.raise(samplePermission(operator, agentName = "Operator", id = "req_refused", createdAt = app.clock.current, minutes = 240))
        app.raise(sampleApproval(operator, id = "req_denied_approval", createdAt = app.clock.current, minutes = 240))
        app.raise(samplePermission(app.id("Researcher"), id = "req_waiting", createdAt = app.clock.current, minutes = 240))
        app.graph.ownerRequests.deny(RequestId("req_refused"))
        app.graph.ownerRequests.deny(RequestId("req_denied_approval"))
        // The test clock jumps ahead on every read: back to just after the refusal, so the ten minutes are not over.
        app.clock.current = app.ownerRequestStore.get(RequestId("req_refused"))!!.decidedAt!! + 1_000
        val vm = requests(app)
        fun shown(id: String) = vm.uiState.value.requests.first { it.id.value == id }

        assertTrue(assertNotNull(shown("req_refused").askAgain).canLetAskAgain)
        assertNull(shown("req_denied_approval").askAgain, "an approval is always put to the owner again")
        assertNull(shown("req_waiting").askAgain)

        vm.letAskAgain(RequestId("req_refused"))
        assertEquals("Operator can ask again. Its next call or request for this permission is put to you.", vm.uiState.value.message?.text)
        val opened = assertNotNull(shown("req_refused").askAgain)
        assertFalse(opened.canLetAskAgain)
        assertTrue(opened.text.startsWith("You let Operator ask again ("), opened.text)
        assertNull(app.ownerRequestStore.lastRefusal(operator, OperatorCapabilities.WEB_READ, CapabilityConstraint.None, 0))
        assertTrue(app.permissions.observeAll().first().isEmpty(), "nothing was given")
        vm.messageShown()
        assertNull(vm.uiState.value.message)

        // Not a refused permission: said, nothing changes.
        vm.letAskAgain(RequestId("req_waiting"))
        assertEquals("Only a permission you refused keeps an agent from asking again.", vm.uiState.value.message?.text)
        assertNull(app.ownerRequestStore.get(RequestId("req_waiting"))?.askAgainAt)
    }

    @Test
    fun `the history lists every request newest first and filters by kind, outcome and agent`() = runTest(mainRule.dispatcher) {
        val app = started()
        val now = app.clock.current
        val operator = app.id("Operator")
        val researcher = app.id("Researcher")
        app.raise(sampleApproval(operator, id = "req_a", createdAt = now - 3_000))
        app.raise(samplePermission(researcher, id = "req_b", createdAt = now - 2_000))
        app.raise(samplePermission(operator, agentName = "Operator", id = "req_c", createdAt = now - 1_000, taskId = null, rootId = null))
        app.raise(sampleApproval(operator, id = "req_old", createdAt = now - 2 * 60 * 60_000L, minutes = 5))
        app.graph.ownerRequests.deny(RequestId("req_a"))
        app.graph.ownerRequests.grant(RequestId("req_c"), GrantScope.ALWAYS)
        val vm = requests(app)

        assertEquals(listOf("req_c", "req_b", "req_a", "req_old"), vm.uiState.value.requests.map { it.id.value })
        assertEquals(listOf("Operator", "Researcher"), vm.uiState.value.agents.map { it.name })

        vm.setKind(OwnerRequestKind.APPROVAL)
        assertEquals(listOf("req_a", "req_old"), vm.uiState.value.requests.map { it.id.value })
        vm.setOutcome(RequestOutcome.EXPIRED)
        assertEquals(listOf("req_old"), vm.uiState.value.requests.map { it.id.value }, "pending past its expiry counts as expired")
        vm.setKind(OwnerRequestKind.APPROVAL)
        assertNull(vm.uiState.value.filter.kind, "tapping the chosen kind again clears it")

        vm.clearFilter()
        vm.setOutcome(RequestOutcome.WAITING)
        assertEquals(listOf("req_b"), vm.uiState.value.requests.map { it.id.value })
        vm.setOutcome(RequestOutcome.ALLOWED)
        assertEquals(listOf("req_c"), vm.uiState.value.requests.map { it.id.value })
        assertEquals("Always allowed", vm.uiState.value.requests.single().status.text)

        vm.clearFilter()
        vm.setAgent(researcher)
        assertEquals(listOf("req_b"), vm.uiState.value.requests.map { it.id.value })
        assertEquals(4, vm.uiState.value.total)
        assertTrue(vm.uiState.value.filter.isActive)
    }

    @Test
    fun `an empty history is empty`() = runTest(mainRule.dispatcher) {
        val app = started()
        val state = requests(app).uiState.value
        assertFalse(state.isLoading)
        assertEquals(emptyList(), state.requests)
        assertFalse(state.filter.isActive)
    }

    @Test
    fun `the folder and app access levels name what they give`() {
        assertEquals(listOf("No access", "Read", "Read and change"), FoldersViewModel.KIND.labels)
        assertEquals(listOf("No access", "See", "See and use"), ScreenControlViewModel.KIND.labels)
        assertEquals(OperatorCapabilities.SCREEN_OPERATE, ScreenControlViewModel.KIND.change)
        assertEquals("Folder “removed folder”", PermissionsViewModel.targetLabel(CapabilityConstraint.Folder(FolderId("fld_x")), emptyList(), emptyList()))
        assertEquals("App “org.example.notes”", PermissionsViewModel.targetLabel(CapabilityConstraint.App("org.example.notes"), emptyList(), emptyList()))
    }

    @Test
    fun `installed app info needs a package name to be offered`() = runTest(mainRule.dispatcher) {
        val app = started()
        app.scanner.installed = listOf(InstalledAppInfo("", "Nameless"), InstalledAppInfo("org.example.notes", "Notes"))
        val vm = screen(app)
        vm.openPicker()
        assertEquals(listOf("Notes"), vm.uiState.value.picker?.apps?.map { it.label })
        vm.closePicker()
        assertNull(vm.uiState.value.picker)
    }
}
