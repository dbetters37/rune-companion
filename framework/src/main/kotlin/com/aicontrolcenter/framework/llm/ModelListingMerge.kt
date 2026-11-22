package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.llm.ModelSyncReport
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.modelId
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus

/**
 * Works out what a provider's fresh model listing changes in the registry. It only computes:
 * storing the result and logging it is the caller's job.
 *
 * Models are matched by wire name. The rules:
 *  - a name seen for the first time becomes a new model, described by the listing or, when
 *    the listing says nothing, by a cautious estimate;
 *  - a known model is marked available again, and its description is refreshed only while it
 *    is still the listing's own ([ModelSource.PROVIDER_LISTING]); anything the owner typed in
 *    or edited keeps its name and capabilities;
 *  - a listed model the provider no longer reports is kept and marked unavailable, so agents
 *    that use it can say why they cannot run. Models from any other source are not judged by
 *    the listing at all.
 */
internal class ModelListingMerge(private val ids: IdGenerator) {

    class Changes(
        val added: List<ModelDescriptor>,
        val updated: List<ModelDescriptor>,
        /** Models that disappeared from the listing with this refresh. */
        val newlyMissing: List<ModelDescriptor>,
        /** Models that were already marked unavailable and are still not listed. */
        val stillMissing: List<ModelDescriptor>,
    ) {
        val isEmpty: Boolean get() = added.isEmpty() && updated.isEmpty() && newlyMissing.isEmpty()

        /** Every record that has to be written. */
        val toStore: List<ModelDescriptor> get() = added + updated + newlyMissing

        fun report() = ModelSyncReport(added, updated, newlyMissing + stillMissing)
    }

    /**
     * @param existing the provider's models as registered now
     * @param now the time stamped on changed records
     * @param firstCreationTime the creation time of the first new model; later ones follow one
     * tick apart, so that the listing's order is the registry's order
     */
    fun changes(provider: ModelProvider, existing: List<ModelDescriptor>, listed: List<RemoteModel>, now: Long, firstCreationTime: Long): Changes {
        val reported = LinkedHashMap<String, RemoteModel>()
        for (remote in listed) {
            val name = remote.name.trim()
            if (name.isNotEmpty() && name !in reported) reported[name] = remote
        }
        val known = existing.associateBy { it.modelName }
        var createdAt = firstCreationTime

        val added = ArrayList<ModelDescriptor>()
        val updated = ArrayList<ModelDescriptor>()
        for ((name, remote) in reported) {
            val current = known[name]
            if (current == null) {
                added += ModelDescriptor(
                    id = ids.modelId(), providerId = provider.id, modelName = name,
                    displayName = remote.displayName?.trim()?.ifEmpty { null } ?: name,
                    capabilities = remote.capabilities ?: ModelCapabilityEstimator.estimate(provider.kind, name),
                    source = ModelSource.PROVIDER_LISTING, sizeBytes = remote.sizeBytes, status = ModelStatus.AVAILABLE,
                    createdAt = createdAt, updatedAt = createdAt,
                )
                createdAt++
                continue
            }
            val describedByListing = current.source == ModelSource.PROVIDER_LISTING
            val refreshed = current.copy(
                displayName = remote.displayName?.trim()?.takeIf { describedByListing && it.isNotEmpty() } ?: current.displayName,
                capabilities = remote.capabilities?.takeIf { describedByListing } ?: current.capabilities,
                sizeBytes = remote.sizeBytes ?: current.sizeBytes,
                status = ModelStatus.AVAILABLE,
            )
            if (refreshed != current) updated += refreshed.copy(updatedAt = now)
        }
        val missing = existing.filter { it.source == ModelSource.PROVIDER_LISTING && it.modelName !in reported }
        return Changes(
            added = added,
            updated = updated,
            newlyMissing = missing.filter { it.status != ModelStatus.UNAVAILABLE }.map { it.copy(status = ModelStatus.UNAVAILABLE, updatedAt = now) },
            stillMissing = missing.filter { it.status == ModelStatus.UNAVAILABLE },
        )
    }
}
