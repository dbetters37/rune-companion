package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.agents.AgentAvailability
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
 * Derives the "needs attention" list from the registry. It is a pure function of the snapshot,
 * so the dashboard and the navigation badges can never disagree about what needs attention.
 *
 * Order: the Director first (nothing works without it), then other agents, then apps.
 * An agent the owner disabled on purpose is not reported.
 */
fun RegistrySnapshot.attentionItems(): List<AttentionItem> = buildList {
    addAll(directorItems())
    addAll(agentItems())
    addAll(appItems())
}

private fun RegistrySnapshot.directorItems(): List<AttentionItem> {
    val director = director
        ?: return listOf(
            AttentionItem(
                id = "director-missing",
                tone = StatusTone.UNAVAILABLE,
                title = "No Director is designated",
                detail = "Objectives have nowhere to go. Open an agent with the Director role and designate it.",
                target = AttentionTarget.Agents,
            ),
        )
    val name = director.profile.name
    val target = AttentionTarget.Agent(director.profile.id)
    return when (val availability = director.availability) {
        is AgentAvailability.Ready -> emptyList()
        AgentAvailability.Disabled -> listOf(
            AttentionItem(
                "director-disabled", StatusTone.UNAVAILABLE, "The Director is disabled",
                "$name cannot receive objectives while it is switched off.", target,
            ),
        )
        is AgentAvailability.Unavailable -> listOf(
            if (availability.reason == UnavailableReason.NO_MODEL_ASSIGNED) {
                AttentionItem(
                    "director-no-model", StatusTone.UNAVAILABLE, "The Director has no model",
                    "$name cannot receive objectives until a model is assigned to it.", target,
                )
            } else {
                AttentionItem(
                    "director-unavailable", StatusTone.UNAVAILABLE, "The Director cannot run", availability.detail, target,
                )
            },
        )
    }
}

private fun RegistrySnapshot.agentItems(): List<AttentionItem> {
    val others = agents.filterNot { it.isDesignatedDirector }
    val (withoutModel, otherwiseUnavailable) = others
        .filter { it.availability is AgentAvailability.Unavailable }
        .partition { it.unavailableReason == UnavailableReason.NO_MODEL_ASSIGNED }
    return buildList {
        when (withoutModel.size) {
            0 -> Unit
            1 -> withoutModel.single().let {
                add(
                    AttentionItem(
                        "agent-no-model:${it.profile.id}", StatusTone.ATTENTION,
                        "${it.profile.name} has no model assigned", "It cannot run until you assign one.",
                        AttentionTarget.Agent(it.profile.id),
                    ),
                )
            }
            else -> add(
                AttentionItem(
                    "agents-no-model", StatusTone.ATTENTION,
                    "${plural(withoutModel.size, "agent")} have no model assigned",
                    summariseNames(withoutModel.map { it.profile.name }) + " cannot run until each has a model.",
                    AttentionTarget.UnavailableAgents,
                ),
            )
        }
        otherwiseUnavailable.forEach {
            add(
                AttentionItem(
                    "agent-unavailable:${it.profile.id}", StatusTone.ATTENTION, "${it.profile.name} cannot run",
                    (it.availability as AgentAvailability.Unavailable).detail, AttentionTarget.Agent(it.profile.id),
                ),
            )
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
                    "It is still listed in the registry. Remove it, or reinstall the app.", target,
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
        if (app.accessibilityAuthorized) {
            add(
                AttentionItem(
                    "app-accessibility:${app.id}", StatusTone.ATTENTION,
                    "${app.label} is authorised for accessibility operation",
                    "You allowed operating this app's screen. Revoke it if that is no longer wanted.", target,
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
