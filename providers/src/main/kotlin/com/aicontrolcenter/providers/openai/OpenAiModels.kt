package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.providers.ModelCapabilityEstimator
import com.aicontrolcenter.providers.http.array
import com.aicontrolcenter.providers.http.boolean
import com.aicontrolcenter.providers.http.int
import com.aicontrolcenter.providers.http.long
import com.aicontrolcenter.providers.http.obj
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import com.aicontrolcenter.providers.http.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads `GET {base}/models`.
 *
 * The protocol itself defines only `id` and `owned_by`, and for a server that sends no more
 * the capabilities are left null, to be estimated from the name. Several services add
 * metadata, which is used when present:
 *  - OpenRouter: `context_length`, `top_provider.max_completion_tokens`,
 *    `architecture.input_modalities` / `output_modalities`, `supported_parameters`.
 *  - Mistral: `max_context_length` and a `capabilities` object.
 *  - Groq: `context_window`, `max_completion_tokens`.
 *  - vLLM: `max_model_len`. llama.cpp: `meta.size` (the file size).
 *
 * Reported facts are laid over the name-based estimate, so a field no service reports (the
 * reasoning and coding tiers, say) is still filled in.
 */
internal object OpenAiModels {

    /** @return null when [root] is not a model list in any known shape */
    fun parse(root: JsonElement): List<RemoteModel>? {
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root.array("data") ?: root.array("models")
            else -> null
        } ?: return null
        return entries.objects().mapNotNull(::model)
    }

    private fun model(entry: JsonObject): RemoteModel? {
        val id = (entry.string("id") ?: entry.string("name"))?.takeIf { it.isNotBlank() } ?: return null
        return RemoteModel(
            name = id,
            displayName = (entry.string("name") ?: entry.string("display_name"))?.takeIf { it.isNotBlank() && it != id },
            capabilities = capabilities(entry, id),
            ownedBy = entry.string("owned_by")?.takeIf { it.isNotBlank() },
            sizeBytes = entry.obj("meta")?.long("size"),
        )
    }

    private fun capabilities(entry: JsonObject, id: String): ModelCapabilities? {
        val architecture = entry.obj("architecture")
        val topProvider = entry.obj("top_provider")
        val flags = entry.obj("capabilities")
        val parameters = entry.array("supported_parameters")?.let { entry.strings("supported_parameters") }
        val inputs = architecture?.array("input_modalities")?.let { architecture.strings("input_modalities") }
        val outputs = architecture?.array("output_modalities")?.let { architecture.strings("output_modalities") }
        val context = entry.int("context_length") ?: topProvider?.int("context_length") ?: entry.int("context_window")
            ?: entry.int("max_context_length") ?: entry.int("max_model_len")
        val maxOutput = topProvider?.int("max_completion_tokens") ?: entry.int("max_completion_tokens")
        if (parameters == null && inputs == null && outputs == null && flags == null && context == null && maxOutput == null) return null

        val estimate = ModelCapabilityEstimator.estimate(id)
        val tools = parameters?.contains("tools") ?: flags?.boolean("function_calling") ?: estimate.toolCalling
        return estimate.copy(
            chat = estimate.chat && outputs?.contains("text") != false && flags?.boolean("completion_chat") != false,
            toolCalling = tools,
            vision = inputs?.contains("image") ?: flags?.boolean("vision") ?: estimate.vision,
            jsonOutput = parameters?.let { "response_format" in it || "structured_outputs" in it } ?: (estimate.jsonOutput || tools),
            contextWindow = context ?: estimate.contextWindow,
            maxOutputTokens = maxOutput ?: estimate.maxOutputTokens,
        )
    }
}
