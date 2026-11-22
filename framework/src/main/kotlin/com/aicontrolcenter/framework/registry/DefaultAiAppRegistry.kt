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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    override fun observeApps(): Flow<List<AiApp>> =
        apps.observeAll().map { all -> all.filterNot { it.hidden } }.distinctUntilChanged()

    override suspend fun discover(): DiscoveryReport = mutex.withLock {
        val now = clock.now()
        val found = scanner.scanForAiApps().associateBy { it.packageName }
        val registered = apps.getAll().associateBy { it.packageName }
        val added = mutableListOf<AiApp>()
        val updated = mutableListOf<AiApp>()
        val missing = mutableListOf<AiApp>()

        // New apps get increasing timestamps in label order, so the registry lists them predictably.
        val newcomers = found.values.filter { it.packageName !in registered }.sortedBy { it.label.lowercase() }
        newcomers.forEachIndexed { index, discovered ->
            val app = discovered.toNewApp(now + index)
            apps.upsert(app)
            added += app
        }
        for ((packageName, existing) in registered) {
            // Apps the scan did not report are re-inspected: they may be manual entries, or gone.
            val fresh = found[packageName] ?: scanner.inspect(packageName)
            when (val change = reconcile(existing, fresh, now)) {
                is Reconciled.Changed -> if (!change.app.hidden) updated += change.app
                is Reconciled.WentMissing -> if (!change.app.hidden) missing += change.app
                Reconciled.Unchanged -> Unit
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
        apps.getAll().count { reconcile(it, scanner.inspect(it.packageName), now) != Reconciled.Unchanged }
    }

    override suspend fun unregisteredInstalledApps(): List<InstalledAppInfo> {
        val registered = apps.getAll().filterNot { it.hidden }.mapTo(HashSet()) { it.packageName }
        return scanner.listInstalledApps()
            .filter { it.packageName !in registered }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun registerManually(packageName: String): AiApp = mutex.withLock {
        val existing = apps.getByPackage(packageName)
        require(existing == null || existing.hidden) { "${existing?.label ?: packageName} is already registered." }
        val discovered = scanner.inspect(packageName)
        requireNotNull(discovered) { "$packageName is not installed on this device." }
        val now = clock.now()
        val app = if (existing != null) {
            // Bringing back a removed app: same entry, visible again, choices reset.
            existing.refreshedFrom(discovered, now).copy(
                hidden = false, enabled = true, source = DiscoverySource.MANUAL, verifiedByUser = true,
            )
        } else {
            discovered.copy(source = DiscoverySource.MANUAL).toNewApp(now).copy(verifiedByUser = true)
        }
        apps.upsert(app)
        activity.record(ActivityType.REGISTRY, "AI app registered", "${app.label} (${app.packageName})")
        app
    }

    override suspend fun setEnabled(id: AiAppId, enabled: Boolean) = mutate(id) { app ->
        if (app.enabled == enabled) return@mutate null
        Change(app.copy(enabled = enabled)) {
            activity.record(ActivityType.REGISTRY, if (enabled) "AI app enabled" else "AI app disabled", app.label)
        }
    }

    override suspend fun confirm(id: AiAppId) = mutate(id) { app ->
        if (app.verifiedByUser) return@mutate null
        Change(app.copy(verifiedByUser = true)) {
            activity.record(ActivityType.REGISTRY, "AI app confirmed", app.label)
        }
    }

    override suspend fun setAccessibilityAuthorized(id: AiAppId, authorized: Boolean) = mutate(id) { app ->
        if (app.accessibilityAuthorized == authorized) return@mutate null
        require(!authorized || app.status == AiAppStatus.INSTALLED) {
            when (app.status) {
                AiAppStatus.DISABLED_BY_SYSTEM -> "${app.label} is disabled in Android settings, so it cannot be authorised for accessibility operation."
                else -> "${app.label} is not installed, so it cannot be authorised for accessibility operation."
            }
        }
        Change(app.copy(accessibilityAuthorized = authorized)) {
            activity.record(
                ActivityType.PERMISSION,
                if (authorized) "Accessibility operation authorised" else "Accessibility operation revoked",
                app.label,
                severity = if (authorized) Severity.WARNING else Severity.INFO,
            )
        }
    }

    override suspend fun linkProvider(id: AiAppId, providerId: ProviderId?) = mutate(id) { app ->
        if (app.linkedProviderId == providerId) return@mutate null
        Change(app.copy(linkedProviderId = providerId)) {
            activity.record(
                ActivityType.REGISTRY,
                if (providerId == null) "AI app unlinked from provider" else "AI app linked to provider",
                app.label,
            )
        }
    }

    override suspend fun updateNotes(id: AiAppId, notes: String) = mutate(id) { app ->
        if (app.notes == notes) null else Change(app.copy(notes = notes)) {}
    }

    override suspend fun remove(id: AiAppId) = mutex.withLock {
        val app = apps.get(id)?.takeUnless { it.hidden } ?: return@withLock
        if (app.source == DiscoverySource.MANUAL) {
            apps.delete(id)
        } else {
            // A scan would find it again, so remember the decision instead of forgetting the app.
            apps.upsert(app.copy(hidden = true, enabled = false, accessibilityAuthorized = false, linkedProviderId = null))
        }
        activity.record(ActivityType.REGISTRY, "AI app removed from registry", app.label)
        Unit
    }

    /** A new state for an app and the log entry to write once it has been stored. */
    private class Change(val app: AiApp, val log: suspend () -> Unit)

    private suspend fun mutate(id: AiAppId, change: suspend (AiApp) -> Change?) = mutex.withLock {
        val app = apps.get(id)?.takeUnless { it.hidden }
            ?: throw IllegalArgumentException("That app is no longer registered.")
        change(app)?.let {
            apps.upsert(it.app)
            it.log()
        }
        Unit
    }

    private sealed interface Reconciled {
        data object Unchanged : Reconciled
        data class Changed(val app: AiApp) : Reconciled
        data class WentMissing(val app: AiApp) : Reconciled
    }

    /** Brings one registered app in line with what the device reports ([fresh] null = not installed). */
    private suspend fun reconcile(existing: AiApp, fresh: DiscoveredApp?, now: Long): Reconciled {
        if (fresh == null) {
            if (existing.status == AiAppStatus.NOT_INSTALLED) return Reconciled.Unchanged
            // Whatever is installed under this name later must be authorised again.
            val gone = existing.copy(status = AiAppStatus.NOT_INSTALLED, accessibilityAuthorized = false)
            apps.upsert(gone)
            if (existing.accessibilityAuthorized) logWithdrawal(existing, "it is no longer installed")
            return Reconciled.WentMissing(gone)
        }
        var refreshed = existing.refreshedFrom(fresh, now)
        if (existing.accessibilityAuthorized && existing.identityDiffersFrom(fresh)) {
            refreshed = refreshed.copy(accessibilityAuthorized = false)
            apps.upsert(refreshed)
            logWithdrawal(existing, "a different installation now uses its package name")
        } else {
            apps.upsert(refreshed)
        }
        return if (refreshed.copy(lastSeenAt = existing.lastSeenAt) != existing) Reconciled.Changed(refreshed) else Reconciled.Unchanged
    }

    private suspend fun logWithdrawal(app: AiApp, why: String) {
        if (app.hidden) return
        activity.record(
            ActivityType.PERMISSION,
            "Accessibility authorisation withdrawn",
            "${app.label}: $why.",
            severity = Severity.WARNING,
        )
    }

    /** True when both sides know an identity fact and they disagree. Unknown never counts as a change. */
    private fun AiApp.identityDiffersFrom(fresh: DiscoveredApp): Boolean {
        val certChanged = signingCertSha256 != null && fresh.signingCertSha256 != null &&
            !signingCertSha256.equals(fresh.signingCertSha256, ignoreCase = true)
        val reinstalled = firstInstallTime != null && fresh.firstInstallTime != null && firstInstallTime != fresh.firstInstallTime
        return certChanged || reinstalled
    }

    private fun DiscoveredApp.toNewApp(now: Long) = AiApp(
        id = ids.aiAppId(), packageName = packageName, label = label, vendor = vendor, versionName = versionName,
        source = source, capabilities = capabilities, status = systemStatus(), promptDeepLink = promptDeepLink,
        firstInstallTime = firstInstallTime, signingCertSha256 = signingCertSha256,
        firstSeenAt = now, lastSeenAt = now,
    )

    /** Refreshes device facts and keeps everything the owner decided (enabled, authorisation, link, notes, source). */
    private fun AiApp.refreshedFrom(fresh: DiscoveredApp, now: Long) = copy(
        label = fresh.label,
        vendor = fresh.vendor ?: vendor,
        versionName = fresh.versionName,
        capabilities = fresh.capabilities,
        status = fresh.systemStatus(),
        promptDeepLink = fresh.promptDeepLink,
        firstInstallTime = fresh.firstInstallTime ?: firstInstallTime,
        signingCertSha256 = fresh.signingCertSha256 ?: signingCertSha256,
        lastSeenAt = now,
    )

    private fun DiscoveredApp.systemStatus() =
        if (enabledInSystem) AiAppStatus.INSTALLED else AiAppStatus.DISABLED_BY_SYSTEM
}
