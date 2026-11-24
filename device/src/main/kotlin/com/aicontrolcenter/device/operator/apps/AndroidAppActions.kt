package com.aicontrolcenter.device.operator.apps

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.TransactionTooLargeException
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.AppActions
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.EmailDraft
import com.aicontrolcenter.core.operator.LinkPolicy
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.device.launch.ActivityStartGate
import com.aicontrolcenter.device.operator.ResultMailbox
import com.aicontrolcenter.device.platform.activitiesFor
import com.aicontrolcenter.device.platform.packageInfoOrNull
import com.aicontrolcenter.device.platform.startableBy
import com.aicontrolcenter.device.platform.toSingleLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * [AppActions] through official intents and PackageInstaller. Nothing here sends, calls or buys:
 * messages and calls open prefilled for the owner to finish; installs and removals go through
 * Android's own confirmation, and the final status Android reports is what is returned.
 *
 * Rules every hand-over follows:
 *  - Protected packages ([ProtectedPackages]: system settings, installers, permission screens,
 *    the system UI and this app) are never targets. Implicit intents are resolved first; a
 *    protected handler is left out (the intent is narrowed to the others), and a hand-over only
 *    protected apps could take is refused.
 *  - Links are opened exactly as [LinkPolicy.check] normalised them, only as browsable links
 *    (the same reach a web page has).
 *  - [AppActionResult.Started] is returned only when Android lets this app start activities now
 *    ([startGate]: a visible window or the bound accessibility service). Otherwise the start would
 *    be dropped silently, so the owner gets a "Tap to open" notification ([notifier]) and the
 *    result is [AppActionResult.Deferred].
 *
 * @param protectedPackages the current protected set (the static floor until start-up resolution finishes).
 * @param apkPackageName reads the package name inside an APK (replaceable for tests).
 */
internal class AndroidAppActions(
    context: Context,
    private val protectedPackages: () -> ProtectedPackages,
    private val startGate: ActivityStartGate,
    private val notifier: HandoverNotifier = HandoverNotifier(context),
    apkPackageName: ((File) -> String?)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppActions {
    private val appContext: Context = context.applicationContext ?: context
    private val pm: PackageManager = appContext.packageManager
    private val ownPackage: String = appContext.packageName
    private val readApkPackage: (File) -> String? = apkPackageName ?: ::archivePackageName

    // --- Apps -------------------------------------------------------------------------------

    override suspend fun listLaunchable(): List<InstalledAppInfo> = withContext(dispatcher) {
        val protected = protectedPackages()
        pm.activitiesFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filterNot { protected.isProtected(it.packageName) }
            .map { app ->
                InstalledAppInfo(
                    packageName = app.packageName,
                    label = labelOf(app),
                    versionName = pm.packageInfoOrNull(app.packageName)?.versionName.toSingleLine(MAX_VERSION_CHARS).ifEmpty { null },
                    isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    override suspend fun open(packageName: String): AppActionResult = withContext(dispatcher) {
        refusalFor(packageName)?.let { return@withContext it }
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: return@withContext AppActionResult.NotFound(
                if (pm.packageInfoOrNull(packageName) == null) packageName else "a screen of $packageName that can be opened",
            )
        handOver(launch, nameOf(packageName))
    }

    // --- Links ------------------------------------------------------------------------------

    override suspend fun openLink(link: String): AppActionResult = withContext(dispatcher) {
        val allowed = when (val check = LinkPolicy.check(link, ownPackage)) {
            is LinkPolicy.Check.Refused -> return@withContext AppActionResult.Refused(check.reason)
            is LinkPolicy.Check.Allowed -> check
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(allowed.link)).addCategory(Intent.CATEGORY_BROWSABLE)
        when (val target = narrow(intent)) {
            is Target.Refused -> target.result
            // No visible app takes it. Protected apps are visible to this app (manifest
            // <queries>), so none of them is the taker; Android finds the app or reports none.
            Target.NoneVisible -> handOver(intent, "the link", notFound = "an app that opens ${allowed.scheme}: links")
            is Target.Ready -> handOver(target.intent, "the link")
        }
    }

    override suspend fun linkHandlers(link: String): List<InstalledAppInfo> = withContext(dispatcher) {
        val allowed = LinkPolicy.check(link, ownPackage) as? LinkPolicy.Check.Allowed ?: return@withContext emptyList()
        val protected = protectedPackages()
        // The same intent openLink starts, so the apps named are the ones that can take it.
        handlersOf(Intent(Intent.ACTION_VIEW, Uri.parse(allowed.link)).addCategory(Intent.CATEGORY_BROWSABLE))
            .map { it.packageName }
            .distinct()
            .filterNot(protected::isProtected)
            .map { InstalledAppInfo(packageName = it, label = nameOf(it)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    override suspend fun openInBrowser(url: String): AppActionResult = withContext(dispatcher) {
        val allowed = when (val check = LinkPolicy.check(url, ownPackage)) {
            is LinkPolicy.Check.Refused -> return@withContext AppActionResult.Refused(check.reason)
            is LinkPolicy.Check.Allowed -> check
        }
        if (allowed.scheme != "http" && allowed.scheme != "https") {
            return@withContext AppActionResult.Refused("Only http and https addresses open in the browser.")
        }
        val uri = Uri.parse(allowed.link)
        val protected = protectedPackages()
        val tabsBrowser = customTabsBrowser()?.takeUnless(protected::isProtected)
        if (tabsBrowser != null) {
            // A Custom Tab: the owner's own browser, logins and all, over this app.
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build().intent
            return@withContext handOver(tab.setData(uri).setPackage(tabsBrowser), "the page in ${nameOf(tabsBrowser)}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = defaultBrowser()?.takeUnless(protected::isProtected)
        if (browser != null) return@withContext handOver(intent.setPackage(browser), "the page in ${nameOf(browser)}")
        when (val target = narrow(intent)) {
            is Target.Refused -> target.result
            Target.NoneVisible -> handOver(intent, "the page", notFound = "a browser")
            is Target.Ready -> handOver(target.intent, "the page")
        }
    }

    // --- Sharing ----------------------------------------------------------------------------

    override suspend fun shareText(text: String, subject: String?, packageName: String?): AppActionResult = withContext(dispatcher) {
        if (text.isEmpty()) return@withContext AppActionResult.Failed("There is no text to share.")
        if (text.length > MAX_SHARED_TEXT_CHARS) {
            return@withContext AppActionResult.Failed("The text is too long to share in one go (${text.length} characters; the limit is $MAX_SHARED_TEXT_CHARS).")
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (!subject.isNullOrEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, subject)
        shareTo(send, packageName, "text")
    }

    override suspend fun shareFile(file: WorkspaceFile, mimeType: String, packageName: String?): AppActionResult = withContext(dispatcher) {
        if (!file.file.isFile) return@withContext AppActionResult.NotFound(file.path.value)
        val type = mimeType.trim().lowercase()
        if (!MIME.matches(type)) return@withContext AppActionResult.Failed("\"${mimeType.take(80)}\" is not a file type such as text/plain or application/pdf.")
        val uri = try {
            FileProvider.getUriForFile(appContext, WorkspaceFileProvider.authority(appContext), file.file)
        } catch (_: IllegalArgumentException) {
            return@withContext AppActionResult.Failed("\"${file.path.value}\" cannot be shared: only workspace files can.")
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.path.name, uri)
        shareTo(send, packageName, "files of type $type")
    }

    /** Sends [send] to [packageName]'s share screen, or to Android's share sheet when null. */
    private fun shareTo(send: Intent, packageName: String?, kind: String): AppActionResult {
        if (packageName == null) return handOver(Intent.createChooser(send, null), "the share sheet")
        refusalFor(packageName)?.let { return it }
        val targets = handlersOf(Intent(send).setPackage(packageName))
        val name = nameOf(packageName)
        return when {
            targets.isEmpty() && pm.packageInfoOrNull(packageName) == null -> AppActionResult.NotFound(packageName)
            targets.isEmpty() -> AppActionResult.NotFound("a share screen in $name that accepts $kind")
            targets.size == 1 -> handOver(send.setComponent(ComponentName(targets[0].packageName, targets[0].name)), "the share screen of $name")
            else -> handOver(send.setPackage(packageName), "the share screen of $name")
        }
    }

    // --- Messages and calls -------------------------------------------------------------------

    override suspend fun composeEmail(draft: EmailDraft): AppActionResult = withContext(dispatcher) {
        val all = draft.to + draft.cc + draft.bcc
        if (all.size > MAX_RECIPIENTS) return@withContext AppActionResult.Failed("At most $MAX_RECIPIENTS recipients can be prepared at once.")
        all.firstOrNull { !EMAIL.matches(it) }?.let {
            return@withContext AppActionResult.Failed("\"${it.take(80)}\" is not an email address.")
        }
        if (draft.subject.length > MAX_SUBJECT_CHARS) return@withContext AppActionResult.Failed("The subject is longer than $MAX_SUBJECT_CHARS characters.")
        if (draft.body.length > MAX_SHARED_TEXT_CHARS) return@withContext AppActionResult.Failed("The message is longer than $MAX_SHARED_TEXT_CHARS characters.")
        val intent = HandoverQuery.EMAIL.intent(Uri.parse("mailto:"))
        if (draft.to.isNotEmpty()) intent.putExtra(Intent.EXTRA_EMAIL, draft.to.toTypedArray())
        if (draft.cc.isNotEmpty()) intent.putExtra(Intent.EXTRA_CC, draft.cc.toTypedArray())
        if (draft.bcc.isNotEmpty()) intent.putExtra(Intent.EXTRA_BCC, draft.bcc.toTypedArray())
        if (draft.subject.isNotEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, draft.subject)
        if (draft.body.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, draft.body)
        prepared(intent, "an email draft", missing = "an email app")
    }

    override suspend fun composeSms(number: String, body: String): AppActionResult = withContext(dispatcher) {
        numberProblem(number)?.let { return@withContext AppActionResult.Failed(it) }
        if (body.length > MAX_SHARED_TEXT_CHARS) return@withContext AppActionResult.Failed("The message is longer than $MAX_SHARED_TEXT_CHARS characters.")
        val intent = HandoverQuery.SMS.intent(Uri.parse("smsto:" + Uri.encode(number.trim())))
        if (body.isNotEmpty()) intent.putExtra(SMS_BODY, body)
        prepared(intent, "a text message draft", missing = "a messaging app")
    }

    override suspend fun dial(number: String): AppActionResult = withContext(dispatcher) {
        numberProblem(number)?.let { return@withContext AppActionResult.Failed(it) }
        // ACTION_DIAL only fills in the dialer; this app never holds CALL_PHONE.
        prepared(HandoverQuery.DIAL.intent(Uri.parse("tel:" + Uri.encode(number.trim()))), "the dialer with the number", missing = "a phone app")
    }

    /** Hands over a prepared message or call when an unprotected app takes it. */
    private fun prepared(intent: Intent, what: String, missing: String): AppActionResult = when (val target = narrow(intent)) {
        is Target.Refused -> target.result
        Target.NoneVisible -> AppActionResult.NotFound(missing)
        is Target.Ready -> handOver(target.intent, what)
    }

    // --- Install and uninstall ----------------------------------------------------------------

    override suspend fun uninstall(packageName: String, waitMs: Long): AppActionResult {
        refusalFor(packageName)?.let { return it }
        val name = withContext(dispatcher) {
            if (pm.packageInfoOrNull(packageName) == null) null else nameOf(packageName)
        } ?: return AppActionResult.NotFound(packageName)
        if (!startGate.mayStartActivities()) return notShownInBackground("removal of $name")
        val mailbox = ResultMailbox(appContext, "uninstall", oneShot = false)
        return try {
            pm.packageInstaller.uninstall(packageName, mailbox.pendingIntent.intentSender)
            awaitOutcome(mailbox, waitMs, "removal of $name") { AppActionResult.Uninstalled(packageName) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            AppActionResult.Failed("Android did not let this app ask to remove $name.")
        } catch (e: RuntimeException) {
            AppActionResult.Failed("Android could not start removing $name (${e.javaClass.simpleName}).")
        } finally {
            mailbox.close()
        }
    }

    override suspend fun install(apk: WorkspaceFile, waitMs: Long): AppActionResult {
        if (!apk.file.isFile) return AppActionResult.NotFound(apk.path.value)
        if (!pm.canRequestPackageInstalls()) {
            return AppActionResult.NeedsSpecialAccess(
                DeviceAccess.INSTALL_UNKNOWN_APPS,
                "Android's \"Install unknown apps\" permission is off for this app. The owner can turn it on in Android's settings (Apps > Special app access).",
            )
        }
        val packageName = withContext(dispatcher) { readApkPackage(apk.file) }
            ?: return AppActionResult.Failed("Android cannot read \"${apk.path.value}\" as an app package (APK).")
        if (protectedPackages().isProtected(packageName)) {
            return AppActionResult.Refused("\"${apk.path.value}\" contains $packageName, which agents may not install or update.")
        }
        if (!startGate.mayStartActivities()) return notShownInBackground("installation of $packageName")
        val installer = pm.packageInstaller
        val mailbox = ResultMailbox(appContext, "install", oneShot = false)
        var sessionId = -1
        var committed = false
        return try {
            withContext(dispatcher) {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(packageName)
                    setSize(apk.file.length())
                    // Truthful source: a file on this device. Android applies its sideloading rules to it.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                    // Always Android's confirmation, even where an update could be silent.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, apk.file.length()).use { out ->
                        FileInputStream(apk.file).use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    session.commit(mailbox.pendingIntent.intentSender)
                    committed = true
                }
            }
            awaitOutcome(mailbox, waitMs, "installation of $packageName") { status ->
                AppActionResult.Installed(status.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: packageName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppActionResult.Failed("\"${apk.path.value}\" could not be handed to Android's installer (${e.javaClass.simpleName}).")
        } catch (_: SecurityException) {
            AppActionResult.Failed("Android did not let this app start an installation.")
        } catch (e: RuntimeException) {
            AppActionResult.Failed("Android's installer reported an error (${e.javaClass.simpleName}).")
        } finally {
            if (!committed && sessionId >= 0) {
                try {
                    installer.abandonSession(sessionId)
                } catch (_: RuntimeException) {
                    // Gone already.
                }
            }
            mailbox.close()
        }
    }

    /**
     * Follows PackageInstaller's answers until the final one: shows Android's confirmation when
     * it asks for the owner, maps the final status, and gives [AppActionResult.OutcomeUnknown]
     * after [waitMs].
     */
    private suspend fun awaitOutcome(mailbox: ResultMailbox, waitMs: Long, what: String, success: (Intent) -> AppActionResult): AppActionResult =
        // The owner's time on Android's confirmation does not count against the task's limits (it is the owner's, not the agent's).
        WaitClock.pausedWhile { awaitOutcomeOn(mailbox, waitMs, what, success) }

    private suspend fun awaitOutcomeOn(mailbox: ResultMailbox, waitMs: Long, what: String, success: (Intent) -> AppActionResult): AppActionResult =
        withTimeoutOrNull(waitMs.coerceAtLeast(1)) {
            var outcome: AppActionResult? = null
            while (outcome == null) {
                val status = mailbox.receive()
                // Every PackageInstaller answer carries a status; anything else is not an answer.
                if (!status.hasExtra(PackageInstaller.EXTRA_STATUS)) continue
                val code = status.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                outcome = when (code) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Null (keep waiting) once the confirmation is on screen or offered.
                        val confirm = IntentCompat.getParcelableExtra(status, Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm == null) {
                            AppActionResult.Failed("Android asked for a confirmation for the $what but gave no screen to show.", code)
                        } else {
                            showConfirmation(confirm, what)
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> success(status)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> AppActionResult.UserCancelled
                    else -> AppActionResult.Failed(failureReason(code, status, what), code)
                }
            }
            outcome
        } ?: AppActionResult.OutcomeUnknown(
            "Android gave no final answer about the $what within ${waitMs / 1000} s; its confirmation may still be open. " +
                "Check before trying again.",
        )

    /** Starts Android's confirmation screen, or offers it as a notification; a failure to show it ends the wait. */
    private fun showConfirmation(confirm: Intent, what: String): AppActionResult? {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startGate.mayStartActivities()) {
            // Keep waiting: the owner may tap the notification within the wait.
            notifier.offer(confirm, "Android's confirmation for the $what")
            return null
        }
        return try {
            appContext.startActivity(confirm)
            null
        } catch (e: RuntimeException) {
            AppActionResult.Failed("Android's confirmation for the $what could not be shown (${e.javaClass.simpleName}).")
        }
    }

    private fun failureReason(code: Int, status: Intent, what: String): String {
        val reason = when {
            status.hasExtra(EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON) ->
                "Android blocked the $what because the app's developer is not verified (Android developer verification)."
            code == PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the $what."
            code == PackageInstaller.STATUS_FAILURE_INVALID -> "The $what failed: the package is invalid or damaged."
            code == PackageInstaller.STATUS_FAILURE_CONFLICT -> "The $what failed: it conflicts with an installed app (for example one signed with a different key)."
            code == PackageInstaller.STATUS_FAILURE_STORAGE -> "The $what failed: there is not enough storage."
            code == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The $what failed: the app is not compatible with this device."
            code == PackageInstaller.STATUS_FAILURE_TIMEOUT -> "The $what timed out in Android."
            else -> "Android reported that the $what failed."
        }
        val detail = status.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).toSingleLine(MAX_STATUS_MESSAGE_CHARS)
        return if (detail.isEmpty()) reason else "$reason Android said: $detail"
    }

    private fun notShownInBackground(what: String) = AppActionResult.Deferred(
        "Android does not show its confirmation for the $what while this app is in the background, so nothing was started. " +
            "Bring the app to the front and ask again.",
    )

    // --- Shared helpers -------------------------------------------------------------------------

    /** Why [packageName] cannot be a target, or null. */
    private fun refusalFor(packageName: String): AppActionResult? = when {
        !PACKAGE.matches(packageName) -> AppActionResult.Failed("\"${packageName.take(80)}\" is not a package name such as com.example.app.")
        protectedPackages().isProtected(packageName) ->
            AppActionResult.Refused("$packageName is protected: agents never use system settings, installers, permission screens, the system UI or this app.")
        else -> null
    }

    /** What an implicit intent resolves to, with protected apps left out. */
    private sealed interface Target {
        data class Ready(val intent: Intent) : Target
        data class Refused(val result: AppActionResult.Refused) : Target
        data object NoneVisible : Target
    }

    private fun narrow(intent: Intent): Target {
        val handlers = handlersOf(intent)
        if (handlers.isEmpty()) return Target.NoneVisible
        val protected = protectedPackages()
        val (blocked, allowed) = handlers.partition { protected.isProtected(it.packageName) }
        return when {
            blocked.isEmpty() -> Target.Ready(intent)
            allowed.isEmpty() -> Target.Refused(
                AppActionResult.Refused("Only ${blocked.map { it.packageName }.distinct().joinToString()} would open this, and agents never use protected apps."),
            )
            allowed.map { it.packageName }.distinct().size == 1 -> Target.Ready(intent.setPackage(allowed[0].packageName))
            else -> Target.Ready(
                Intent.createChooser(intent, null).putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    blocked.map { ComponentName(it.packageName, it.name) }.toTypedArray(),
                ),
            )
        }
    }

    /** Activities that would take [intent] if started now (implicit starts add CATEGORY_DEFAULT). */
    private fun handlersOf(intent: Intent): List<ActivityInfo> =
        pm.activitiesFor(intent, PackageManager.MATCH_DEFAULT_ONLY.toLong()).startableBy(pm, ownPackage)

    /** The default browser if it offers Custom Tabs, else the first installed browser that does. */
    private fun customTabsBrowser(): String? = try {
        CustomTabsClient.getPackageName(appContext, browsers().toList())
    } catch (_: RuntimeException) {
        null
    }

    /** Apps that open any web address: a link with no host matches browsers, not apps that claim certain sites. */
    private fun browsers(): Set<String> =
        handlersOf(HandoverQuery.WEB_LINK_HTTPS.intent(Uri.parse("https://"))).map { it.packageName }.toCollection(LinkedHashSet())

    /** The default browser, when the owner chose one (otherwise Android would ask). */
    private fun defaultBrowser(): String? {
        val browsers = browsers()
        val chosen = pm.resolveActivity(HandoverQuery.WEB_LINK_HTTPS.intent(Uri.parse("https://")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return chosen?.takeIf { it in browsers } ?: browsers.singleOrNull()
    }

    /** Starts [intent] now, or offers it as a notification when Android would not let it start. */
    private fun handOver(intent: Intent, what: String, notFound: String = what): AppActionResult {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!startGate.mayStartActivities()) return notifier.offer(intent, what)
        return try {
            appContext.startActivity(intent)
            AppActionResult.Started
        } catch (_: ActivityNotFoundException) {
            AppActionResult.NotFound(notFound)
        } catch (_: SecurityException) {
            AppActionResult.Failed("Android did not let this app open $what.")
        } catch (e: RuntimeException) {
            // A binder overflow arrives wrapped in a bare RuntimeException. Only the class name is
            // reported: the message may describe the intent.
            if (generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is TransactionTooLargeException }) {
                AppActionResult.Failed("What was handed over is too large for Android to pass on. Shorten it.")
            } else {
                AppActionResult.Failed("Android could not open $what (${e.javaClass.simpleName}).")
            }
        }
    }

    private fun nameOf(packageName: String): String =
        pm.packageInfoOrNull(packageName)?.applicationInfo?.let(::labelOf) ?: packageName

    private fun labelOf(app: ApplicationInfo): String = app.loadLabel(pm).toSingleLine(MAX_LABEL_CHARS).ifEmpty { app.packageName }

    private fun archivePackageName(file: File): String? =
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(file.path, 0)?.packageName

    /** Limits and formats. */
    companion object {
        /** Like the AI app launcher: intent extras share a binder buffer of about 1 MB. */
        const val MAX_SHARED_TEXT_CHARS = 100_000
        const val MAX_SUBJECT_CHARS = 1_000
        const val MAX_RECIPIENTS = 50
        private const val MAX_LABEL_CHARS = 80
        private const val MAX_VERSION_CHARS = 64
        private const val MAX_STATUS_MESSAGE_CHARS = 300
        private const val MAX_CAUSE_DEPTH = 8

        /** The extra messaging apps read the prefilled text from. */
        const val SMS_BODY = "sms_body"

        /** `PackageInstaller.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON` (Android 16.1). */
        const val EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON = "android.content.pm.extra.DEVELOPER_VERIFICATION_FAILURE_REASON"

        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        private val MIME = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/([a-z0-9][a-z0-9!#$&^_.+-]{0,126}|\\*)$")
        private val EMAIL = Regex("^[^\\s@<>(),;:\"\\\\]{1,64}@[^\\s@<>(),;:\"\\\\]{1,255}$")
        private val PHONE_ALLOWED = Regex("^\\+?[0-9 ().-]{1,40}$")

        /**
         * Why [number] is not prepared, or null. Digits, spaces, "+", "-", "." and brackets only:
         * service codes ("*", "#") and dial pauses (",", ";") are refused, because some dialers
         * run such codes as they are typed in.
         */
        internal fun numberProblem(number: String): String? {
            val n = number.trim()
            return when {
                n.isEmpty() -> "There is no phone number."
                n.any { it == '*' || it == '#' } -> "Service codes with * or # are not prepared by agents; the owner can dial them."
                n.any { it == ',' || it == ';' } -> "Dial pauses (, and ;) are not prepared by agents."
                !PHONE_ALLOWED.matches(n) || n.none(Char::isDigit) -> "\"${n.take(40)}\" is not a phone number."
                else -> null
            }
        }
    }
}
