package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.di.Ports
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.health.NetworkStatus
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.OwnerKeyCheck
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.registry.ReadOnlyRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.device.net.AndroidNetworkStatus
import com.aicontrolcenter.framework.llm.DailyLimiter
import com.aicontrolcenter.framework.llm.DefaultModelGateway
import com.aicontrolcenter.framework.llm.DefaultProviderManager
import com.aicontrolcenter.framework.llm.DefaultProviderRechecks
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.llm.VaultKeyCheck
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import kotlinx.coroutines.CoroutineScope
import java.time.ZoneId
import com.aicontrolcenter.core.usage.counted

/*
 * Models, providers and the registry (Phase 6: WP4 owns this file in wave 2; usage, cost, daily limits, provider
 * trouble). Moved out of AppGraph unchanged in wave 1 (WP1d); AppGraph keeps its public members as delegates.
 */

/**
 * The registry directory, the provider connections, the daily limits, the model gateway, the provider manager, the
 * provider re-checks and the owner's key check.
 *
 * @param ports the app's stores. Read here only: none of them is kept by this class, so nothing agent-facing that
 *   it builds can reach the owner's stores through it. The gateway and its limits get the usage ledger, the usage
 *   reports and the read-only prices ([Ports.modelPrices]) only: never the owner's prices, the owner's usage history
 *   or usage pruning (F4).
 * @param settings read-only: the gateway and the registry are agent-facing (rule 3).
 * @param activity where model calls and provider checks are logged.
 * @param adapterFactories how each provider kind is reached; tests pass fakes.
 * @param appScope lives as long as the process: the shared registry snapshot (P8) and the provider re-checks run in it.
 * @param network whether the phone has a network: providers that stopped answering are checked at once when it is back.
 * @param zone the owner's zone: where today starts for the daily limits, and the time in "Not answering since 10:42".
 */
class ModelsAssembly(
    ports: Ports,
    settings: SettingsView,
    activity: ActivityRecorder,
    adapterFactories: List<ProviderAdapterFactory>,
    appScope: CoroutineScope,
    network: NetworkStatus,
    zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    // Agent-facing: reads agents, providers, models and apps through read-only views, never the stores that change them (F3).
    // One snapshot shared by every observer while anyone observes (P8).
    val registryDirectory: RegistryDirectory = DefaultRegistryDirectory(
        agents = ReadOnlyRegistry.agents(ports.agents),
        providers = ReadOnlyRegistry.providers(ports.providers),
        models = ReadOnlyRegistry.models(ports.models),
        apps = ReadOnlyRegistry.apps(ports.aiApps),
        settings = settings,
        sharing = appScope,
    )

    /** One open adapter per provider, shared by the gateway, the provider manager and the re-checks. */
    private val providerConnections = ProviderConnections(ports.providers, ports.secretVault, adapterFactories, ports.clock)

    /**
     * Today's limits of model answers, tokens and estimated money (rule 6; the owner's Q2: 200 answers, 500,000
     * tokens, US$1.00 once a price is entered, on from the start). One per process: every call reserves under its lock.
     */
    // Only the prices that count (US$, D3A-8), as every screen reads them.
    val dailyLimiter: DailyLimiter = DailyLimiter(ports.usageReports, ports.modelPrices.counted(), settings, ports.clock, zone)

    val modelGateway: ModelGateway = DefaultModelGateway(
        providers = ReadOnlyRegistry.providers(ports.providers),
        models = ReadOnlyRegistry.models(ports.models),
        settings = settings,
        connections = providerConnections,
        activity = activity,
        clock = ports.clock,
        usage = ports.usageLedger,
        limiter = dailyLimiter,
        zone = zone,
    )

    /**
     * Owner-only: adds, changes and removes providers and their keys (security review of wave 1, F3). Only the owner's
     * screens and start-up hold it; agent-facing code gets [providerRechecks].
     */
    val providerManager: ProviderManager = DefaultProviderManager(
        providers = ports.providers,
        models = ports.models,
        settings = settings,
        vault = ports.secretVault,
        connections = providerConnections,
        activity = activity,
        ids = ports.idGenerator,
        clock = ports.clock,
    )

    /**
     * What agent-facing work may ask of the providers: re-check one that was not answering, and when the next
     * scheduled re-check is due (K15, D28; F3). The orchestrator and chat get this, never [providerManager]; the
     * wake lock reads its waits. It holds neither the provider manager nor the vault nor the provider store: it reads
     * providers through a read-only view and checks them through the connections (the architecture tests walk into it).
     */
    val providerRechecks: ProviderRechecks = DefaultProviderRechecks(
        providers = ReadOnlyRegistry.providers(ports.providers),
        settings = settings,
        connections = providerConnections,
        activity = activity,
        clock = ports.clock,
        scope = appScope,
        network = network,
    ).also { it.start() }

    /**
     * Owner-only: whether an answer the owner is about to send holds a stored provider key (rule 32; F3). Only the
     * owner's answer path gets it (`OwnerServices.answer`, WP5, through `OperatorAssembly`).
     */
    val ownerKeyCheck: OwnerKeyCheck = VaultKeyCheck(ports.secretVault)
}

object ModelsWiring {
    /**
     * Whether the phone has a network, for the provider re-checks: Android's default network
     * (`ConnectivityManager.registerDefaultNetworkCallback`, `ACCESS_NETWORK_STATE`). Called once per process by
     * `createProductionPorts`.
     */
    fun network(context: Context): NetworkStatus = AndroidNetworkStatus(context)
}
