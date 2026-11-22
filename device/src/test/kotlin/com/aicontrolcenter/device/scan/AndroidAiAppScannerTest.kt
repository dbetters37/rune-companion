package com.aicontrolcenter.device.scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.registry.AiAppCapability.ASSISTANT
import com.aicontrolcenter.core.registry.AiAppCapability.LAUNCH
import com.aicontrolcenter.core.registry.AiAppCapability.LOCAL_MODEL_HOST
import com.aicontrolcenter.core.registry.AiAppCapability.PROCESS_TEXT
import com.aicontrolcenter.core.registry.AiAppCapability.PROMPT_DEEP_LINK
import com.aicontrolcenter.core.registry.AiAppCapability.SHARE_IMAGE
import com.aicontrolcenter.core.registry.AiAppCapability.SHARE_TEXT
import com.aicontrolcenter.core.registry.DiscoveredApp
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.device.catalog.IdEvidence
import com.aicontrolcenter.device.catalog.KnownAiApp
import com.aicontrolcenter.device.catalog.KnownAiAppKind
import com.aicontrolcenter.device.catalog.PromptDeepLink
import com.aicontrolcenter.device.testing.FakeDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AndroidAiAppScannerTest {

    private val device = FakeDevice()
    private val scanner = AndroidAiAppScanner(device.application, dispatcher = Dispatchers.Unconfined)

    private suspend fun scan(): Map<String, DiscoveredApp> = scanner.scanForAiApps().associateBy { it.packageName }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test
    fun `finds installed catalog apps with exactly the capabilities their intent filters give`() = runTest {
        device.install("com.openai.chatgpt", label = "ChatGPT", version = "1.2026.301") {
            launcher()
            sharesText()
            sharesImages()
            processesText()
            assist()
            // The catalog's ChatGPT link is community-observed only, so resolving it must not count.
            opensHttpsLinks("chatgpt.com")
        }
        device.install("com.anthropic.claude", label = "Claude", version = "3.4") {
            launcher()
            opensHttpsLinks("claude.ai")
        }
        device.install("com.pocketpalai", label = "PocketPal") {
            launcher()
            sharesText()
        }
        device.install("com.termux", label = "Termux", version = null) { launcher() }

        val apps = scanner.scanForAiApps()

        assertEquals(
            listOf("com.openai.chatgpt", "com.anthropic.claude", "com.pocketpalai", "com.termux"),
            apps.map { it.packageName },
            "only installed catalog apps, in catalog order (Gemini and the others are not installed)",
        )
        assertEquals(
            DiscoveredApp(
                packageName = "com.openai.chatgpt", label = "ChatGPT", versionName = "1.2026.301", vendor = "OpenAI",
                capabilities = setOf(LAUNCH, SHARE_TEXT, SHARE_IMAGE, PROCESS_TEXT, ASSISTANT),
                source = DiscoverySource.CATALOG, enabledInSystem = true, promptDeepLink = null,
            ),
            apps[0],
        )
        assertEquals(
            DiscoveredApp(
                packageName = "com.anthropic.claude", label = "Claude", versionName = "3.4", vendor = "Anthropic",
                capabilities = setOf(LAUNCH, PROMPT_DEEP_LINK),
                source = DiscoverySource.CATALOG, enabledInSystem = true,
                promptDeepLink = "https://claude.ai/code/new?q={prompt}",
            ),
            apps[1],
        )
        assertEquals(setOf(LAUNCH, SHARE_TEXT, LOCAL_MODEL_HOST), apps[2].capabilities)
        assertEquals(setOf(LAUNCH, LOCAL_MODEL_HOST), apps[3].capabilities)
        assertNull(apps[3].versionName)
        assertEquals("Termux", apps[3].vendor)
    }

    @Test
    fun `a documented prompt link counts only when the installed app opens it`() = runTest {
        device.install("com.anthropic.claude", label = "Claude") {
            launcher()
            opensHttpsLinks("example.org")
        }
        val claude = scan().getValue("com.anthropic.claude")
        assertEquals(setOf(LAUNCH), claude.capabilities)
        assertNull(claude.promptDeepLink)
    }

    @Test
    fun `share capabilities follow the mime types and the default category`() = runTest {
        device.install("com.openai.chatgpt", label = "Text only") { sharesText() }
        device.install("com.microsoft.copilot", label = "Images only") { sharesImages(mimeType = "image/png") }
        device.install("com.poe.android", label = "Anything") { sharesImages(mimeType = "*/*") }
        // Without CATEGORY_DEFAULT Android would not deliver a share to this activity.
        device.install("ai.x.grok", label = "Not startable") { sharesText(default = false) }

        val apps = scan()
        assertEquals(setOf(SHARE_TEXT), apps.getValue("com.openai.chatgpt").capabilities)
        assertEquals(setOf(SHARE_IMAGE), apps.getValue("com.microsoft.copilot").capabilities)
        assertEquals(setOf(SHARE_TEXT, SHARE_IMAGE), apps.getValue("com.poe.android").capabilities)
        assertEquals(emptySet(), apps.getValue("ai.x.grok").capabilities)
    }

    @Test
    fun `activities this app is not allowed to start are not capabilities`() = runTest {
        device.install("com.openai.chatgpt", label = "Internal share") {
            exported = false
            sharesText()
            assist()
        }
        device.install("com.microsoft.copilot", label = "Guarded share") {
            permission = "com.microsoft.copilot.permission.SHARE"
            sharesText()
            processesText()
        }
        device.install("com.poe.android", label = "Guarded, but we hold the permission") {
            permission = "com.poe.android.permission.PARTNER"
            sharesText()
        }
        device.grantToSelf("com.poe.android.permission.PARTNER")

        val apps = scan()
        assertEquals(emptySet(), apps.getValue("com.openai.chatgpt").capabilities)
        assertEquals(emptySet(), apps.getValue("com.microsoft.copilot").capabilities)
        assertEquals(setOf(SHARE_TEXT), apps.getValue("com.poe.android").capabilities)
        assertEquals(emptySet(), scanner.inspect("com.microsoft.copilot")?.capabilities)
    }

    @Test
    fun `labels and versions supplied by other apps are reduced to one bounded line`() = runTest {
        device.install(
            "com.openai.chatgpt",
            label = "  Chat\nGPT\t\u202Eevil\u0000  " + "x".repeat(200),
            version = "1.0\n" + "9".repeat(200),
        ) { launcher() }
        device.install("com.termux", label = " \n ", version = "  ") { launcher() }

        val apps = scan()
        val chatGpt = apps.getValue("com.openai.chatgpt")
        assertTrue(chatGpt.label.startsWith("Chat GPT evil x"), chatGpt.label)
        assertEquals(80, chatGpt.label.length)
        assertTrue(chatGpt.versionName!!.startsWith("1.0 9"))
        assertEquals(64, chatGpt.versionName!!.length)
        assertEquals("com.termux", apps.getValue("com.termux").label, "a blank label falls back to the package name")
        assertNull(apps.getValue("com.termux").versionName)
        assertEquals(chatGpt.label, scanner.listInstalledApps().first { it.packageName == "com.openai.chatgpt" }.label)
    }

    @Test
    fun `an app disabled in android settings is reported as not enabled and offers nothing`() = runTest {
        device.install("com.openai.chatgpt", label = "ChatGPT") {
            launcher()
            sharesText()
        }
        device.install("com.termux", label = "Termux") { launcher() }
        device.disableInSettings("com.openai.chatgpt")

        val apps = scan()
        val chatGpt = apps.getValue("com.openai.chatgpt")
        assertFalse(chatGpt.enabledInSystem)
        assertEquals(emptySet(), chatGpt.capabilities)
        assertEquals("ChatGPT", chatGpt.label)
        assertTrue(apps.getValue("com.termux").enabledInSystem)

        device.enableInSettings("com.openai.chatgpt")
        val reEnabled = scan().getValue("com.openai.chatgpt")
        assertTrue(reEnabled.enabledInSystem)
        assertEquals(setOf(LAUNCH, SHARE_TEXT), reEnabled.capabilities)
    }

    @Test
    fun `assistants outside the catalog are found by intent query`() = runTest {
        device.install("com.vendor.buddy", label = "Buddy", version = "7") {
            launcher()
            assist()
        }
        device.install("com.vendor.talk", label = "Talk") { voiceCommand() }
        device.install("com.vendor.helper", label = "Helper") { voiceInteractionService() }
        device.install("com.vendor.notes", label = "Notes") { launcher() }

        val apps = scan()
        assertEquals(setOf("com.vendor.buddy", "com.vendor.talk", "com.vendor.helper"), apps.keys)
        assertTrue(apps.values.all { it.source == DiscoverySource.INTENT_QUERY })
        assertEquals(setOf(LAUNCH, ASSISTANT), apps.getValue("com.vendor.buddy").capabilities)
        assertEquals(setOf(ASSISTANT), apps.getValue("com.vendor.talk").capabilities)
        assertEquals(setOf(ASSISTANT), apps.getValue("com.vendor.helper").capabilities)
        assertNull(apps.getValue("com.vendor.buddy").vendor, "a vendor is only known for catalog apps")
        assertEquals("7", apps.getValue("com.vendor.buddy").versionName)
    }

    @Test
    fun `the name heuristic proposes launchable AI-named apps and leaves messaging apps alone`() = runTest {
        device.install("com.example.pocket", label = "Pocket AI") { launcher() }
        device.install("com.example.gptnotes", label = "Smart Notes") {
            launcher()
            sharesText()
        }
        // Tools that merely advertise an AI feature.
        device.install("com.example.typing", label = "AI Keyboard") { launcher() }
        device.install("com.opera.browser", label = "Opera browser with AI") {
            launcher()
            sharesText()
        }
        device.install("com.whatsapp", label = "WhatsApp") {
            launcher()
            sharesText()
            sharesImages()
        }
        device.install("org.telegram.messenger", label = "Telegram") { launcher() }
        device.install("com.google.android.apps.messaging", label = "Messages") { launcher() }
        device.install("com.google.android.apps.dynamite", label = "Chat") { launcher() }
        // Named like an AI app but without a launcher icon: the heuristic only looks at launchable apps.
        device.install("com.example.aiservice", label = "AI Service") { sharesText() }

        val apps = scan()
        assertEquals(setOf("com.example.pocket", "com.example.gptnotes"), apps.keys)
        assertTrue(apps.values.all { it.source == DiscoverySource.HEURISTIC })
        assertEquals(setOf(LAUNCH), apps.getValue("com.example.pocket").capabilities)
        assertEquals(setOf(LAUNCH, SHARE_TEXT), apps.getValue("com.example.gptnotes").capabilities)
        assertEquals(DiscoverySource.MANUAL, scanner.inspect("com.example.typing")?.source, "inspect agrees with the scan")
    }

    @Test
    fun `reports when the app was first installed and which certificate signs it`() = runTest {
        device.install(
            "com.openai.chatgpt", label = "ChatGPT", firstInstalledAt = 1_700_000_000_000, signedBy = listOf("vendor key"),
        ) { launcher() }
        device.install("com.termux", label = "Termux") { launcher() }

        val chatGpt = scan().getValue("com.openai.chatgpt")
        assertEquals(1_700_000_000_000, chatGpt.firstInstallTime)
        assertEquals(sha256Hex("vendor key"), chatGpt.signingCertSha256)
        assertEquals(64, chatGpt.signingCertSha256?.length)
        assertEquals(chatGpt.signingCertSha256, chatGpt.signingCertSha256?.lowercase())

        val inspected = assertNotNull(scanner.inspect("com.openai.chatgpt"))
        assertEquals(chatGpt.firstInstallTime to chatGpt.signingCertSha256, inspected.firstInstallTime to inspected.signingCertSha256)

        // What Android does not report stays unknown instead of becoming a made-up value.
        val termux = scan().getValue("com.termux")
        assertNull(termux.firstInstallTime)
        assertNull(termux.signingCertSha256)
    }

    @Test
    fun `the identity changes when the certificate or the installation changes`() = runTest {
        device.install(
            "com.openai.chatgpt", label = "ChatGPT", firstInstalledAt = 1_700_000_000_000, signedBy = listOf("vendor key"),
        ) { launcher() }
        val original = scan().getValue("com.openai.chatgpt")

        device.setVersion("com.openai.chatgpt", "2.0")
        val updated = scan().getValue("com.openai.chatgpt")
        assertEquals(original.signingCertSha256, updated.signingCertSha256, "an ordinary update keeps the identity")
        assertEquals(original.firstInstallTime, updated.firstInstallTime)

        device.setSigners("com.openai.chatgpt", listOf("somebody else's key"))
        val resigned = scan().getValue("com.openai.chatgpt")
        assertEquals(sha256Hex("somebody else's key"), resigned.signingCertSha256)
        assertNotEquals(original.signingCertSha256, resigned.signingCertSha256)
        assertEquals(resigned.signingCertSha256, scanner.inspect("com.openai.chatgpt")?.signingCertSha256)

        device.setFirstInstallTime("com.openai.chatgpt", 1_800_000_000_000)
        assertEquals(1_800_000_000_000, scan().getValue("com.openai.chatgpt").firstInstallTime)
    }

    @Test
    fun `several signers give one value that does not depend on their order`() = runTest {
        device.install("com.openai.chatgpt", label = "ChatGPT", signedBy = listOf("key one", "key two")) { launcher() }
        val oneWay = scan().getValue("com.openai.chatgpt").signingCertSha256

        device.setSigners("com.openai.chatgpt", listOf("key two", "key one"))
        val otherWay = scan().getValue("com.openai.chatgpt").signingCertSha256

        assertEquals(oneWay, otherWay)
        assertEquals(sha256Hex(listOf(sha256Hex("key one"), sha256Hex("key two")).sorted().joinToString("")), oneWay)
        assertNotEquals(sha256Hex("key one"), oneWay)

        device.setSigners("com.openai.chatgpt", listOf("key one", "key three"))
        assertNotEquals(oneWay, scan().getValue("com.openai.chatgpt").signingCertSha256)
    }

    @Test
    fun `an app found several ways is reported once with the strongest source`() = runTest {
        device.install("com.openai.chatgpt", label = "ChatGPT AI") {
            launcher()
            assist()
        }
        device.install("com.vendor.buddy", label = "Buddy AI") {
            launcher()
            assist()
        }
        val apps = scanner.scanForAiApps()
        assertEquals(listOf("com.openai.chatgpt", "com.vendor.buddy"), apps.map { it.packageName })
        assertEquals(listOf(DiscoverySource.CATALOG, DiscoverySource.INTENT_QUERY), apps.map { it.source })
    }

    @Test
    fun `never returns this app itself`() = runTest {
        // Make this app look as discoverable as possible: in the catalog, an assistant, AI-named.
        device.setLabel(device.ownPackage, "AI Control Center")
        device.manifestOf(device.ownPackage).apply {
            launcher()
            sharesText()
            assist()
        }
        device.install("com.vendor.buddy", label = "Buddy") { assist() }
        val self = KnownAiApp(device.ownPackage, "Self", "Us", KnownAiAppKind.CLOUD_ASSISTANT, IdEvidence.VENDOR_SOURCE)
        val scanner = AndroidAiAppScanner(device.application, catalog = listOf(self), dispatcher = Dispatchers.Unconfined)

        assertEquals(listOf("com.vendor.buddy"), scanner.scanForAiApps().map { it.packageName })
        assertEquals(emptyList(), scanner.listInstalledApps())
        assertNull(scanner.inspect(device.ownPackage))
    }

    @Test
    fun `inspect returns null for packages that are not installed`() = runTest {
        assertNull(scanner.inspect("com.openai.chatgpt"))
        assertNull(scanner.inspect("not.installed.anywhere"))
        assertNull(scanner.inspect(""))

        device.install("com.openai.chatgpt", label = "ChatGPT") { launcher() }
        assertNotNull(scanner.inspect("com.openai.chatgpt"))
        device.uninstall("com.openai.chatgpt")
        assertNull(scanner.inspect("com.openai.chatgpt"))
    }

    @Test
    fun `inspect reports fresh facts and how the app would have been found`() = runTest {
        device.install("com.openai.chatgpt", label = "ChatGPT", version = "1.0") {
            launcher()
            sharesText()
        }
        device.install("com.vendor.buddy", label = "Buddy") { assist() }
        device.install("com.example.pocket", label = "Pocket AI") { launcher() }
        device.install("com.example.notes", label = "Notes", version = "2.1") {
            launcher()
            processesText()
        }
        // Another app's filters must not leak into the inspected one.
        device.install("com.example.other", label = "Other") { sharesImages() }

        assertEquals(
            DiscoveredApp(
                packageName = "com.openai.chatgpt", label = "ChatGPT", versionName = "1.0", vendor = "OpenAI",
                capabilities = setOf(LAUNCH, SHARE_TEXT), source = DiscoverySource.CATALOG,
            ),
            scanner.inspect("com.openai.chatgpt"),
        )
        assertEquals(DiscoverySource.INTENT_QUERY, scanner.inspect("com.vendor.buddy")?.source)
        assertEquals(DiscoverySource.HEURISTIC, scanner.inspect("com.example.pocket")?.source)
        assertEquals(
            DiscoveredApp(
                packageName = "com.example.notes", label = "Notes", versionName = "2.1",
                capabilities = setOf(LAUNCH, PROCESS_TEXT), source = DiscoverySource.MANUAL,
            ),
            scanner.inspect("com.example.notes"),
        )

        device.setVersion("com.openai.chatgpt", "2.0")
        device.disableInSettings("com.openai.chatgpt")
        val changed = assertNotNull(scanner.inspect("com.openai.chatgpt"))
        assertEquals("2.0", changed.versionName)
        assertFalse(changed.enabledInSystem)
        assertEquals(emptySet(), changed.capabilities)
    }

    @Test
    fun `a custom catalog link is offered when verified and resolvable, and withheld when unverified`() = runTest {
        fun catalogEntry(packageName: String, verified: Boolean) = KnownAiApp(
            packageName = packageName, name = "Example", vendor = "Example Inc.",
            kind = KnownAiAppKind.LOCAL_RUNNER, idEvidence = IdEvidence.VENDOR_SOURCE,
            promptLinks = listOf(PromptDeepLink("https://chat.example.com/?q={prompt}", verified, "A new chat.")),
        )
        device.install("com.example.verified", label = "Verified") { opensHttpsLinks("chat.example.com") }
        device.install("com.example.unverified", label = "Unverified") { opensHttpsLinks("chat.example.com") }
        val scanner = AndroidAiAppScanner(
            device.application,
            catalog = listOf(catalogEntry("com.example.verified", true), catalogEntry("com.example.unverified", false)),
            dispatcher = Dispatchers.Unconfined,
        )

        val apps = scanner.scanForAiApps().associateBy { it.packageName }
        val verified = apps.getValue("com.example.verified")
        assertEquals(setOf(PROMPT_DEEP_LINK, LOCAL_MODEL_HOST), verified.capabilities)
        assertEquals("https://chat.example.com/?q={prompt}", verified.promptDeepLink)
        assertEquals("Example Inc.", verified.vendor)
        val unverified = apps.getValue("com.example.unverified")
        assertEquals(setOf(LOCAL_MODEL_HOST), unverified.capabilities)
        assertNull(unverified.promptDeepLink)
    }

    @Test
    fun `lists every launchable app except this one, once, sorted by label`() = runTest {
        device.manifestOf(device.ownPackage).launcher()
        device.install("com.example.zebra", label = "zebra", version = "3.0") {
            launcher()
            launcher(activity = "SecondLauncherActivity")
        }
        device.install("com.example.alpha", label = "Alpha", version = "1.5", system = true) { launcher() }
        device.install("com.example.middle", label = "Middle", version = null) { launcher() }
        device.install("com.example.background", label = "Background") { sharesText() }
        device.install("com.example.off", label = "Off") { launcher() }
        device.disableInSettings("com.example.off")

        assertEquals(
            listOf(
                InstalledAppInfo("com.example.alpha", "Alpha", "1.5", isSystemApp = true),
                InstalledAppInfo("com.example.middle", "Middle", null, isSystemApp = false),
                InstalledAppInfo("com.example.zebra", "zebra", "3.0", isSystemApp = false),
            ),
            scanner.listInstalledApps(),
        )
    }

    @Test
    fun `works on the default background dispatcher`() = runTest {
        device.install("com.termux", label = "Termux") { launcher() }
        val scanner = AndroidAiAppScanner(device.application)
        assertEquals(listOf("com.termux"), scanner.scanForAiApps().map { it.packageName })
        assertEquals(listOf("com.termux"), scanner.listInstalledApps().map { it.packageName })
        assertEquals(setOf(LAUNCH, LOCAL_MODEL_HOST), scanner.inspect("com.termux")?.capabilities)
    }
}
