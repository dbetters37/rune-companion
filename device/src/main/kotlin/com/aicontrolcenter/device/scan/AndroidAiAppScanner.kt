package com.aicontrolcenter.device.scan

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.aicontrolcenter.core.registry.AiAppCapability
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.DiscoveredApp
import com.aicontrolcenter.core.registry.DiscoverySource
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.device.catalog.KnownAiApp
import com.aicontrolcenter.device.catalog.KnownAiApps
import com.aicontrolcenter.device.catalog.PromptDeepLink
import com.aicontrolcenter.device.catalog.PromptLinkTemplate
import com.aicontrolcenter.device.platform.activitiesFor
import com.aicontrolcenter.device.platform.installedPackages
import com.aicontrolcenter.device.platform.packageInfoOrNull
import com.aicontrolcenter.device.platform.startableBy
import com.aicontrolcenter.device.platform.toSingleLine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Finds AI applications with PackageManager queries only. It never reads another app's data and
 * relies on the `<queries>` block of this module's manifest instead of QUERY_ALL_PACKAGES.
 *
 * Capabilities are what Android reports for the installed package at the moment of the call:
 * an app that is disabled in system settings is still reported (with `enabledInSystem = false`)
 * but has none of the intent-based capabilities, because Android would not deliver to it. The
 * same goes for activities this app may not start (not exported, or permission-guarded).
 *
 * Labels and version names come from the other app and are untrusted; they are reduced to one
 * bounded line before they leave this class.
 *
 * PROMPT_DEEP_LINK is stricter than "the catalog knows a link": the catalog link must be
 * vendor-documented and the installed app must actually resolve it, so the registry never
 * offers a link that would open nothing.
 *
 * The control center itself is never reported, not by a scan, not in the installed-app list and
 * not by [inspect], so it cannot be registered as a hand-off target for itself.
 */
class AndroidAiAppScanner(
    context: Context,
    catalog: List<KnownAiApp> = KnownAiApps.all,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AiAppScanner {

    private val pm: PackageManager = context.packageManager
    private val ownPackage: String = context.packageName
    private val catalogByPackage: Map<String, KnownAiApp> = catalog.associateBy { it.packageName }

    override suspend fun scanForAiApps(): List<DiscoveredApp> = withContext(dispatcher) {
        val surfaces = IntentSurfaces.query(pm, callerPackage = ownPackage)
        val found = LinkedHashMap<String, DiscoveredApp>()

        suspend fun consider(packageName: String, source: DiscoverySource) {
            coroutineContext.ensureActive()
            if (packageName == ownPackage || packageName in found) return
            val info = pm.packageInfoOrNull(packageName) ?: return
            describe(info, source, surfaces)?.let { found[packageName] = it }
        }

        catalogByPackage.keys.forEach { consider(it, DiscoverySource.CATALOG) }
        surfaces.assistant.sorted().forEach { consider(it, DiscoverySource.INTENT_QUERY) }
        launchableApps()
            .filter { AiNameHeuristic.looksLikeAiApp(it.label, it.packageName) }
            .forEach { consider(it.packageName, DiscoverySource.HEURISTIC) }
        found.values.toList()
    }

    override suspend fun listInstalledApps(): List<InstalledAppInfo> = withContext(dispatcher) {
        launchableApps()
    }

    /**
     * Fresh facts about [packageName], or null when it is not installed, not visible to this app,
     * or is this app. The source says how a scan would have found it; MANUAL means it would not.
     */
    override suspend fun inspect(packageName: String): DiscoveredApp? = withContext(dispatcher) {
        if (packageName == ownPackage) return@withContext null
        val info = pm.packageInfoOrNull(packageName) ?: return@withContext null
        val surfaces = IntentSurfaces.query(pm, callerPackage = ownPackage, onlyPackage = packageName)
        val app = describe(info, DiscoverySource.MANUAL, surfaces) ?: return@withContext null
        val source = when {
            packageName in catalogByPackage -> DiscoverySource.CATALOG
            AiAppCapability.ASSISTANT in app.capabilities -> DiscoverySource.INTENT_QUERY
            // Like the scan, the name heuristic only applies to apps with a launcher icon.
            AiAppCapability.LAUNCH in app.capabilities && AiNameHeuristic.looksLikeAiApp(app.label, packageName) ->
                DiscoverySource.HEURISTIC
            else -> DiscoverySource.MANUAL
        }
        app.copy(source = source)
    }

    private fun describe(info: PackageInfo, source: DiscoverySource, surfaces: IntentSurfaces): DiscoveredApp? {
        val appInfo = info.applicationInfo ?: return null
        val packageName = info.packageName
        val known = catalogByPackage[packageName]
        val promptLink = known?.verifiedPromptLink?.takeIf { opensIn(packageName, it) }
        val capabilities = buildSet {
            if (pm.getLaunchIntentForPackage(packageName) != null) add(AiAppCapability.LAUNCH)
            if (packageName in surfaces.shareText) add(AiAppCapability.SHARE_TEXT)
            if (packageName in surfaces.shareImage) add(AiAppCapability.SHARE_IMAGE)
            if (packageName in surfaces.processText) add(AiAppCapability.PROCESS_TEXT)
            if (packageName in surfaces.assistant) add(AiAppCapability.ASSISTANT)
            if (promptLink != null) add(AiAppCapability.PROMPT_DEEP_LINK)
            if (known?.hostsLocalModels == true) add(AiAppCapability.LOCAL_MODEL_HOST)
        }
        return DiscoveredApp(
            packageName = packageName,
            label = appInfo.label(packageName),
            versionName = info.versionName.displayVersion(),
            vendor = known?.vendor,
            capabilities = capabilities,
            source = source,
            enabledInSystem = isEnabledInSystem(appInfo),
            promptDeepLink = promptLink?.template,
        )
    }

    /** True when the installed [packageName] has an activity that accepts [link]. */
    private fun opensIn(packageName: String, link: PromptDeepLink): Boolean {
        if (!PromptLinkTemplate.isUsable(link.template)) return false
        val probe = Intent(Intent.ACTION_VIEW, PromptLinkTemplate.fill(link.template, "probe").toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(packageName)
        return pm.activitiesFor(probe, PackageManager.MATCH_DEFAULT_ONLY.toLong()).startableBy(pm, ownPackage).isNotEmpty()
    }

    private fun isEnabledInSystem(appInfo: ApplicationInfo): Boolean {
        if (!appInfo.enabled) return false
        val setting = try {
            pm.getApplicationEnabledSetting(appInfo.packageName)
        } catch (_: IllegalArgumentException) {
            // The package vanished between the two calls; the flag just read is the best answer.
            return true
        }
        return setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
    }

    /** Every app with a launcher icon except this one, sorted by label. */
    private fun launchableApps(): List<InstalledAppInfo> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val applications = pm.activitiesFor(launcher)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != ownPackage }
            .distinctBy { it.packageName }
        if (applications.isEmpty()) return emptyList()
        val versions = pm.installedPackages().associate { it.packageName to it.versionName.displayVersion() }
        return applications
            .map { appInfo ->
                InstalledAppInfo(
                    packageName = appInfo.packageName,
                    label = appInfo.label(appInfo.packageName),
                    versionName = versions[appInfo.packageName],
                    isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun ApplicationInfo?.label(fallback: String): String =
        this?.loadLabel(pm).toSingleLine(MAX_LABEL_CHARS).ifEmpty { fallback }

    private fun String?.displayVersion(): String? = toSingleLine(MAX_VERSION_CHARS).ifEmpty { null }

    private companion object {
        const val MAX_LABEL_CHARS = 80
        const val MAX_VERSION_CHARS = 64
    }
}
