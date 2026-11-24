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
    val all: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5)
}

/**
 * Adds the phone operator's storage: `permission_grants`, `owner_requests`, `shared_folders` and
 * `operator_apps`, with their indexes. Nothing that version 4 stored is changed.
 *
 * The statements are the `createSql` of the exported schema 5, verbatim (`MigrationTest`
 * validates the result against it and `SchemaExportTest` compares the text). No table has a
 * foreign key, so their order does not matter.
 *
 * Then [COPY_AUTHORISED_APPS] carries the owner's earlier choice over: every AI app with
 * `accessibility_authorized` set becomes an operator app (package, label, stored signing
 * certificate, authorised now, asking before every action), because `operator_apps` is from now
 * on the one record of which apps may be operated through the screen. Where several AI app rows share a package (storage
 * allows it; the registry avoids it), the oldest authorised one is used, as a lookup by
 * package returns the oldest row.
 */
internal object Migration4To5 : Migration(4, 5) {
    /** Internal so `SchemaExportTest` can compare them with the exported `createSql`. */
    internal val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `permission_grants` (`id` TEXT NOT NULL, `agent_id` TEXT NOT NULL, `capability` TEXT NOT NULL, " +
            "`constraint_key` TEXT NOT NULL, `scope` TEXT NOT NULL, `task_id` TEXT, `request_id` TEXT, `granted_at` INTEGER NOT NULL, " +
            "`expires_at` INTEGER, `revoked_at` INTEGER, `consumed_at` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_permission_grants_agent_id_capability_constraint_key` ON `permission_grants` " +
            "(`agent_id`, `capability`, `constraint_key`)",
        "CREATE INDEX IF NOT EXISTS `index_permission_grants_request_id` ON `permission_grants` (`request_id`)",
        "CREATE TABLE IF NOT EXISTS `owner_requests` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `status` TEXT NOT NULL, " +
            "`agent_id` TEXT NOT NULL, `tool_id` TEXT, `capability` TEXT NOT NULL, `constraint_key` TEXT NOT NULL, `risk` TEXT NOT NULL, " +
            "`summary` TEXT NOT NULL, `details_json` TEXT NOT NULL, `data_goes_to` TEXT, `reason` TEXT, `task_id` TEXT, `root_id` TEXT, " +
            "`conversation_id` TEXT, `created_at` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, `decided_at` INTEGER, " +
            "`decision_scope` TEXT, `decision_note` TEXT, `agent_name` TEXT, `tool_title` TEXT, `target_label` TEXT, " +
            "`delegation_chain_json` TEXT NOT NULL, `asked_again` INTEGER NOT NULL DEFAULT 0, `ask_again_at` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_owner_requests_status_created_at_id` ON `owner_requests` (`status`, `created_at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `index_owner_requests_conversation_id_created_at_id` ON `owner_requests` " +
            "(`conversation_id`, `created_at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `index_owner_requests_root_id_created_at_id` ON `owner_requests` (`root_id`, `created_at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `index_owner_requests_task_id` ON `owner_requests` (`task_id`)",
        "CREATE INDEX IF NOT EXISTS `index_owner_requests_created_at_id` ON `owner_requests` (`created_at`, `id`)",
        "CREATE TABLE IF NOT EXISTS `shared_folders` (`id` TEXT NOT NULL, `tree_uri` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
            "`added_at` INTEGER NOT NULL, `read_only` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_shared_folders_tree_uri` ON `shared_folders` (`tree_uri`)",
        "CREATE TABLE IF NOT EXISTS `operator_apps` (`package_name` TEXT NOT NULL, `label` TEXT NOT NULL, " +
            "`authorised_at` INTEGER NOT NULL, `signing_cert_sha256` TEXT, `action_policy` TEXT NOT NULL, PRIMARY KEY(`package_name`))",
    )

    /**
     * Copies the authorised AI apps into `operator_apps`; the one parameter is the time of the
     * migration (epoch ms). Version 4's column names, which this step must keep using whatever
     * later happens to the entity classes. `1` is how SQLite stores `true`. Every copied app asks
     * before every screen action (`ScreenActionPolicy.ASK_EVERY_ACTION`), as a newly authorised
     * one does.
     */
    internal const val COPY_AUTHORISED_APPS =
        "INSERT INTO `operator_apps` (`package_name`, `label`, `authorised_at`, `signing_cert_sha256`, `action_policy`) " +
            "SELECT a.`package_name`, a.`label`, ?, a.`signing_cert_sha256`, 'ASK_EVERY_ACTION' FROM `ai_apps` AS a " +
            "WHERE a.`accessibility_authorized` = 1 AND NOT EXISTS (SELECT 1 FROM `ai_apps` AS b " +
            "WHERE b.`package_name` = a.`package_name` AND b.`accessibility_authorized` = 1 " +
            "AND (b.`first_seen_at` < a.`first_seen_at` OR (b.`first_seen_at` = a.`first_seen_at` AND b.`id` < a.`id`)))"

    // Room calls one or the other depending on how the database is opened; both must work.
    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
        db.execSQL(COPY_AUTHORISED_APPS, arrayOf<Any>(System.currentTimeMillis()))
    }

    override fun migrate(connection: SQLiteConnection) {
        statements.forEach(connection::execSQL)
        connection.prepare(COPY_AUTHORISED_APPS).use { statement ->
            statement.bindLong(1, System.currentTimeMillis())
            statement.step()
        }
    }
}

/**
 * Adds orchestration: `tasks` (the owner's objectives and every task under them) and
 * `agent_messages`, with their indexes. Purely additive like the earlier steps; nothing that
 * version 3 stored is read, moved or rewritten.
 *
 * The statements are the `createSql` of the exported schema 4, verbatim (`MigrationTest`
 * validates the result against it and `SchemaExportTest` compares the text). Neither table has
 * a foreign key, so their order does not matter.
 */
internal object Migration3To4 : Migration(3, 4) {
    /** Internal so `SchemaExportTest` can compare them with the exported `createSql`. */
    internal val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `root_id` TEXT NOT NULL, `parent_id` TEXT, " +
            "`kind` TEXT NOT NULL, `status` TEXT NOT NULL, `assignee_id` TEXT, `sort_order` INTEGER NOT NULL, " +
            "`attempt` INTEGER NOT NULL, `revision_round` INTEGER NOT NULL, `needs_review` INTEGER NOT NULL, " +
            "`conversation_id` TEXT, `title` TEXT NOT NULL, `instructions` TEXT NOT NULL, `status_detail` TEXT, " +
            "`result` TEXT, `error` TEXT, `criteria_json` TEXT NOT NULL, `depends_on_json` TEXT NOT NULL, " +
            "`delegation_chain_json` TEXT NOT NULL, `reviews_json` TEXT NOT NULL, `usage_json` TEXT NOT NULL, " +
            "`options_json` TEXT, `requested_by_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
            "`updated_at` INTEGER NOT NULL, `started_at` INTEGER, `finished_at` INTEGER, `deadline_at` INTEGER, " +
            "PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_tasks_kind_created_at_id` ON `tasks` (`kind` ASC, `created_at` DESC, `id` ASC)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_root_id_created_at_id` ON `tasks` (`root_id`, `created_at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `index_tasks_status_created_at_id` ON `tasks` (`status`, `created_at`, `id`)",
        "CREATE TABLE IF NOT EXISTS `agent_messages` (`id` TEXT NOT NULL, `root_id` TEXT NOT NULL, `task_id` TEXT, " +
            "`from_kind` TEXT NOT NULL, `from_agent_id` TEXT, `to_kind` TEXT NOT NULL, `to_agent_id` TEXT, " +
            "`type` TEXT NOT NULL, `body` TEXT NOT NULL, `reply_to` TEXT, `hop` INTEGER NOT NULL, `at` INTEGER NOT NULL, " +
            "PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_agent_messages_root_id_at_id` ON `agent_messages` (`root_id`, `at`, `id`)",
        "CREATE INDEX IF NOT EXISTS `index_agent_messages_at_id` ON `agent_messages` (`at`, `id`)",
    )

    // Room calls one or the other depending on how the database is opened; both must work.
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
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
