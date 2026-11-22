package com.aicontrolcenter.providers.ollama

import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.providers.ModelCapabilityEstimator
import com.aicontrolcenter.providers.http.ProviderHttp
import com.aicontrolcenter.providers.http.array
import com.aicontrolcenter.providers.http.long
import com.aicontrolcenter.providers.http.obj
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import com.aicontrolcenter.providers.http.strings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Lists the models an Ollama server has pulled: `GET /api/tags` for names and sizes, then
 * `POST /api/show` per model for what it can do.
 *
 * `/api/show` answers with a `capabilities` array ("completion", "tools", "vision",
 * "thinking", "embedding", ...), which maps directly onto tool calling, vision, chat and
 * embeddings. Reasoning and coding tiers are not something Ollama reports; they are estimated
 * from the parameter count it does report, one step higher for a model with a thinking mode.
 *
 * The context window is the `num_ctx` set in the model's Modelfile when there is one, otherwise
 * the length the model was trained with (`<architecture>.context_length`). The latter is an
 * upper bound: a server left at its defaults loads models with a smaller window, which this
 * API does not expose, so the owner may need to lower the registered value.
 *
 * A model whose details cannot be fetched is still listed, with capabilities left to estimation.
 */
internal class OllamaModels(private val http: ProviderHttp) {

    suspend fun list(): List<RemoteModel> {
        val tags = http.getJson("api/tags") as? JsonObject
        val entries = tags?.array("models")?.objects() ?: throw http.errors.malformed("the model list had an unknown shape")
        val limit = Semaphore(MAX_PARALLEL_SHOWS)
        return coroutineScope {
            entries.map { entry -> async { limit.withPermit { describe(entry) } } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun describe(entry: JsonObject): RemoteModel? {
        val name = (entry.string("name") ?: entry.string("model"))?.takeIf { it.isNotBlank() } ?: return null
        val details = try {
            http.postJson("api/show", buildJsonObject { put("model", name) }) as? JsonObject
        } catch (e: LlmException) {
            null
        }
        return RemoteModel(
            name = name,
            capabilities = details?.let { capabilities(name, entry.obj("details")?.string("parameter_size"), it) },
            sizeBytes = entry.long("size"),
        )
    }

    private fun capabilities(name: String, parameterSize: String?, details: JsonObject): ModelCapabilities? {
        // Servers older than the capabilities array report nothing usable.
        if (details.array("capabilities") == null) return null
        val reported = details.strings("capabilities")
        val estimate = ModelCapabilityEstimator.estimate(name, parameterSize, thinks = "thinking" in reported)
        val completes = "completion" in reported
        return ModelCapabilities(
            chat = completes,
            toolCalling = "tools" in reported,
            vision = "vision" in reported,
            streaming = completes,
            // Ollama constrains output to JSON for any generating model.
            jsonOutput = completes,
            embeddings = "embedding" in reported,
            reasoning = estimate.reasoning,
            coding = estimate.coding,
            contextWindow = configuredContext(details.string("parameters")) ?: trainedContext(details.obj("model_info")),
        )
    }

    private fun configuredContext(parameters: String?): Int? =
        parameters?.let { NUM_CTX.find(it) }?.groupValues?.get(1)?.toIntOrNull()

    private fun trainedContext(modelInfo: JsonObject?): Int? =
        modelInfo?.entries?.firstOrNull { it.key.endsWith(".context_length") }
            ?.let { (it.value as? JsonPrimitive)?.longOrNull }
            ?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()

    private companion object {
        const val MAX_PARALLEL_SHOWS = 4
        val NUM_CTX = Regex("(?m)^\\s*num_ctx\\s+(\\d+)")
    }
}
