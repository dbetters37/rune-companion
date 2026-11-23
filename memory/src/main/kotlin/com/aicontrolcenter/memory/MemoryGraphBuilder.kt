package com.aicontrolcenter.memory

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.GraphCluster
import com.aicontrolcenter.core.memory.GraphEdge
import com.aicontrolcenter.core.memory.GraphGenerator
import com.aicontrolcenter.core.memory.GraphNode
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryLink
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Turns memories the actor may read into a neutral [MemoryGraphSnapshot]. Pure: the service
 * has already applied permissions and the request's filters.
 *
 * - When there are more memories than [maxNodes], the ones kept are those with the highest
 *   average of importance and recency (how recently updated or used); ties keep the newer.
 * - Edges are kept only when both ends are drawn.
 * - Every node belongs to its owner cluster (agent or space; task memory also to its task)
 *   and to its kind cluster. Cluster sizes count drawn nodes.
 * - Activation, 0..1, is 70% recency and 30% use frequency, so a node "glows" when it was
 *   used lately and often.
 */
internal class MemoryGraphBuilder(private val config: MemoryConfig, private val scorer: RecallScorer) {
    fun build(
        records: List<MemoryRecord>,
        links: List<MemoryLink>,
        agentNames: Map<AgentId, String>,
        spaceNames: Map<SpaceId, String>,
        maxNodes: Int,
        alreadyTruncated: Boolean,
        now: Long,
    ): MemoryGraphSnapshot {
        val ranked = records.sortedWith(
            compareByDescending<MemoryRecord> { 0.5 * it.importance + 0.5 * scorer.recency(it, now) }
                .thenByDescending { it.updatedAt }
                .thenBy { it.id.value },
        )
        val kept = ranked.take(maxNodes.coerceAtLeast(0))
        val clusters = LinkedHashMap<String, Pair<GraphCluster, Int>>()
        fun join(id: String, kind: String, label: String): String {
            val previous = clusters[id]
            clusters[id] = (previous?.first ?: GraphCluster(id, kind, label, 0)) to ((previous?.second ?: 0) + 1)
            return id
        }

        val nodes = kept.map { record ->
            val clusterIds = ArrayList<String>(3)
            when (val scope = record.scope) {
                is MemoryScope.Agent -> clusterIds += join("cl:agent:${scope.agentId.value}", "agent", agentLabel(scope.agentId, agentNames))
                is MemoryScope.Shared -> clusterIds += join("cl:space:${scope.spaceId.value}", "space", spaceNames[scope.spaceId] ?: "Space ${scope.spaceId.value}")
                is MemoryScope.Task -> {
                    clusterIds += join("cl:agent:${scope.agentId.value}", "agent", agentLabel(scope.agentId, agentNames))
                    clusterIds += join("cl:task:${scope.taskId.value}", "task", "Task ${scope.taskId.value}")
                }
            }
            clusterIds += join("cl:kind:${record.kind.lower}", "kind", kindLabel(record.kind))
            node(record, clusterIds, now)
        }
        val drawn = kept.mapTo(HashSet()) { it.id }
        val edges = links.filter { it.from in drawn && it.to in drawn }.map(::edge)
        return MemoryGraphSnapshot(
            generatedAt = now,
            generator = GraphGenerator(app = APP, appVersion = config.appVersion, exporter = EXPORTER),
            nodes = nodes,
            edges = edges,
            clusters = clusters.values.map { (cluster, size) -> cluster.copy(size = size) },
            truncated = alreadyTruncated || kept.size < records.size,
        )
    }

    private fun node(record: MemoryRecord, clusterIds: List<String>, now: Long): GraphNode {
        val scope = record.scope
        return GraphNode(
            id = nodeId(record), kind = "memory", subkind = record.kind.lower, label = record.title, content = record.content,
            tags = record.tags.sorted(), clusterIds = clusterIds,
            ownerAgentId = when (scope) {
                is MemoryScope.Agent -> scope.agentId.value
                is MemoryScope.Task -> scope.agentId.value
                is MemoryScope.Shared -> null
            },
            visibility = when (scope) {
                is MemoryScope.Agent -> "private"
                is MemoryScope.Shared -> "shared"
                is MemoryScope.Task -> "task"
            },
            weight = record.importance.coerceIn(0.0, 1.0), confidence = record.confidence.coerceIn(0.0, 1.0),
            activation = activation(record, now),
            createdAt = record.createdAt, updatedAt = record.updatedAt, lastAccessedAt = record.lastAccessedAt,
            accessCount = record.accessCount, expiresAt = record.expiresAt, provenance = record.provenance,
            attrs = buildJsonObject {
                put("scope", scope.key)
                put("tier", if (record.tier == MemoryTier.SHORT_TERM) "short_term" else "long_term")
                put("revision", record.revision)
            },
        )
    }

    private fun edge(link: MemoryLink) = GraphEdge(
        id = "link:${link.id.value}", source = "mem:${link.from.value}", target = "mem:${link.to.value}",
        relation = link.relation.name,
        directed = link.relation != LinkRelation.RELATES_TO && link.relation != LinkRelation.CONTRADICTS,
        weight = link.weight.coerceIn(0.0, 1.0), createdAt = link.createdAt, provenance = link.provenance,
    )

    fun activation(record: MemoryRecord, now: Long): Double {
        val frequency = 1.0 - 1.0 / (1.0 + record.accessCount.coerceAtLeast(0) / 5.0)
        return (0.7 * scorer.recency(record, now) + 0.3 * frequency).coerceIn(0.0, 1.0)
    }

    private fun agentLabel(id: AgentId, names: Map<AgentId, String>) = names[id] ?: "Removed agent ${id.value}"

    private val MemoryKind.lower: String get() = name.lowercase(Locale.ROOT)

    private fun kindLabel(kind: MemoryKind): String = when (kind) {
        MemoryKind.FACT -> "Facts"
        MemoryKind.PREFERENCE -> "Preferences"
        MemoryKind.EVENT -> "Events"
        MemoryKind.FINDING -> "Findings"
        MemoryKind.DECISION -> "Decisions"
        MemoryKind.PROCEDURE -> "Procedures"
        MemoryKind.INSIGHT -> "Insights"
        MemoryKind.SUMMARY -> "Summaries"
        MemoryKind.NOTE -> "Notes"
    }

    companion object {
        const val APP = "ai-control-center"
        const val EXPORTER = "aicc-memory/1"

        fun nodeId(record: MemoryRecord) = "mem:${record.id.value}"
    }
}
