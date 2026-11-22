package com.aicontrolcenter.data.db

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the exported schema that is committed next to the code (`data/schemas`): it has to
 * exist, describe the six tables and their indexes, match the database this build actually
 * creates, and every version in it must be reachable by a migration.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SchemaExportTest {
    private val schemaDir = File(
        checkNotNull(System.getProperty("aicc.schemaDir")) { "aicc.schemaDir is set by data/build.gradle.kts" },
        AiccDatabase::class.java.name,
    )

    private fun schema(version: Int): JsonObject {
        val file = File(schemaDir, "$version.json")
        assertTrue(file.isFile, "exported schema not found: $file")
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun JsonObject.tables(): Map<String, JsonObject> =
        getValue("entities").jsonArray.map { it.jsonObject }.associateBy { it.getValue("tableName").jsonPrimitive.content }

    private fun JsonObject.indexColumns(): Set<List<String>> =
        (get("indices")?.jsonArray ?: emptyList()).map { index ->
            index.jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
        }.toSet()

    @Test
    fun `the current schema is exported and lists all six tables`() {
        val current = schema(AiccDatabase.VERSION)
        assertEquals(AiccDatabase.VERSION, current.getValue("version").jsonPrimitive.int)
        assertEquals(
            setOf("agents", "agent_stats", "providers", "models", "ai_apps", "activity_events"),
            current.tables().keys,
        )
    }

    @Test
    fun `the exported schema declares the indexes the queries rely on`() {
        val tables = schema(AiccDatabase.VERSION).tables()
        assertEquals(
            setOf(
                listOf("id"), listOf("at"), listOf("type", "at"), listOf("agent_id", "at"), listOf("task_id", "at"),
                listOf("root_task_id", "at"), listOf("severity", "at"), listOf("has_egress", "at"),
            ),
            tables.getValue("activity_events").indexColumns(),
        )
        assertEquals(setOf(listOf("created_at", "id"), listOf("model_id")), tables.getValue("agents").indexColumns())
        assertEquals(setOf(listOf("created_at", "id")), tables.getValue("providers").indexColumns())
        assertEquals(
            setOf(listOf("created_at", "id"), listOf("provider_id", "created_at", "id")),
            tables.getValue("models").indexColumns(),
        )
        assertEquals(
            setOf(listOf("first_seen_at", "id"), listOf("package_name"), listOf("linked_provider_id")),
            tables.getValue("ai_apps").indexColumns(),
        )
    }

    @Test
    fun `the committed schema is the one this build creates`() {
        // Room stores the hash of the schema it was compiled with in every database it creates.
        // If an entity changed without the schema file being regenerated, the two differ.
        val exportedHash = schema(AiccDatabase.VERSION).getValue("identityHash").jsonPrimitive.content
        val database = AiccDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>())
        try {
            val sqlite = database.openHelper.writableDatabase
            val storedHash = sqlite.query(SimpleSQLiteQuery("SELECT identity_hash FROM room_master_table")).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertEquals(exportedHash, storedHash)

            val actualTables = sqlite.query(
                SimpleSQLiteQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata'",
                ),
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertEquals(schema(AiccDatabase.VERSION).tables().keys, actualTables)
        } finally {
            database.close()
        }
    }

    @Test
    fun `every exported version has a migration to the next one`() {
        val exported = assertNotNull(schemaDir.listFiles { file -> file.extension == "json" }, "no schema directory: $schemaDir")
            .map { it.nameWithoutExtension.toInt() }
            .sorted()
        assertEquals((1..AiccDatabase.VERSION).toList(), exported, "each version from 1 to the current one must be exported")
        val steps = AiccMigrations.all.map { it.startVersion to it.endVersion }.toSet()
        for (from in 1 until AiccDatabase.VERSION) {
            assertTrue((from to from + 1) in steps, "no migration from schema version $from to ${from + 1}")
        }
        assertEquals(AiccDatabase.VERSION - 1, steps.size, "unexpected extra or missing migrations: $steps")
    }
}
