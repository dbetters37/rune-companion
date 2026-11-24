package com.aicontrolcenter.app.ui.tools

import com.aicontrolcenter.app.ui.agents.firstSentence
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolSpec

/**
 * How tools are grouped for the owner, in the order they are listed: the Tools catalogue and
 * the agent editor use the same families, so a tool is found in the same place in both.
 */
enum class ToolFamily(val title: String, val description: String) {
    INFORMATION("Information", "Facts about the app and the clock, and asking you for a permission."),
    TEAM("Teamwork", "Asking a teammate a question and handing work to a teammate."),
    MEMORY("Memory", "Searching, saving and sharing what agents remember."),
    WORKSPACE("Workspace", "Files in the agents' own folder inside this app. Nothing outside it is reachable."),
    FOLDERS("Shared folders", "Files in folders you picked on this phone, one folder at a time."),
    MEDIA("Photos and videos", "Seeing which photos and videos exist, and copying one into the workspace."),
    APPS("Apps and messages", "Opening apps and links, preparing messages you send yourself, installing and removing apps."),
    WEB("Web", "Reading web pages and opening them in the browser. Blocked in local-only mode."),
    SCREEN("Screen control", "Reading and using the screen of apps you authorised, through Android's accessibility service."),
    SCRIPTS("Scripts", "Running JavaScript in a sealed sandbox, and commands you approve one by one."),
    ;

    companion object {
        /** The family of [spec], from its category. */
        fun of(spec: ToolSpec): ToolFamily = when (spec.category) {
            ToolCategory.SYSTEM -> INFORMATION
            ToolCategory.COMMUNICATION -> TEAM
            ToolCategory.MEMORY -> MEMORY
            ToolCategory.WORKSPACE -> WORKSPACE
            ToolCategory.FILES -> FOLDERS
            ToolCategory.MEDIA -> MEDIA
            ToolCategory.APPS -> APPS
            ToolCategory.WEB, ToolCategory.BROWSER -> WEB
            ToolCategory.ACCESSIBILITY -> SCREEN
            ToolCategory.SCRIPTS -> SCRIPTS
        }
    }
}

/**
 * What a tool does, in plain words for the owner. Tool descriptions are written for the model
 * (with argument names and advice on when to call); this says what happens instead. Tools not
 * listed here use the first sentence of their description, which says what they do.
 */
fun plainWordsFor(spec: ToolSpec): String = PLAIN_WORDS[spec.id.value] ?: firstSentence(spec.description)

/** The owner-facing sentence of each phone operator tool, by id. A test checks every one of them has one. */
internal val PLAIN_WORDS: Map<String, String> = mapOf(
    "workspace.list" to "Lists the files in a workspace folder.",
    "workspace.read" to "Reads a text file in the workspace.",
    "workspace.search" to "Finds workspace files by name or by the text in them.",
    "workspace.write" to "Creates a text file in the workspace, or replaces one. A replaced file goes to the workspace trash.",
    "workspace.append" to "Adds text at the end of a workspace file.",
    "workspace.replace_text" to "Changes a piece of text in a workspace file.",
    "workspace.make_folder" to "Creates a folder in the workspace.",
    "workspace.move" to "Moves or renames a workspace file or folder. A file it replaces goes to the workspace trash.",
    "workspace.trash" to "Moves workspace files to the workspace trash, from where they can be restored.",
    "workspace.restore" to "Puts an item from the workspace trash back where it was.",
    "workspace.empty_trash" to "Deletes items in the workspace trash for good. You approve it every time.",
    "workspace.export" to "Opens the share screen for a workspace file. You pick the app; nothing is sent by itself. " +
        "The app gets the file, so local-only mode blocks it.",
    "folders.list" to "Lists the files in a folder you shared.",
    "folders.read" to "Reads a text file in a folder you shared.",
    "folders.copy_to_workspace" to "Copies a file from a folder you shared into the workspace.",
    "folders.write" to "Creates or replaces a text file in a folder you shared. Replacing a file asks you every time: there is no trash.",
    "folders.make_folder" to "Creates a folder inside a folder you shared.",
    "folders.rename" to "Renames a file or folder in a folder you shared.",
    "folders.copy_from_workspace" to "Copies a workspace file into a folder you shared. Replacing a file there asks you every time.",
    "folders.delete" to "Deletes a file or folder in a folder you shared. There is no trash, so you approve it every time.",
    "media.list" to "Lists photos and videos by name, date and size, never their content.",
    "media.copy_to_workspace" to "Copies one photo or video into the workspace.",
    "apps.list" to "Lists the apps on this phone that can be opened.",
    "apps.open" to "Opens an installed app.",
    "apps.open_link" to "Opens a web address or map link in the app that handles it. Other app links (payments, calls, chats) ask you every time.",
    "apps.share_text" to "Opens the share screen with a text. You pick the app and send it. The app gets the text, so local-only mode blocks it.",
    "apps.compose_email" to "Opens your email app with an email filled in. You send it, or not. Local-only mode blocks it.",
    "apps.compose_sms" to "Opens your messaging app with a text message filled in. You send it, or not. Local-only mode blocks it.",
    "apps.dial" to "Opens the dialer with a number filled in. You place the call, or not. Local-only mode blocks it.",
    "apps.install" to "Asks Android to install an app from the workspace. You approve it here, then Android asks too.",
    "apps.uninstall" to "Asks Android to remove an app. You approve it here, then Android asks too.",
    "web.read_page" to "Downloads a web page and reads its text. The website learns the address.",
    "browser.open" to "Opens a web address in your browser for you to see.",
    "screen.read" to "Reads the text and controls on the screen of an app you authorised. Password fields are never read.",
    "screen.wait_for" to "Waits until a text appears on the screen of an app you authorised.",
    "screen.screenshot" to "Saves a picture of an authorised app's window in the workspace.",
    "screen.tap" to "Taps a control in an app you authorised. Asks you every time, unless you set that app to ask only before buying, sending, deleting and similar.",
    "screen.type" to "Types into a field in an app you authorised. Asks you every time, unless you set that app to ask less; sending what was typed always asks.",
    "screen.scroll" to "Scrolls the screen of an app you authorised. Asks you every time, unless you set that app to ask less.",
    "screen.back" to "Presses Back in an authorised app, only when you also authorised the home screen app, as Back can leave the app. Asks you every time, unless you set that app to ask less.",
    "screen.home" to "Presses Home, only when you also authorised the home screen app. Asks you every time, unless you set that app to ask less.",
    "scripts.run_js" to "Runs a small JavaScript program in a sealed sandbox with no access to the phone or the network.",
    "scripts.run_shell" to "Runs a command with Android's shell in the workspace. You approve each command and see the scripts it runs. " +
        "It can use the internet, so local-only mode blocks it.",
    "termux.run" to "Runs a command in the Termux app. You approve each command. It can use the internet, so local-only mode blocks it.",
    "permissions.request" to "Asks you for a permission it does not have, with its reason. It can never grant itself anything, and after you " +
        "refuse it cannot ask you for the same permission again for 10 minutes, unless you let it on the refused request in Tools › History.",
)

/**
 * The mark of a tool that local-only mode blocks, in the owner's words: the tool sends data off
 * this phone itself, or (sharing, preparing a message or a call) hands it to another app, which
 * may send it on.
 */
fun offPhoneMark(id: ToolId): String =
    if (OperatorTools.info(id)?.capability == OperatorCapabilities.APPS_COMPOSE) "Hands data to another app" else "Sends data off this phone"

/** Whether [id] is one of the phone operator's tools. */
fun isOperatorTool(id: ToolId): Boolean = id.value in PLAIN_WORDS
