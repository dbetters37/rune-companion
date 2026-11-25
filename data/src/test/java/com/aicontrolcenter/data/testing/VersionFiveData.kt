package com.aicontrolcenter.data.testing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.testkit.contract.OperatorSamples
import com.aicontrolcenter.testkit.contract.TaskSamples

/**
 * What a phone that ran release 0.5.x holds in the tables schema version 5 added, and in the
 * tables schema 6 changes: the owner's grants and requests, a shared folder and an operator
 * app, and a second, finished objective whose results the migration to version 6 measures (one
 * with characters outside the basic plane, one without a result, one large), with a memory shared
 * into the "system" as 0.5 stored it. Written with plain SQL under the column names of version
 * 5. Together with [VersionOneData] to [VersionFourData] this is a version 5 database with data
 * in every table.
 *
 * As in the other fixtures, the column names are spelled out and must never follow a later rename.
 */
object VersionFiveData {
    val objective = TaskSamples.objective("tsk_root5", at = 10_000, status = TaskStatus.COMPLETED)
        .copy(result = "Plan: three apps, \"ranked\" 📝🙂", statusDetail = null, finishedAt = 19_000)

    /** In creation order, the order of a tree. */
    val tasks = listOf(
        objective,
        TaskSamples.work("tsk_51", root = "tsk_root5", at = 11_000, status = TaskStatus.COMPLETED).copy(result = "Résumé 📝 of the work\nwith \"quotes\""),
        TaskSamples.work("tsk_52", root = "tsk_root5", at = 12_000, status = TaskStatus.FAILED).copy(result = null, error = "The provider stopped answering."),
        TaskSamples.work("tsk_53", root = "tsk_root5", at = 13_000, status = TaskStatus.COMPLETED).copy(result = "x".repeat(200_000) + "𝄞"),
    )

    /** What `result_chars` must hold for each of [tasks] after the migration: characters, not UTF-16 units or bytes. */
    val resultChars = mapOf("tsk_root5" to 29, "tsk_51" to 34, "tsk_52" to 0, "tsk_53" to 200_001)

    /** A memory shared into a space as release 0.5 stored it: to the system. */
    val messages = listOf(
        TaskSamples.message("msg_51", root = "tsk_root5", at = 11_500, type = MessageType.MEMORY_SHARE).copy(to = Participant.System),
    )

    val grants = listOf(
        OperatorSamples.grant("grt_51", GrantScope.ALWAYS, at = 5_000),
        OperatorSamples.grant("grt_52", GrantScope.ONCE, at = 5_100).copy(consumedAt = 5_200),
    )

    /** Oldest first: answered with a grant, refused and opened for asking again, and still waiting. */
    val requests = listOf(
        OperatorSamples.request("req_51", at = 4_000).copy(
            status = OwnerRequestStatus.APPROVED, decidedAt = 4_500, decisionScope = GrantScope.ALWAYS, decisionNote = "Only \"Reports\"",
        ),
        OperatorSamples.request("req_52", at = 4_100).copy(
            status = OwnerRequestStatus.DENIED, decidedAt = 4_600, decisionNote = "Not today", askedAgain = 2, askAgainAt = 4_700,
        ),
        OperatorSamples.request("req_53", OwnerRequestKind.APPROVAL, at = 4_200),
    )

    val folder = SharedFolder(FolderId("fld_51"), "content://com.android.externalstorage.documents/tree/primary%3ANotes", "Notes", 3_000)

    val operatorApp = OperatorApp("com.example.calendar", "Calendar \"work\" 📅", 3_100, "ef".repeat(32), ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)

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
        grants.map { it.toEntity() }.forEach {
            db.insertRow(
                "permission_grants",
                "id" to it.id, "agent_id" to it.agentId, "capability" to it.capability, "constraint_key" to it.constraintKey,
                "scope" to it.scope, "task_id" to it.taskId, "request_id" to it.requestId, "granted_at" to it.grantedAt,
                "expires_at" to it.expiresAt, "revoked_at" to it.revokedAt, "consumed_at" to it.consumedAt,
            )
        }
        requests.map { it.toEntity() }.forEach {
            db.insertRow(
                "owner_requests",
                "id" to it.id, "kind" to it.kind, "status" to it.status, "agent_id" to it.agentId, "tool_id" to it.toolId,
                "capability" to it.capability, "constraint_key" to it.constraintKey, "risk" to it.risk, "summary" to it.summary,
                "details_json" to it.detailsJson, "data_goes_to" to it.dataGoesTo, "reason" to it.reason, "task_id" to it.taskId,
                "root_id" to it.rootId, "conversation_id" to it.conversationId, "created_at" to it.createdAt,
                "expires_at" to it.expiresAt, "decided_at" to it.decidedAt, "decision_scope" to it.decisionScope,
                "decision_note" to it.decisionNote, "agent_name" to it.agentName, "tool_title" to it.toolTitle,
                "target_label" to it.targetLabel, "delegation_chain_json" to it.delegationChainJson,
                "asked_again" to it.askedAgain, "ask_again_at" to it.askAgainAt,
            )
        }
        folder.toEntity().let {
            db.insertRow(
                "shared_folders",
                "id" to it.id, "tree_uri" to it.treeUri, "display_name" to it.displayName, "added_at" to it.addedAt, "read_only" to it.readOnly,
            )
        }
        operatorApp.toEntity().let {
            db.insertRow(
                "operator_apps",
                "package_name" to it.packageName, "label" to it.label, "authorised_at" to it.authorisedAt,
                "signing_cert_sha256" to it.signingCertSha256, "action_policy" to it.actionPolicy,
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
