package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.registry.ProviderKind

/**
 * Settings of the Claude adapter that are not part of a provider record.
 *
 * @property refusalFallbacks Opt in to Anthropic's server-side refusal fallbacks: when a safety
 *   classifier declines a request, the API re-runs it on a substitute model instead of returning
 *   the refusal. Sent only to the models that document the feature; the model that actually
 *   answered is reported in `ChatResponse.modelName`. If the endpoint rejects the opt-in (a
 *   gateway or platform without the beta), the adapter stops sending it and repeats the call.
 * @property maxRetries How often the SDK itself repeats a call that failed with a connection
 *   error, 408, 409, 429 or 5xx. Null, the default, means never: the framework's gateway then
 *   owns every retry, re-checks local-only mode and the provider before each attempt, honours
 *   its own limit on how long a provider may ask it to wait, and records each attempt. A
 *   positive number is an explicit opt-in to retries that happen inside this adapter and are
 *   invisible to all of that: the egress log shows one transfer however many times the prompt
 *   was sent, and policy is not re-checked between the SDK's attempts.
 */
data class AnthropicOptions(
    val refusalFallbacks: Boolean = true,
    val maxRetries: Int? = null,
) {
    init {
        require(maxRetries == null || maxRetries >= 0) { "maxRetries must not be negative" }
    }
}

/**
 * Creates adapters for [ProviderKind.ANTHROPIC]. This module is the only place in the app that
 * knows the Claude API; everything it returns is expressed in core's provider-neutral types.
 *
 * An adapter performs single Messages API turns. The agent loop, tool execution and history
 * stay in the framework, so they behave the same for every provider.
 */
class AnthropicAdapterFactory(private val options: AnthropicOptions = AnthropicOptions()) : ProviderAdapterFactory {
    override val kind: ProviderKind = ProviderKind.ANTHROPIC

    /** Cheap: no connection is opened and the key is not touched until the first call. */
    override fun create(connection: ProviderConnection): ProviderAdapter {
        require(connection.provider.kind == ProviderKind.ANTHROPIC) {
            "Expected an ANTHROPIC provider but got ${connection.provider.kind}"
        }
        return AnthropicAdapter(connection, options)
    }
}
