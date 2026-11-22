package com.aicontrolcenter.providers.gemini

import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.providers.ModelCapabilityEstimator
import com.aicontrolcenter.providers.http.ProviderHttp
import com.aicontrolcenter.providers.http.array
import com.aicontrolcenter.providers.http.boolean
import com.aicontrolcenter.providers.http.int
import com.aicontrolcenter.providers.http.objects
import com.aicontrolcenter.providers.http.string
import com.aicontrolcenter.providers.http.strings
import kotlinx.serialization.json.JsonObject

/**
 * Lists the models of a Gemini API key: `GET models`, following `nextPageToken`.
 *
 * Only models whose `supportedGenerationMethods` include `generateContent` are returned; the
 * rest (embedding, prediction-only) cannot be driven by this adapter. The listing reports the
 * token limits and whether the model thinks. It says nothing about tool calling or vision, so
 * those, like the tiers, come from the name-based estimate.
 */
internal class GeminiModels(private val http: ProviderHttp) {

    suspend fun list(): List<RemoteModel> {
        val entries = ArrayList<JsonObject>()
        var pageToken: String? = null
        var pages = 0
        do {
            val query = buildMap {
                put("pageSize", PAGE_SIZE.toString())
                pageToken?.let { put("pageToken", it) }
            }
            val page = http.getJson("models", query) as? JsonObject ?: throw http.errors.malformed("the model list was not a JSON object")
            entries += page.array("models")?.objects() ?: throw http.errors.malformed("the model list had an unknown shape")
            pageToken = page.string("nextPageToken")?.takeIf { it.isNotEmpty() }
        } while (pageToken != null && ++pages < MAX_PAGES)
        return entries.filter { GENERATE in it.strings("supportedGenerationMethods") }.mapNotNull(::model)
    }

    private fun model(entry: JsonObject): RemoteModel? {
        val name = entry.string("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return null
        val estimate = ModelCapabilityEstimator.estimate(name, thinks = entry.boolean("thinking"))
        return RemoteModel(
            name = name,
            displayName = entry.string("displayName")?.takeIf { it.isNotBlank() },
            capabilities = estimate.copy(
                contextWindow = entry.int("inputTokenLimit") ?: estimate.contextWindow,
                maxOutputTokens = entry.int("outputTokenLimit"),
            ),
            ownedBy = "google",
        )
    }

    private companion object {
        const val GENERATE = "generateContent"
        const val PAGE_SIZE = 1000
        const val MAX_PAGES = 20
    }
}
