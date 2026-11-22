package com.aicontrolcenter.data

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration used for everything this module persists as JSON: nested columns
 * in the database, the settings value in DataStore and the vault index file.
 *
 * - `ignoreUnknownKeys` lets a row written by a newer build (or a field that was later removed)
 *   still be read.
 * - `encodeDefaults` writes every field, so what is stored never silently changes meaning when
 *   a default value changes in code.
 *
 * Together they mean that adding a field with a default to a nested object needs no migration.
 */
internal val DataJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
