package com.aicontrolcenter.device.operator.apps

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.AppActionResult
import com.aicontrolcenter.core.operator.DeviceAccess
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.EmailDraft
import com.aicontrolcenter.core.operator.ProtectedPackages
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.device.operator.AndroidWorkspaceRoot
import com.aicontrolcenter.device.testing.FakeDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import com.aicontrolcenter.core.tasks.withPausableTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowPackageInstaller
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidAppActionsTest {
    private val device = FakeDevice()
    private val app = device.application
    private var mayStart = true
    private var resolvedProtected = emptySet<String>()
    private var apkPackage: String? = "com.example.newapp"
    private val actions = AndroidAppActions(
        app,
        protectedPackages = { ProtectedPackages(app.packageName, resolvedProtected) },
        startGate = { mayStart },
        apkPackageName = { apkPackage },
        dispatcher = Dispatchers.Unconfined,
    )
    private val notifications: NotificationManager = app.getSystemService(NotificationManager::class.java)

    init {
        // FileProvider keeps each authority's paths in a static cache. Every Robolectric test
        // has a new files folder in the same JVM, so the cache would hold an earlier test's.
        (FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>).clear()
    }

    // --- Device setup -----------------------------------------------------------------------------

    private fun activity(pkg: String, name: String, filter: IntentFilter): ComponentName {
        val component = ComponentName(pkg, "$pkg.$name")
        device.shadowPackageManager.addActivityIfNotPresent(component).exported = true
        device.shadowPackageManager.addIntentFilterForActivity(component, filter)
        return component
    }

    private fun browser(pkg: String, customTabs: Boolean = false): ComponentName {
        device.install(pkg, label = pkg.substringAfterLast('.'))
        if (customTabs) {
            val service = ComponentName(pkg, "$pkg.TabsService")
            device.shadowPackageManager.addServiceIfNotPresent(service)
            device.shadowPackageManager.addIntentFilterForService(service, IntentFilter("android.support.customtabs.action.CustomTabsService"))
        }
        return activity(
            pkg, "BrowserActivity",
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
                addDataScheme("https")
            },
        )
    }

    private fun linkHandler(pkg: String, host: String): ComponentName {
        if (device.packageManager.getInstalledPackages(0).none { it.packageName == pkg }) device.install(pkg)
        return activity(
            pkg, "LinkActivity",
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority(host, null)
            },
        )
    }

    private fun handles(pkg: String, action: String, scheme: String): ComponentName {
        device.install(pkg)
        return activity(pkg, "Handler", IntentFilter(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme(scheme)
        })
    }

    private fun started(): Intent = assertNotNull(shadowOf(app).nextStartedActivity, "an activity must have been started")

    private fun assertNothingStarted() = assertNull(shadowOf(app).peekNextStartedActivity(), "nothing may be started")

    private fun refusedReason(result: AppActionResult): String = assertIs<AppActionResult.Refused>(result).reason

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // --- Apps -------------------------------------------------------------------------------------

    @Test
    fun `lists launchable apps by label, leaving out protected apps and this one`() = runTest {
        device.install("com.example.zebra", label = "zebra", version = "2.0") { launcher() }
        device.install("com.example.alpha", label = "Alpha") { launcher() }
        device.install("com.example.service", label = "No icon")
        device.install("com.android.settings", label = "Settings", system = true) { launcher() }
        device.manifestOf(app.packageName).launcher()

        assertEquals(
            listOf(
                InstalledAppInfo("com.example.alpha", "Alpha", "1.0"),
                InstalledAppInfo("com.example.zebra", "zebra", "2.0"),
            ),
            actions.listLaunchable(),
        )
    }

    @Test
    fun `open starts the app's launcher screen`() = runTest {
        device.install("com.example.notes") { launcher() }

        assertEquals(AppActionResult.Started, actions.open("com.example.notes"))

        val intent = started()
        assertEquals(ComponentName("com.example.notes", "com.example.notes.MainActivity"), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `open refuses protected apps, this app and bad names, and reports missing ones`() = runTest {
        device.install("com.android.settings", system = true) { launcher() }
        device.install("com.oem.settings") { launcher() }
        resolvedProtected = setOf("com.oem.settings")

        assertTrue("protected" in refusedReason(actions.open("com.android.settings")))
        assertTrue("protected" in refusedReason(actions.open("com.oem.settings")), "handlers resolved at start-up count too")
        assertTrue("protected" in refusedReason(actions.open(app.packageName)))
        assertTrue("protected" in refusedReason(actions.open("com.aicontrolcenter.other")))
        assertIs<AppActionResult.Failed>(actions.open("not a package"))
        assertEquals(AppActionResult.NotFound("com.example.missing"), actions.open("com.example.missing"))
        assertNothingStarted()
    }

    @Test
    fun `in the background a start becomes a Tap to open notification`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        device.install("com.example.notes", label = "Notes") { launcher() }
        mayStart = false

        val result = assertIs<AppActionResult.Deferred>(actions.open("com.example.notes"))

        assertTrue("Tap to open" in result.reason, result.reason)
        assertNothingStarted()
        val posted = shadowOf(notifications).allNotifications.single()
        assertEquals(HandoverNotifier.CHANNEL_ID, posted.channelId)
        assertEquals("Tap to open Notes", shadowOf(posted).contentTitle.toString())
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals(ComponentName("com.example.notes", "com.example.notes.MainActivity"), tap.component)
        assertTrue(shadowOf(posted.contentIntent).isActivity)
    }

    @Test
    fun `in the background without notifications nothing is offered, and the result says so`() = runTest {
        device.install("com.example.notes") { launcher() }
        mayStart = false

        val result = assertIs<AppActionResult.Deferred>(actions.open("com.example.notes"))

        assertTrue("nothing was offered" in result.reason, result.reason)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        assertNothingStarted()
    }

    // --- Links ------------------------------------------------------------------------------------

    @Test
    fun `a link opens exactly as LinkPolicy normalised it, as a browsable link`() = runTest {
        browser("org.example.browser")

        assertEquals(AppActionResult.Started, actions.openLink("  HTTPS://Example.com/a?b=1  "))

        val intent = started()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://Example.com/a?b=1"), intent.data)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `links LinkPolicy refuses never reach Android`() = runTest {
        browser("org.example.browser")

        for (link in listOf("javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a", "content://x/y", "mailto:a@b.c", "tel:123", "com.aicontrolcenter.app:open", "no scheme")) {
            assertIs<AppActionResult.Refused>(actions.openLink(link), link)
        }
        assertIs<AppActionResult.Refused>(actions.openLink("https://example.com/a b"))
        assertNothingStarted()
    }

    @Test
    fun `a link only a protected app would open is refused`() = runTest {
        linkHandler("com.google.android.gms", "accounts.example.com")

        assertTrue("protected" in refusedReason(actions.openLink("https://accounts.example.com/sign-in")))
        assertNothingStarted()
    }

    @Test
    fun `protected handlers are left out of a link's choices`() = runTest {
        linkHandler("com.google.android.gms", "shop.example.com")
        browser("org.example.browser")

        assertEquals(AppActionResult.Started, actions.openLink("https://shop.example.com/cart"))
        assertEquals("org.example.browser", started().`package`)

        browser("org.other.browser")
        assertEquals(AppActionResult.Started, actions.openLink("https://shop.example.com/cart"))
        val chooser = started()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val excluded = IntentCompat.getParcelableArrayExtra(chooser, Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java)!!.toList()
        assertEquals(listOf(ComponentName("com.google.android.gms", "com.google.android.gms.LinkActivity")), excluded)
        assertEquals(Uri.parse("https://shop.example.com/cart"), IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!.data)
    }

    @Test
    fun `an app link no visible app claims is started for Android to resolve, or reported missing`() = runTest {
        assertEquals(AppActionResult.Started, actions.openLink("spotify:track:42"))
        assertEquals(Uri.parse("spotify:track:42"), started().data)

        shadowOf(app).checkActivities(true)
        assertEquals(AppActionResult.NotFound("an app that opens spotify: links"), actions.openLink("spotify:track:42"))
    }

    @Test
    fun `the browser opens a Custom Tab when the browser offers them`() = runTest {
        browser("org.example.browser", customTabs = true)

        assertEquals(AppActionResult.Started, actions.openInBrowser("https://example.com/page"))

        val intent = started()
        assertEquals("org.example.browser", intent.`package`)
        assertEquals(Uri.parse("https://example.com/page"), intent.data)
        assertTrue(intent.hasExtra("android.support.customtabs.extra.SESSION"), "a Custom Tab, not a plain view")
    }

    @Test
    fun `without Custom Tabs the default browser opens the page, never an app that claims the site`() = runTest {
        browser("org.example.browser")
        linkHandler("com.example.shop", "shop.example.com")

        assertEquals(AppActionResult.Started, actions.openInBrowser("https://shop.example.com/"))

        val intent = started()
        assertEquals("org.example.browser", intent.`package`)
        assertTrue(!intent.hasExtra("android.support.customtabs.extra.SESSION"))
    }

    @Test
    fun `only web addresses open in the browser`() = runTest {
        browser("org.example.browser", customTabs = true)

        assertIs<AppActionResult.Refused>(actions.openInBrowser("geo:0,0?q=cafe"))
        assertIs<AppActionResult.Refused>(actions.openInBrowser("javascript:alert(1)"))
        assertNothingStarted()
    }

    // --- Sharing ----------------------------------------------------------------------------------

    @Test
    fun `text goes to the share sheet, or to the chosen app's share screen`() = runTest {
        device.install("com.example.chat", label = "Chat") { sharesText() }

        assertEquals(AppActionResult.Started, actions.shareText("Hello", "Greeting", null))
        val chooser = started()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val shared = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("Hello", shared.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Greeting", shared.getStringExtra(Intent.EXTRA_SUBJECT))

        assertEquals(AppActionResult.Started, actions.shareText("Hello", null, "com.example.chat"))
        val direct = started()
        assertEquals(ComponentName("com.example.chat", "com.example.chat.ShareActivity"), direct.component)
        assertEquals("text/plain", direct.type)
        assertEquals("Hello", direct.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `sharing refuses protected apps and reports apps that take no text`() = runTest {
        device.install("com.example.viewer") { launcher() }

        assertIs<AppActionResult.Refused>(actions.shareText("x", null, "com.android.settings"))
        assertEquals(AppActionResult.NotFound("com.example.gone"), actions.shareText("x", null, "com.example.gone"))
        assertTrue("accepts text" in assertIs<AppActionResult.NotFound>(actions.shareText("x", null, "com.example.viewer")).what)
        assertIs<AppActionResult.Failed>(actions.shareText("", null, null))
        assertIs<AppActionResult.Failed>(actions.shareText("x".repeat(AndroidAppActions.MAX_SHARED_TEXT_CHARS + 1), null, null))
        assertNothingStarted()
    }

    @Test
    fun `a workspace file is shared read-only through the workspace provider`() = runTest {
        val root = AndroidWorkspaceRoot(app).directory()
        val file = (WorkspaceFile.resolve(root, RelativePath.of("out/report.pdf")) as DocResult.Ok).value
        file.file.parentFile!!.mkdirs()
        file.file.writeText("%PDF")

        assertEquals(AppActionResult.Started, actions.shareFile(file, "application/pdf", null))

        val chooser = started()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(WorkspaceFileProvider.authority(app), uri.authority)
        assertEquals("application/pdf", send.type)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0, "the chooser carries the read grant")
        assertTrue(chooser.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0, "never a write grant")
        assertEquals("%PDF", app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
    }

    @Test
    fun `files outside the workspace, missing files and bad types are not shared`() = runTest {
        val elsewhere = Files.createTempDirectory("elsewhere").toFile()
        val outside = (WorkspaceFile.resolve(elsewhere, RelativePath.of("x.txt")) as DocResult.Ok).value
        outside.file.writeText("x")
        val root = AndroidWorkspaceRoot(app).directory()
        val inside = (WorkspaceFile.resolve(root, RelativePath.of("a.txt")) as DocResult.Ok).value
        inside.file.writeText("a")

        assertIs<AppActionResult.Failed>(actions.shareFile(outside, "text/plain", null))
        assertEquals(AppActionResult.NotFound("missing.txt"), actions.shareFile((WorkspaceFile.resolve(root, RelativePath.of("missing.txt")) as DocResult.Ok).value, "text/plain", null))
        assertIs<AppActionResult.Failed>(actions.shareFile(inside, "text plain", null))
        assertNothingStarted()
    }

    // --- Messages and calls -----------------------------------------------------------------------

    @Test
    fun `an email opens prefilled in the mail app and nothing is sent`() = runTest {
        handles("com.example.mail", Intent.ACTION_SENDTO, "mailto")

        val draft = EmailDraft(to = listOf("a@example.com"), cc = listOf("b@example.com"), subject = "Plan", body = "Line 1\nLine 2")
        assertEquals(AppActionResult.Started, actions.composeEmail(draft))

        val intent = started()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(Uri.parse("mailto:"), intent.data)
        assertEquals(listOf("a@example.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL)!!.toList())
        assertEquals(listOf("b@example.com"), intent.getStringArrayExtra(Intent.EXTRA_CC)!!.toList())
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_BCC))
        assertEquals("Plan", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Line 1\nLine 2", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `email drafts are checked before anything opens`() = runTest {
        assertEquals(AppActionResult.NotFound("an email app"), actions.composeEmail(EmailDraft(to = listOf("a@example.com"))))
        handles("com.example.mail", Intent.ACTION_SENDTO, "mailto")

        assertIs<AppActionResult.Failed>(actions.composeEmail(EmailDraft(to = listOf("not an address"))))
        assertIs<AppActionResult.Failed>(actions.composeEmail(EmailDraft(to = listOf("a@b.c\nBcc: x@y.z"))))
        assertIs<AppActionResult.Failed>(actions.composeEmail(EmailDraft(to = List(AndroidAppActions.MAX_RECIPIENTS + 1) { "u$it@example.com" })))
        assertNothingStarted()
    }

    @Test
    fun `a text message opens prefilled in the messaging app`() = runTest {
        handles("com.example.sms", Intent.ACTION_SENDTO, "smsto")

        assertEquals(AppActionResult.Started, actions.composeSms("+1 (555) 010-0199", "On my way"))

        val intent = started()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data!!.scheme)
        assertEquals("+1 (555) 010-0199", intent.data!!.schemeSpecificPart)
        assertEquals("On my way", intent.getStringExtra(AndroidAppActions.SMS_BODY))
    }

    @Test
    fun `dial fills in the dialer and never calls`() = runTest {
        handles("com.example.dialer", Intent.ACTION_DIAL, "tel")

        assertEquals(AppActionResult.Started, actions.dial("+44 20 7946 0958"))

        val intent = started()
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals("+44 20 7946 0958", intent.data!!.schemeSpecificPart)
        assertEquals(AppActionResult.NotFound("a messaging app"), actions.composeSms("123", "x"))
    }

    @Test
    fun `service codes, pauses and non-numbers are never prepared`() = runTest {
        handles("com.example.dialer", Intent.ACTION_DIAL, "tel")
        handles("com.example.sms", Intent.ACTION_SENDTO, "smsto")

        for (number in listOf("*#06#", "*21*123#", "123,456", "123;9", "", "   ", "call me", "++1", "12345678901234567890123456789012345678901")) {
            assertIs<AppActionResult.Failed>(actions.dial(number), number)
            assertIs<AppActionResult.Failed>(actions.composeSms(number, "x"), number)
        }
        assertNothingStarted()
        assertEquals(null, AndroidAppActions.numberProblem("112"))
        assertTrue("Service codes" in AndroidAppActions.numberProblem("*#06#")!!)
    }

    @Test
    fun `a message only a protected app takes is refused`() = runTest {
        handles("com.android.settings", Intent.ACTION_DIAL, "tel")

        assertIs<AppActionResult.Refused>(actions.dial("123"))
        assertNothingStarted()
    }

    // --- Uninstall --------------------------------------------------------------------------------

    private val installer get() = device.packageManager.packageInstaller

    private fun status(code: Int, extras: Intent.() -> Unit = {}): Intent = Intent().putExtra(PackageInstaller.EXTRA_STATUS, code).apply(extras)

    private fun TestScope.answer(sender: android.content.IntentSender, status: Intent) {
        sender.sendIntent(app, 0, status, null, null)
        idleMain()
        runCurrent()
    }

    @Test
    fun `uninstall shows Android's confirmation and reports the removal`() = runTest {
        device.install("com.example.old") { launcher() }

        val result = async { actions.uninstall("com.example.old", waitMs = 60_000) }
        runCurrent()
        val sender = assertNotNull(shadowOf(installer).getLastUninstalledStatusReceiver("com.example.old"))
        answer(sender, status(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, Intent("android.intent.action.UNINSTALL_PACKAGE")) })

        val confirm = started()
        assertEquals("android.intent.action.UNINSTALL_PACKAGE", confirm.action)
        assertTrue(confirm.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        answer(sender, status(PackageInstaller.STATUS_SUCCESS))
        assertEquals(AppActionResult.Uninstalled("com.example.old"), result.await())
    }

    @Test
    fun `the owner's time on Android's confirmation does not count against the task's limit`() = runTest {
        device.install("com.example.old") { launcher() }

        // The task has one second left; the owner reads Android's dialog for five.
        val result = async { withPausableTimeout(1_000, testTimeSource) { actions.uninstall("com.example.old", waitMs = 60_000) } }
        runCurrent()
        val sender = assertNotNull(shadowOf(installer).getLastUninstalledStatusReceiver("com.example.old"))
        answer(sender, status(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, Intent("android.intent.action.UNINSTALL_PACKAGE")) })
        advanceTimeBy(5_000)
        answer(sender, status(PackageInstaller.STATUS_SUCCESS))

        assertEquals(AppActionResult.Uninstalled("com.example.old"), result.await())
    }

    @Test
    fun `the apps that would open a link are named, protected ones left out`() = runTest {
        for (pkg in listOf("com.example.pay", "com.google.android.gms")) {
            device.install(pkg, label = "Pay")
            activity(pkg, "PayActivity", IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("upi")
            })
        }

        assertEquals(listOf("com.example.pay"), actions.linkHandlers("upi://pay?pa=x@bank&am=500").map { it.packageName })
        assertEquals(emptyList(), actions.linkHandlers("intent://x#Intent;end"), "a link the policy refuses has no handler")
        assertEquals(emptyList(), actions.linkHandlers("skype:echo123?call"))
    }

    @Test
    fun `uninstall reports a cancelled confirmation and Android's failures`() = runTest {
        device.install("com.example.old") { launcher() }

        val cancelled = async { actions.uninstall("com.example.old", waitMs = 60_000) }
        runCurrent()
        answer(shadowOf(installer).getLastUninstalledStatusReceiver("com.example.old")!!, status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(AppActionResult.UserCancelled, cancelled.await())

        val failed = async { actions.uninstall("com.example.old", waitMs = 60_000) }
        runCurrent()
        answer(
            shadowOf(installer).getLastUninstalledStatusReceiver("com.example.old")!!,
            status(PackageInstaller.STATUS_FAILURE_BLOCKED) { putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "Device admin\npolicy") },
        )
        val failure = assertIs<AppActionResult.Failed>(failed.await())
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, failure.code)
        assertTrue("Device admin policy" in failure.reason, failure.reason)
    }

    @Test
    fun `without a final answer in time the outcome is unknown`() = runTest {
        device.install("com.example.old") { launcher() }

        val result = async { actions.uninstall("com.example.old", waitMs = 5_000) }
        runCurrent()
        advanceTimeBy(5_001)

        val unknown = assertIs<AppActionResult.OutcomeUnknown>(result.await())
        assertTrue("may still be open" in unknown.reason)
        assertTrue(shadowOf(app).registeredReceivers.none { it.intentFilter.actionsIterator().asSequence().any { a -> ".operator.uninstall." in a } }, "the status receiver is gone")
    }

    @Test
    fun `uninstall refuses protected apps and this app, and asks nothing in the background`() = runTest {
        device.install("com.example.old") { launcher() }
        device.install("com.android.settings", system = true)

        assertIs<AppActionResult.Refused>(actions.uninstall("com.android.settings"))
        assertIs<AppActionResult.Refused>(actions.uninstall(app.packageName))
        assertEquals(AppActionResult.NotFound("com.example.none"), actions.uninstall("com.example.none"))
        mayStart = false
        assertTrue("nothing was started" in assertIs<AppActionResult.Deferred>(actions.uninstall("com.example.old")).reason)
        assertNull(shadowOf(installer).getLastUninstalledStatusReceiver("com.example.old"))
        assertNull(shadowOf(installer).getLastUninstalledStatusReceiver("com.android.settings"))
    }

    // --- Install ----------------------------------------------------------------------------------

    private fun apk(name: String = "app.apk"): WorkspaceFile {
        val root = AndroidWorkspaceRoot(app).directory()
        return (WorkspaceFile.resolve(root, RelativePath.of("downloads/$name")) as DocResult.Ok).value.also {
            it.file.parentFile!!.mkdirs()
            it.file.writeBytes(ByteArray(1024) { i -> i.toByte() })
        }
    }

    private fun statusReceiverOf(sessionId: Int): android.content.IntentSender {
        val shadowInstaller: ShadowPackageInstaller = shadowOf(installer)
        @Suppress("UNCHECKED_CAST")
        val sessions = ShadowPackageInstaller::class.java.getDeclaredField("sessions").apply { isAccessible = true }
            .get(shadowInstaller) as Map<Int, PackageInstaller.Session>
        val shadowSession = Shadow.extract<ShadowPackageInstaller.ShadowSession>(sessions.getValue(sessionId))
        return ShadowPackageInstaller.ShadowSession::class.java.getDeclaredField("statusReceiver").apply { isAccessible = true }
            .get(shadowSession) as android.content.IntentSender
    }

    @Test
    fun `install writes a session, shows Android's confirmation and reports the install`() = runTest {
        shadowOf(device.packageManager).setCanRequestPackageInstalls(true)

        val result = async { actions.install(apk(), waitMs = 60_000) }
        runCurrent()
        val session = installer.allSessions.single()
        assertEquals("com.example.newapp", session.appPackageName)
        val sender = statusReceiverOf(session.sessionId)
        answer(sender, status(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, Intent("android.content.pm.action.CONFIRM_INSTALL")) })
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", started().action)

        answer(sender, status(PackageInstaller.STATUS_SUCCESS) { putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, "com.example.newapp") })
        assertEquals(AppActionResult.Installed("com.example.newapp"), result.await())
    }

    @Test
    fun `install reports cancellation, developer verification and other failures`() = runTest {
        shadowOf(device.packageManager).setCanRequestPackageInstalls(true)

        val cancelled = async { actions.install(apk("a.apk"), waitMs = 60_000) }
        runCurrent()
        answer(statusReceiverOf(installer.allSessions.last().sessionId), status(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(AppActionResult.UserCancelled, cancelled.await())

        val blocked = async { actions.install(apk("b.apk"), waitMs = 60_000) }
        runCurrent()
        answer(
            statusReceiverOf(installer.allSessions.last().sessionId),
            status(PackageInstaller.STATUS_FAILURE_BLOCKED) { putExtra(AndroidAppActions.EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON, 2) },
        )
        val failure = assertIs<AppActionResult.Failed>(blocked.await())
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, failure.code)
        assertTrue("developer is not verified" in failure.reason, failure.reason)

        val conflict = async { actions.install(apk("c.apk"), waitMs = 60_000) }
        runCurrent()
        answer(statusReceiverOf(installer.allSessions.last().sessionId), status(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, assertIs<AppActionResult.Failed>(conflict.await()).code)
    }

    @Test
    fun `when the confirmation cannot be shown it is offered as a notification and the wait goes on`() = runTest {
        shadowOf(device.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val result = async { actions.install(apk(), waitMs = 60_000) }
        runCurrent()
        val sender = statusReceiverOf(installer.allSessions.single().sessionId)
        mayStart = false
        answer(sender, status(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, Intent("android.content.pm.action.CONFIRM_INSTALL")) })

        assertNothingStarted()
        val posted = shadowOf(notifications).allNotifications.single()
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", shadowOf(posted.contentIntent).savedIntent.action)
        answer(sender, status(PackageInstaller.STATUS_SUCCESS))
        assertEquals(AppActionResult.Installed("com.example.newapp"), result.await())
    }

    @Test
    fun `install needs the special access, a readable APK and an unprotected package`() = runTest {
        shadowOf(device.packageManager).setCanRequestPackageInstalls(false)
        val needs = assertIs<AppActionResult.NeedsSpecialAccess>(actions.install(apk()))
        assertEquals(DeviceAccess.INSTALL_UNKNOWN_APPS, needs.access)

        shadowOf(device.packageManager).setCanRequestPackageInstalls(true)
        val root = AndroidWorkspaceRoot(app).directory()
        assertEquals(AppActionResult.NotFound("none.apk"), actions.install((WorkspaceFile.resolve(root, RelativePath.of("none.apk")) as DocResult.Ok).value))
        apkPackage = null
        assertIs<AppActionResult.Failed>(actions.install(apk()))
        apkPackage = app.packageName
        assertIs<AppActionResult.Refused>(actions.install(apk()), "an agent never replaces this app")
        apkPackage = "com.android.settings"
        assertIs<AppActionResult.Refused>(actions.install(apk()))
        apkPackage = "com.example.newapp"
        mayStart = false
        assertIs<AppActionResult.Deferred>(actions.install(apk()))
        assertTrue(installer.allSessions.isEmpty(), "no session was started")
    }

    @Test
    fun `an install with no final answer is unknown, and its receiver is released`() = runTest {
        shadowOf(device.packageManager).setCanRequestPackageInstalls(true)

        val result = async { actions.install(apk(), waitMs = 2_000) }
        runCurrent()
        advanceTimeBy(2_001)

        assertIs<AppActionResult.OutcomeUnknown>(result.await())
        assertTrue(shadowOf(app).registeredReceivers.none { it.intentFilter.actionsIterator().asSequence().any { a -> ".operator.install." in a } })
    }

    @Test
    fun `a status broadcast without the session's own action is ignored`() = runTest {
        shadowOf(device.packageManager).setCanRequestPackageInstalls(true)

        val result = async { actions.install(apk(), waitMs = 2_000) }
        runCurrent()
        // Only the session's PendingIntent carries the unique action (and on a device the
        // receiver is not exported either).
        app.sendBroadcast(Intent("com.aicontrolcenter.device.operator.install").putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        idleMain()
        advanceTimeBy(2_001)

        assertIs<AppActionResult.OutcomeUnknown>(result.await())
    }
}
