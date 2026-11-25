package com.aicontrolcenter.core.agents

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.settings.FrameworkSettings

/** Whether an agent could run a task right now, and if not, exactly why. */
sealed interface AgentAvailability {
    /** Can run. [modelId] is the model that would be used (the primary or a fallback). */
    data class Ready(val modelId: ModelId, val usingFallback: Boolean) : AgentAvailability

    /** Switched off by the user. */
    data object Disabled : AgentAvailability

    data class Unavailable(val reason: UnavailableReason, val detail: String) : AgentAvailability

    val isReady: Boolean get() = this is Ready
}

enum class UnavailableReason {
    NO_MODEL_ASSIGNED,
    MODEL_NOT_REGISTERED,
    MODEL_DISABLED,
    MODEL_UNAVAILABLE,
    PROVIDER_NOT_REGISTERED,
    PROVIDER_DISABLED,
    PROVIDER_OFFLINE,
    PROVIDER_AUTH_ERROR,
    BLOCKED_BY_LOCAL_ONLY_MODE,
    ;

    /**
     * True when the reason is expected to pass by itself: the provider is only not answering
     * (Phase 6, K15). Work then waits for it instead of moving farther from the phone (rule 25).
     * Every other reason is the owner's doing (disabled, removed, key rejected, local-only) and
     * keeps the fallback order.
     */
    val isTransient: Boolean get() = this == PROVIDER_OFFLINE
}

/**
 * Resolves an agent's availability from the registry. The primary model is tried first, then
 * each fallback in order; the reason reported on failure is the primary model's.
 *
 * Rule 25 (Phase 6, K15, N8): no silent move farther from the phone. When the primary model's
 * problem is only transient ([UnavailableReason.isTransient]: its provider is not answering), a
 * fallback is used only if its provider is no farther from the phone than the primary's
 * ([Locality] order: on this phone, on the owner's network, on the internet); otherwise the
 * primary's reason is reported and the work waits. Every other reason keeps the fallback order.
 */
fun resolveAvailability(
    agent: AgentProfile,
    models: Map<ModelId, ModelDescriptor>,
    providers: Map<ProviderId, ModelProvider>,
    settings: FrameworkSettings,
): AgentAvailability {
    if (!agent.enabled) return AgentAvailability.Disabled
    val primary = agent.modelId
        ?: return AgentAvailability.Unavailable(UnavailableReason.NO_MODEL_ASSIGNED, "No model is assigned to this agent.")
    var firstProblem: AgentAvailability.Unavailable? = null
    var farthestAllowed: Locality? = null
    for ((index, candidate) in (listOf(primary) + agent.fallbackModelIds).distinct().withIndex()) {
        if (index > 0 && farthestAllowed != null) {
            val locality = models[candidate]?.let { providers[it.providerId] }?.effectiveLocality
            if (locality == null || locality > farthestAllowed) continue
        }
        val problem = modelProblem(candidate, models, providers, settings)
        if (problem == null) return AgentAvailability.Ready(candidate, usingFallback = index > 0)
        if (firstProblem == null) {
            firstProblem = problem
            if (problem.reason.isTransient) {
                farthestAllowed = models[candidate]?.let { providers[it.providerId] }?.effectiveLocality
            }
        }
    }
    return firstProblem!!
}

private fun modelProblem(
    modelId: ModelId,
    models: Map<ModelId, ModelDescriptor>,
    providers: Map<ProviderId, ModelProvider>,
    settings: FrameworkSettings,
): AgentAvailability.Unavailable? {
    fun no(reason: UnavailableReason, detail: String) = AgentAvailability.Unavailable(reason, detail)
    val model = models[modelId]
        ?: return no(UnavailableReason.MODEL_NOT_REGISTERED, "Its model is no longer registered.")
    if (!model.enabled) return no(UnavailableReason.MODEL_DISABLED, "Model ${model.displayName} is disabled.")
    if (model.status == ModelStatus.UNAVAILABLE) {
        return no(UnavailableReason.MODEL_UNAVAILABLE, "Model ${model.displayName} is unavailable.")
    }
    val provider = providers[model.providerId]
        ?: return no(UnavailableReason.PROVIDER_NOT_REGISTERED, "The provider of ${model.displayName} is no longer registered.")
    if (!provider.enabled) return no(UnavailableReason.PROVIDER_DISABLED, "Provider ${provider.name} is disabled.")
    if (settings.localOnlyMode && provider.effectiveLocality.leavesDevice) {
        return no(
            UnavailableReason.BLOCKED_BY_LOCAL_ONLY_MODE,
            "Local-only mode is on and ${provider.name} would send data off this device.",
        )
    }
    return when (provider.status) {
        ConnectionStatus.OFFLINE ->
            no(UnavailableReason.PROVIDER_OFFLINE, "Provider ${provider.name} is offline.")
        ConnectionStatus.AUTH_ERROR ->
            no(UnavailableReason.PROVIDER_AUTH_ERROR, "Provider ${provider.name} rejected its credentials.")
        // UNKNOWN and ERROR do not block: the next call decides, and its failure is reported then.
        ConnectionStatus.UNKNOWN, ConnectionStatus.ONLINE, ConnectionStatus.ERROR -> null
    }
}
