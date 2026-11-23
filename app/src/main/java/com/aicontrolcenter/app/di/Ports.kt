package com.aicontrolcenter.app.di

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RandomIdGenerator
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.device.DeviceInspector
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.registry.AiAppLauncher
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.core.registry.AiAppScanner
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.security.SecretVault
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Everything the app needs from outside the framework, expressed only as `:core` interfaces.
 *
 * This is the seam between the app and its platform: production fills it with the Room,
 * DataStore, Keystore and PackageManager implementations (see `createProductionPorts`), tests
 * fill it with the in-memory implementations from `:testkit`. Nothing above this type knows
 * which one it got.
 */
data class Ports(
    val agents: AgentRepository,
    val providers: ProviderRepository,
    val models: ModelRepository,
    val aiApps: AiAppRepository,
    /** Direct conversations between the owner and an agent, with every message and tool result. */
    val conversations: ConversationRepository,
    val activityLog: ActivityLog,
    val settings: SettingsRepository,
    val secretVault: SecretVault,
    val aiAppScanner: AiAppScanner,
    val aiAppLauncher: AiAppLauncher,
    val deviceInspector: DeviceInspector,
    val clock: Clock = WallClock,
    val idGenerator: IdGenerator = RandomIdGenerator,
    /**
     * Emits once whenever another app is installed, removed, updated, enabled or disabled.
     * The payload is deliberately empty: the registry re-checks every registered app, so which
     * package changed does not matter. Collected for as long as the app process lives.
     */
    val packageChanges: Flow<Unit> = emptyFlow(),
)
