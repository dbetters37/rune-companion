package com.aicontrolcenter.device

import com.aicontrolcenter.device.catalog.CompanionPackages
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.device.catalog.KnownAiApps
import com.aicontrolcenter.device.operator.apps.HandoverQuery
import com.aicontrolcenter.device.platform.QueriedIntent
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scanner can only see what the manifest `<queries>` block declares, so the block is checked
 * against the code that depends on it.
 */
class QueriesManifestTest {

    private data class IntentQuery(val action: String, val categories: Set<String>, val mimeType: String?, val scheme: String? = null)

    private val manifestFile: File = File(System.getProperty("aicc.deviceManifest") ?: "src/main/AndroidManifest.xml")

    private fun parse(file: File): Element {
        check(file.isFile) { "Not found: ${file.absolutePath}" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private val manifest: Element = parse(manifestFile)

    private val queries: Element = manifest.children("queries").single()

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.android(attribute: String): String? =
        getAttributeNS("http://schemas.android.com/apk/res/android", attribute).ifEmpty { null }

    private val intentQueries: List<IntentQuery> = queries.children("intent").map { intent ->
        val actions = intent.children("action").mapNotNull { it.android("name") }
        assertEquals(1, actions.size, "a <queries> intent must declare exactly one action")
        IntentQuery(
            action = actions.single(),
            categories = intent.children("category").mapNotNull { it.android("name") }.toSet(),
            mimeType = intent.children("data").singleOrNull()?.android("mimeType"),
            scheme = intent.children("data").singleOrNull()?.android("scheme"),
        )
    }

    /** The hand-over intents the phone operator resolves (AndroidAppActions). */
    private val handoverByCode: Set<IntentQuery> =
        HandoverQuery.entries.map { IntentQuery(it.action, setOfNotNull(it.category), null, it.scheme) }.toSet()

    /**
     * Protected packages are made visible so a hand-over they would take is recognised. The
     * framework package "android" is visible to every app and is not a valid <package> entry.
     */
    private val protectedPackages: Set<String> = ConsequentialControls.PROTECTED_PACKAGES - "android"

    @Test
    fun `package entries are exactly the catalog, the companion packages and the protected packages`() {
        val declared = queries.children("package").mapNotNull { it.android("name") }
        assertEquals(declared.size, declared.toSet().size, "duplicate <package> entries")
        assertEquals(
            (KnownAiApps.packageNames + CompanionPackages.notInCatalog + protectedPackages).distinct().sorted(),
            declared.sorted(),
        )
    }

    @Test
    fun `intent entries are exactly the intents the code queries device-wide`() {
        val queriedByCode = QueriedIntent.entries.map { IntentQuery(it.action, setOfNotNull(it.category), it.mimeType) }
        assertEquals(queriedByCode.size, queriedByCode.toSet().size, "duplicate queried intents")
        assertEquals(intentQueries.size, intentQueries.toSet().size, "duplicate <intent> entries")
        assertEquals(handoverByCode.size, HandoverQuery.entries.size, "duplicate hand-over queries")
        assertEquals(queriedByCode.toSet() + handoverByCode, intentQueries.toSet())
    }

    @Test
    fun `the queried intents are the documented android actions`() {
        // Spelled out once, so a typo in QueriedIntent cannot agree with the same typo in the manifest.
        assertEquals(
            setOf(
                // Phone operator hand-overs: web links, Custom Tabs, and prefilled email, text and dialer.
                IntentQuery("android.intent.action.VIEW", setOf("android.intent.category.BROWSABLE"), null, "https"),
                IntentQuery("android.intent.action.VIEW", setOf("android.intent.category.BROWSABLE"), null, "http"),
                IntentQuery("android.support.customtabs.action.CustomTabsService", emptySet(), null),
                IntentQuery("android.intent.action.SENDTO", emptySet(), null, "mailto"),
                IntentQuery("android.intent.action.SENDTO", emptySet(), null, "smsto"),
                IntentQuery("android.intent.action.DIAL", emptySet(), null, "tel"),
                IntentQuery("android.intent.action.MAIN", setOf("android.intent.category.LAUNCHER"), null),
                IntentQuery("android.intent.action.SEND", emptySet(), "text/plain"),
                IntentQuery("android.intent.action.SEND", emptySet(), "image/*"),
                IntentQuery("android.intent.action.PROCESS_TEXT", emptySet(), "text/plain"),
                IntentQuery("android.intent.action.ASSIST", emptySet(), null),
                IntentQuery("android.intent.action.VOICE_COMMAND", emptySet(), null),
                IntentQuery("android.service.voice.VoiceInteractionService", emptySet(), null),
            ),
            intentQueries.toSet(),
        )
    }

    @Test
    fun `requests exactly the permissions its features use, and none of the broad ones`() {
        val permissions = manifest.children("uses-permission").associate { it.android("name") to it.android("maxSdkVersion") }
        assertEquals(
            mapOf<String?, String?>(
                "android.permission.QUERY_ADVANCED_PROTECTION_MODE" to null,
                // Phone operator: photo library, install and uninstall, Termux, hand-over notifications.
                "android.permission.READ_MEDIA_IMAGES" to null,
                "android.permission.READ_MEDIA_VIDEO" to null,
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" to null,
                "android.permission.READ_EXTERNAL_STORAGE" to "32",
                "android.permission.REQUEST_INSTALL_PACKAGES" to null,
                "android.permission.REQUEST_DELETE_PACKAGES" to null,
                "com.termux.permission.RUN_COMMAND" to null,
                "android.permission.POST_NOTIFICATIONS" to null,
            ),
            permissions,
        )
        for (broad in listOf(
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.SEND_SMS",
            "android.permission.CALL_PHONE",
            "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
            "android.permission.INSTALL_PACKAGES",
            "android.permission.DELETE_PACKAGES",
        )) {
            assertFalse(broad in permissions, "$broad must not be requested")
        }
    }

    @Test
    fun `declares only the workspace file provider, private and granting per hand-off`() {
        val application = manifest.children("application").single()
        val components = (0 until application.childNodes.length).map { application.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(
            listOf("provider:com.aicontrolcenter.device.operator.apps.WorkspaceFileProvider"),
            components.map { "${it.tagName}:${it.android("name")}" },
        )
        val provider = components.single { it.tagName == "provider" }
        assertEquals("\${applicationId}.workspace", provider.android("authorities"))
        assertEquals("false", provider.android("exported"))
        assertEquals("true", provider.android("grantUriPermissions"))
        val paths = provider.children("meta-data").single()
        assertEquals("android.support.FILE_PROVIDER_PATHS" to "@xml/workspace_share_paths", paths.android("name") to paths.android("resource"))
    }

    @Test
    fun `the workspace provider serves the workspace folder and nothing else`() {
        val paths = parse(File(manifestFile.parentFile, "res/xml/workspace_share_paths.xml"))
        val entries = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filterIsInstance<Element>()
        assertEquals(listOf("files-path" to "workspace/"), entries.map { it.tagName to it.getAttribute("path") })
    }
}
