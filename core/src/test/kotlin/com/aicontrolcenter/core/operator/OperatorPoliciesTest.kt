package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ConstraintKind
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OperatorPoliciesTest {
    @Test
    fun `previews show what a command really holds`() {
        // A carriage return and a wall of spaces would hide the dangerous tail.
        val hidden = PreviewText.exact("ls\r" + " ".repeat(200) + "rm -rf ~")
        assertEquals("ls⟨U+000D⟩␣×200rm -rf ~", hidden)
        assertEquals("invoice⟨U+202E⟩fdp.exe", PreviewText.exact("invoice‮fdp.exe"))
        assertEquals("pay⟨U+200B⟩pal.com", PreviewText.exact("pay​pal.com"))
        assertEquals("a⏎\nb", PreviewText.exact("a\nb"))
        assertEquals("a⏎×5\nb", PreviewText.exact("a\n\n\n\n\nb"))
        assertEquals("␣x y␣", PreviewText.exact(" x y "))
        assertEquals("a⇥b ⟨U+00A0⟩c ⟨U+E0041⟩", PreviewText.exact("a\tb  c 󠁁"))
        val plain = "Delete \"2024, Q1.csv\" in Reports ✓ 🙂"
        assertEquals(plain, PreviewText.exact(plain))
    }

    @Test
    fun `links are read one way, and only safe ones are allowed`() {
        assertEquals(LinkPolicy.Check.Allowed("https://Example.com/a?b=1", "https", "example.com"), LinkPolicy.check("  HTTPS://Example.com/a?b=1 "))
        assertIs<LinkPolicy.Check.Allowed>(LinkPolicy.check("geo:0,0?q=coffee"))
        assertIs<LinkPolicy.Check.Allowed>(LinkPolicy.check("spotify:track:123"))
        for (link in listOf(
            "intent://scan#Intent;end", "INTENT:#Intent;end", "file:///sdcard/x", "content://x/y", "javascript:alert(1)", " jaVascript:x", "data:text/html,x",
            "android-app://com.x/https/y", "mailto:a@b.c", "sms:123", "smsto:123", "tel:123", "java\tscript:x", "https://a.b/‮x",
            "https://a b", "no scheme", "", "%6Aavascript:x", "com.aicontrolcenter.app://approve", "https://com.aicontrolcenter.app/x",
            "https://" + "a".repeat(LinkPolicy.MAX_LENGTH),
        )) {
            assertIs<LinkPolicy.Check.Refused>(LinkPolicy.check(link, ownPackage = "com.aicontrolcenter.app"), link.take(40))
        }
        assertIs<LinkPolicy.Check.Refused>(LinkPolicy.check("org.renamed://x", ownPackage = "org.renamed"))
    }

    @Test
    fun `web reads reach public addresses only`() {
        for (address in listOf(
            "127.0.0.1", "127.8.8.8", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "100.127.255.255", "169.254.1.1",
            "0.0.0.0", "0.1.2.3", "224.0.0.1", "240.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "64:ff9b::a00:1",
        )) {
            assertNotNull(WebAddressPolicy.refusal(InetAddress.getByName(address)), address)
        }
        for (address in listOf("8.8.8.8", "93.184.216.34", "172.32.0.1", "100.128.0.1", "2606:4700::1111", "64:ff9b::808:808")) {
            assertNull(WebAddressPolicy.refusal(InetAddress.getByName(address)), address)
        }
        val mine = InetAddress.getByName("203.0.113.7")
        assertNotNull(WebAddressPolicy.refusal(mine, ownAddresses = setOf(mine)))
    }

    @Test
    fun `workspace files stay inside the root, out of the trash and off links`() {
        val root = Files.createTempDirectory("ws").toFile()
        val ok = assertIs<DocResult.Ok<WorkspaceFile>>(WorkspaceFile.resolve(root, RelativePath.of("notes/a.md"))).value
        assertEquals(root.toPath().toAbsolutePath().normalize().resolve("notes/a.md").toFile(), ok.file)
        assertEquals("notes/a.md", ok.path.value)
        assertIs<DocResult.NotAllowed>(WorkspaceFile.resolve(root, RelativePath.of(".trash/x")))
        assertIs<DocResult.Ok<*>>(WorkspaceFile.resolve(root, RelativePath.of(".trash/x"), allowTrash = true))
        val outside = Files.createTempDirectory("elsewhere")
        Files.createSymbolicLink(root.toPath().resolve("escape"), outside)
        assertIs<DocResult.NotAllowed>(WorkspaceFile.resolve(root, RelativePath.of("escape/secret.txt")))
        assertIs<DocResult.NotAllowed>(WorkspaceFile.resolve(root, RelativePath.of("escape")))
        Files.createDirectories(root.toPath().resolve("real"))
        Files.createSymbolicLink(root.toPath().resolve("real/link.txt"), outside.resolve("f.txt"))
        assertIs<DocResult.NotAllowed>(WorkspaceFile.resolve(root, RelativePath.of("real/link.txt")))
        val linkedRoot = Files.createTempDirectory("lr").resolve("root")
        Files.createSymbolicLink(linkedRoot, outside)
        assertIs<DocResult.NotAllowed>(WorkspaceFile.resolve(linkedRoot.toFile(), RelativePath.of("a")))
    }

    @Test
    fun `the tool catalogue is consistent with the capabilities and the plan`() {
        val all = OperatorTools.all
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.all { Regex("[a-z]+\\.[a-z_]+").matches(it.id.value) })
        val known = (OperatorCapabilities.all + Capabilities.builtIn).associateBy { it.capability }
        assertTrue(all.all { it.capability in known })
        for (tool in all) {
            val kind = known.getValue(tool.capability).constrainedBy
            val family = tool.id.value.substringBefore('.')
            assertEquals(if (family == "folders") ConstraintKind.FOLDER else if (family == "screen") ConstraintKind.APP else ConstraintKind.NONE, kind, tool.id.value)
        }
        assertEquals(
            setOf("workspace.empty_trash", "folders.delete", "apps.install", "apps.uninstall", "scripts.run_shell", "termux.run"),
            all.filter { it.risk == RiskLevel.CONSEQUENTIAL }.map { it.id.value }.toSet(),
        )
        // The shell and Termux can use the network, so local-only mode blocks them too.
        assertEquals(
            setOf("web.read_page", "browser.open", "apps.open_link", "scripts.run_shell", "termux.run"),
            all.filter { it.sendsDataOffDevice }.map { it.id.value }.toSet(),
        )
        assertEquals(Capabilities.BASIC_INFO, OperatorTools.info(ToolId("permissions.request"))?.capability)
        assertTrue(all.filter { "copy_to_workspace" in it.id.value || it.id.value == "screen.screenshot" }.all { it.rule?.contains("Never overwrites") == true })
        assertEquals(RiskLevel.CONSEQUENTIAL, OperatorTools.riskOf(OperatorCapabilities.APPS_MANAGE))
        assertEquals(RiskLevel.SAFE, OperatorTools.riskOf(OperatorCapabilities.WEB_READ))
        assertTrue(OperatorCapabilities.all.all { info -> all.any { it.capability == info.capability } })
    }

    private val agent = AgentProfile(AgentId("agt_op"), "Operator", AgentKind.OPERATOR, toolIds = setOf(ToolId("screen.tap"), ToolId("folders.read")), createdAt = 1, updatedAt = 1)
    private val folder = SharedFolder(FolderId("fld_1"), "content://tree/a", "Reports", 1)
    private val on = FrameworkSettings(operatorEnabled = true, accessibilityOperatorEnabled = true)

    private fun facts(
        toolId: ToolId? = ToolId("screen.tap"),
        capability: com.aicontrolcenter.core.tools.Capability = OperatorCapabilities.SCREEN_OPERATE,
        constraint: CapabilityConstraint = CapabilityConstraint.App("com.example.notes"),
        settings: FrameworkSettings = on,
        stopped: Boolean = false,
        agent: AgentProfile? = this.agent,
        leadsTo: String? = null,
        apps: Set<String> = setOf("com.example.notes"),
        sendsData: Boolean = false,
    ) = OperatorGate.Facts(
        agent, toolId, toolId?.let { "Tap" }, capability, (OperatorCapabilities.all + Capabilities.builtIn).firstOrNull { it.capability == capability },
        constraint, leadsTo, sendsData, settings, stopped, folder, apps, ProtectedPackages("com.aicontrolcenter.app"),
    )

    @Test
    fun `the gate refuses before any request is raised, with fresh facts`() {
        assertNull(OperatorGate.refusal(facts()))
        assertNull(OperatorGate.refusal(facts(toolId = null)))
        assertNull(OperatorGate.refusal(facts(ToolId("folders.read"), OperatorCapabilities.FOLDERS_READ, CapabilityConstraint.Folder(folder.id))))
        val refused = listOf(
            facts(agent = null),
            facts(agent = agent.copy(enabled = false)),
            facts(agent = agent.copy(toolIds = emptySet())),
            facts(capability = com.aicontrolcenter.core.tools.Capability("made.up")),
            facts(constraint = CapabilityConstraint.None),
            facts(settings = on.copy(operatorEnabled = false)),
            facts(settings = on.copy(accessibilityOperatorEnabled = false)),
            facts(stopped = true),
            facts(apps = emptySet()),
            facts(constraint = CapabilityConstraint.App("com.android.settings"), apps = setOf("com.android.settings")),
            facts(constraint = CapabilityConstraint.App("com.aicontrolcenter.app"), apps = setOf("com.aicontrolcenter.app")),
            facts(leadsTo = "com.android.launcher3"),
            facts(ToolId("folders.read"), OperatorCapabilities.FOLDERS_READ, CapabilityConstraint.Folder(FolderId("fld_gone"))),
            facts(null, OperatorCapabilities.WEB_READ, CapabilityConstraint.None, settings = on.copy(localOnlyMode = true), sendsData = true),
        )
        for ((i, f) in refused.withIndex()) assertNotNull(OperatorGate.refusal(f), "case $i")
        assertNull(OperatorGate.refusal(facts(leadsTo = "com.android.launcher3", apps = setOf("com.example.notes", "com.android.launcher3"))))
        // Built-in capabilities do not need the operator switch.
        assertNull(OperatorGate.refusal(facts(null, Capabilities.BASIC_INFO, CapabilityConstraint.None, settings = FrameworkSettings())))
    }

    @Test
    fun `screen previews name the app, the control, its row and what would be sent`() {
        val send = ScreenElement(4, 3, "com.example.mail", "Button", text = "Send", context = "Reply to Sam", bounds = ScreenBounds(0, 0, 1, 1), clickable = true)
        val body = ScreenElement(4, 2, "com.example.mail", "EditText", hint = "Message", text = "See you at 5‮", bounds = ScreenBounds(0, 0, 1, 1), editable = true)
        val snapshot = ScreenSnapshot(4, "com.example.mail", "Inbox", listOf(body, send), withheld = 1, truncated = false, capturedAt = 1)
        val details = ScreenPreview.details("Mail", send, snapshot, consequential = true)
        assertEquals(
            listOf(
                "App: Mail (com.example.mail), window \"Inbox\"",
                "Control: Button \"Send\"",
                "Where: Reply to Sam",
                "Field \"Message\" contains: \"See you at 5⟨U+202E⟩\"",
            ),
            details,
        )
        assertEquals(3, ScreenPreview.details("Mail", send, snapshot, consequential = false).size)
        assertEquals("unlabelled ImageButton", ScreenPreview.nameOf(send.copy(role = "ImageButton", text = null, context = null)))
    }

    @Test
    fun `tools run with their assessment, and a changed world is refused in one sentence`() = runTest {
        val tool = object : Tool {
            override val spec = ToolSpec(ToolId("t.x"), "x", "X", "X.", buildJsonObject { }, ToolCategory.SYSTEM, Capabilities.BASIC_INFO, RiskLevel.SAFE)
            override suspend fun execute(arguments: JsonObject, context: ToolContext) = ToolOutcome.success("two-argument")
        }
        val outcome = tool.execute(buildJsonObject { }, ToolContext(agent), ActionAssessment(RiskLevel.SAFE, "X"))
        assertEquals("two-argument", outcome.content)
        val changed = ToolOutcome.changedSinceApproved("the folder now holds 300 files")
        assertFalse(changed.ok)
        assertTrue("Nothing was done: the folder now holds 300 files" in changed.content)
    }

    @Test
    fun `requests keep who asked and what for, through storage`() {
        val r = OwnerRequest(
            RequestId("req_1"), OwnerRequestKind.APPROVAL, agentId = agent.id, toolId = ToolId("screen.tap"), capability = OperatorCapabilities.SCREEN_OPERATE,
            constraint = CapabilityConstraint.App("com.example.mail"), risk = RiskLevel.CONSEQUENTIAL, summary = "Tap \"Send\"", createdAt = 1, expiresAt = 2,
            agentName = "Operator", toolTitle = "Tap", targetLabel = "Mail", delegationChain = listOf(AgentId("agt_director")),
        )
        assertEquals(r, Json.decodeFromString<OwnerRequest>(Json.encodeToString(OwnerRequest.serializer(), r)))
        assertEquals(OperatorApp("a.b", "B", 1, "ff"), Json.decodeFromString<OperatorApp>(Json.encodeToString(OperatorApp.serializer(), OperatorApp("a.b", "B", 1, "ff"))))
    }
}
