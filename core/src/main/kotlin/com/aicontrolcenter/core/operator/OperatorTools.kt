package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory

/** What the catalogue fixes for one operator tool; its [com.aicontrolcenter.core.tools.ToolSpec] must agree. */
data class OperatorToolInfo(
    val id: ToolId,
    val capability: Capability,
    /** The declared risk; a call's assessment may raise it, never lower it. */
    val risk: RiskLevel,
    val category: ToolCategory,
    val sendsDataOffDevice: Boolean = false,
    /** A rule the tool must keep beyond its risk, for the builder and the reviewer. */
    val rule: String? = null,
)

/**
 * The single table of operator tools: id, capability, declared risk and whether data leaves the
 * device. `:framework` builds each spec from its entry and an `:app` test checks every
 * registered operator tool against it.
 */
object OperatorTools {
    private val C = OperatorCapabilities
    private const val NO_OVERWRITE = "Never overwrites a workspace file: an existing target is refused."
    private const val BULK = "CONSEQUENTIAL once more than BULK_FILE_LIMIT existing files are replaced, edited or removed within " +
        "BULK_WINDOW_MS (RecentFileChanges), counted across calls per agent and task, per agent across its tasks and per root objective."
    private const val KEEP_REPLACED = "A replaced file is kept in the workspace trash. $BULK"
    private const val KEEP_EDITED = "The version before the edit is kept in the workspace trash, unless the same task wrote it itself within " +
        "BULK_WINDOW_MS (OwnVersions). $BULK"
    private const val REPLACE_IN_FOLDER = "Replacing an existing file is CONSEQUENTIAL: shared folders have no trash."

    val all: List<OperatorToolInfo> = listOf(
        info("workspace.list", C.WORKSPACE_READ, RiskLevel.SAFE, ToolCategory.WORKSPACE),
        info("workspace.read", C.WORKSPACE_READ, RiskLevel.SAFE, ToolCategory.WORKSPACE),
        info("workspace.search", C.WORKSPACE_READ, RiskLevel.SAFE, ToolCategory.WORKSPACE),
        info("workspace.write", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE, rule = KEEP_REPLACED),
        info("workspace.append", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.replace_text", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE, rule = KEEP_EDITED),
        info("workspace.make_folder", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.move", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE, rule = KEEP_REPLACED),
        info("workspace.trash", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE, rule = BULK),
        info("workspace.restore", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.empty_trash", C.WORKSPACE_WRITE, RiskLevel.CONSEQUENTIAL, ToolCategory.WORKSPACE),
        info(
            "workspace.export", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE, sendsDataOffDevice = true,
            rule = "Opens the share sheet; the owner picks the app. dataGoesTo names the file and \"the app you choose\". $HAND_OVER",
        ),
        info("folders.list", C.FOLDERS_READ, RiskLevel.SAFE, ToolCategory.FILES),
        info("folders.read", C.FOLDERS_READ, RiskLevel.SAFE, ToolCategory.FILES),
        info("folders.copy_to_workspace", C.FOLDERS_READ, RiskLevel.MODIFYING, ToolCategory.FILES, rule = NO_OVERWRITE),
        info("folders.write", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES, rule = REPLACE_IN_FOLDER),
        info("folders.make_folder", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES),
        info("folders.rename", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES),
        info("folders.copy_from_workspace", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES, rule = REPLACE_IN_FOLDER),
        info("folders.delete", C.FOLDERS_WRITE, RiskLevel.CONSEQUENTIAL, ToolCategory.FILES),
        info("media.list", C.MEDIA_READ, RiskLevel.SAFE, ToolCategory.MEDIA),
        info("media.copy_to_workspace", C.MEDIA_READ, RiskLevel.MODIFYING, ToolCategory.MEDIA, rule = NO_OVERWRITE),
        info("apps.list", C.APPS_OPEN, RiskLevel.SAFE, ToolCategory.APPS),
        info("apps.open", C.APPS_OPEN, RiskLevel.MODIFYING, ToolCategory.APPS),
        info(
            "apps.open_link", C.APPS_OPEN, RiskLevel.MODIFYING, ToolCategory.APPS, sendsDataOffDevice = true,
            rule = "LinkPolicy.check first; only LinkPolicy.PLAIN_SCHEMES stay MODIFYING, any other scheme is CONSEQUENTIAL with the app " +
                "that opens it named. dataGoesTo is the web host, or that app for other schemes.",
        ),
        // Handing text, a file or a draft to another app hands it to whatever that app does with it (a browser or a
        // translator sends a shared text on at once), so local-only mode blocks these four and workspace.export too.
        info("apps.share_text", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS, sendsDataOffDevice = true, rule = HAND_OVER),
        info("apps.compose_email", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS, sendsDataOffDevice = true, rule = HAND_OVER),
        info("apps.compose_sms", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS, sendsDataOffDevice = true, rule = HAND_OVER),
        info("apps.dial", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS, sendsDataOffDevice = true, rule = HAND_OVER),
        info("apps.install", C.APPS_MANAGE, RiskLevel.CONSEQUENTIAL, ToolCategory.APPS),
        info("apps.uninstall", C.APPS_MANAGE, RiskLevel.CONSEQUENTIAL, ToolCategory.APPS),
        info("web.read_page", C.WEB_READ, RiskLevel.SAFE, ToolCategory.WEB, sendsDataOffDevice = true, rule = "dataGoesTo is the host."),
        info(
            "browser.open", C.APPS_OPEN, RiskLevel.MODIFYING, ToolCategory.BROWSER, sendsDataOffDevice = true,
            rule = "http(s) only; the browser sends the address, so it is blocked in local-only mode. dataGoesTo is the host.",
        ),
        info("screen.read", C.SCREEN_READ, RiskLevel.SAFE, ToolCategory.ACCESSIBILITY),
        info("screen.wait_for", C.SCREEN_READ, RiskLevel.SAFE, ToolCategory.ACCESSIBILITY),
        info("screen.screenshot", C.SCREEN_READ, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, rule = NO_OVERWRITE),
        // Acting on another app's screen may send data off the phone through that app (a browser's
        // address bar, a chat), so local-only mode blocks these five; reading and screenshots stay.
        info(
            "screen.tap", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, sendsDataOffDevice = true,
            rule = "CONSEQUENTIAL for an app that asks every time (ScreenActionPolicy), else when ConsequentialControls.consequentialReason is not null.",
        ),
        info(
            "screen.type", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, sendsDataOffDevice = true,
            rule = "CONSEQUENTIAL for an app that asks every time, with submit, or on a field ConsequentialControls.typingReason names.",
        ),
        info(
            "screen.scroll", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, sendsDataOffDevice = true,
            rule = "CONSEQUENTIAL for an app that asks every time, or on a target ConsequentialControls.consequentialReason names.",
        ),
        info(
            "screen.back", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, sendsDataOffDevice = true,
            rule = "Sets ActionAssessment.leadsTo to the launcher (Back may leave the app). CONSEQUENTIAL for an app that asks every time.",
        ),
        info(
            "screen.home", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, sendsDataOffDevice = true,
            rule = "Sets ActionAssessment.leadsTo to the launcher. CONSEQUENTIAL for an app that asks every time.",
        ),
        info("scripts.run_js", C.SCRIPTS_JS, RiskLevel.MODIFYING, ToolCategory.SCRIPTS, rule = "Host functions reach the workspace only, through Workspace."),
        info(
            "scripts.run_shell", C.SCRIPTS_SHELL, RiskLevel.CONSEQUENTIAL, ToolCategory.SCRIPTS, sendsDataOffDevice = true,
            rule = "The command can use the network, so it is blocked in local-only mode. It runs only if nothing changed the workspace " +
                "since it was assessed, and holds the workspace's change lock while it runs. Everything it starts is stopped when it ends. " +
                "The scripts found in it are shown and pinned; a command the reader is not sure of is refused.",
        ),
        info(
            "termux.run", C.SCRIPTS_TERMUX, RiskLevel.CONSEQUENTIAL, ToolCategory.SCRIPTS, sendsDataOffDevice = true,
            rule = "Termux can use the network, so it is blocked in local-only mode.",
        ),
        info(
            "permissions.request", Capabilities.BASIC_INFO, RiskLevel.SAFE, ToolCategory.SYSTEM,
            rule = "Its only dependency is CapabilityRequester; it never waits for the answer. Within OwnerRequest.REFUSAL_COOLDOWN_MS " +
                "of a refusal of the same permission and target, it is refused without a request, unless the owner let the agent ask " +
                "again on the refused request (OwnerRequests.letAskAgain).",
        ),
    )

    private val byId: Map<ToolId, OperatorToolInfo> = all.associateBy { it.id }

    fun info(id: ToolId): OperatorToolInfo? = byId[id]

    /** The highest declared risk among the tools that need [capability]; MODIFYING when none does. Used as an ask's risk. */
    fun riskOf(capability: Capability): RiskLevel = all.filter { it.capability == capability }.maxOfOrNull { it.risk } ?: RiskLevel.MODIFYING

    private fun info(id: String, capability: Capability, risk: RiskLevel, category: ToolCategory, sendsDataOffDevice: Boolean = false, rule: String? = null) =
        OperatorToolInfo(ToolId(id), capability, risk, category, sendsDataOffDevice, rule)

    /** The rule of the tools that hand content to another app (round 2, B1). */
    private const val HAND_OVER = "Hands content to another app, which may send it on, so it is blocked in local-only mode. Each " +
        "hand-over is logged as DATA_EGRESS with the agent, task, objective, the receiving app, the kind and the amount, never the content."
}
