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
 * exist, describe all tables and their indexes, match the database this build actually
 * creates, every version in it must be reachable by a migration, and a version that has been
 * released must stay as it was.
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
    fun `the current schema is exported and lists all twenty-five tables`() {
        val current = schema(AiccDatabase.VERSION)
        assertEquals(AiccDatabase.VERSION, current.getValue("version").jsonPrimitive.int)
        assertEquals(
            setOf(
                "agents", "agent_stats", "providers", "models", "ai_apps", "activity_events",
                "conversations", "conversation_entries",
                "knowledge_spaces", "memories", "memory_links", "memory_embeddings",
                "tasks", "agent_messages",
                "permission_grants", "owner_requests", "shared_folders", "operator_apps",
                "usage_hours", "work_usage", "model_prices", "routines", "routine_runs", "pickup_rules", "pickup_imports",
            ),
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
        assertEquals(setOf(listOf("updated_at"), listOf("agent_id", "updated_at")), tables.getValue("conversations").indexColumns())
        assertEquals(
            setOf(
                listOf("updated_at", "id"), listOf("scope_agent_id", "scope_type", "scope_task_id"),
                listOf("scope_agent_id", "scope_type", "updated_at", "id"), listOf("scope_space_id"),
                listOf("kind", "updated_at"), listOf("expires_at"),
            ),
            tables.getValue("memories").indexColumns(),
        )
        assertEquals(setOf(listOf("from_id"), listOf("to_id")), tables.getValue("memory_links").indexColumns())
        assertEquals(setOf(listOf("embedder_id")), tables.getValue("memory_embeddings").indexColumns())
        assertEquals(setOf(listOf("created_at", "id")), tables.getValue("knowledge_spaces").indexColumns())
        assertEquals(
            setOf(
                listOf("kind", "created_at", "id"), listOf("root_id", "created_at", "id"), listOf("status", "created_at", "id"),
                listOf("assignee_id", "updated_at", "id"),
            ),
            tables.getValue("tasks").indexColumns(),
        )
        assertEquals(setOf(listOf("root_id", "at", "id"), listOf("at", "id")), tables.getValue("agent_messages").indexColumns())
        assertEquals(
            setOf(listOf("agent_id", "capability", "constraint_key"), listOf("request_id")),
            tables.getValue("permission_grants").indexColumns(),
        )
        assertEquals(
            setOf(
                listOf("status", "created_at", "id"), listOf("conversation_id", "created_at", "id"), listOf("root_id", "created_at", "id"),
                listOf("task_id"), listOf("created_at", "id"),
            ),
            tables.getValue("owner_requests").indexColumns(),
        )
        assertEquals(setOf(listOf("tree_uri")), tables.getValue("shared_folders").indexColumns())
        assertEquals(emptySet(), tables.getValue("operator_apps").indexColumns())
        assertEquals(setOf(listOf("model_id", "hour_start"), listOf("agent_id", "hour_start")), tables.getValue("usage_hours").indexColumns())
        assertEquals(emptySet(), tables.getValue("work_usage").indexColumns())
        assertEquals(emptySet(), tables.getValue("model_prices").indexColumns())
        assertEquals(emptySet(), tables.getValue("routines").indexColumns())
        assertEquals(setOf(listOf("routine_id", "due_at", "id")), tables.getValue("routine_runs").indexColumns())
        assertEquals(setOf(listOf("folder_id")), tables.getValue("pickup_rules").indexColumns())
        assertEquals(
            setOf(listOf("rule_id", "imported_at", "content_sha256"), listOf("imported_at")),
            tables.getValue("pickup_imports").indexColumns(),
        )
    }

    @Test
    fun `an agent's recent tasks and a rule's imports are listed from an index in exactly the listing order`() {
        val tables = schema(AiccDatabase.VERSION).tables()
        fun orders(table: String, index: String) = tables.getValue(table).getValue("indices").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == index }.getValue("orders").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("ASC", "DESC", "ASC"), orders("tasks", "index_tasks_assignee_id_updated_at_id"))
        assertEquals(listOf("ASC", "DESC", "ASC"), orders("pickup_imports", "index_pickup_imports_rule_id_imported_at_content_sha256"))
    }

    @Test
    fun `the tables of schema 6 stand alone, and their keys are the ones the stores rely on`() {
        val tables = schema(AiccDatabase.VERSION).tables()
        fun JsonObject.primaryKey() = getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
        for (table in listOf("usage_hours", "work_usage", "model_prices", "routines", "routine_runs", "pickup_rules", "pickup_imports")) {
            // A usage row outlives what it counts; runs and imports go with their routine or rule by the stores' own transactions.
            assertTrue(tables.getValue(table)["foreignKeys"]?.jsonArray.isNullOrEmpty(), table)
        }
        assertEquals(listOf("hour_start", "model_id", "agent_id", "source"), tables.getValue("usage_hours").primaryKey())
        assertEquals(listOf("work_kind", "work_id", "model_id"), tables.getValue("work_usage").primaryKey())
        assertEquals(listOf("model_id"), tables.getValue("model_prices").primaryKey())
        // One record per rule and content: the de-duplication of folder pick-up.
        assertEquals(listOf("rule_id", "content_sha256"), tables.getValue("pickup_imports").primaryKey())
        val folderIndex = tables.getValue("pickup_rules").getValue("indices").jsonArray.single().jsonObject
        assertEquals(true, folderIndex.getValue("unique").jsonPrimitive.content.toBoolean(), "one rule per folder")
    }

    @Test
    fun `one tree is one folder, the operator tables stand alone, and no column is named constraint`() {
        val tables = schema(AiccDatabase.VERSION).tables()
        val treeIndex = tables.getValue("shared_folders").getValue("indices").jsonArray.single().jsonObject
        assertEquals(true, treeIndex.getValue("unique").jsonPrimitive.content.toBoolean())
        for (table in listOf("permission_grants", "owner_requests", "shared_folders", "operator_apps")) {
            // Grants and requests are the record of what the owner allowed: nothing may delete them by cascade.
            assertTrue(tables.getValue(table)["foreignKeys"]?.jsonArray.isNullOrEmpty(), table)
            val columns = tables.getValue(table).getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
            assertTrue("constraint" !in columns, "$table: `constraint` is an SQL keyword")
        }
        assertEquals(
            listOf("package_name"),
            tables.getValue("operator_apps").getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `objectives are listed from an index in exactly the listing order, and tasks and messages stand alone`() {
        val tables = schema(AiccDatabase.VERSION).tables()
        val index = tables.getValue("tasks").getValue("indices").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "index_tasks_kind_created_at_id" }
        assertEquals(listOf("ASC", "DESC", "ASC"), index.getValue("orders").jsonArray.map { it.jsonPrimitive.content })
        // Tasks of a plan are written in any order and messages may outlive their tasks; see TaskEntity.
        for (table in listOf("tasks", "agent_messages")) {
            assertTrue(tables.getValue(table)["foreignKeys"]?.jsonArray.isNullOrEmpty(), table)
            assertEquals(
                listOf("id"),
                tables.getValue(table).getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `memories are listed from an index in exactly the listing order`() {
        val index = schema(AiccDatabase.VERSION).tables().getValue("memories").getValue("indices").jsonArray
            .map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "index_memories_updated_at_id" }
        assertEquals(listOf("DESC", "ASC"), index.getValue("orders").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `an agent's memory is listed newest first from an index, and the history is a column of its own`() {
        val memories = schema(AiccDatabase.VERSION).tables().getValue("memories")
        val index = memories.getValue("indices").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "index_memories_scope_agent_id_scope_type_updated_at_id" }
        assertEquals(listOf("ASC", "ASC", "DESC", "ASC"), index.getValue("orders").jsonArray.map { it.jsonPrimitive.content })
        val history = memories.getValue("fields").jsonArray.map { it.jsonObject }.single { it.getValue("columnName").jsonPrimitive.content == "history_json" }
        assertEquals("TEXT", history.getValue("affinity").jsonPrimitive.content)
        assertEquals(true, history.getValue("notNull").jsonPrimitive.content.toBoolean())
    }

    /** The exported `createSql` of [tables] in schema [version], each table followed by its indexes. */
    private fun exportedStatements(version: Int, tables: List<String>): List<String> {
        val entities = schema(version).tables()
        return tables.flatMap { table ->
            val entity = entities.getValue(table)
            val indexes = (entity["indices"]?.jsonArray ?: emptyList()).map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
            (listOf(entity.getValue("createSql").jsonPrimitive.content) + indexes).map { it.replace("\${TABLE_NAME}", table) }
        }
    }

    @Test
    fun `the migration to version 3 runs exactly the exported statements of the tables it adds`() {
        // Version 3 is created by Room on new installations and by Migration2To3 on upgraded
        // phones; both must produce the very same tables and indexes, in parents-first order.
        assertEquals(
            exportedStatements(3, listOf("knowledge_spaces", "memories", "memory_links", "memory_embeddings")),
            Migration2To3.statements,
        )
    }

    @Test
    fun `the migration to version 4 runs exactly the exported statements of the tables it adds`() {
        assertEquals(exportedStatements(4, listOf("tasks", "agent_messages")), Migration3To4.statements)
    }

    @Test
    fun `the migration to version 5 runs exactly the exported statements of the tables it adds`() {
        assertEquals(
            exportedStatements(5, listOf("permission_grants", "owner_requests", "shared_folders", "operator_apps")),
            Migration4To5.statements,
        )
    }

    @Test
    fun `the migration to version 6 runs exactly the exported statements of the tables and the index it adds`() {
        val createTasksIndex = schema(6).tables().getValue("tasks").getValue("indices").jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "index_tasks_assignee_id_updated_at_id" }
            .getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", "tasks")
        assertEquals(
            listOf(createTasksIndex) +
                exportedStatements(6, listOf("usage_hours", "work_usage", "model_prices", "routines", "routine_runs", "pickup_rules", "pickup_imports")),
            Migration5To6.CREATE_TABLES,
        )
    }

    @Test
    fun `each column the migration to version 6 adds is defined exactly as exported, and added last`() {
        val tables = schema(6).tables()
        val added = Migration5To6.ADD_COLUMNS.map { statement ->
            val match = assertNotNull(Regex("ALTER TABLE `(\\w+)` ADD COLUMN (`(\\w+)` .+)").matchEntire(statement), statement)
            Triple(match.groupValues[1], match.groupValues[3], match.groupValues[2])
        }
        for ((table, column, definition) in added) {
            // The definition in the exported CREATE TABLE, from the column's name to the next comma.
            val createSql = tables.getValue(table).getValue("createSql").jsonPrimitive.content
            val exported = createSql.substringAfter(", `$column` ", missingDelimiterValue = "").substringBefore(", ")
            assertEquals(definition, "`$column` $exported", "$table.$column")
        }
        // SQLite appends an added column: the exported columns of each table end with the added ones, in the order added.
        for ((table, columns) in added.groupBy({ it.first }, { it.second })) {
            val fields = tables.getValue(table).getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }
            assertEquals(columns, fields.takeLast(columns.size), table)
            assertEquals(fields.dropLast(columns.size), fieldsOf(5, table), "the columns of version 5 come first, unchanged: $table")
        }
        assertEquals(
            setOf("tasks" to "retry_at", "tasks" to "result_chars", "agent_messages" to "to_space_id", "owner_requests" to "question",
                "owner_requests" to "options_json", "owner_requests" to "answer", "conversations" to "turn_started_at",
                "shared_folders" to "read_only_since"),
            added.map { it.first to it.second }.toSet(),
        )
    }

    private fun fieldsOf(version: Int, table: String): List<String> =
        schema(version).tables().getValue(table).getValue("fields").jsonArray.map { it.jsonObject.getValue("columnName").jsonPrimitive.content }

    @Test
    fun `the tables version 6 did not change are exactly those of version 5`() {
        val released = schema(5).tables()
        val current = schema(6).tables()
        for (table in released.keys - setOf("tasks", "agent_messages", "owner_requests", "conversations", "shared_folders")) {
            assertEquals(released.getValue(table)["createSql"], current.getValue(table)["createSql"], table)
            assertEquals(released.getValue(table)["indices"], current.getValue(table)["indices"], table)
        }
    }

    @Test
    fun `links and embeddings cannot outlive their memories`() {
        val tables = schema(AiccDatabase.VERSION).tables()
        fun JsonObject.cascades(): List<List<String>> = getValue("foreignKeys").jsonArray.map { it.jsonObject }.map { key ->
            assertEquals("memories", key.getValue("table").jsonPrimitive.content)
            assertEquals("CASCADE", key.getValue("onDelete").jsonPrimitive.content)
            assertEquals(listOf("id"), key.getValue("referencedColumns").jsonArray.map { it.jsonPrimitive.content })
            key.getValue("columns").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(listOf(listOf("from_id"), listOf("to_id")), tables.getValue("memory_links").cascades())
        assertEquals(listOf(listOf("memory_id")), tables.getValue("memory_embeddings").cascades())
        assertEquals(
            listOf("memory_id", "embedder_id"),
            tables.getValue("memory_embeddings").getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        // Shared memory may name a space that is not registered; see MemoryEntity.
        assertTrue(tables.getValue("memories")["foreignKeys"]?.jsonArray.isNullOrEmpty())
    }

    @Test
    fun `entries are unique per conversation and sequence number and belong to their conversation`() {
        val entries = schema(AiccDatabase.VERSION).tables().getValue("conversation_entries")
        // The primary key is the unique index that reading a conversation in order relies on.
        assertEquals(
            listOf("conversation_id", "seq"),
            entries.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
        )
        val foreignKey = entries.getValue("foreignKeys").jsonArray.single().jsonObject
        assertEquals("conversations", foreignKey.getValue("table").jsonPrimitive.content)
        assertEquals("CASCADE", foreignKey.getValue("onDelete").jsonPrimitive.content)
        assertEquals(listOf("conversation_id"), foreignKey.getValue("columns").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("id"), foreignKey.getValue("referencedColumns").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `the schema of the released version 1 is still the one that was released`() {
        // Phones hold databases with exactly this schema. Regenerating or editing 1.json would make
        // the migration tests start from a database that never existed.
        val released = schema(1)
        assertEquals("771a2500805fc58d277bd82d0535d125", released.getValue("identityHash").jsonPrimitive.content)
        assertEquals(
            setOf("agents", "agent_stats", "providers", "models", "ai_apps", "activity_events"),
            released.tables().keys,
        )
    }

    @Test
    fun `the schema of the released version 2 is still the one that was released`() {
        // Release 0.2.0 created and migrated databases to exactly this schema.
        val released = schema(2)
        assertEquals("21e1ac3cc1536e3d16cd97afc3452ea5", released.getValue("identityHash").jsonPrimitive.content)
        assertEquals(
            setOf("agents", "agent_stats", "providers", "models", "ai_apps", "activity_events", "conversations", "conversation_entries"),
            released.tables().keys,
        )
    }

    @Test
    fun `the schema of the released version 3 is still the one that was released`() {
        // Release 0.3.0 created and migrated databases to exactly this schema.
        val released = schema(3)
        assertEquals("a70d0fe70a66569c529ece795df8d1a9", released.getValue("identityHash").jsonPrimitive.content)
        assertEquals(
            setOf(
                "agents", "agent_stats", "providers", "models", "ai_apps", "activity_events", "conversations", "conversation_entries",
                "knowledge_spaces", "memories", "memory_links", "memory_embeddings",
            ),
            released.tables().keys,
        )
    }

    @Test
    fun `the schema of the released version 4 is still the one that was released`() {
        // Release 0.4.0 created and migrated databases to exactly this schema.
        val released = schema(4)
        assertEquals("7d18931fa2031a623923c0f6f5803e29", released.getValue("identityHash").jsonPrimitive.content)
        assertEquals(
            setOf(
                "agents", "agent_stats", "providers", "models", "ai_apps", "activity_events", "conversations", "conversation_entries",
                "knowledge_spaces", "memories", "memory_links", "memory_embeddings", "tasks", "agent_messages",
            ),
            released.tables().keys,
        )
    }

    @Test
    fun `the schema of the released version 5 is still the one that was released`() {
        // Releases 0.5.0 to 0.5.2 created and migrated databases to exactly this schema.
        val released = schema(5)
        assertEquals("bc4e01775e3fd8bfd8c8671981c54bda", released.getValue("identityHash").jsonPrimitive.content)
        assertEquals(
            setOf(
                "agents", "agent_stats", "providers", "models", "ai_apps", "activity_events", "conversations", "conversation_entries",
                "knowledge_spaces", "memories", "memory_links", "memory_embeddings", "tasks", "agent_messages",
                "permission_grants", "owner_requests", "shared_folders", "operator_apps",
            ),
            released.tables().keys,
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
