package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
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
 * True while the registry holds no model at all. An agent without a model is then the expected
 * state of an unfinished setup, not a fault the owner can fix, so it is explained once (see
 * [setupNotice]) instead of being listed per agent.
 */
val RegistrySnapshot.awaitingFirstModel: Boolean get() = models.isEmpty()

/**
 * The one sentence that explains why no agent can run while no model is registered, or null
 * when that is not the situation. Connecting a provider is not possible in this version, so
 * the sentence says what comes next rather than telling the owner to do something.
 */
fun RegistrySnapshot.setupNotice(): String? =
    if (awaitingFirstModel && agents.any { it.unavailableReason == UnavailableReason.NO_MODEL_ASSIGNED }) {
        SETUP_NOTICE
    } else {
        null
    }

const val SETUP_NOTICE = "Agents need a model to work. Connecting model providers is the next stage of this build."

/**
 * Derives the "needs attention" list from the registry. It is a pure function of the snapshot,
 * so the dashboard and the navigation badges can never disagree about what needs attention.
 *
 * Only things the owner can act on are listed. Order: the Director first (nothing works without
 * it), then other agents, then apps. An agent the owner disabled on purpose is not reported,
 * and neither is a missing model while [awaitingFirstModel].
 */
fun RegistrySnapshot.attentionItems(): List<AttentionItem> = buildList {
    addAll(directorItems())
    addAll(agentItems())
    addAll(appItems())
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
            add(
                AttentionItem(
                    "agent-unavailable:${it.profile.id}", StatusTone.UNAVAILABLE, "${it.profile.name} cannot run",
                    (it.availability as AgentAvailability.Unavailable).detail, AttentionTarget.Agent(it.profile.id),
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

/** "Researcher, Analyst and Writer" / "Researcher, Analyst and 3 others". */
private fun summariseNames(names: List<String>): String = when {
    names.size <= 1 -> names.joinToString()
    names.size <= 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    else -> names.take(2).joinToString(", ") + " and ${names.size - 2} others"
}
