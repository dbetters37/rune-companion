package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.EmailDraft
import com.aicontrolcenter.core.operator.LinkPolicy
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import com.aicontrolcenter.framework.operator.OperatorText.size
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Hand-overs to other apps (`apps.*`, `browser.open`) through
 * [com.aicontrolcenter.core.operator.AppActions]. Nothing here sends, calls or buys: messages
 * open prefilled for the owner; installs and removals go through Android's own confirmation
 * after the owner's approval here.
 */
internal object AppTools {
    fun create(d: OperatorToolDependencies): List<Tool> = listOf(
        AppsList(d), AppsOpen(d), AppsOpenLink(d), BrowserOpen(d), AppsShareText(d), AppsComposeEmail(d), AppsComposeSms(d), AppsDial(d),
        AppsInstall(d), AppsUninstall(d),
    )

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val EMAIL = Regex("^[^\\s@<>,;\"]{1,64}@[^\\s@<>,;\"]{1,255}$")
    private val PHONE = Regex("^\\+?[0-9 ()*#.\\-]{2,40}$")

    fun checkPackageName(name: String) {
        if (name.length > 255 || !PACKAGE.matches(name)) throw ToolCallRejected("${q(name.take(80))} is not a package name such as \"com.example.app\".")
    }

    fun checkEmail(address: String) {
        if (!EMAIL.matches(address)) throw ToolCallRejected("${q(address.take(80))} is not an email address.")
    }

    fun checkPhone(number: String) {
        if (!PHONE.matches(number) || number.count { it.isDigit() } < 2) throw ToolCallRejected("${q(number.take(60))} is not a phone number.")
    }

    suspend fun launchable(d: OperatorToolDependencies, packageName: String): InstalledAppInfo? = d.apps.listLaunchable().firstOrNull { it.packageName == packageName }

    fun label(app: InstalledAppInfo?, packageName: String): String = app?.let { "${q(it.label)} ($packageName)" } ?: packageName

    fun checkNotProtected(d: OperatorToolDependencies, packageName: String) {
        if (d.protectedPackages().isProtected(packageName)) throw ToolCallRejected("$packageName is a protected app (system settings, installers, this app); agents never use it.")
    }

    /** What Android reported, for the model; [started] says what a successful start means. */
    fun answer(result: AppActionResult, started: String): ToolOutcome = when (result) {
        AppActionResult.Started -> ToolOutcome.success(started)
        is AppActionResult.Installed -> ToolOutcome.success("Android installed ${result.packageName}.")
        is AppActionResult.Uninstalled -> ToolOutcome.success("Android removed ${result.packageName}.")
        AppActionResult.UserCancelled -> ToolOutcome.failure("The owner cancelled Android's confirmation; nothing was changed.")
        is AppActionResult.OutcomeUnknown -> ToolOutcome.failure("Android's confirmation started, but no result came back: ${result.reason}")
        is AppActionResult.Deferred -> ToolOutcome.failure("Not opened now: ${result.reason}")
        is AppActionResult.NotFound -> ToolOutcome.failure("Not found: ${result.what}.")
        is AppActionResult.Refused -> ToolOutcome.failure("Refused: ${result.reason}")
        is AppActionResult.NeedsSpecialAccess -> ToolOutcome.failure("${result.reason} The owner can allow it in Android's settings.")
        is AppActionResult.Failed -> ToolOutcome.failure("Android refused: ${result.reason}" + (result.code?.let { " (status $it)" } ?: ""))
    }

    /** A link the policy allows, or a rejection; [webOnly] limits it to http(s). */
    fun link(d: OperatorToolDependencies, raw: String, webOnly: Boolean): LinkPolicy.Check.Allowed {
        val check = LinkPolicy.check(raw, d.ownPackage)
        if (check is LinkPolicy.Check.Refused) throw ToolCallRejected(check.reason)
        check as LinkPolicy.Check.Allowed
        if (webOnly && check.scheme != "http" && check.scheme != "https") throw ToolCallRejected("Only http and https addresses open in the browser.")
        if ((check.scheme == "http" || check.scheme == "https") && check.host.isNullOrEmpty()) throw ToolCallRejected("The address has no host.")
        return check
    }
}

/** apps.list */
internal class AppsList(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.list", "List apps",
    "Lists the apps on the phone that can be opened: name and package name.",
    objectSchema { stringProp("query", "Optional text to filter names and package names by.") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val query = Args(arguments).string("query")?.trim()?.take(100)?.lowercase(Locale.ROOT).orEmpty()
        return assessment(
            "List the apps on the phone",
            prepared {
                val apps = d.apps.listLaunchable().filter { query.isEmpty() || query in it.label.lowercase(Locale.ROOT) || query in it.packageName.lowercase(Locale.ROOT) }
                val shown = apps.take(d.limits.maxListed)
                ToolOutcome.success(
                    if (apps.isEmpty()) "No app matches." else "${OperatorText.count(apps.size, "app")}:\n" + shown.joinToString("\n") { "${it.label} (${it.packageName})" },
                    buildJsonArray { shown.forEach { addJsonObject { put("label", it.label); put("package", it.packageName) } } },
                )
            },
        )
    }
}

/** apps.open */
internal class AppsOpen(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.open", "Open an app",
    "Opens an installed app by its package name (see apps_list).",
    objectSchema("package") { stringProp("package", "The app's package name, e.g. \"com.android.calculator2\".") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val pkg = Args(arguments).requireString("package").trim().also(AppTools::checkPackageName)
        AppTools.checkNotProtected(d, pkg)
        val app = AppTools.launchable(d, pkg)
        val summary = "Open the app ${AppTools.label(app, pkg)}"
        if (app == null) return assessment(summary, failing("No app $pkg that can be opened is installed."))
        return assessment(
            summary,
            prepared(check = { if (AppTools.launchable(d, pkg) == null) "the app $pkg was removed" else null }) {
                AppTools.answer(d.apps.open(pkg), "${app.label} is open. What happens in it is not reported back.")
            },
        )
    }
}

/** apps.open_link */
internal class AppsOpenLink(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.open_link", "Open a link",
    "Opens a web address or an app link (geo:, market: and similar) in the app that handles it. Email, text and phone links " +
        "are prepared with the compose and dial tools instead.",
    objectSchema("link") { stringProp("link", "The link, e.g. \"https://example.com/page\" or \"geo:48.85,2.35\".") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val link = AppTools.link(d, Args(arguments).text("link", LinkPolicy.MAX_LENGTH)!!, webOnly = false)
        val goesTo = link.host ?: "the app that opens ${link.scheme}: links"
        return assessment(
            "Open the link ${q(link.link)}",
            prepared { AppTools.answer(d.apps.openLink(link.link), "The link opened. What happens there is not reported back.") },
            listOf("Link: ${q(link.link)}", "The address is sent to $goesTo."),
            dataGoesTo = goesTo,
        )
    }
}

/** browser.open */
internal class BrowserOpen(private val d: OperatorToolDependencies) : OperatorTool(
    "browser.open", "Open in the browser",
    "Opens an http or https address in the browser for the owner to see. To read a page's text yourself, use web_read_page.",
    objectSchema("url") { stringProp("url", "The address, e.g. \"https://example.com\".") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val link = AppTools.link(d, Args(arguments).text("url", LinkPolicy.MAX_LENGTH)!!, webOnly = true)
        return assessment(
            "Open ${q(link.link)} in the browser",
            prepared { AppTools.answer(d.apps.openInBrowser(link.link), "The page opened in the browser. What happens there is not reported back.") },
            listOf("Address: ${q(link.link)}", "The browser sends the address to ${link.host}."),
            dataGoesTo = link.host,
        )
    }
}

/** apps.share_text */
internal class AppsShareText(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.share_text", "Share text",
    "Opens Android's share screen with a text, or one app's share screen. The owner picks the recipient and sends it; nothing is sent by itself.",
    objectSchema("text") {
        stringProp("text", "The text to share.")
        stringProp("subject", "Optional subject, used by email apps.")
        stringProp("app", "Optional package name of the app to share to. Omit to let the owner pick.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val text = args.text("text", 100_000)!!
        val subject = args.text("subject", 500, required = false)
        val pkg = args.string("app")?.trim()?.ifEmpty { null }?.also { AppTools.checkPackageName(it); AppTools.checkNotProtected(d, it) }
        val app = pkg?.let { AppTools.launchable(d, it) }
        val goesTo = if (pkg == null) "the app you choose" else app?.label ?: pkg
        val details = listOfNotNull("Text: ${q(text)}", subject?.let { "Subject: ${q(it)}" }, "You send it, or not, in $goesTo.")
        val summary = "Open the share screen" + (pkg?.let { " of ${AppTools.label(app, it)}" } ?: "") + " with ${OperatorText.count(text.length, "character")} of text"
        if (pkg != null && app == null) return assessment(summary, failing("No app $pkg is installed."), details, dataGoesTo = goesTo)
        return assessment(
            summary,
            prepared(check = { if (pkg != null && AppTools.launchable(d, pkg) == null) "the app $pkg was removed" else null }) {
                AppTools.answer(d.apps.shareText(text, subject, pkg), "The share screen opened with the text; the owner completes it there.")
            },
            details,
            dataGoesTo = goesTo,
        )
    }
}

/** apps.compose_email */
internal class AppsComposeEmail(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.compose_email", "Prepare an email",
    "Opens the owner's email app with a prefilled email. The owner reviews and sends it; nothing is sent by itself.",
    objectSchema("to") {
        stringArrayProp("to", "Recipient addresses.", 50)
        stringArrayProp("cc", "Copy addresses.", 50)
        stringArrayProp("bcc", "Blind copy addresses.", 50)
        stringProp("subject", "The subject.")
        stringProp("body", "The text of the email.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        fun addresses(name: String) = args.strings(name, 50, 320).orEmpty().map { it.trim() }.onEach(AppTools::checkEmail)
        val draft = EmailDraft(
            to = addresses("to").ifEmpty { throw ToolCallRejected("\"to\" needs at least one address.") },
            cc = addresses("cc"), bcc = addresses("bcc"),
            subject = args.text("subject", 1_000, required = false).orEmpty(), body = args.text("body", 100_000, required = false).orEmpty(),
        )
        val details = listOfNotNull(
            "To: ${draft.to.joinToString(", ") { q(it) }}",
            draft.cc.takeIf { it.isNotEmpty() }?.let { "Cc: ${it.joinToString(", ") { a -> q(a) }}" },
            draft.bcc.takeIf { it.isNotEmpty() }?.let { "Bcc: ${it.joinToString(", ") { a -> q(a) }}" },
            "Subject: ${q(draft.subject)}",
            "Body: ${q(draft.body)}",
            "Nothing is sent until you send it from your email app.",
        )
        return assessment(
            "Open an email to ${draft.to.joinToString(", ") { q(it) }} with the subject ${q(draft.subject)} in your email app, for you to send",
            prepared { AppTools.answer(d.apps.composeEmail(draft), "The email app opened with the draft; the owner sends it, or not.") },
            details,
            dataGoesTo = "your email app",
        )
    }
}

/** apps.compose_sms */
internal class AppsComposeSms(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.compose_sms", "Prepare a text message",
    "Opens the owner's messaging app with a prefilled text message. The owner sends it; nothing is sent by itself.",
    objectSchema("number", "body") {
        stringProp("number", "The phone number, e.g. \"+44 20 7946 0958\".")
        stringProp("body", "The message.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val number = args.text("number", 40)!!.trim().also(AppTools::checkPhone)
        val body = args.text("body", 10_000, required = false).orEmpty()
        return assessment(
            "Open a text message to ${q(number)} in your messaging app, for you to send",
            prepared { AppTools.answer(d.apps.composeSms(number, body), "The messaging app opened with the message; the owner sends it, or not.") },
            listOf("To: ${q(number)}", "Message: ${q(body)}", "Nothing is sent until you send it."),
            dataGoesTo = "your messaging app",
        )
    }
}

/** apps.dial */
internal class AppsDial(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.dial", "Prepare a phone call",
    "Opens the dialer with a number filled in. The owner places the call, or not; the call is never placed by itself.",
    objectSchema("number") { stringProp("number", "The phone number.") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val number = Args(arguments).text("number", 40)!!.trim().also(AppTools::checkPhone)
        return assessment(
            "Open the dialer with ${q(number)}, for you to call",
            prepared { AppTools.answer(d.apps.dial(number), "The dialer opened with the number; the owner places the call, or not.") },
            listOf("Number: ${q(number)}", "The call is placed only if you press call."),
            dataGoesTo = "your phone app",
        )
    }
}

/** apps.install */
internal class AppsInstall(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.install", "Install an app",
    "Asks Android to install an app from an .apk file in the workspace. The owner approves here, then Android asks again.",
    objectSchema("path") { stringProp("path", "The .apk file in the workspace.") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val path = Args(arguments).entryPath("path")
        if (!path.name.lowercase(Locale.ROOT).endsWith(".apk")) throw ToolCallRejected("Only .apk files can be installed.")
        val before = WorkspaceStates.of(d.workspace, path, hashUpTo = d.limits.maxCopyBytes * 4)
        val summary = "Install the app in the workspace file ${q(path.value)} (${size(before.sizeBytes)})"
        val details = listOfNotNull(
            "File: ${q(path.value)}, ${WorkspaceStates.describe(before)}",
            before.sha256?.let { "SHA-256: $it" },
            "Android then shows the app's name and asks you to confirm again.",
        )
        return when {
            !before.exists || before.isFolder -> assessment(summary, failing("There is no file ${q(path.value)}."), details)
            before.sha256 == null -> assessment(summary, failing("${q(path.value)} is too large to check, so it is not installed."), details)
            else -> assessment(
                summary,
                prepared(check = { before.changeTo(WorkspaceStates.of(d.workspace, path, hashUpTo = d.limits.maxCopyBytes * 4), q(path.value)) }) {
                    val apk = when (val r = d.workspace.resolve(path)) {
                        is DocResult.Ok -> r.value
                        else -> return@prepared ToolOutcome.failure(OperatorText.problem(r, "Installing ${q(path.value)}"))
                    }
                    AppTools.answer(d.apps.install(apk), "Android's install screen opened; the owner confirms it there.")
                },
                details,
                dataGoesTo = "Android's package installer",
            )
        }
    }
}

/** apps.uninstall */
internal class AppsUninstall(private val d: OperatorToolDependencies) : OperatorTool(
    "apps.uninstall", "Remove an app",
    "Asks Android to remove an app. The owner approves here, then Android asks again. Protected and system apps are refused.",
    objectSchema("package") { stringProp("package", "The app's package name.") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val pkg = Args(arguments).requireString("package").trim().also(AppTools::checkPackageName)
        AppTools.checkNotProtected(d, pkg)
        val app = AppTools.launchable(d, pkg)
        return assessment(
            "Remove the app ${AppTools.label(app, pkg)} from this phone",
            prepared(check = { if (app != null && AppTools.launchable(d, pkg) == null) "the app $pkg is no longer installed" else null }) {
                AppTools.answer(d.apps.uninstall(pkg), "Android's removal screen opened; the owner confirms it there.")
            },
            listOf("App: ${AppTools.label(app, pkg)}", "Its data on this phone is deleted with it.", "Android asks you to confirm again."),
            dataGoesTo = "Android's package installer",
        )
    }
}
