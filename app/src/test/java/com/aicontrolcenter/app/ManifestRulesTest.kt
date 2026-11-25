package com.aicontrolcenter.app

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's own manifest entries of Phase 6 (WP1d), read from the source files.
 *
 * R6: no copy of the app's data leaves the phone through Android's backup or a device-to-device transfer (which some
 * phone makers do on Android 12 and later although allowBackup is false); such a copy would carry the encrypted
 * database without its key. Both rule files exclude every domain and include nothing.
 *
 * The pre-inserted components of the wave-2 packages are not exported (nothing new is reachable from other apps), the
 * script service runs isolated in its own process, only the job scheduler may bind the upkeep job, and the routine
 * receivers stay disabled until a routine exists. The release merged-manifest test of rules 22 and 23 (WP8) checks the
 * APK as a whole.
 */
class ManifestRulesTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { File(it, "AndroidManifest.xml").isFile }

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    private val manifest = parse(File(main, "AndroidManifest.xml"))

    private fun Element.android(name: String): String? = getAttributeNS(ANDROID, name).takeIf { hasAttributeNS(ANDROID, name) }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private val application: Element get() = manifest.children("application").single()

    private fun component(tag: String, name: String): Element = application.children(tag).single { it.android("name") == name }

    @Test
    fun `the application names both rule files and keeps backups off`() {
        assertEquals("false", application.android("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.android("dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.android("fullBackupContent"))
    }

    @Test
    fun `Android 12 and later - neither a cloud backup nor a device transfer takes anything`() {
        val rules = parse(File(main, "res/xml/data_extraction_rules.xml"))
        assertEquals("data-extraction-rules", rules.tagName)
        assertEquals(listOf("cloud-backup", "device-transfer"), rules.children("cloud-backup").map { it.tagName } + rules.children("device-transfer").map { it.tagName })
        // A cross-platform transfer needs an iOS bundle and team id, and there is nothing to transfer.
        assertEquals(emptyList(), rules.children("cross-platform-transfer"))
        for (section in rules.children("cloud-backup") + rules.children("device-transfer")) {
            assertEveryDomainExcluded(section)
        }
    }

    @Test
    fun `Android 11 and earlier - the full backup takes nothing`() {
        val rules = parse(File(main, "res/xml/backup_rules.xml"))
        assertEquals("full-backup-content", rules.tagName)
        assertEveryDomainExcluded(rules)
    }

    private fun assertEveryDomainExcluded(section: Element) {
        assertEquals(emptyList(), section.children("include"), "${section.tagName} includes nothing")
        val excluded = section.children("exclude").map { it.getAttribute("domain") to it.getAttribute("path") }
        assertEquals(DOMAINS.map { it to "." }.toSet(), excluded.toSet(), "${section.tagName} excludes every domain whole")
        assertEquals(DOMAINS.size, excluded.size, "${section.tagName} lists each domain once")
    }

    @Test
    fun `the wave-2 components are declared, none is exported, and each is limited as planned`() {
        val upkeep = component("service", ".work.UpkeepJobService")
        assertEquals("false", upkeep.android("exported"))
        assertEquals("android.permission.BIND_JOB_SERVICE", upkeep.android("permission"))

        val wake = component("receiver", ".work.WakeReceiver")
        assertEquals("false", wake.android("exported"))
        assertEquals(emptyList(), wake.children("intent-filter"), "only the app's own alarm reaches it")

        val scripts = component("service", ".scripts.ScriptProcessService")
        assertEquals("false", scripts.android("exported"))
        assertEquals("true", scripts.android("isolatedProcess"))
        assertEquals(":scripts", scripts.android("process"))

        val alarm = component("receiver", ".routines.RoutineAlarmReceiver")
        assertEquals("false", alarm.android("exported"))
        assertEquals("false", alarm.android("enabled"), "disabled until a routine exists")
        assertEquals(emptyList(), alarm.children("intent-filter"), "only the app's own alarms and notification reach it")

        val boot = component("receiver", ".routines.BootReceiver")
        assertEquals("false", boot.android("exported"))
        assertEquals("false", boot.android("enabled"), "disabled until a routine exists")
        val actions = boot.children("intent-filter").flatMap { it.children("action") }.map { it.android("name") }
        assertEquals(listOf("android.intent.action.BOOT_COMPLETED", "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"), actions)

        // Every service, receiver and provider of the app's own manifest says whether it is exported, and only the
        // main activity is (rule 22: the share receiver of WP16 is added in wave 3, if it ships).
        val components = listOf("activity", "service", "receiver", "provider").flatMap { application.children(it) }
        assertTrue(components.all { it.android("exported") != null }, "every component states exported")
        assertEquals(listOf(".MainActivity"), components.filter { it.android("exported") == "true" }.map { it.android("name") })
    }

    @Test
    fun `the main activity keeps its one launcher filter`() {
        // Rule 23: no new entry points on the main activity; no intent opens Backup, Restore, Recovery or Start over.
        val filters = component("activity", ".MainActivity").children("intent-filter")
        assertEquals(1, filters.size)
        assertEquals(listOf("android.intent.action.MAIN"), filters.single().children("action").map { it.android("name") })
        assertEquals(listOf("android.intent.category.LAUNCHER"), filters.single().children("category").map { it.android("name") })
        assertEquals(emptyList(), filters.single().children("data"))
    }

    @Test
    fun `the app's own permissions are the planned ones`() {
        // Rule 22: three normal permissions for background work and recovery, and two for routines (owner decision
        // Q1). The modules' manifests add theirs; the release merged-manifest test (WP8) counts the APK's.
        val permissions = manifest.children("uses-permission").map { it.android("name") }
        assertEquals(
            listOf(
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.WAKE_LOCK",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                "android.permission.SCHEDULE_EXACT_ALARM",
                "android.permission.RECEIVE_BOOT_COMPLETED",
            ),
            permissions,
        )
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"

        /** Every domain the backup rules know (https://developer.android.com/identity/data/autobackup). */
        val DOMAINS = listOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )
    }
}
