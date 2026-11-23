package com.aicontrolcenter.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Every schema migration of [AiccDatabase], in order.
 *
 * Each schema change adds a `Migration(n, n + 1)` here together with the exported
 * `schemas/.../<n + 1>.json`; the database is never rebuilt destructively. `SchemaExportTest`
 * fails when a version is exported without a migration leading to it, and `MigrationTest` runs
 * each step on a database that holds data.
 *
 * The migrations are written out as SQL rather than generated from the entities: a step that
 * has shipped must keep doing exactly what it did on the devices that already ran it, whatever
 * happens to the entity classes later.
 */
internal object AiccMigrations {
    val all: Array<Migration> = arrayOf(Migration1To2, Migration2To3)
}

/**
 * Adds memory: `knowledge_spaces`, `memories`, `memory_links` and `memory_embeddings`, with
 * their indexes. Purely additive like 1 -> 2; nothing that version 2 stored is touched.
 *
 * The statements are the `createSql` of the exported schema 3, verbatim (`MigrationTest`
 * validates the result against it). The parent table `memories` comes before the tables whose
 * foreign keys point at it.
 */
internal object Migration2To3 : Migration(2, 3) {
    /** Internal so `SchemaExportTest` can compare them with the exported `createSql`. */
    internal val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `knowledge_spaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
            "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_knowledge_spaces_created_at_id` ON `knowledge_spaces` (`created_at`, `id`)",
        "CREATE TABLE IF NOT EXISTS `memories` (`id` TEXT NOT NULL, `scope_type` TEXT NOT NULL, `scope_agent_id` TEXT, " +
            "`scope_space_id` TEXT, `scope_task_id` TEXT, `kind` TEXT NOT NULL, `tier` TEXT NOT NULL, `title` TEXT NOT NULL, " +
            "`content` TEXT NOT NULL, `tags_json` TEXT NOT NULL, `importance` REAL NOT NULL, `confidence` REAL NOT NULL, " +
            "`provenance_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
            "`last_accessed_at` INTEGER, `access_count` INTEGER NOT NULL, `expires_at` INTEGER, `revision` INTEGER NOT NULL, " +
            "`history_json` TEXT NOT NULL, `search_text` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_memories_updated_at_id` ON `memories` (`updated_at` DESC, `id` ASC)",
        "CREATE INDEX IF NOT EXISTS `index_memories_scope_agent_id_scope_type_scope_task_id` ON `memories` " +
            "(`scope_agent_id`, `scope_type`, `scope_task_id`)",
        "CREATE INDEX IF NOT EXISTS `index_memories_scope_agent_id_scope_type_updated_at_id` ON `memories` " +
            "(`scope_agent_id` ASC, `scope_type` ASC, `updated_at` DESC, `id` ASC)",
        "CREATE INDEX IF NOT EXISTS `index_memories_scope_space_id` ON `memories` (`scope_space_id`)",
        "CREATE INDEX IF NOT EXISTS `index_memories_kind_updated_at` ON `memories` (`kind`, `updated_at`)",
        "CREATE INDEX IF NOT EXISTS `index_memories_expires_at` ON `memories` (`expires_at`)",
        "CREATE TABLE IF NOT EXISTS `memory_links` (`id` TEXT NOT NULL, `from_id` TEXT NOT NULL, `to_id` TEXT NOT NULL, " +
            "`relation` TEXT NOT NULL, `weight` REAL NOT NULL, `provenance_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`), " +
            "FOREIGN KEY(`from_id`) REFERENCES `memories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
            "FOREIGN KEY(`to_id`) REFERENCES `memories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_memory_links_from_id` ON `memory_links` (`from_id`)",
        "CREATE INDEX IF NOT EXISTS `index_memory_links_to_id` ON `memory_links` (`to_id`)",
        "CREATE TABLE IF NOT EXISTS `memory_embeddings` (`memory_id` TEXT NOT NULL, `embedder_id` TEXT NOT NULL, " +
            "`dimensions` INTEGER NOT NULL, `vector` BLOB NOT NULL, PRIMARY KEY(`memory_id`, `embedder_id`), " +
            "FOREIGN KEY(`memory_id`) REFERENCES `memories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_memory_embeddings_embedder_id` ON `memory_embeddings` (`embedder_id`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
}

/**
 * Adds conversation storage: `conversations` and `conversation_entries`. Purely additive, so
 * nothing that version 1 stored is read, moved or rewritten.
 */
private object Migration1To2 : Migration(1, 2) {
    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `agent_id` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_conversations_updated_at` ON `conversations` (`updated_at`)",
        "CREATE INDEX IF NOT EXISTS `index_conversations_agent_id_updated_at` ON `conversations` (`agent_id`, `updated_at`)",
        "CREATE TABLE IF NOT EXISTS `conversation_entries` (`conversation_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, " +
            "`at` INTEGER NOT NULL, `model_name` TEXT, `message_json` TEXT NOT NULL, `usage_json` TEXT, `error` TEXT, " +
            "PRIMARY KEY(`conversation_id`, `seq`), " +
            "FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )

    // Room calls one or the other depending on how the database is opened; both must work.
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
}
