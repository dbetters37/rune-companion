package com.aicontrolcenter.data.testing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.testkit.Samples

/**
 * What a phone that ran the first release holds: one or more rows in every table of schema
 * version 1, written with plain SQL under the column names of that version.
 *
 * The column names are spelled out here on purpose and must never follow a later rename in the
 * entity classes: this is the old database, and the migrations have to cope with it as it was.
 */
object VersionOneData {
    val agent = Samples.agent()
    val stats = AgentStats(
        tasksAssigned = 3, tasksCompleted = 2, modelCalls = 7, outputTokens = 6_000_000_000, lastActiveAt = 42,
    )
    val provider = Samples.provider()
    val model = Samples.model()
    val app = Samples.aiApp()

    /** Oldest first, which is also the order they were recorded in. */
    val events = listOf(Samples.event(id = "evt_1", at = 100), Samples.event(id = "evt_2", at = 200, egress = false))

    fun insertInto(db: SupportSQLiteDatabase) {
        agent.toEntity().let {
            db.insertRow(
                "agents",
                "id" to it.id, "name" to it.name, "kind" to it.kind, "description" to it.description,
                "system_instructions" to it.systemInstructions, "personality" to it.personality,
                "responsibilities_json" to it.responsibilitiesJson, "skills_json" to it.skillsJson,
                "model_id" to it.modelId, "fallback_model_ids_json" to it.fallbackModelIdsJson,
                "generation_json" to it.generationJson, "tool_ids_json" to it.toolIdsJson,
                "memory_json" to it.memoryJson, "delegation_json" to it.delegationJson, "enabled" to it.enabled,
                "color_index" to it.colorIndex, "template_id" to it.templateId, "created_at" to it.createdAt,
                "updated_at" to it.updatedAt,
            )
        }
        stats.toEntity(agent.id).let {
            db.insertRow(
                "agent_stats",
                "agent_id" to it.agentId, "tasks_assigned" to it.tasksAssigned, "tasks_completed" to it.tasksCompleted,
                "tasks_failed" to it.tasksFailed, "tasks_timed_out" to it.tasksTimedOut,
                "tasks_cancelled" to it.tasksCancelled, "reviews_passed" to it.reviewsPassed,
                "revisions_requested" to it.revisionsRequested, "reviews_rejected" to it.reviewsRejected,
                "model_calls" to it.modelCalls, "model_errors" to it.modelErrors, "tool_calls" to it.toolCalls,
                "tool_errors" to it.toolErrors, "input_tokens" to it.inputTokens, "output_tokens" to it.outputTokens,
                "total_task_duration_ms" to it.totalTaskDurationMs, "last_active_at" to it.lastActiveAt,
            )
        }
        provider.toEntity().let {
            db.insertRow(
                "providers",
                "id" to it.id, "name" to it.name, "kind" to it.kind, "base_url" to it.baseUrl,
                "api_key_alias" to it.apiKeyAlias, "extra_headers_json" to it.extraHeadersJson,
                "locality" to it.locality, "enabled" to it.enabled, "status" to it.status,
                "status_message" to it.statusMessage, "last_checked_at" to it.lastCheckedAt,
                "created_at" to it.createdAt, "updated_at" to it.updatedAt,
            )
        }
        model.toEntity().let {
            db.insertRow(
                "models",
                "id" to it.id, "provider_id" to it.providerId, "model_name" to it.modelName,
                "display_name" to it.displayName, "capabilities_json" to it.capabilitiesJson, "source" to it.source,
                "local_path" to it.localPath, "size_bytes" to it.sizeBytes, "enabled" to it.enabled,
                "status" to it.status, "notes" to it.notes, "created_at" to it.createdAt, "updated_at" to it.updatedAt,
            )
        }
        app.toEntity().let {
            db.insertRow(
                "ai_apps",
                "id" to it.id, "package_name" to it.packageName, "label" to it.label, "vendor" to it.vendor,
                "version_name" to it.versionName, "source" to it.source, "capabilities_json" to it.capabilitiesJson,
                "status" to it.status, "enabled" to it.enabled,
                "accessibility_authorized" to it.accessibilityAuthorized, "linked_provider_id" to it.linkedProviderId,
                "prompt_deep_link" to it.promptDeepLink, "notes" to it.notes,
                "first_install_time" to it.firstInstallTime, "signing_cert_sha256" to it.signingCertSha256,
                "verified_by_user" to it.verifiedByUser, "hidden" to it.hidden, "first_seen_at" to it.firstSeenAt,
                "last_seen_at" to it.lastSeenAt,
            )
        }
        // `seq`, the insertion order, is assigned by the database as it was on the phone.
        events.map { it.toEntity() }.forEach {
            db.insertRow(
                "activity_events",
                "id" to it.id, "at" to it.at, "type" to it.type, "severity" to it.severity, "agent_id" to it.agentId,
                "task_id" to it.taskId, "root_task_id" to it.rootTaskId, "title" to it.title, "detail" to it.detail,
                "data_json" to it.dataJson, "egress_json" to it.egressJson, "has_egress" to it.hasEgress,
            )
        }
    }

    private fun SupportSQLiteDatabase.insertRow(table: String, vararg columns: Pair<String, Any?>) {
        val values = ContentValues()
        for ((name, value) in columns) {
            when (value) {
                null -> values.putNull(name)
                is String -> values.put(name, value)
                is Int -> values.put(name, value)
                is Long -> values.put(name, value)
                is Boolean -> values.put(name, if (value) 1 else 0)
                else -> error("no SQLite type for column $table.$name")
            }
        }
        check(insert(table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) { "could not insert into $table" }
    }
}
