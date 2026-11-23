package com.aicontrolcenter.data.testing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.data.db.mapper.toEntity

/**
 * What the tables added by schema version 2 hold on a phone that ran release 0.2.0: a
 * conversation with entries, written with plain SQL under the column names of that version.
 * Together with [VersionOneData] (whose tables version 2 kept unchanged) this is a version 2
 * database with data in every table.
 *
 * As in [VersionOneData], the column names are spelled out and must never follow a later rename.
 */
object VersionTwoData {
    val conversation = Conversation(ConversationId("cnv_v2"), VersionOneData.agent.id, "Chat from 0.2.0", createdAt = 300, updatedAt = 320)
    val entries = listOf(
        ConversationEntry(conversation.id, seq = 0, ChatMessage.user("Hello"), at = 310),
        ConversationEntry(
            conversation.id, seq = 1, ChatMessage.assistant("Hi."), at = 320, modelName = "model-x", usage = TokenUsage(12, 3),
        ),
    )

    fun insertInto(db: SupportSQLiteDatabase) {
        conversation.toEntity().let {
            db.insertRow(
                "conversations",
                "id" to it.id, "agent_id" to it.agentId, "title" to it.title, "created_at" to it.createdAt,
                "updated_at" to it.updatedAt,
            )
        }
        entries.map { it.toEntity() }.forEach {
            db.insertRow(
                "conversation_entries",
                "conversation_id" to it.conversationId, "seq" to it.seq, "at" to it.at, "model_name" to it.modelName,
                "message_json" to it.messageJson, "usage_json" to it.usageJson, "error" to it.error,
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
                else -> error("no SQLite type for column $table.$name")
            }
        }
        check(insert(table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) { "could not insert into $table" }
    }
}
