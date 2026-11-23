package com.aicontrolcenter.app.ui.tasks

import androidx.compose.runtime.Immutable
import com.aicontrolcenter.app.ui.models.isSimulator
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality

/** One model the team would use, with where text sent to it goes and which agents use it. */
@Immutable
data class TeamModelUi(
    val modelName: String,
    val providerName: String,
    val locality: Locality,
    val isSimulator: Boolean,
    val agents: List<String>,
)

/**
 * Where an objective's text can go: the models of the agents that can take part right now.
 * Any of them may receive the objective, task results and teammates' findings, so the honest
 * answer to "does anything leave this phone" is whether any of them is off the device.
 */
@Immutable
data class TeamDestinationsUi(
    /** Off-device models first, as they matter most. */
    val models: List<TeamModelUi> = emptyList(),
    /** Agents that are switched on but cannot run now (no model, provider offline…): they get no work. */
    val agentsThatCannotRun: List<String> = emptyList(),
    val localOnly: Boolean = false,
) {
    val leavesDevice: Boolean get() = models.any { it.locality != Locality.ON_DEVICE }
}

fun RegistrySnapshot.teamDestinations(): TeamDestinationsUi {
    // The Director first, then everyone else by name.
    val team = agents.filter { it.availability !is AgentAvailability.Disabled }
        .sortedWith(compareBy({ !it.isDesignatedDirector }, { it.profile.kind != AgentKind.DIRECTOR }, { it.profile.name.lowercase() }))
    val byModel = LinkedHashMap<ModelId, MutableList<String>>()
    val cannotRun = ArrayList<String>()
    for (entry in team) {
        val ready = entry.availability as? AgentAvailability.Ready
        if (ready == null) cannotRun += entry.profile.name else byModel.getOrPut(ready.modelId) { ArrayList() } += entry.profile.name
    }
    val models = byModel.mapNotNull { (id, names) ->
        val model = modelsById[id] ?: return@mapNotNull null
        val provider = provider(model) ?: return@mapNotNull null
        TeamModelUi(model.displayName, provider.name, provider.effectiveLocality, model.isSimulator, names)
    }.sortedBy { LOCALITY_ORDER.indexOf(it.locality) }
    return TeamDestinationsUi(models, cannotRun, settings.localOnlyMode)
}

private val LOCALITY_ORDER = listOf(Locality.CLOUD, Locality.LOCAL_NETWORK, Locality.ON_DEVICE)
