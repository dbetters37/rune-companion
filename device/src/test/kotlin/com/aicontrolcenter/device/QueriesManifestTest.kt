package com.aicontrolcenter.device

import com.aicontrolcenter.device.catalog.CompanionPackages
import com.aicontrolcenter.device.catalog.KnownAiApps
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
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
    fun `declares every intent signature the scanner queries`() {
        val expected = setOf(
            IntentQuery("android.intent.action.MAIN", setOf("android.intent.category.LAUNCHER"), null),
            IntentQuery("android.intent.action.SEND", emptySet(), "text/plain"),
            IntentQuery("android.intent.action.SEND", emptySet(), "image/*"),
            IntentQuery("android.intent.action.PROCESS_TEXT", emptySet(), "text/plain"),
            IntentQuery("android.intent.action.ASSIST", emptySet(), null),
            IntentQuery("android.intent.action.VOICE_COMMAND", emptySet(), null),
            IntentQuery("android.service.voice.VoiceInteractionService", emptySet(), null),
        )
        assertEquals(expected, intentQueries.toSet())
    }

    @Test
    fun `requests no permission at all, in particular not QUERY_ALL_PACKAGES`() {
        val permissions = manifest.children("uses-permission").mapNotNull { it.android("name") }
        assertEquals(emptyList(), permissions)
        assertTrue(manifest.children("application").isEmpty(), "the device library declares no components")
    }
}
