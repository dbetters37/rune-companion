package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.app.ui.agents.summariseNames
import com.aicontrolcenter.app.ui.models.hasAddress
import com.aicontrolcenter.app.ui.models.isSimulator
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.registry.AgentEntry
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.RegistrySnapshot

/** Where the owner goes to deal with an [AttentionItem]. The shell turns it into a route. */
sealed interface AttentionTarget {
    data class Agent(val id: AgentId) : AttentionTarget

    /** The agent list, narrowed to agents that cannot run. */
    data object UnavailableAgents : AttentionTarget

    /** The agent list, unfiltered. */
    data object Agents : AttentionTarget

    data class AiApp(val id: AiAppId) : AttentionTarget

    data class Provider(val id: ProviderId) : AttentionTarget
}

/** Something in the registry the owner should look at, with the place to fix it. */
data class AttentionItem(
    /** Stable across recompositions and snapshots, for list keys. */
    val id: String,
    val tone: StatusTone,
    val title: String,
    val detail: String,
    val target: AttentionTarget,
)

/**
 * True while the registry holds no real model, only the built-in simulator (or nothing). An
 * agent without a model is then the expected state of an unfinished setup, not a fault: the
 * dashboard's quick-start card deals with it instead of one attention item per agent.
 */
val RegistrySnapshot.awaitingFirstModel: Boolean get() = models.none { !it.isSimulator }

/**
 * Derives the "needs attention" list from the registry. It is a pure function of the snapshot,
 * so the dashboard and the navigation badges can never disagree about what needs attention.
 *
 * Only things the owner can act on are listed. Order: the Director first (nothing works without
 * it), then providers (one broken provider stops every agent on it), then other agents, then
 * apps. An agent or provider the owner disabled on purpose is not reported, and neither is a
 * missing model while [awaitingFirstModel].
 */
fun RegistrySnapshot.attentionItems(): List<AttentionItem> = buildList {
    addAll(directorItems())
    addAll(providerItems())
    addAll(agentItems())
    addAll(appItems())
}

/**
 * [message] when it reads as a sentence for the owner, else null (D3A-14): a provider check's raw status ("ok", "503",
 * "ECONNREFUSED") never reaches the Dashboard. A sentence starts with a letter, has a space, ends with a full stop and is
 * not only a code.
 */
internal fun ownerSentence(message: String?): String? {
    val text = message?.trim() ?: return null
    if (text.length < 8 || !text.first().isLetter() || ' ' !in text || !(text.endsWith('.') || text.endsWith('!') || text.endsWith('?'))) return null
    if (text.trimEnd('.').equals("ok", ignoreCase = true)) return null
    return text
}

private fun RegistrySnapshot.providerItems(): List<AttentionItem> = buildList {
    for (provider in providers) {
        if (!provider.enabled || provider.isSimulator) continue
        // Local-only mode keeps it from being contacted at all; its last status says nothing new.
        if (settings.localOnlyMode && provider.effectiveLocality.leavesDevice) continue
        val target = AttentionTarget.Provider(provider.id)
        val name = provider.name
        when (provider.status) {
            ConnectionStatus.OFFLINE -> add(
                AttentionItem(
                    "provider-offline:${provider.id}", StatusTone.UNAVAILABLE, "$name is offline",
                    (ownerSentence(provider.statusMessage) ?: "It could not be reached at the last check.") + " Agents that use it cannot run until it is checked again.",
                    target,
                ),
            )
            ConnectionStatus.AUTH_ERROR -> add(
                AttentionItem(
                    "provider-auth:${provider.id}", StatusTone.UNAVAILABLE, "$name rejected its key",
                    "Agents that use it cannot run. Enter a valid API key on its page.", target,
                ),
            )
            ConnectionStatus.ERROR -> add(
                AttentionItem(
                    "provider-error:${provider.id}", StatusTone.ATTENTION, "$name reported an error",
                    ownerSentence(provider.statusMessage) ?: "Its last check failed. Check it again on its page.", target,
                ),
            )
            ConnectionStatus.UNKNOWN, ConnectionStatus.ONLINE -> Unit
        }
        if (provider.apiKeyAlias == null && provider.kind.hasAddress && provider.effectiveLocality == Locality.CLOUD &&
            provider.status != ConnectionStatus.AUTH_ERROR
        ) {
            add(
                AttentionItem(
                    "provider-no-key:${provider.id}", StatusTone.ATTENTION, "$name has no API key",
                    "Cloud services almost always need one. Add it on the provider's page.", target,
                ),
            )
        }
    }
}

private fun RegistrySnapshot.directorItems(): List<AttentionItem> {
    // With no agents at all there is nobody to direct; the empty dashboard says what to do.
    if (agents.isEmpty()) return emptyList()
    val director = director
        ?: return listOf(
            AttentionItem(
                id = "director-missing",
                tone = StatusTone.ATTENTION,
                title = "No Director is designated",
                detail = if (agents.any { it.profile.kind == AgentKind.DIRECTOR }) {
                    "Objectives will have nowhere to go. Open an agent with the Director role and designate it."
                } else {
                    "Objectives will have nowhere to go. Create an agent with the Director role and designate it."
                },
                target = AttentionTarget.Agents,
            ),
        )
    val name = director.profile.name
    val target = AttentionTarget.Agent(director.profile.id)
    return when (val availability = director.availability) {
        is AgentAvailability.Ready -> emptyList()
        AgentAvailability.Disabled -> listOf(
            AttentionItem(
                "director-disabled", StatusTone.ATTENTION, "The Director is disabled",
                "$name cannot receive objectives while it is switched off. Enable it on its page.", target,
            ),
        )
        is AgentAvailability.Unavailable -> when {
            availability.reason == UnavailableReason.MODEL_NOT_REGISTERED -> listOf(
                AttentionItem(
                    "director-model-removed", StatusTone.UNAVAILABLE, "The Director's model was removed",
                    "$name cannot receive objectives until you assign another model to it.", target,
                ),
            )
            availability.reason != UnavailableReason.NO_MODEL_ASSIGNED -> listOf(
                AttentionItem("director-unavailable", StatusTone.UNAVAILABLE, "The Director cannot run", availability.detail, target),
            )
            awaitingFirstModel -> emptyList()
            else -> listOf(
                AttentionItem(
                    "director-no-model", StatusTone.ATTENTION, "The Director has no model",
                    "$name cannot receive objectives until you assign one of your models to it.", target,
                ),
            )
        }
    }
}

private fun RegistrySnapshot.agentItems(): List<AttentionItem> {
    val others = agents.filterNot { it.isDesignatedDirector }
    val (withoutModel, otherwiseUnavailable) = others
        .filter { it.availability is AgentAvailability.Unavailable }
        .partition { it.unavailableReason == UnavailableReason.NO_MODEL_ASSIGNED }
    return buildList {
        // Faults first: they are worse than a setup that was never finished.
        otherwiseUnavailable.forEach {
            val unavailable = it.availability as AgentAvailability.Unavailable
            val modelRemoved = unavailable.reason == UnavailableReason.MODEL_NOT_REGISTERED
            add(
                AttentionItem(
                    id = "agent-unavailable:${it.profile.id}",
                    tone = StatusTone.UNAVAILABLE,
                    title = if (modelRemoved) "${it.profile.name}'s model was removed" else "${it.profile.name} cannot run",
                    detail = if (modelRemoved) "It cannot run until you assign another model to it." else unavailable.detail,
                    target = AttentionTarget.Agent(it.profile.id),
                ),
            )
        }
        if (!awaitingFirstModel) {
            when (withoutModel.size) {
                0 -> Unit
                1 -> withoutModel.single().let {
                    add(
                        AttentionItem(
                            "agent-no-model:${it.profile.id}", StatusTone.ATTENTION,
                            "${it.profile.name} has no model", "Assign one of your models to it on its page.",
                            AttentionTarget.Agent(it.profile.id),
                        ),
                    )
                }
                else -> add(
                    AttentionItem(
                        "agents-no-model", StatusTone.ATTENTION,
                        "${plural(withoutModel.size, "agent")} have no model",
                        summariseNames(withoutModel.map { it.profile.name }) + " cannot run until each has a model.",
                        AttentionTarget.UnavailableAgents,
                    ),
                )
            }
        }
    }
}

private fun RegistrySnapshot.appItems(): List<AttentionItem> = buildList {
    for (app in apps) {
        val target = AttentionTarget.AiApp(app.id)
        when (app.status) {
            AiAppStatus.NOT_INSTALLED -> add(
                AttentionItem(
                    "app-missing:${app.id}", StatusTone.ATTENTION, "${app.label} is no longer installed",
                    "It is still listed in the registry. Remove it there, or reinstall the app.", target,
                ),
            )
            AiAppStatus.DISABLED_BY_SYSTEM -> if (app.enabled) {
                add(
                    AttentionItem(
                        "app-system-disabled:${app.id}", StatusTone.ATTENTION, "${app.label} is disabled in Android",
                        "It cannot be opened until you enable it again in Android settings.", target,
                    ),
                )
            }
            AiAppStatus.INSTALLED -> Unit
        }
        if (app.isUnverified) {
            add(
                AttentionItem(
                    "app-unverified:${app.id}", StatusTone.ATTENTION, "${app.label} is unverified",
                    "It was found by its name only. Confirm that it is an AI app, or remove it.", target,
                ),
            )
        }
    }
}

private val AgentEntry.unavailableReason: UnavailableReason?
    get() = (availability as? AgentAvailability.Unavailable)?.reason

