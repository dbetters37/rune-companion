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

    val all: List<OperatorToolInfo> = listOf(
        info("workspace.list", C.WORKSPACE_READ, RiskLevel.SAFE, ToolCategory.WORKSPACE),
        info("workspace.read", C.WORKSPACE_READ, RiskLevel.SAFE, ToolCategory.WORKSPACE),
        info("workspace.search", C.WORKSPACE_READ, RiskLevel.SAFE, ToolCategory.WORKSPACE),
        info("workspace.write", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.append", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.replace_text", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.make_folder", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.move", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE, rule = "Overwriting more than BULK_FILE_LIMIT files is CONSEQUENTIAL."),
        info("workspace.trash", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.restore", C.WORKSPACE_WRITE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE),
        info("workspace.empty_trash", C.WORKSPACE_WRITE, RiskLevel.CONSEQUENTIAL, ToolCategory.WORKSPACE),
        info(
            "workspace.export", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.WORKSPACE,
            rule = "Opens the share sheet; the owner picks the app. dataGoesTo names the file and \"the app you choose\".",
        ),
        info("folders.list", C.FOLDERS_READ, RiskLevel.SAFE, ToolCategory.FILES),
        info("folders.read", C.FOLDERS_READ, RiskLevel.SAFE, ToolCategory.FILES),
        info("folders.copy_to_workspace", C.FOLDERS_READ, RiskLevel.MODIFYING, ToolCategory.FILES, rule = NO_OVERWRITE),
        info("folders.write", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES),
        info("folders.make_folder", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES),
        info("folders.rename", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES),
        info("folders.copy_from_workspace", C.FOLDERS_WRITE, RiskLevel.MODIFYING, ToolCategory.FILES),
        info("folders.delete", C.FOLDERS_WRITE, RiskLevel.CONSEQUENTIAL, ToolCategory.FILES),
        info("media.list", C.MEDIA_READ, RiskLevel.SAFE, ToolCategory.MEDIA),
        info("media.copy_to_workspace", C.MEDIA_READ, RiskLevel.MODIFYING, ToolCategory.MEDIA, rule = NO_OVERWRITE),
        info("apps.list", C.APPS_OPEN, RiskLevel.SAFE, ToolCategory.APPS),
        info("apps.open", C.APPS_OPEN, RiskLevel.MODIFYING, ToolCategory.APPS),
        info(
            "apps.open_link", C.APPS_OPEN, RiskLevel.MODIFYING, ToolCategory.APPS, sendsDataOffDevice = true,
            rule = "LinkPolicy.check first; dataGoesTo is the link's host or app.",
        ),
        info("apps.share_text", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS),
        info("apps.compose_email", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS),
        info("apps.compose_sms", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS),
        info("apps.dial", C.APPS_COMPOSE, RiskLevel.MODIFYING, ToolCategory.APPS),
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
        info("screen.tap", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, rule = "CONSEQUENTIAL when ConsequentialControls.consequentialReason is not null."),
        info("screen.type", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, rule = "CONSEQUENTIAL with submit, or on a consequential field."),
        info("screen.scroll", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY),
        info("screen.back", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY),
        info("screen.home", C.SCREEN_OPERATE, RiskLevel.MODIFYING, ToolCategory.ACCESSIBILITY, rule = "Sets ActionAssessment.leadsTo to the launcher."),
        info("scripts.run_js", C.SCRIPTS_JS, RiskLevel.MODIFYING, ToolCategory.SCRIPTS, rule = "Host functions reach the workspace only, through Workspace."),
        info("scripts.run_shell", C.SCRIPTS_SHELL, RiskLevel.CONSEQUENTIAL, ToolCategory.SCRIPTS),
        info("termux.run", C.SCRIPTS_TERMUX, RiskLevel.CONSEQUENTIAL, ToolCategory.SCRIPTS),
        info(
            "permissions.request", Capabilities.BASIC_INFO, RiskLevel.SAFE, ToolCategory.SYSTEM,
            rule = "Its only dependency is CapabilityRequester; it never waits for the answer.",
        ),
    )

    private val byId: Map<ToolId, OperatorToolInfo> = all.associateBy { it.id }

    fun info(id: ToolId): OperatorToolInfo? = byId[id]

    /** The highest declared risk among the tools that need [capability]; MODIFYING when none does. Used as an ask's risk. */
    fun riskOf(capability: Capability): RiskLevel = all.filter { it.capability == capability }.maxOfOrNull { it.risk } ?: RiskLevel.MODIFYING

    private fun info(id: String, capability: Capability, risk: RiskLevel, category: ToolCategory, sendsDataOffDevice: Boolean = false, rule: String? = null) =
        OperatorToolInfo(ToolId(id), capability, risk, category, sendsDataOffDevice, rule)
}
