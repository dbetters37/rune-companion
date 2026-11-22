package com.aicontrolcenter.framework.registry

import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.aiAppId
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.DiscoveredApp
import com.aicontrolcenter.core.registry.DiscoveryReport
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.InstalledAppInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultAiAppRegistry(
    private val apps: AiAppRepository,
    private val scanner: AiAppScanner,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
) : AiAppRegistry {

    private val mutex = Mutex()

    override fun observeApps(): Flow<List<AiApp>> = apps.observeAll()

    override suspend fun discover(): DiscoveryReport = mutex.withLock {
        val now = clock.now()
        val found = scanner.scanForAiApps().associateBy { it.packageName }
        val registered = apps.getAll().associateBy { it.packageName }
        val added = mutableListOf<AiApp>()
        val updated = mutableListOf<AiApp>()
        val missing = mutableListOf<AiApp>()

        for ((packageName, discovered) in found) {
            val existing = registered[packageName]
            if (existing == null) {
                val app = discovered.toNewApp(now)
                apps.upsert(app)
                added += app
            } else {
                val refreshed = existing.refreshedFrom(discovered, now)
                apps.upsert(refreshed)
                if (refreshed.copy(lastSeenAt = existing.lastSeenAt) != existing) updated += refreshed
            }
        }
        // Registered apps the scan did not report: manually added ones, or ones that were removed.
        for ((packageName, existing) in registered) {
            if (packageName in found) continue
            when (val change = recheck(existing, now)) {
                is Recheck.Changed -> updated += change.app
                is Recheck.WentMissing -> missing += change.app
                Recheck.Unchanged -> Unit
            }
        }
        if (added.isNotEmpty() || updated.isNotEmpty() || missing.isNotEmpty()) {
            activity.record(
                ActivityType.REGISTRY,
                "AI app scan",
                "${added.size} new, ${updated.size} changed, ${missing.size} no longer installed.",
            )
        }
        DiscoveryReport(added = added, updated = updated, missing = missing, scannedAt = now)
    }

    override suspend fun refreshStatuses(): Int = mutex.withLock {
        val now = clock.now()
        apps.getAll().count { recheck(it, now) != Recheck.Unchanged }
    }

    override suspend fun unregisteredInstalledApps(): List<InstalledAppInfo> {
        val registered = apps.getAll().mapTo(HashSet()) { it.packageName }
        return scanner.listInstalledApps()
            .filter { it.packageName !in registered }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun registerManually(packageName: String): AiApp = mutex.withLock {
        val existing = apps.getByPackage(packageName)
        require(existing == null) { "${existing?.label ?: packageName} is already registered." }
        val discovered = scanner.inspect(packageName)
        requireNotNull(discovered) { "$packageName is not installed on this device." }
        val app = discovered.copy(source = DiscoverySource.MANUAL).toNewApp(clock.now())
        apps.upsert(app)
        activity.record(ActivityType.REGISTRY, "AI app registered", "${app.label} (${app.packageName})")
        app
    }

    override suspend fun setEnabled(id: AiAppId, enabled: Boolean) = mutate(id) { app ->
        if (app.enabled == enabled) return@mutate null
        activity.record(ActivityType.REGISTRY, if (enabled) "AI app enabled" else "AI app disabled", app.label)
        app.copy(enabled = enabled)
    }

    override suspend fun setAccessibilityAuthorized(id: AiAppId, authorized: Boolean) = mutate(id) { app ->
        if (app.accessibilityAuthorized == authorized) return@mutate null
        require(!authorized || app.status == AiAppStatus.INSTALLED) {
            "${app.label} is not installed, so it cannot be authorised for accessibility operation."
        }
        activity.record(
            ActivityType.PERMISSION,
            if (authorized) "Accessibility operation authorised" else "Accessibility operation revoked",
            app.label,
            severity = if (authorized) Severity.WARNING else Severity.INFO,
        )
        app.copy(accessibilityAuthorized = authorized)
    }

    override suspend fun linkProvider(id: AiAppId, providerId: ProviderId?) = mutate(id) { app ->
        if (app.linkedProviderId == providerId) null else app.copy(linkedProviderId = providerId)
    }

    override suspend fun updateNotes(id: AiAppId, notes: String) = mutate(id) { app ->
        if (app.notes == notes) null else app.copy(notes = notes)
    }

    override suspend fun remove(id: AiAppId) = mutex.withLock {
        val app = apps.get(id) ?: return@withLock
        apps.delete(id)
        activity.record(ActivityType.REGISTRY, "AI app removed from registry", app.label)
        Unit
    }

    private suspend fun mutate(id: AiAppId, change: suspend (AiApp) -> AiApp?) = mutex.withLock {
        val app = apps.get(id) ?: throw IllegalArgumentException("That app is no longer registered.")
        change(app)?.let { apps.upsert(it) }
        Unit
    }

    private sealed interface Recheck {
        data object Unchanged : Recheck
        data class Changed(val app: AiApp) : Recheck
        data class WentMissing(val app: AiApp) : Recheck
    }

    private suspend fun recheck(existing: AiApp, now: Long): Recheck {
        val fresh = scanner.inspect(existing.packageName)
        if (fresh == null) {
            if (existing.status == AiAppStatus.NOT_INSTALLED) return Recheck.Unchanged
            // A package that disappears loses its accessibility authorisation: whatever is
            // installed under this name later must be authorised again by the user.
            val gone = existing.copy(status = AiAppStatus.NOT_INSTALLED, accessibilityAuthorized = false)
            apps.upsert(gone)
            return Recheck.WentMissing(gone)
        }
        val refreshed = existing.refreshedFrom(fresh, now)
        apps.upsert(refreshed)
        return if (refreshed.copy(lastSeenAt = existing.lastSeenAt) != existing) Recheck.Changed(refreshed) else Recheck.Unchanged
    }

    private fun DiscoveredApp.toNewApp(now: Long) = AiApp(
        id = ids.aiAppId(), packageName = packageName, label = label, vendor = vendor, versionName = versionName,
        source = source, capabilities = capabilities, status = systemStatus(), promptDeepLink = promptDeepLink,
        firstSeenAt = now, lastSeenAt = now,
    )

    /** Refreshes device facts and keeps everything the user decided (enabled, authorisation, link, notes, source). */
    private fun AiApp.refreshedFrom(fresh: DiscoveredApp, now: Long) = copy(
        label = fresh.label,
        vendor = fresh.vendor ?: vendor,
        versionName = fresh.versionName,
        capabilities = fresh.capabilities,
        status = fresh.systemStatus(),
        promptDeepLink = fresh.promptDeepLink ?: promptDeepLink,
        lastSeenAt = now,
    )

    private fun DiscoveredApp.systemStatus() =
        if (enabledInSystem) AiAppStatus.INSTALLED else AiAppStatus.DISABLED_BY_SYSTEM
}
