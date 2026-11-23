package com.aicontrolcenter.device.screen

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the service declares is what the owner is asked to trust, so it is pinned here: only the
 * system can bind it, it is not an accessibility tool, and it asks for window content and view
 * ids but never key events, gestures or notifications.
 */
class AccessibilityConfigTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val manifestFile = File(System.getProperty("aicc.deviceManifest") ?: "src/main/AndroidManifest.xml")

    private fun parse(file: File): Element {
        check(file.isFile) { "Not found: ${file.absolutePath}" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.attr(name: String): String? = getAttributeNS(android, name).ifEmpty { null }

    private val application: Element get() = parse(manifestFile).children("application").single()

    @Test
    fun `only the system can bind the service, and it points at its configuration`() {
        val service = application.children("service").single()
        assertEquals(OperatorAccessibilityService::class.java.name, service.attr("name"))
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.attr("permission"))
        assertEquals("true", service.attr("exported"))
        val actions = service.children("intent-filter").single().children("action").map { it.attr("name") }
        assertEquals(listOf("android.accessibilityservice.AccessibilityService"), actions)
        val meta = service.children("meta-data").single()
        assertEquals("android.accessibilityservice", meta.attr("name"))
        assertEquals("@xml/operator_accessibility_service", meta.attr("resource"))
    }

    @Test
    fun `the Stop receiver is not exported`() {
        val receiver = application.children("receiver").single()
        assertEquals(ScreenStopReceiver::class.java.name, receiver.attr("name"))
        assertEquals("false", receiver.attr("exported"))
        assertTrue(receiver.children("intent-filter").isEmpty(), "no filter: only an explicit intent from this app reaches it")
    }

    @Test
    fun `the configuration is not an accessibility tool and asks for no key events or gestures`() {
        val config = parse(File(manifestFile.parentFile, "res/xml/operator_accessibility_service.xml"))
        assertEquals("accessibility-service", config.tagName)

        assertEquals("false", config.attr("isAccessibilityTool"), "false declarations are forbidden")
        assertEquals("true", config.attr("canRetrieveWindowContent"))
        assertEquals("true", config.attr("canTakeScreenshot"))
        assertNull(config.attr("canRequestFilterKeyEvents"))
        assertNull(config.attr("canPerformGestures"), "actions are node actions, never gestures at coordinates")
        assertNull(config.attr("packageNames"), "every app's events: the operator decides per call which app it may read")

        val flags = config.attr("accessibilityFlags").orEmpty().split('|').toSet()
        assertTrue("flagReportViewIds" in flags)
        assertTrue("flagRetrieveInteractiveWindows" in flags)
        assertFalse("flagRequestFilterKeyEvents" in flags)
        assertFalse(flags.any { "KeyEvents" in it || "Fingerprint" in it || "TouchExploration" in it || "AccessibilityButton" in it })

        val events = config.attr("accessibilityEventTypes").orEmpty().split('|').toSet()
        assertFalse("typeNotificationStateChanged" in events, "notifications are not read")
        assertFalse("typeAllMask" in events)
    }
}
