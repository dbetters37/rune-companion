package com.aicontrolcenter.app.di.wiring

import android.content.Context
import com.aicontrolcenter.app.Phase6Stub
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
import com.aicontrolcenter.framework.llm.DefaultModelGateway
import com.aicontrolcenter.framework.llm.DefaultProviderManager
import com.aicontrolcenter.framework.llm.ProviderConnections
import com.aicontrolcenter.framework.registry.DefaultRegistryDirectory
import kotlinx.coroutines.CoroutineScope

/*
 * Models, providers and the registry (Phase 6: WP4 owns this file in wave 2; usage, cost, daily limits, provider
 * trouble). Moved out of AppGraph unchanged in wave 1 (WP1d); AppGraph keeps its public members as delegates.
 */

/**
 * The registry directory, the provider connections, the model gateway and the provider manager.
 *
 * @param ports the app's stores. Read here only: none of them is kept by this class, so nothing agent-facing that
 *   it builds can reach the owner's stores through it.
 * @param settings read-only: the gateway and the registry are agent-facing (rule 3).
 * @param activity where model calls and provider checks are logged.
 * @param adapterFactories how each provider kind is reached; tests pass fakes.
 * @param appScope lives as long as the process (WP4: provider re-checks run in it).
 * @param network whether the phone has a network (WP4: an OFFLINE provider is re-checked when it comes back).
 */
class ModelsAssembly(
    ports: Ports,
    settings: SettingsView,
    activity: ActivityRecorder,
    adapterFactories: List<ProviderAdapterFactory>,
    @Suppress("UNUSED_PARAMETER") appScope: CoroutineScope,
    @Suppress("UNUSED_PARAMETER") network: NetworkStatus,
) {
    // Agent-facing: reads agents, providers, models and apps through read-only views, never the stores that change them (F3).
    val registryDirectory: RegistryDirectory = DefaultRegistryDirectory(
        agents = ReadOnlyRegistry.agents(ports.agents),
        providers = ReadOnlyRegistry.providers(ports.providers),
        models = ReadOnlyRegistry.models(ports.models),
        apps = ReadOnlyRegistry.apps(ports.aiApps),
        settings = settings,
    )

    /** One open adapter per provider, shared by the gateway and the provider manager. */
    private val providerConnections = ProviderConnections(ports.providers, ports.secretVault, adapterFactories, ports.clock)

    val modelGateway: ModelGateway = DefaultModelGateway(
        providers = ReadOnlyRegistry.providers(ports.providers),
        models = ReadOnlyRegistry.models(ports.models),
        settings = settings,
        connections = providerConnections,
        activity = activity,
        clock = ports.clock,
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
     * scheduled re-check is due (K15, D28; F3). The orchestrator and chat get this, never [providerManager]. WP4
     * builds `DefaultProviderRechecks` here; it must hold neither the provider manager nor the vault (the
     * architecture tests walk into it).
     */
    val providerRechecks: ProviderRechecks = ProviderRechecks.None

    /**
     * Owner-only: whether an answer the owner is about to send holds a stored provider key (rule 32; F3). Only the
     * owner's answer path gets it (`OwnerServices.answer`, WP5). WP4 builds it over the vault here.
     */
    val ownerKeyCheck: OwnerKeyCheck = OwnerKeyCheck.None
}

@Phase6Stub("WP4")
object ModelsWiring {
    /**
     * Whether the phone has a network, for the provider re-checks. Called once per process by `createProductionPorts`;
     * WP4 returns `AndroidNetworkStatus` (`ConnectivityManager.registerDefaultNetworkCallback`, `ACCESS_NETWORK_STATE`).
     */
    fun network(@Suppress("UNUSED_PARAMETER") context: Context): NetworkStatus = NetworkStatus.AlwaysOn
}
