package com.aicontrolcenter.data.db

import androidx.room.migration.Migration

/**
 * Every schema migration of [AiccDatabase], in order.
 *
 * Version 1 is the first schema, so there is nothing to migrate from yet. From the first
 * released build on, each schema change adds a `Migration(n, n + 1)` here together with the
 * exported `schemas/.../<n + 1>.json`; the database is never rebuilt destructively, and
 * `SchemaExportTest` fails when a version is exported without a migration leading to it.
 */
internal object AiccMigrations {
    val all: Array<Migration> = emptyArray()
}
