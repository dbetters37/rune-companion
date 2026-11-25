package com.aicontrolcenter.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.data.testing.InMemoryDatabaseRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Rule 7: usage records hold numbers and ids only (and the labels of models and providers): no
 * message content, no keys, no addresses. The columns of `usage_hours`, `work_usage` and
 * `model_prices` are pinned here one by one, with their types, so a column that could hold text
 * from a conversation, a key or an address cannot be added without this test changing. Text
 * columns are ids, the owner-visible labels of a model and a provider, stored enum names and the
 * currency label the owner typed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UsageColumnsTest {
    @get:Rule val db = InMemoryDatabaseRule()

    /** Column name to declared type, as the database built from the entities has them. */
    private fun columns(table: String): Map<String, String> =
        db.database.openHelper.readableDatabase.query(SimpleSQLiteQuery("PRAGMA table_info(`$table`)")).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val type = cursor.getColumnIndexOrThrow("type")
            buildMap { while (cursor.moveToNext()) put(cursor.getString(name), cursor.getString(type)) }
        }

    private val counters = listOf(
        "calls", "ok", "failed", "cancelled", "blocked", "retries", "substituted", "truncated", "no_usage", "rate_limited_waits",
        "err_auth", "err_rate_limited", "err_overloaded", "err_timeout", "err_network", "err_server", "err_invalid", "err_context",
        "err_other", "input_tokens", "output_tokens", "cached_input_tokens", "answer_ms_sum", "ttft_ms_sum", "ttft_count", "bytes_sent",
    )

    @Test
    fun `the hourly usage holds counters, times, ids and the labels of models and providers only`() {
        val text = mapOf(
            // Ids and stored enum names.
            "model_id" to "TEXT", "agent_id" to "TEXT", "source" to "TEXT", "provider_id" to "TEXT", "locality" to "TEXT",
            // What the owner sees in the model and provider lists: names, never addresses or keys.
            "model_label" to "TEXT", "provider_label" to "TEXT",
        )
        val numbers = (listOf("hour_start", "last_call_at") + counters).associateWith { "INTEGER" }
        assertEquals(text + numbers, columns("usage_hours"))
    }

    @Test
    fun `the cost of a task, objective or chat holds counters, a time and ids only`() {
        assertEquals(
            mapOf("work_kind" to "TEXT", "work_id" to "TEXT", "model_id" to "TEXT") +
                listOf("calls", "input_tokens", "output_tokens", "cached_input_tokens", "updated_at").associateWith { "INTEGER" },
            columns("work_usage"),
        )
    }

    @Test
    fun `a price holds the model's id, the amounts, the currency the owner typed and a time`() {
        assertEquals(
            mapOf("model_id" to "TEXT", "currency_label" to "TEXT") +
                listOf("input_per_mtok_micros", "output_per_mtok_micros", "cached_input_per_mtok_micros", "updated_at").associateWith { "INTEGER" },
            columns("model_prices"),
        )
    }
}
