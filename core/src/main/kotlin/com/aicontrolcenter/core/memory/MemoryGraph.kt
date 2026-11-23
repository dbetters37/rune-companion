package com.aicontrolcenter.core.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.SpaceId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What part of memory to draw or export. Always limited further to what the actor may read. */
data class GraphRequest(
    val agentIds: Set<AgentId>? = null,
    val spaceIds: Set<SpaceId>? = null,
    val includeTaskMemory: Boolean = false,
    val since: Long? = null,
    /** Caps the node count; the most important and most recent memories are kept. */
    val maxNodes: Int = 500,
)

/**
 * A provider- and app-neutral snapshot of a memory graph ("aicc.memory-graph", version 1): the
 * contract by which this app's memory connects to graph views, its own and external ones. Ids
 * are stable strings, scores are 0..1, times are epoch milliseconds, and every node and edge
 * carries provenance. Consumers ignore fields they do not know.
 */
@Serializable
data class MemoryGraphSnapshot(
    val schema: String = SCHEMA,
    val schemaVersion: Int = SCHEMA_VERSION,
    val generatedAt: Long,
    val generator: GraphGenerator,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val clusters: List<GraphCluster>,
    /** True when [GraphRequest.maxNodes] left memories out. */
    val truncated: Boolean = false,
) {
    companion object {
        const val SCHEMA = "aicc.memory-graph"
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class GraphGenerator(val app: String, val appVersion: String, val exporter: String)

@Serializable
data class GraphNode(
    /** "mem:<memory id>". */
    val id: String,
    /** "memory" today; consumers must accept other values. */
    val kind: String,
    /** The memory kind in lower case, e.g. "fact". */
    val subkind: String,
    val label: String,
    val content: String,
    val tags: List<String>,
    val clusterIds: List<String>,
    /** The owning agent for private and task memory. */
    val ownerAgentId: String?,
    /** "private", "shared" or "task". */
    val visibility: String,
    /** Importance 0..1. */
    val weight: Double,
    val confidence: Double,
    /** How recently and often it was used, 0..1. */
    val activation: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long?,
    val accessCount: Int,
    val expiresAt: Long?,
    val provenance: Provenance,
    val attrs: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GraphEdge(
    /** "link:<link id>". */
    val id: String,
    val source: String,
    val target: String,
    /** The relation name, e.g. "RELATES_TO". */
    val relation: String,
    val directed: Boolean,
    val weight: Double,
    val createdAt: Long,
    val provenance: Provenance,
)

@Serializable
data class GraphCluster(
    /** "cl:agent:<id>", "cl:space:<id>", "cl:task:<id>" or "cl:kind:<kind>". */
    val id: String,
    /** "agent", "space", "task" or "kind". */
    val kind: String,
    val label: String,
    val size: Int,
)

/**
 * Something outside the memory engine that receives graph snapshots: the app's own graph view,
 * a file export, or an external visual memory graph. New consumers implement this; the engine
 * does not change.
 */
fun interface MemoryGraphBridge {
    suspend fun publish(snapshot: MemoryGraphSnapshot)
}
