package com.aicontrolcenter.data.testing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.testkit.Samples
import com.aicontrolcenter.testkit.contract.TaskSamples

/**
 * What the tables added by schema version 4 hold on a phone that ran release 0.4.0: an
 * objective with its tasks and the messages exchanged on it, written with plain SQL under the
 * column names of that version. Together with [VersionOneData], [VersionTwoData] and
 * [VersionThreeData] this is a version 4 database with data in every table.
 *
 * It also holds [apps], AI apps the owner registered under release 0.4.0, so that the copy of
 * authorised apps into `operator_apps` by the migration to version 5 meets the cases it must
 * handle: a second authorised row for a package that already has one (storage allows it), and
 * an app that is not authorised.
 *
 * As in the other fixtures, the column names are spelled out and must never follow a later rename.
 */
object VersionFourData {
    val objective = TaskSamples.objective(status = TaskStatus.RUNNING)

    /** In creation order, the order of a tree. */
    val tasks = listOf(objective, TaskSamples.work("tsk_1", at = 2_000, status = TaskStatus.RUNNING), TaskSamples.work("tsk_2", at = 3_000, kind = TaskKind.REVIEW))
    val messages = listOf(TaskSamples.message("msg_1", at = 2_000), TaskSamples.message("msg_2", at = 2_500))

    /** Authorised, signed: becomes an operator app with its certificate. */
    val notes: AiApp = Samples.aiApp(id = "app_notes", packageName = "com.example.notes", firstSeenAt = 3_000)
        .copy(label = "Notes, \"daily\" 📝", signingCertSha256 = "cd".repeat(32))

    /** A later, second row of [VersionOneData.app]'s package, also authorised: the older row wins. */
    val chatbotAgain: AiApp = Samples.aiApp(id = "app_chatbot_again", firstSeenAt = 4_000).copy(label = "Chatbot (second row)", signingCertSha256 = null)

    /** Not authorised: stays out of `operator_apps`. */
    val mail: AiApp = Samples.aiApp(id = "app_mail", packageName = "com.example.mail", firstSeenAt = 5_000).copy(accessibilityAuthorized = false)

    /** Oldest first, the order AI apps are listed in. */
    val apps = listOf(notes, chatbotAgain, mail)

    fun insertInto(db: SupportSQLiteDatabase) {
        tasks.map { it.toEntity() }.forEach {
            db.insertRow(
                "tasks",
                "id" to it.id, "root_id" to it.rootId, "parent_id" to it.parentId, "kind" to it.kind, "status" to it.status,
                "assignee_id" to it.assigneeId, "sort_order" to it.sortOrder, "attempt" to it.attempt,
                "revision_round" to it.revisionRound, "needs_review" to it.needsReview, "conversation_id" to it.conversationId,
                "title" to it.title, "instructions" to it.instructions, "status_detail" to it.statusDetail, "result" to it.result,
                "error" to it.error, "criteria_json" to it.criteriaJson, "depends_on_json" to it.dependsOnJson,
                "delegation_chain_json" to it.delegationChainJson, "reviews_json" to it.reviewsJson, "usage_json" to it.usageJson,
                "options_json" to it.optionsJson, "requested_by_json" to it.requestedByJson, "created_at" to it.createdAt,
                "updated_at" to it.updatedAt, "started_at" to it.startedAt, "finished_at" to it.finishedAt, "deadline_at" to it.deadlineAt,
            )
        }
        messages.map { it.toEntity() }.forEach {
            db.insertRow(
                "agent_messages",
                "id" to it.id, "root_id" to it.rootId, "task_id" to it.taskId, "from_kind" to it.fromKind,
                "from_agent_id" to it.fromAgentId, "to_kind" to it.toKind, "to_agent_id" to it.toAgentId, "type" to it.type,
                "body" to it.body, "reply_to" to it.replyTo, "hop" to it.hop, "at" to it.at,
            )
        }
        apps.map { it.toEntity() }.forEach {
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
