package com.aicontrolcenter.app

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rules 22 and 23 on the manifests Android really gets (Phase 6, wave 3, WP8): the merged manifest of each build type,
 * with every library's entries, as the build hands them to the tests (app/build.gradle.kts).
 *
 * Rule 22: exactly 18 permissions (0.5.2's 13, the three normal ones of background work, and the two of routines, owner
 * decision Q1); the exported components are the main activity, the accessibility service, AndroidX's profile installer
 * and the share receiver (WP16, Tier B); every other component says it is not exported. Rule 23: the main activity keeps
 * its one launcher filter (no new entry point; no intent opens Backup, Restore, Recovery or Start over), and the share
 * receiver takes only SEND and SEND_MULTIPLE of text. The debug build adds exactly three things nobody ships: Compose's
 * exported test and preview activities (ui-test-manifest, ui-tooling) and the script probe of the device tests (WP6).
 */
class MergedManifestTest {
    private fun manifest(buildType: String): Element {
        val path = System.getProperty("aicc.mergedManifest.$buildType")
        val file = File(checkNotNull(path) { "the build passes no $buildType merged manifest (app/build.gradle.kts)" })
        assertTrue(file.isFile, "the $buildType merged manifest exists: $file")
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.android(name: String): String? = getAttributeNS(ANDROID, name).takeIf { hasAttributeNS(ANDROID, name) }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.components(): List<Element> =
        children("application").single().let { app -> COMPONENT_TAGS.flatMap { app.children(it) } }

    /** A component's full class name (the merged manifest writes them in full). */
    private fun Element.className(): String = android("name").orEmpty()

    /** Whether Android treats it as exported: said so, or (only before API 31 targets) implied by an intent filter. */
    private fun Element.exported(): Boolean = android("exported")?.toBoolean() ?: children("intent-filter").isNotEmpty()

    @Test
    fun `release - exactly the planned permissions`() {
        assertEquals(RELEASE_PERMISSIONS, manifest("release").children("uses-permission").mapNotNull { it.android("name") }.toSortedSet())
        assertEquals(18, RELEASE_PERMISSIONS.size, "rule 22: 13 of 0.5.2, three for background work, two for routines")
    }

    @Test
    fun `release - only the main activity, the accessibility service, the profile installer and the share receiver are exported`() {
        val release = manifest("release").components()
        assertEquals(RELEASE_EXPORTED, release.filter { it.exported() }.map { it.className() }.toSortedSet())
        // Every one of the app's own components says what it is, so none is exported by accident.
        val own = release.filter { it.className().startsWith(APP) }
        assertTrue(own.all { it.android("exported") != null }, own.filter { it.android("exported") == null }.map { it.className() }.toString())
    }

    @Test
    fun `release - the main activity keeps its one launcher filter and nothing else reaches it`() {
        val main = manifest("release").components().single { it.className() == "$APP.MainActivity" }
        val filters = main.children("intent-filter")
        assertEquals(1, filters.size, "rule 23: no new intent filter on the main activity")
        assertEquals(listOf("android.intent.action.MAIN"), filters.single().children("action").map { it.android("name") })
        assertEquals(listOf("android.intent.category.LAUNCHER"), filters.single().children("category").map { it.android("name") })
        assertEquals(emptyList(), filters.single().children("data"))
        // No other activity of the app opens Backup, Restore, Recovery or Start over from outside: the only other exported
        // activity of the app is the share receiver.
        val exportedActivities = manifest("release").children("application").single().children("activity")
            .filter { it.exported() && it.className().startsWith(APP) }.map { it.className() }
        assertEquals(listOf("$APP.MainActivity", "$APP.share.ShareReceiverActivity"), exportedActivities.sorted())
    }

    @Test
    fun `release - the share receiver takes only SEND and SEND_MULTIPLE of text`() {
        val share = manifest("release").components().single { it.className() == "$APP.share.ShareReceiverActivity" }
        val filters = share.children("intent-filter")
        assertEquals(
            listOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"),
            filters.flatMap { f -> f.children("action").map { it.android("name") } }.sortedBy { it },
        )
        for (filter in filters) {
            assertEquals(listOf("android.intent.category.DEFAULT"), filter.children("category").map { it.android("name") })
            assertEquals(listOf("text/*"), filter.children("data").map { it.android("mimeType") }, "text only (rule 34)")
            assertTrue(filter.children("data").all { it.android("scheme") == null && it.android("host") == null })
        }
    }

    @Test
    fun `release - the background components are as planned`() {
        val release = manifest("release").components().associateBy { it.className() }
        val scripts = release.getValue("$APP.scripts.ScriptProcessService")
        assertEquals("true", scripts.android("isolatedProcess"))
        assertEquals(":scripts", scripts.android("process"))
        assertEquals("android.permission.BIND_JOB_SERVICE", release.getValue("$APP.work.UpkeepJobService").android("permission"))
        assertEquals("false", release.getValue("$APP.routines.RoutineAlarmReceiver").android("enabled"))
        assertEquals("false", release.getValue("$APP.routines.BootReceiver").android("enabled"))
        assertTrue("$APP.scripts.ScriptProbeService" !in release, "the debug probe is never in a release build")
    }

    @Test
    fun `debug - the same, plus only the test and preview activities and the script probe`() {
        val debug = manifest("debug")
        assertEquals(RELEASE_PERMISSIONS, debug.children("uses-permission").mapNotNull { it.android("name") }.toSortedSet(), "no debug-only permission")
        val releaseNames = manifest("release").components().map { it.className() }.toSet()
        val added = debug.components().map { it.className() }.filter { it !in releaseNames }.toSet()
        assertEquals(setOf("androidx.activity.ComponentActivity", "androidx.compose.ui.tooling.PreviewActivity", "$APP.scripts.ScriptProbeService"), added)
        val probe = debug.components().single { it.className() == "$APP.scripts.ScriptProbeService" }
        assertEquals("false", probe.android("exported"))
        assertEquals("true", probe.android("isolatedProcess"))
        assertEquals(
            (RELEASE_EXPORTED + "androidx.activity.ComponentActivity" + "androidx.compose.ui.tooling.PreviewActivity").toSortedSet(),
            debug.components().filter { it.exported() }.map { it.className() }.toSortedSet(),
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val APP = "com.aicontrolcenter.app"
        val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

        val RELEASE_PERMISSIONS = sortedSetOf(
            // 0.5.2 (13): the app's own four, the phone operator's (device), and the libraries'.
            "android.permission.INTERNET",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.REQUEST_DELETE_PACKAGES",
            "com.termux.permission.RUN_COMMAND",
            "android.permission.QUERY_ADVANCED_PROTECTION_MODE",
            "com.aicontrolcenter.hq.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            // Phase 6, rule 22: three normal permissions.
            "android.permission.WAKE_LOCK",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            // Owner decision Q1: routines.
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )

        val RELEASE_EXPORTED = sortedSetOf(
            "$APP.MainActivity",
            "$APP.share.ShareReceiverActivity",
            "com.aicontrolcenter.device.screen.OperatorAccessibilityService",
            "androidx.profileinstaller.ProfileInstallReceiver",
        )
    }
}
