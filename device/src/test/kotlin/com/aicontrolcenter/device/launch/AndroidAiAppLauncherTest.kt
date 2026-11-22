package com.aicontrolcenter.device.launch

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.TransactionTooLargeException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppCapability.LAUNCH
import com.aicontrolcenter.core.registry.AiAppCapability.PROCESS_TEXT
import com.aicontrolcenter.core.registry.AiAppCapability.PROMPT_DEEP_LINK
import com.aicontrolcenter.core.registry.AiAppCapability.SHARE_IMAGE
import com.aicontrolcenter.core.registry.AiAppCapability.SHARE_TEXT
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.LaunchResult
import com.aicontrolcenter.device.catalog.IdEvidence
import com.aicontrolcenter.device.catalog.KnownAiApp
import com.aicontrolcenter.device.catalog.KnownAiAppKind
import com.aicontrolcenter.device.catalog.PromptDeepLink
import com.aicontrolcenter.device.scan.AndroidAiAppScanner
import com.aicontrolcenter.device.testing.FakeDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidAiAppLauncherTest {

    private val pkg = "com.vendor.chatbot"
    private val link = "https://chat.vendor.example/new?q={prompt}"
    private val observedLink = "https://chat.vendor.example/observed?q={prompt}"

    /** The vendor documents [link] for [pkg]; [observedLink] is only a lead. */
    private val catalog = listOf(
        KnownAiApp(
            packageName = pkg, name = "Chatbot", vendor = "Vendor", kind = KnownAiAppKind.CLOUD_ASSISTANT,
            idEvidence = IdEvidence.VENDOR_SOURCE,
            promptLinks = listOf(
                PromptDeepLink(observedLink, verified = false, opens = "A new chat, as observed on the web."),
                PromptDeepLink(link, verified = true, opens = "A new chat with the prompt typed in."),
            ),
        ),
        KnownAiApp(
            packageName = "com.vendor.legacy", name = "Chatbot Legacy", vendor = "Vendor",
            kind = KnownAiAppKind.CLOUD_ASSISTANT, idEvidence = IdEvidence.VENDOR_SOURCE,
            promptLinks = listOf(PromptDeepLink(link, verified = true, opens = "A new chat with the prompt typed in.")),
        ),
    )

    private val device = FakeDevice()
    private var mayStart = true
    private val launcher =
        AndroidAiAppLauncher(device.application, catalog, gate = { mayStart }, dispatcher = Dispatchers.Unconfined)

    private fun registered(
        vararg capabilities: AiAppCapability,
        packageName: String = pkg,
        status: AiAppStatus = AiAppStatus.INSTALLED,
        enabled: Boolean = true,
        hidden: Boolean = false,
        promptDeepLink: String? = null,
        firstInstallTime: Long? = null,
        signingCertSha256: String? = null,
    ) = AiApp(
        id = AiAppId("app_1"), packageName = packageName, label = "Chatbot", source = DiscoverySource.CATALOG,
        capabilities = capabilities.toSet(), status = status, enabled = enabled, hidden = hidden,
        promptDeepLink = promptDeepLink, firstInstallTime = firstInstallTime, signingCertSha256 = signingCertSha256,
        firstSeenAt = 1, lastSeenAt = 2,
    )

    private fun nextStarted(): Intent? = shadowOf(device.application).nextStartedActivity

    private fun assertNothingStarted() = assertNull(shadowOf(device.application).peekNextStartedActivity(), "no activity may be started")

    private fun LaunchResult.reason(): String = assertIs<LaunchResult.NotPossible>(this).reason

    @Test
    fun `launch starts the app's launcher activity in a new task`() = runTest {
        device.install(pkg) { launcher() }

        assertEquals(LaunchResult.Started, launcher.launch(registered(LAUNCH)))

        val intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(device.manifestOf(pkg).component("MainActivity"), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(intent.extras, "opening an app sends nothing to it")
        assertNothingStarted()
    }

    @Test
    fun `launch is refused when the registry entry does not permit it`() = runTest {
        device.install(pkg) { launcher() }

        assertTrue("switched off" in launcher.launch(registered(LAUNCH, enabled = false)).reason())
        assertTrue("removed from the registry" in launcher.launch(registered(LAUNCH, hidden = true)).reason())
        assertTrue("removed from the registry" in launcher.launch(registered(LAUNCH, hidden = true, enabled = false)).reason())
        assertTrue("not installed" in launcher.launch(registered(LAUNCH, status = AiAppStatus.NOT_INSTALLED)).reason())
        assertTrue("disabled in Android settings" in launcher.launch(registered(LAUNCH, status = AiAppStatus.DISABLED_BY_SYSTEM)).reason())
        assertTrue("no launcher screen" in launcher.launch(registered(SHARE_TEXT, PROCESS_TEXT)).reason())
        assertNothingStarted()
    }

    @Test
    fun `launch notices that the app was removed or disabled since the last scan`() = runTest {
        val reasonWhenMissing = launcher.launch(registered(LAUNCH)).reason()
        assertTrue("no longer installed" in reasonWhenMissing, reasonWhenMissing)

        device.install(pkg) { launcher() }
        device.disableInSettings(pkg)
        val reasonWhenDisabled = launcher.launch(registered(LAUNCH)).reason()
        assertTrue("no screen that can be opened" in reasonWhenDisabled, reasonWhenDisabled)
        assertNothingStarted()
    }

    @Test
    fun `hand-off shares the text with the app's single share target`() = runTest {
        device.install(pkg) {
            launcher()
            sharesText()
        }
        val text = "Summarise this:\n• first point\n• second point"

        assertEquals(LaunchResult.Started, launcher.handOffText(registered(LAUNCH, SHARE_TEXT), text))

        val intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(text, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(device.manifestOf(pkg).component("ShareActivity"), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(intent.data)
        assertNothingStarted()
    }

    @Test
    fun `with several share targets the choice stays inside the app's package`() = runTest {
        device.install(pkg) {
            sharesText(activity = "NewChatActivity")
            sharesText(activity = "AddToProjectActivity")
        }
        // Another app that also accepts text must never receive the hand-off.
        device.install("com.other.notes") { sharesText() }

        assertEquals(LaunchResult.Started, launcher.handOffText(registered(SHARE_TEXT), "hello"))

        val intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(pkg, intent.`package`)
        assertNull(intent.component)
        assertEquals("hello", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `hand-off uses the prompt link when the app takes no shared text`() = runTest {
        device.install(pkg) {
            launcher()
            opensHttpsLinks("chat.vendor.example")
        }
        val app = registered(LAUNCH, PROMPT_DEEP_LINK, promptDeepLink = link)

        assertEquals(LaunchResult.Started, launcher.handOffText(app, "Why is 1 + 1 = 2 & not 3?"))

        val intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://chat.vendor.example/new?q=Why%20is%201%20%2B%201%20%3D%202%20%26%20not%203%3F"), intent.data)
        assertEquals("Why is 1 + 1 = 2 & not 3?", intent.data?.getQueryParameter("q"))
        assertEquals(pkg, intent.`package`, "the link must be pinned to the app so no browser can pick it up")
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(intent.extras)
    }

    @Test
    fun `share targets this app may not start are not used`() = runTest {
        device.install(pkg) {
            exported = false
            sharesText(activity = "InternalShareActivity")
            exported = true
            sharesText(activity = "PublicShareActivity")
        }

        assertEquals(LaunchResult.Started, launcher.handOffText(registered(SHARE_TEXT), "hello"))
        assertEquals(device.manifestOf(pkg).component("PublicShareActivity"), nextStarted()?.component)

        device.install("com.vendor.locked") {
            permission = "com.vendor.locked.permission.SHARE"
            sharesText()
        }
        val reason = launcher.handOffText(registered(SHARE_TEXT, packageName = "com.vendor.locked"), "hello").reason()
        assertTrue("no longer accepts shared text" in reason, reason)
        assertNothingStarted()
    }

    @Test
    fun `sharing is preferred over the prompt link when both exist`() = runTest {
        device.install(pkg) {
            sharesText()
            opensHttpsLinks("chat.vendor.example")
        }
        val app = registered(SHARE_TEXT, PROMPT_DEEP_LINK, promptDeepLink = link)

        assertEquals(LaunchResult.Started, launcher.handOffText(app, "hello"))
        assertEquals(Intent.ACTION_SEND, nextStarted()?.action)
    }

    @Test
    fun `a share target that disappeared falls back to the link, or says so`() = runTest {
        device.install(pkg) { opensHttpsLinks("chat.vendor.example") }

        val withLink = registered(SHARE_TEXT, PROMPT_DEEP_LINK, promptDeepLink = link)
        assertEquals(LaunchResult.Started, launcher.handOffText(withLink, "hello"))
        assertEquals(Intent.ACTION_VIEW, nextStarted()?.action)

        val reason = launcher.handOffText(registered(SHARE_TEXT), "hello").reason()
        assertTrue("no longer accepts shared text" in reason, reason)
        assertNothingStarted()
    }

    @Test
    fun `hand-off is refused when the registry entry does not permit it`() = runTest {
        device.install(pkg) {
            launcher()
            sharesText()
        }

        assertTrue("does not accept text" in launcher.handOffText(registered(LAUNCH, PROCESS_TEXT), "hi").reason())
        // Accepting images is not a way to receive text, even though the app has a share target.
        val imagesOnly = launcher.handOffText(registered(LAUNCH, SHARE_IMAGE), "hi").reason()
        assertTrue("images" in imagesOnly && "no way to receive text" in imagesOnly, imagesOnly)
        assertTrue("switched off" in launcher.handOffText(registered(SHARE_TEXT, enabled = false), "hi").reason())
        assertTrue("removed from the registry" in launcher.handOffText(registered(SHARE_TEXT, hidden = true), "hi").reason())
        assertTrue("not installed" in launcher.handOffText(registered(SHARE_TEXT, status = AiAppStatus.NOT_INSTALLED), "hi").reason())
        assertTrue(
            "disabled in Android settings" in
                launcher.handOffText(registered(SHARE_TEXT, status = AiAppStatus.DISABLED_BY_SYSTEM), "hi").reason(),
        )
        // A link without the capability (for example left over from an older scan) grants nothing.
        assertTrue("does not accept text" in launcher.handOffText(registered(LAUNCH, promptDeepLink = link), "hi").reason())
        assertNothingStarted()
    }

    @Test
    fun `hand-off explains what is wrong with the text or the target`() = runTest {
        device.install(pkg) {
            sharesText()
            sharesImages()
            opensHttpsLinks("chat.vendor.example")
        }
        val sharing = registered(SHARE_TEXT)

        assertTrue("no text" in launcher.handOffText(sharing, " \n\t").reason())

        val tooLong = "x".repeat(AndroidAiAppLauncher.MAX_SHARED_TEXT_CHARS + 1)
        val tooLongReason = launcher.handOffText(sharing, tooLong).reason()
        assertTrue("too long" in tooLongReason && "100001" in tooLongReason, tooLongReason)

        // The capability without a stored link (an inconsistent entry) must not start anything either.
        val noLink = launcher.handOffText(registered(PROMPT_DEEP_LINK), "hi").reason()
        assertTrue("No prompt link is stored" in noLink, noLink)

        val linked = registered(PROMPT_DEEP_LINK, promptDeepLink = link)
        val tooLongForLink = launcher.handOffText(linked, "é".repeat(AndroidAiAppLauncher.MAX_PROMPT_LINK_CHARS / 6 + 10)).reason()
        assertTrue("too long" in tooLongForLink && "link" in tooLongForLink, tooLongForLink)

        for (badTemplate in listOf("http://chat.vendor.example/?q={prompt}", "intent://x/#Intent;S.q={prompt};end", "https://chat.vendor.example/")) {
            val reason = launcher.handOffText(registered(PROMPT_DEEP_LINK, promptDeepLink = badTemplate), "hi").reason()
            assertTrue("not a valid https link" in reason, "$badTemplate -> $reason")
        }

        device.uninstall(pkg)
        assertTrue("no longer installed" in launcher.handOffText(sharing, "hi").reason())
        assertNothingStarted()
    }

    @Test
    fun `only the vendor-documented prompt link of the catalog is ever opened`() = runTest {
        device.install(pkg) { opensHttpsLinks("chat.vendor.example") }
        // Another package that would happily open the same link, but for which nothing is documented.
        device.install("com.other.app") { opensHttpsLinks("chat.vendor.example") }

        for (stored in listOf(observedLink, "https://chat.vendor.example/elsewhere?q={prompt}")) {
            val reason = launcher.handOffText(registered(PROMPT_DEEP_LINK, promptDeepLink = stored), "hi").reason()
            assertTrue("not one its vendor documents" in reason, "$stored -> $reason")
        }
        val notInCatalog = registered(PROMPT_DEEP_LINK, packageName = "com.other.app", promptDeepLink = link)
        assertTrue("not one its vendor documents" in launcher.handOffText(notInCatalog, "hi").reason())
        assertNothingStarted()

        assertEquals(LaunchResult.Started, launcher.handOffText(registered(PROMPT_DEEP_LINK, promptDeepLink = link), "hi"))
        assertEquals(Intent.ACTION_VIEW, nextStarted()?.action)

        // The link is documented for this package, but the installed version does not claim that host.
        device.install("com.vendor.legacy") { opensHttpsLinks("elsewhere.example") }
        val legacy = registered(PROMPT_DEEP_LINK, packageName = "com.vendor.legacy", promptDeepLink = link)
        val unclaimed = launcher.handOffText(legacy, "hi").reason()
        assertTrue("does not open its prompt link" in unclaimed, unclaimed)
        assertNothingStarted()
    }

    @Test
    fun `text is not handed to a different installation than the registered one`() = runTest {
        device.install(pkg, firstInstalledAt = 1_700_000_000_000, signedBy = listOf("vendor key")) {
            launcher()
            sharesText()
        }
        val certificate = "0f".repeat(32)
        val someoneElse = registered(LAUNCH, SHARE_TEXT, firstInstallTime = 1_700_000_000_000, signingCertSha256 = certificate)
        val resigned = launcher.handOffText(someoneElse, "secret plan").reason()
        assertTrue("different installation" in resigned && "Scan for apps again" in resigned, resigned)

        val reinstalled = registered(LAUNCH, SHARE_TEXT, firstInstallTime = 1_600_000_000_000)
        assertTrue("different installation" in launcher.handOffText(reinstalled, "secret plan").reason())
        assertNothingStarted()

        // Opening sends nothing, so it is not held back.
        assertEquals(LaunchResult.Started, launcher.launch(someoneElse))
        assertEquals(Intent.ACTION_MAIN, nextStarted()?.action)

        // Facts the registry or the device does not know are never counted as a difference.
        assertEquals(LaunchResult.Started, launcher.handOffText(registered(SHARE_TEXT), "hello"))
        assertEquals(Intent.ACTION_SEND, nextStarted()?.action)
        device.install("com.vendor.unsigned") { sharesText() }
        val knownOnlyToRegistry = registered(
            SHARE_TEXT, packageName = "com.vendor.unsigned", firstInstallTime = 5, signingCertSha256 = certificate,
        )
        assertEquals(LaunchResult.Started, launcher.handOffText(knownOnlyToRegistry, "hello"))
        assertEquals(Intent.ACTION_SEND, nextStarted()?.action)
    }

    @Test
    fun `the registered installation receives the text, whatever the letter case of its certificate`() = runTest {
        device.install(pkg, firstInstalledAt = 1_700_000_000_000, signedBy = listOf("vendor key")) { sharesText() }
        val scanned = assertNotNull(AndroidAiAppScanner(device.application, catalog, Dispatchers.Unconfined).inspect(pkg))
        val same = registered(
            SHARE_TEXT, firstInstallTime = scanned.firstInstallTime, signingCertSha256 = scanned.signingCertSha256?.uppercase(),
        )

        assertEquals(LaunchResult.Started, launcher.handOffText(same, "hello"))
        assertEquals("hello", nextStarted()?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `the longest permitted text is still handed over`() = runTest {
        device.install(pkg) { sharesText() }
        val text = "x".repeat(AndroidAiAppLauncher.MAX_SHARED_TEXT_CHARS)

        assertEquals(LaunchResult.Started, launcher.handOffText(registered(SHARE_TEXT), text))
        assertEquals(text.length, nextStarted()?.getStringExtra(Intent.EXTRA_TEXT)?.length)
    }

    @Test
    fun `nothing is started while this app is not on screen`() = runTest {
        device.install(pkg) {
            launcher()
            sharesText()
        }
        mayStart = false

        assertTrue("on screen" in launcher.launch(registered(LAUNCH)).reason())
        assertTrue("on screen" in launcher.handOffText(registered(SHARE_TEXT), "hi").reason())
        assertNothingStarted()

        mayStart = true
        assertEquals(LaunchResult.Started, launcher.launch(registered(LAUNCH)))
    }

    @Test
    fun `this app is never a hand-off target`() = runTest {
        device.manifestOf(device.ownPackage).apply {
            launcher()
            sharesText()
        }
        val self = registered(LAUNCH, SHARE_TEXT, packageName = device.ownPackage)

        assertTrue("this app itself" in launcher.launch(self).reason())
        assertTrue("this app itself" in launcher.handOffText(self, "hi").reason())
        assertNothingStarted()
    }

    @Test
    fun `failures reported by android become reasons instead of exceptions`() = runTest {
        device.install(pkg) {
            launcher()
            sharesText()
        }
        fun launcherFailingWith(error: RuntimeException) = AndroidAiAppLauncher(
            FailingStartContext(device.application, error), catalog, gate = { true }, dispatcher = Dispatchers.Unconfined,
        )

        val notFound = launcherFailingWith(ActivityNotFoundException("gone")).launch(registered(LAUNCH)).reason()
        assertTrue("found no screen" in notFound, notFound)

        val denied = launcherFailingWith(SecurityException("Permission Denial")).launch(registered(LAUNCH)).reason()
        assertTrue("did not allow" in denied, denied)

        val tooLarge = launcherFailingWith(RuntimeException("Failure from system", TransactionTooLargeException()))
            .handOffText(registered(SHARE_TEXT), "hi").reason()
        assertTrue("too large" in tooLarge, tooLarge)
        val wrappedTwice = RuntimeException("outer", RuntimeException("Failure from system", TransactionTooLargeException()))
        assertTrue("too large" in launcherFailingWith(wrappedTwice).handOffText(registered(SHARE_TEXT), "hi").reason())

        val other = launcherFailingWith(IllegalStateException("boom")).launch(registered(LAUNCH)).reason()
        assertTrue("could not open Chatbot" in other && "IllegalStateException" in other, other)
    }

    @Test
    fun `no reason ever repeats the text that was to be handed over`() = runTest {
        device.install(pkg, firstInstalledAt = 7, signedBy = listOf("vendor key")) {
            launcher()
            sharesText()
        }
        val secret = "zebra-crossing-4711"
        val failing = AndroidAiAppLauncher(
            FailingStartContext(device.application, IllegalArgumentException("Bad intent: $secret")),
            catalog, gate = { true }, dispatcher = Dispatchers.Unconfined,
        )
        val reasons = listOf(
            failing.handOffText(registered(SHARE_TEXT), secret),
            launcher.handOffText(registered(LAUNCH), secret),
            launcher.handOffText(registered(SHARE_TEXT, enabled = false), secret),
            launcher.handOffText(registered(SHARE_TEXT, firstInstallTime = 8), secret),
            launcher.handOffText(registered(PROMPT_DEEP_LINK, promptDeepLink = "https://chat.vendor.example/"), secret),
            launcher.handOffText(registered(PROMPT_DEEP_LINK, promptDeepLink = observedLink), secret),
            launcher.handOffText(registered(SHARE_TEXT), secret.repeat(AndroidAiAppLauncher.MAX_SHARED_TEXT_CHARS / 10)),
        ).map { it.reason() }

        assertEquals(7, reasons.size)
        reasons.forEach { assertFalse(secret in it, it) }
    }

    @Test
    fun `works on the default background dispatcher`() = runTest {
        device.install(pkg) {
            launcher()
            sharesText()
        }
        val launcher = AndroidAiAppLauncher(device.application)

        assertEquals(LaunchResult.Started, launcher.launch(registered(LAUNCH)))
        assertEquals(Intent.ACTION_MAIN, nextStarted()?.action)
        assertEquals(LaunchResult.Started, launcher.handOffText(registered(SHARE_TEXT), "hi"))
        assertEquals(Intent.ACTION_SEND, nextStarted()?.action)
    }

    /** An application context whose activity starts fail the way the platform can make them fail. */
    private class FailingStartContext(base: Context, private val error: RuntimeException) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent?) {
            throw error
        }

        override fun startActivity(intent: Intent?, options: Bundle?) {
            throw error
        }
    }
}
