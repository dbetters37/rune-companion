package com.aicontrolcenter.framework

import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ControlChannel
import com.aicontrolcenter.core.registry.DiscoveredApp
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.registry.controlChannels
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiAppRegistryTest {
    private val world = TestWorld()
    private val registry = world.appRegistry

    private fun chatbot(version: String = "1.0", capabilities: Set<AiAppCapability> = setOf(AiAppCapability.LAUNCH, AiAppCapability.SHARE_TEXT)) =
        DiscoveredApp(
            packageName = "com.vendor.chatbot", label = "Chatbot", versionName = version, vendor = "Vendor",
            capabilities = capabilities, source = DiscoverySource.CATALOG, promptDeepLink = "https://chat.vendor/?q={prompt}",
        )

    private val notes = InstalledAppInfo("com.other.notes", "Notes", "2.0")

    @Test
    fun `discovery adds new apps and is quiet when nothing changed`() = runTest {
        world.scanner.aiApps = listOf(chatbot())
        val first = registry.discover()
        assertEquals(listOf("com.vendor.chatbot"), first.added.map { it.packageName })
        val app = world.apps.getAll().single()
        assertEquals(AiAppStatus.INSTALLED, app.status)
        assertEquals(DiscoverySource.CATALOG, app.source)
        assertFalse(app.accessibilityAuthorized, "registration must never imply accessibility control")
        assertEquals(setOf(ControlChannel.LAUNCH, ControlChannel.INTENT_HANDOFF), app.controlChannels())

        val events = world.log.all.size
        val second = registry.discover()
        assertTrue(second.added.isEmpty() && second.updated.isEmpty() && second.missing.isEmpty())
        assertEquals(events, world.log.all.size, "an unchanged scan must not spam the activity log")
        assertTrue(world.apps.getAll().single().lastSeenAt > app.lastSeenAt)
        assertEquals(app.id, world.apps.getAll().single().id)
    }

    @Test
    fun `discovery refreshes device facts and keeps the user's decisions`() = runTest {
        world.scanner.aiApps = listOf(chatbot())
        val id = registry.discover().added.single().id
        registry.setEnabled(id, false)
        registry.setAccessibilityAuthorized(id, true)
        registry.linkProvider(id, ProviderId("prv_1"))
        registry.updateNotes(id, "work account")

        world.scanner.aiApps = listOf(chatbot(version = "2.0", capabilities = setOf(AiAppCapability.LAUNCH, AiAppCapability.PROCESS_TEXT)))
        val report = registry.discover()
        val updated = report.updated.single()
        assertEquals("2.0", updated.versionName)
        assertEquals(setOf(AiAppCapability.LAUNCH, AiAppCapability.PROCESS_TEXT), updated.capabilities)
        assertFalse(updated.enabled)
        assertTrue(updated.accessibilityAuthorized)
        assertEquals(ProviderId("prv_1"), updated.linkedProviderId)
        assertEquals("work account", updated.notes)
        assertEquals(updated, world.apps.get(id))
    }

    @Test
    fun `an uninstalled app stays listed, loses authorisation and can come back`() = runTest {
        world.scanner.aiApps = listOf(chatbot())
        val id = registry.discover().added.single().id
        registry.setAccessibilityAuthorized(id, true)

        world.scanner.aiApps = emptyList()
        val gone = registry.discover().missing.single()
        assertEquals(AiAppStatus.NOT_INSTALLED, gone.status)
        assertFalse(gone.accessibilityAuthorized, "authorisation must not survive uninstall")
        assertEquals(emptySet(), gone.controlChannels())
        assertTrue(registry.discover().missing.isEmpty(), "already-missing apps are not reported again")
        assertFailsWith<IllegalArgumentException> { registry.setAccessibilityAuthorized(id, true) }

        world.scanner.aiApps = listOf(chatbot())
        val back = registry.discover().updated.single()
        assertEquals(id, back.id)
        assertEquals(AiAppStatus.INSTALLED, back.status)
        assertFalse(back.accessibilityAuthorized)
    }

    @Test
    fun `apps disabled in android settings are marked and not usable`() = runTest {
        world.scanner.aiApps = listOf(chatbot().copy(enabledInSystem = false))
        val app = registry.discover().added.single()
        assertEquals(AiAppStatus.DISABLED_BY_SYSTEM, app.status)
        assertEquals(emptySet(), app.controlChannels())
    }

    @Test
    fun `manual registration works for any installed app, once`() = runTest {
        world.scanner.aiApps = listOf(chatbot())
        world.scanner.installed = listOf(notes, InstalledAppInfo("com.vendor.chatbot", "Chatbot"), InstalledAppInfo("a.b", "alpha"))
        registry.discover()
        assertEquals(listOf("alpha", "Notes"), registry.unregisteredInstalledApps().map { it.label })

        val app = registry.registerManually("com.other.notes")
        assertEquals(DiscoverySource.MANUAL, app.source)
        assertEquals(setOf(AiAppCapability.LAUNCH), app.capabilities)
        assertEquals(listOf("alpha"), registry.unregisteredInstalledApps().map { it.label })
        assertFailsWith<IllegalArgumentException> { registry.registerManually("com.other.notes") }
        assertFailsWith<IllegalArgumentException> { registry.registerManually("not.installed") }

        // A manually registered app is not in the AI scan, yet a later scan must keep it.
        val report = registry.discover()
        assertTrue(report.missing.isEmpty())
        assertEquals(DiscoverySource.MANUAL, world.apps.getByPackage("com.other.notes")?.source)
    }

    @Test
    fun `refresh rechecks registered apps only`() = runTest {
        world.scanner.aiApps = listOf(chatbot())
        registry.discover()
        assertEquals(0, registry.refreshStatuses())
        world.scanner.aiApps = listOf(chatbot(version = "3.0"))
        assertEquals(1, registry.refreshStatuses())
        world.scanner.aiApps = emptyList()
        assertEquals(1, registry.refreshStatuses())
        assertEquals(AiAppStatus.NOT_INSTALLED, world.apps.getAll().single().status)
        assertEquals(0, registry.refreshStatuses())
    }

    @Test
    fun `authorising accessibility is logged as a warning and removal forgets the app`() = runTest {
        world.scanner.aiApps = listOf(chatbot())
        val id = registry.discover().added.single().id
        registry.setAccessibilityAuthorized(id, true)
        val event = world.log.all.last()
        assertEquals(ActivityType.PERMISSION, event.type)
        assertEquals(Severity.WARNING, event.severity)
        val count = world.log.all.size
        registry.setAccessibilityAuthorized(id, true)
        assertEquals(count, world.log.all.size, "repeating a decision is a no-op")

        registry.remove(id)
        assertNull(world.apps.get(id))
        registry.remove(id)
        assertFailsWith<IllegalArgumentException> { registry.setEnabled(AiAppId("app_missing"), false) }
    }
}
