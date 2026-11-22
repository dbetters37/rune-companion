package com.aicontrolcenter.device

import com.aicontrolcenter.device.catalog.CompanionPackages
import com.aicontrolcenter.device.catalog.KnownAiApps
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

    private data class IntentQuery(val action: String, val categories: Set<String>, val mimeType: String?)

    private val manifest: Element = run {
        val path = System.getProperty("aicc.deviceManifest") ?: "src/main/AndroidManifest.xml"
        val file = File(path)
        check(file.isFile) { "Manifest not found at ${file.absolutePath}" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(file).documentElement
    }

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
        )
    }

    @Test
    fun `package entries are exactly the catalog plus the companion packages`() {
        val declared = queries.children("package").mapNotNull { it.android("name") }
        assertEquals(declared.size, declared.toSet().size, "duplicate <package> entries")
        assertEquals(
            (KnownAiApps.packageNames + CompanionPackages.notInCatalog).sorted(),
            declared.sorted(),
        )
    }

    @Test
    fun `intent entries are exactly the intents the code queries device-wide`() {
        val queriedByCode = QueriedIntent.entries.map { IntentQuery(it.action, setOfNotNull(it.category), it.mimeType) }
        assertEquals(queriedByCode.size, queriedByCode.toSet().size, "duplicate queried intents")
        assertEquals(intentQueries.size, intentQueries.toSet().size, "duplicate <intent> entries")
        assertEquals(queriedByCode.toSet(), intentQueries.toSet())
    }

    @Test
    fun `the queried intents are the documented android actions`() {
        // Spelled out once, so a typo in QueriedIntent cannot agree with the same typo in the manifest.
        assertEquals(
            setOf(
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
    fun `requests only the advanced protection query permission, and never QUERY_ALL_PACKAGES`() {
        val permissions = manifest.children("uses-permission").mapNotNull { it.android("name") }
        assertEquals(listOf("android.permission.QUERY_ADVANCED_PROTECTION_MODE"), permissions)
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in permissions)
        assertTrue(manifest.children("application").isEmpty(), "the device library declares no components")
    }
}
