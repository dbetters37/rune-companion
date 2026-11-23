package com.aicontrolcenter.memory

import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/** The document is not a readable "aicc.memory-graph" v1 snapshot. The message never quotes the document. */
class MemoryGraphFormatException(message: String) : IllegalArgumentException(message)

/**
 * Reads and writes [MemoryGraphSnapshot]s as "aicc.memory-graph" version 1 JSON documents, the
 * format by which memory reaches graph views outside the engine (a file export, another app).
 *
 * Writing emits every field except nulls. Reading is tolerant in the ways the format promises:
 * unknown fields are ignored, missing optional fields get neutral defaults (no tags, weight
 * 0.5, confidence 1, activation 0, updatedAt = createdAt ...), and a provenance origin this app
 * does not know (another app's vocabulary, e.g. "llm_extraction") is read as IMPORT. It is
 * strict about identity: the document must say it is an "aicc.memory-graph" of version 1.
 */
object MemoryGraphJson {
    /** A vendor media type for sharing intents and file pickers. */
    const val MIME_TYPE = "application/vnd.aicc.memory-graph+json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }
    private val pretty = Json(json) { prettyPrint = true }

    fun encode(snapshot: MemoryGraphSnapshot, prettyPrint: Boolean = false): String =
        (if (prettyPrint) pretty else json).encodeToString(MemoryGraphSnapshot.serializer(), snapshot)

    /** @throws MemoryGraphFormatException when [text] is not a v1 snapshot. */
    fun decode(text: String): MemoryGraphSnapshot {
        // kotlinx messages quote the input, which may be private memory text: report only the type of failure.
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw MemoryGraphFormatException("The memory graph document is not valid JSON.")
        }
        if (root !is JsonObject) throw MemoryGraphFormatException("The memory graph document must be a JSON object.")
        val schema = (root["schema"] as? JsonPrimitive)?.contentOrNull
        if (schema != MemoryGraphSnapshot.SCHEMA) throw MemoryGraphFormatException("This is not an ${MemoryGraphSnapshot.SCHEMA} document.")
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw MemoryGraphFormatException("The memory graph document has no schema version.")
        if (version != MemoryGraphSnapshot.SCHEMA_VERSION) {
            throw MemoryGraphFormatException("Memory graph version $version is not supported; this app reads version ${MemoryGraphSnapshot.SCHEMA_VERSION}.")
        }
        val snapshot = try {
            json.decodeFromJsonElement(MemoryGraphSnapshot.serializer(), withDefaults(root))
        } catch (e: SerializationException) {
            throw MemoryGraphFormatException("The memory graph document is missing required fields or has fields of the wrong type.")
        } catch (e: IllegalArgumentException) {
            throw MemoryGraphFormatException("The memory graph document has invalid values.")
        }
        // Every edge must resolve to two nodes, or it is dropped (the format's rule for consumers).
        val nodeIds = snapshot.nodes.mapTo(HashSet()) { it.id }
        return snapshot.copy(edges = snapshot.edges.filter { it.source in nodeIds && it.target in nodeIds })
    }

    private fun withDefaults(root: JsonObject): JsonObject {
        val generatedAt = root["generatedAt"] ?: JsonPrimitive(0L)
        return JsonObject(
            root + mapOf(
                "generator" to (root["generator"] as? JsonObject ?: JsonObject(mapOf("app" to str("unknown"), "appVersion" to str(""), "exporter" to str("")))),
                "nodes" to array(root["nodes"]) { node(it, generatedAt) },
                "edges" to array(root["edges"]) { edge(it, generatedAt) },
                "clusters" to array(root["clusters"]) { cluster(it) },
            ),
        )
    }

    private fun node(node: JsonObject, generatedAt: JsonElement): JsonObject {
        val createdAt = node["createdAt"] ?: generatedAt
        return fill(
            node,
            "subkind" to str(""), "content" to (node["label"] ?: str("")), "tags" to JsonArray(emptyList()),
            "clusterIds" to JsonArray(emptyList()), "visibility" to str("shared"), "weight" to JsonPrimitive(0.5),
            "confidence" to JsonPrimitive(1.0), "activation" to JsonPrimitive(0.0), "createdAt" to createdAt,
            "updatedAt" to createdAt, "accessCount" to JsonPrimitive(0),
        ).withProvenance(createdAt)
    }

    private fun edge(edge: JsonObject, generatedAt: JsonElement): JsonObject {
        val createdAt = edge["createdAt"] ?: generatedAt
        return fill(edge, "directed" to JsonPrimitive(true), "weight" to JsonPrimitive(1.0), "createdAt" to createdAt).withProvenance(createdAt)
    }

    private fun cluster(cluster: JsonObject): JsonObject =
        fill(cluster, "label" to (cluster["id"] ?: str("")), "size" to JsonPrimitive(0))

    /** Supplies a provenance where there is none, and maps an unknown origin to IMPORT. */
    private fun JsonObject.withProvenance(createdAt: JsonElement): JsonObject {
        val given = this["provenance"] as? JsonObject ?: JsonObject(emptyMap())
        val origin = (given["origin"] as? JsonPrimitive)?.contentOrNull?.uppercase(Locale.ROOT)
        val known = ProvenanceOrigin.entries.firstOrNull { it.name == origin } ?: ProvenanceOrigin.IMPORT
        val provenance = fill(given, "recordedAt" to createdAt) + ("origin" to str(known.name))
        return JsonObject(this + ("provenance" to JsonObject(provenance)))
    }

    private fun fill(obj: JsonObject, vararg defaults: Pair<String, JsonElement>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(obj)
        for ((key, value) in defaults) if (out[key] == null || out[key] is JsonNull) out[key] = value
        return JsonObject(out)
    }

    private fun array(element: JsonElement?, transform: (JsonObject) -> JsonObject): JsonArray {
        val items = (element as? JsonArray).orEmpty()
        // An element that is not an object cannot be a node, edge or cluster: consumers drop what they cannot read.
        return JsonArray(items.filterIsInstance<JsonObject>().map(transform))
    }

    private fun str(value: String) = JsonPrimitive(value)
}
