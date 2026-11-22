package com.aicontrolcenter.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.data.DataJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.SerializationException
import java.io.IOException

/**
 * [SettingsRepository] on Preferences DataStore. The whole [FrameworkSettings] object is one
 * JSON string under one key, so a change to several fields is written atomically and a new
 * field needs no migration (a value stored before the field existed decodes with its default).
 *
 * Stored data that cannot be used never crashes the app; it degrades to defaults:
 * - a preferences file that is not a valid file at all is replaced by an empty one;
 * - a value that is not valid settings JSON reads as [FrameworkSettings] defaults, and the
 *   next [update] overwrites it;
 * - a file that cannot be read right now (I/O error) reads as defaults without being touched,
 *   and observers are switched to the real values as soon as a later read succeeds.
 */
class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<FrameworkSettings> = store.data
        .retryWhen { failure, attempt ->
            if (failure !is IOException) return@retryWhen false
            Log.w(TAG, "Settings could not be read; using defaults and trying again.", failure)
            emit(emptyPreferences())
            delay(retryDelayMillis(attempt))
            true
        }
        .map { preferences -> decode(preferences[SETTINGS_KEY]) }
        .distinctUntilChanged()

    override suspend fun get(): FrameworkSettings = settings.first()

    /**
     * Atomic: DataStore runs one edit at a time and hands each the latest stored value, so
     * concurrent updates compose instead of overwriting each other. If [transform] throws,
     * nothing is written. A failure to write is thrown to the caller as an [IOException].
     */
    override suspend fun update(transform: (FrameworkSettings) -> FrameworkSettings): FrameworkSettings {
        var updated: FrameworkSettings? = null
        store.edit { preferences ->
            val next = transform(decode(preferences[SETTINGS_KEY]))
            preferences[SETTINGS_KEY] = DataJson.encodeToString(FrameworkSettings.serializer(), next)
            updated = next
        }
        return checkNotNull(updated) { "DataStore finished an edit without running it." }
    }

    private fun decode(stored: String?): FrameworkSettings {
        if (stored == null) return FrameworkSettings()
        return try {
            DataJson.decodeFromString(FrameworkSettings.serializer(), stored)
        } catch (failure: SerializationException) {
            // The message of a parse failure can quote the stored text, so only its type is logged.
            Log.w(TAG, "Stored settings are unreadable (${failure.javaClass.simpleName}); using defaults.")
            FrameworkSettings()
        }
    }

    /** 1 s, 2 s, 4 s ... capped at 30 s between attempts to re-read an unreadable file. */
    private fun retryDelayMillis(attempt: Long): Long =
        (FIRST_RETRY_MILLIS shl attempt.coerceAtMost(MAX_BACKOFF_STEPS).toInt()).coerceAtMost(MAX_RETRY_MILLIS)

    companion object {
        const val DEFAULT_FILE_NAME = "aicc_settings"
        private const val TAG = "AiccSettings"
        private const val FIRST_RETRY_MILLIS = 1_000L
        private const val MAX_RETRY_MILLIS = 30_000L
        private const val MAX_BACKOFF_STEPS = 5L

        internal val SETTINGS_KEY = stringPreferencesKey("framework_settings")

        /**
         * Creates the repository over the preferences file [fileName] in app-private storage.
         * DataStore allows one live instance per file: create it once per process, and cancel
         * [scope] to release the file (tests do this between cases).
         */
        fun create(
            context: Context,
            scope: CoroutineScope,
            fileName: String = DEFAULT_FILE_NAME,
        ): DataStoreSettingsRepository = DataStoreSettingsRepository(createStore(context, scope, fileName))

        internal fun createStore(context: Context, scope: CoroutineScope, fileName: String): DataStore<Preferences> {
            val appContext = context.applicationContext
            return PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { corruption ->
                    Log.w(TAG, "Settings file is corrupt and was reset to defaults.", corruption)
                    emptyPreferences()
                },
                scope = scope,
                produceFile = { appContext.preferencesDataStoreFile(fileName) },
            )
        }
    }
}
