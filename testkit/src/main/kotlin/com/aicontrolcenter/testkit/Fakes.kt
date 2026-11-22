package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.authorisedChannels
import com.aicontrolcenter.core.registry.DiscoveredApp
import com.aicontrolcenter.core.registry.InstalledAppInfo
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** A clock that only moves when told to. Every [now] call can optionally auto-advance. */
class FakeClock(var current: Long = 1_700_000_000_000L, private val autoAdvanceMs: Long = 0) : Clock {
    override fun now(): Long {
        val value = current
        current += autoAdvanceMs
        return value
    }

    fun advance(ms: Long) {
        current += ms
    }
}

/** Deterministic ids: agt_1, agt_2, tsk_1 ... */
class SequentialIdGenerator : IdGenerator {
    private val counters = mutableMapOf<String, Int>()

    @Synchronized
    override fun newId(prefix: String): String {
        val next = (counters[prefix] ?: 0) + 1
        counters[prefix] = next
        return "${prefix}_$next"
    }
}

class InMemorySettingsRepository(initial: FrameworkSettings = FrameworkSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<FrameworkSettings> = state

    override suspend fun get(): FrameworkSettings = state.value

    override suspend fun update(transform: (FrameworkSettings) -> FrameworkSettings): FrameworkSettings =
        state.updateAndGet(transform)
}

class InMemorySecretVault : SecretVault {
    private val secrets = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun put(alias: String, secret: String) { secrets[alias] = secret }
    override suspend fun get(alias: String): String? = secrets[alias]
    override suspend fun contains(alias: String): Boolean = secrets.containsKey(alias)
    override suspend fun delete(alias: String) { secrets.remove(alias) }
    override suspend fun aliases(): Set<String> = secrets.keys.toSet()
}

/** A scriptable device: tests set what is "installed". */
class FakeAiAppScanner(
    var aiApps: List<DiscoveredApp> = emptyList(),
    var installed: List<InstalledAppInfo> = emptyList(),
) : AiAppScanner {
    var scanCount = 0
        private set

    override suspend fun scanForAiApps(): List<DiscoveredApp> {
        scanCount++
        return aiApps
    }

    override suspend fun listInstalledApps(): List<InstalledAppInfo> = installed

    override suspend fun inspect(packageName: String): DiscoveredApp? =
        aiApps.firstOrNull { it.packageName == packageName }
            ?: installed.firstOrNull { it.packageName == packageName }?.let {
                DiscoveredApp(
                    packageName = it.packageName, label = it.label, versionName = it.versionName,
                    capabilities = setOf(com.aicontrolcenter.core.registry.AiAppCapability.LAUNCH),
                    source = com.aicontrolcenter.core.registry.DiscoverySource.MANUAL,
                )
            }
}

class FakeDeviceInspector(
    var report: com.aicontrolcenter.core.device.DeviceReport = com.aicontrolcenter.core.device.DeviceReport(
        manufacturer = "Google", model = "Pixel 9", androidRelease = "16", sdkInt = 36, securityPatch = "2026-10-05",
        socManufacturer = "Google", socModel = "Tensor G4", supportedAbis = listOf("arm64-v8a"), cpuCores = 8,
        totalRamBytes = 12L * 1024 * 1024 * 1024 - 300_000_000, availableRamBytes = 5L * 1024 * 1024 * 1024,
        storageTotalBytes = 256L * 1000 * 1000 * 1000, storageFreeBytes = 91L * 1000 * 1000 * 1000,
        openGlEsVersion = "3.2",
        features = listOf(
            com.aicontrolcenter.core.device.DeviceFeature("accessibility_service", "Operator accessibility service", false, "Not enabled in Android settings."),
            com.aicontrolcenter.core.device.DeviceFeature("termux", "Termux", true, "Installed; can host local model servers and run scripts."),
        ),
        generatedAt = 1_700_000_000_000L,
    ),
) : com.aicontrolcenter.core.device.DeviceInspector {
    override suspend fun inspect() = report
}

/** Records hand-offs instead of starting activities. */
class FakeAiAppLauncher : com.aicontrolcenter.core.registry.AiAppLauncher {
    val launched = mutableListOf<String>()
    val handedOff = mutableListOf<Pair<String, String>>()

    override suspend fun launch(app: com.aicontrolcenter.core.registry.AiApp): com.aicontrolcenter.core.registry.LaunchResult {
        if (com.aicontrolcenter.core.registry.ControlChannel.LAUNCH !in app.authorisedChannels()) {
            return com.aicontrolcenter.core.registry.LaunchResult.NotPossible("${app.label} cannot be opened.")
        }
        launched += app.packageName
        return com.aicontrolcenter.core.registry.LaunchResult.Started
    }

    override suspend fun handOffText(app: com.aicontrolcenter.core.registry.AiApp, text: String): com.aicontrolcenter.core.registry.LaunchResult {
        if (com.aicontrolcenter.core.registry.ControlChannel.INTENT_HANDOFF !in app.authorisedChannels()) {
            return com.aicontrolcenter.core.registry.LaunchResult.NotPossible("${app.label} does not accept shared text.")
        }
        handedOff += app.packageName to text
        return com.aicontrolcenter.core.registry.LaunchResult.Started
    }
}
