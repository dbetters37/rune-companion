package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.anthropic.models.models.ModelInfo
import com.anthropic.models.models.ModelListParams
import kotlinx.coroutines.CancellationException
import okhttp3.Dns
import kotlin.jvm.optionals.getOrNull

/** One Anthropic provider connection: its chat client, model listing and health check. */
internal class AnthropicAdapter(
    connection: ProviderConnection,
    options: AnthropicOptions,
    /** Name resolution under the locality check; replaced only by tests. */
    resolver: Dns = Dns.SYSTEM,
) : ProviderAdapter {
    private val providerName = connection.provider.name
    private val sdk = SdkClient(connection, options, resolver)

    override val client: LlmClient = AnthropicLlmClient(sdk, options)

    /**
     * Lists one model, the cheapest call that proves the address, the key and the API all
     * work. Failures become a status; only cancellation leaves this function as an exception.
     */
    override suspend fun checkHealth(): ProviderHealth {
        val started = System.nanoTime()
        return try {
            // Reading the items makes the SDK parse the body, so an answer that is not a model
            // list (a captive portal, some other service) does not count as healthy.
            sdk.call { it.models().list(ModelListParams.builder().limit(1).build()).items().size }
            ProviderHealth(ConnectionStatus.ONLINE, latencyMs = (System.nanoTime() - started) / 1_000_000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            ProviderHealth(statusOf(e), e.message)
        } catch (e: Exception) {
            ProviderHealth(ConnectionStatus.ERROR, "The check of $providerName failed unexpectedly (${e.javaClass.simpleName}).")
        }
    }

    /** Every model the key can use, following the listing's pagination to the end. */
    override suspend fun listModels(): List<RemoteModel> =
        sdk.call { it.models().list(ModelListParams.builder().limit(PAGE_SIZE).build()).autoPager().map(::remoteModel) }

    override fun close() = sdk.close()

    override fun toString(): String = "AnthropicAdapter($providerName)"

    private fun statusOf(error: LlmException): ConnectionStatus = when (error) {
        is LlmException.Authentication -> ConnectionStatus.AUTH_ERROR
        is LlmException.Network, is LlmException.Timeout -> ConnectionStatus.OFFLINE
        else -> ConnectionStatus.ERROR
    }

    private companion object {
        const val PAGE_SIZE = 1000L

        /**
         * Limits and image input come from the listing. Tool calling and streaming are true for
         * every model Anthropic currently serves. JSON output stays false because Claude
         * enforces JSON only against a schema, which a neutral request cannot supply.
         */
        fun remoteModel(info: ModelInfo): RemoteModel {
            val tier = ModelTraits.tierOf(info.id())
            return RemoteModel(
                name = info.id(),
                displayName = info.displayName().takeIf { it.isNotBlank() },
                capabilities = ModelCapabilities(
                    chat = true,
                    toolCalling = true,
                    vision = info.capabilities().getOrNull()?.imageInput()?.supported() ?: true,
                    streaming = true,
                    jsonOutput = false,
                    embeddings = false,
                    reasoning = tier,
                    coding = tier,
                    contextWindow = info.maxInputTokens().getOrNull()?.toIntSaturated(),
                    maxOutputTokens = info.maxTokens().getOrNull()?.toIntSaturated(),
                ),
                ownedBy = "anthropic",
            )
        }

        fun Long.toIntSaturated(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
