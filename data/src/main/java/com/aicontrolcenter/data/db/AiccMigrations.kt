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
    val all: Array<Migration> = arrayOf(Migration1To2)
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
