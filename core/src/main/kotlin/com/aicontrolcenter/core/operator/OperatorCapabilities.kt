package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.ConstraintKind
import com.aicontrolcenter.core.tools.ToolCategory

/**
 * The phone operator's capabilities, with what the owner is told about each. Only
 * [WORKSPACE_READ] is granted by default. Folder capabilities are granted per folder and screen
 * capabilities per app ([CapabilityInfo.constrainedBy]). Register [all] in the capability
 * registry at start-up.
 */
object OperatorCapabilities {
    val WORKSPACE_READ = Capability("workspace.read")
    val WORKSPACE_WRITE = Capability("workspace.write")
    val FOLDERS_READ = Capability("folders.read")
    val FOLDERS_WRITE = Capability("folders.write")
    val MEDIA_READ = Capability("media.read")
    val APPS_OPEN = Capability("apps.open")
    val APPS_COMPOSE = Capability("apps.compose")
    val APPS_MANAGE = Capability("apps.manage")
    val WEB_READ = Capability("web.read")
    val SCREEN_READ = Capability("screen.read")
    val SCREEN_OPERATE = Capability("screen.operate")
    val SCRIPTS_JS = Capability("scripts.js")
    val SCRIPTS_SHELL = Capability("scripts.shell")
    val SCRIPTS_TERMUX = Capability("scripts.termux")

    val all: List<CapabilityInfo> = listOf(
        CapabilityInfo(
            WORKSPACE_READ, "Read the workspace",
            "List, read and search files in the agents' workspace, a folder inside this app.",
            ToolCategory.WORKSPACE, grantedByDefault = true,
        ),
        CapabilityInfo(
            WORKSPACE_WRITE, "Change the workspace",
            "Create, edit, move and trash files in the agents' workspace. A file that is replaced is kept in the workspace trash. " +
                "Emptying the trash always asks you first, and so does changing more than 20 existing files within 10 minutes.",
            ToolCategory.WORKSPACE, grantedByDefault = false,
        ),
        CapabilityInfo(
            FOLDERS_READ, "Read a shared folder",
            "List and read files in one folder you picked, and copy them into the workspace. Given per folder.",
            ToolCategory.FILES, grantedByDefault = false, constrainedBy = ConstraintKind.FOLDER,
        ),
        CapabilityInfo(
            FOLDERS_WRITE, "Change a shared folder",
            "Create, rename and copy files in one folder you picked. Replacing or deleting a file always asks you first, " +
                "because shared folders have no trash. Given per folder.",
            ToolCategory.FILES, grantedByDefault = false, constrainedBy = ConstraintKind.FOLDER,
        ),
        CapabilityInfo(
            MEDIA_READ, "See photos and videos",
            "List photos and videos (names, dates, sizes) and copy chosen ones into the workspace. Needs Android's photo and video access.",
            ToolCategory.MEDIA, grantedByDefault = false,
        ),
        CapabilityInfo(
            APPS_OPEN, "Open apps and links",
            "Open installed apps, web pages and map links. Other app links (payment, call or chat links) ask you first. " +
                "Blocked in local-only mode for links.",
            ToolCategory.APPS, grantedByDefault = false,
        ),
        CapabilityInfo(
            APPS_COMPOSE, "Prepare messages",
            "Open a prefilled email, text message, dialer or share screen. Nothing is sent: you send it in that app.",
            ToolCategory.APPS, grantedByDefault = false,
        ),
        CapabilityInfo(
            APPS_MANAGE, "Install and remove apps",
            "Ask Android to install an app from the workspace or to remove an app. You approve every time, and Android asks too.",
            ToolCategory.APPS, grantedByDefault = false,
        ),
        CapabilityInfo(
            WEB_READ, "Read web pages",
            "Download a web page and read its text. The website learns the address asked for. Blocked in local-only mode.",
            ToolCategory.WEB, grantedByDefault = false,
        ),
        CapabilityInfo(
            SCREEN_READ, "See an app's screen",
            "Read the text and controls on the screen of an app you authorised. Given per app.",
            ToolCategory.ACCESSIBILITY, grantedByDefault = false, constrainedBy = ConstraintKind.APP,
        ),
        CapabilityInfo(
            SCREEN_OPERATE, "Use an app's screen",
            "Tap, type and scroll in an app you authorised. Buying, sending, deleting and similar always ask you first. Given per app.",
            ToolCategory.ACCESSIBILITY, grantedByDefault = false, constrainedBy = ConstraintKind.APP,
        ),
        CapabilityInfo(
            SCRIPTS_JS, "Run JavaScript",
            "Run small JavaScript programs in a sealed sandbox inside this app, with no access to the phone.",
            ToolCategory.SCRIPTS, grantedByDefault = false,
        ),
        CapabilityInfo(
            SCRIPTS_SHELL, "Run shell commands",
            "Run commands in the workspace with Android's shell. You approve each command. A command can use the internet, " +
                "so this is blocked in local-only mode.",
            ToolCategory.SCRIPTS, grantedByDefault = false,
        ),
        CapabilityInfo(
            SCRIPTS_TERMUX, "Run Termux commands",
            "Run commands in the Termux app, with everything Termux can reach, the internet included. You approve each command. " +
                "Blocked in local-only mode.",
            ToolCategory.SCRIPTS, grantedByDefault = false,
        ),
    )
}
