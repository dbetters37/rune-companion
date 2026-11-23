package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.registry.InstalledAppInfo
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Something the owner turns on in Android before an operator feature works. The UI offers the way there. */
enum class DeviceAccess {
    /** "Install unknown apps" for this app. */
    INSTALL_UNKNOWN_APPS,

    /** The photo and video permission. */
    MEDIA,

    /** The operator accessibility service. */
    ACCESSIBILITY_SERVICE,

    /** Termux installed, `allow-external-apps=true`, and the "Run commands in Termux" permission. */
    TERMUX_RUN_COMMAND,
}

/** The outcome of asking Android to do something with another app. Every value says what is known to have happened, no more. */
sealed interface AppActionResult {
    /**
     * The other app's or the system's screen came to the front. Returned only when `:device`
     * knows the start was not blocked (its accessibility service is bound or one of its windows
     * is visible); what happens on that screen is not reported back.
     */
    data object Started : AppActionResult

    /** Android reported the install as finished. */
    data class Installed(val packageName: String) : AppActionResult

    /** Android reported the app as removed. */
    data class Uninstalled(val packageName: String) : AppActionResult

    /** The owner cancelled Android's own confirmation. */
    data object UserCancelled : AppActionResult

    /** Started, but no final status arrived in time (the confirmation may still be open); [reason] says so for the model. */
    data class OutcomeUnknown(val reason: String) : AppActionResult

    /**
     * Not opened now: Android blocks starting it while this app is in the background. [reason]
     * says what the owner was offered instead (a "Tap to open" notification) or that nothing was.
     */
    data class Deferred(val reason: String) : AppActionResult

    /** No such app, no app that handles the link, or no such file. */
    data class NotFound(val what: String) : AppActionResult

    /** Refused by policy before Android was asked (a protected package, a link [LinkPolicy] refuses). */
    data class Refused(val reason: String) : AppActionResult

    /** The owner must first turn on [access] in Android. */
    data class NeedsSpecialAccess(val access: DeviceAccess, val reason: String) : AppActionResult

    /** Android refused or something went wrong; [code] is Android's status code when there is one (PackageInstaller `STATUS_*`). */
    data class Failed(val reason: String, val code: Int? = null) : AppActionResult
}

/** An email for [AppActions.composeEmail]. Opens the mail app's compose screen; the owner sends it. */
data class EmailDraft(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
)

/**
 * Hand-overs to other apps through official intents (`:device`). Nothing here sends or buys by
 * itself: messages open prefilled for the owner to send; installs and removals go through
 * Android's own confirmation and report its final status. Protected packages ([ProtectedPackages])
 * are refused as targets; links go through [LinkPolicy] first and are opened exactly as it
 * normalised them, and a link whose resolved handler is protected is refused.
 */
interface AppActions {
    /** Apps with a launcher entry, by label. */
    suspend fun listLaunchable(): List<InstalledAppInfo>

    suspend fun open(packageName: String): AppActionResult

    /** ACTION_VIEW of a web URL or an app link that [LinkPolicy.check] allows. */
    suspend fun openLink(link: String): AppActionResult

    /** An http(s) URL in a Custom Tab or the default browser. */
    suspend fun openInBrowser(url: String): AppActionResult

    /** The share sheet, or [packageName]'s share screen when given. The owner completes it. */
    suspend fun shareText(text: String, subject: String?, packageName: String?): AppActionResult

    /** Shares a workspace file through a FileProvider URI (workspace export). */
    suspend fun shareFile(file: WorkspaceFile, mimeType: String, packageName: String?): AppActionResult

    /** ACTION_SENDTO mailto:. */
    suspend fun composeEmail(draft: EmailDraft): AppActionResult

    /** ACTION_SENDTO smsto:. */
    suspend fun composeSms(number: String, body: String): AppActionResult

    /** ACTION_DIAL; never places the call. */
    suspend fun dial(number: String): AppActionResult

    /**
     * Removes an app through `PackageInstaller.uninstall` with a status receiver (needs
     * `REQUEST_DELETE_PACKAGES`), waiting up to [waitMs] for Android's confirmation:
     * [AppActionResult.Uninstalled], [AppActionResult.UserCancelled], [AppActionResult.Failed]
     * or [AppActionResult.OutcomeUnknown]. Protected packages and this app are refused.
     */
    suspend fun uninstall(packageName: String, waitMs: Long = OUTCOME_WAIT_MS): AppActionResult

    /**
     * Installs a workspace APK through a PackageInstaller session, waiting up to [waitMs] for the
     * session's final status: [AppActionResult.Installed], [AppActionResult.UserCancelled]
     * (`STATUS_FAILURE_ABORTED`), [AppActionResult.Failed] with the status code, or
     * [AppActionResult.OutcomeUnknown].
     */
    suspend fun install(apk: WorkspaceFile, waitMs: Long = OUTCOME_WAIT_MS): AppActionResult

    /** Link schemes and limits. */
    companion object {
        /** Link schemes [openLink] refuses: they reach other apps' components, local files or scripts. Compared lower-case. */
        val REFUSED_LINK_SCHEMES: Set<String> = setOf("intent", "file", "content", "javascript", "data", "android-app", "vnd.android.intent")

        /** How long install and uninstall wait for Android's final status by default. */
        const val OUTCOME_WAIT_MS: Long = 120_000
    }
}

/**
 * The one reading of a link an agent wants opened. `:device` opens [Check.Allowed.link], never
 * the raw text, and [Check.Allowed.host] is what the assessment shows as `dataGoesTo`.
 */
object LinkPolicy {
    /** Schemes that prepare messages or calls; they belong to the compose tools and their capability. */
    val COMPOSE_SCHEMES: Set<String> = setOf("mailto", "sms", "smsto", "mms", "mmsto", "tel")

    const val MAX_LENGTH: Int = 4096

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** The outcome of [check]. */
    sealed interface Check {
        /** [link] is the normalised form (trimmed, scheme lower-case). */
        data class Allowed(val link: String, val scheme: String, val host: String?) : Check

        /** [reason] is a sentence the model can act on. */
        data class Refused(val reason: String) : Check
    }

    /**
     * Trims surrounding spaces; refuses control, whitespace and invisible characters inside,
     * links longer than [MAX_LENGTH], anything without a valid scheme or that `java.net.URI`
     * cannot read, [AppActions.REFUSED_LINK_SCHEMES], [COMPOSE_SCHEMES], and links to this app
     * (scheme or host under [ConsequentialControls.OWN_PACKAGE_PREFIX] or [ownPackage]).
     */
    fun check(raw: String, ownPackage: String? = null): Check {
        val link = raw.trim()
        if (link.length > MAX_LENGTH) return Check.Refused("The link is longer than $MAX_LENGTH characters.")
        if (link.any { it.isWhitespace() || it.isISOControl() || PreviewText.isHidden(it) }) {
            return Check.Refused("The link contains spaces, control or invisible characters; encode them first.")
        }
        val scheme = SCHEME.find(link)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            ?: return Check.Refused("\"${link.take(80)}\" is not a link: it needs a scheme such as https:.")
        if (scheme in AppActions.REFUSED_LINK_SCHEMES) return Check.Refused("$scheme: links are not opened.")
        if (scheme in COMPOSE_SCHEMES) return Check.Refused("$scheme: links are prepared with the compose and dial tools, not opened as links.")
        val normalised = scheme + link.substring(scheme.length)
        val host = try {
            URI(normalised).host?.lowercase(Locale.ROOT)
        } catch (e: URISyntaxException) {
            return Check.Refused("The link could not be read (${e.reason}).")
        }
        if (isOwn(scheme, ownPackage) || (host != null && isOwn(host, ownPackage))) return Check.Refused("Links into this app are not opened.")
        return Check.Allowed(normalised, scheme, host)
    }

    private fun isOwn(name: String, ownPackage: String?): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.startsWith(ConsequentialControls.OWN_PACKAGE_PREFIX) || n + "." == ConsequentialControls.OWN_PACKAGE_PREFIX ||
            (ownPackage != null && n == ownPackage.lowercase(Locale.ROOT))
    }
}
