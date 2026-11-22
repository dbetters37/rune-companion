package com.aicontrolcenter.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

/**
 * [SettingsRepository] on Preferences DataStore. The whole [FrameworkSettings] object is one
 * JSON string under one key, so a change to several fields is written atomically and a new
 * field needs no migration (a value stored before the field existed decodes with its default).
 *
 * Stored data that is damaged never crashes the app, and costs as little as possible:
 * - a field that cannot be read takes its default while all other fields keep their values
 *   (see [SettingsCodec]);
 * - before the first [update] overwrites a value that could not be read completely, the
 *   original text is copied to a second key ([UNREADABLE_COPY_KEY]), so it can still be
 *   recovered or inspected;
 * - a value or a whole preferences file that is not readable at all gives the defaults, with
 *   local-only mode switched **on**, because the owner's choice is unknown.
 *
 * A file that merely cannot be read right now (an I/O error) is a different case and is *not*
 * papered over with defaults: the real settings may be intact. [get] and [update] fail with
 * [IOException], and [settings] keeps retrying and emits once the file is readable again.
 */
class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<FrameworkSettings> = store.data
        .retryWhen { failure, attempt ->
            if (failure !is IOException) return@retryWhen false
            Log.w(TAG, "Settings could not be read; trying again.", failure)
            delay(retryDelayMillis(attempt))
            true
        }
        .map { preferences -> decode(preferences[SETTINGS_KEY]).settings }
        .distinctUntilChanged()

    /** @throws IOException if the settings file exists but cannot be read. */
    override suspend fun get(): FrameworkSettings = decode(store.data.first()[SETTINGS_KEY]).settings

    /**
     * Atomic: DataStore runs one edit at a time and hands each the latest stored value, so
     * concurrent updates compose instead of overwriting each other. If [transform] throws,
     * nothing is written. A failure to read or write the file is thrown to the caller as an
     * [IOException], and nothing is changed.
     *
     * DataStore calls [transform] in the caller's coroutine context; only the file access
     * happens on the store's own dispatcher.
     */
    override suspend fun update(transform: (FrameworkSettings) -> FrameworkSettings): FrameworkSettings {
        var updated: FrameworkSettings? = null
        store.edit { preferences ->
            val stored = preferences[SETTINGS_KEY]
            val decoded = decode(stored)
            val next = transform(decoded.settings)
            // Saving re-encodes what could be read, which would erase the rest for good.
            if (stored != null && !decoded.complete) preferences[UNREADABLE_COPY_KEY] = stored
            preferences[SETTINGS_KEY] = SettingsCodec.encode(next)
            updated = next
        }
        return checkNotNull(updated) { "DataStore finished an edit without running it." }
    }

    private fun decode(stored: String?): SettingsCodec.Decoded =
        SettingsCodec.decode(stored).also { decoded ->
            if (!decoded.complete) {
                // Field names only: the stored text itself is never logged.
                Log.w(TAG, "Stored settings could not be read completely; defaults used for ${decoded.unreadableFields}.")
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

        /** Holds the latest stored value that could not be read completely, exactly as it was. */
        internal val UNREADABLE_COPY_KEY = stringPreferencesKey("framework_settings_unreadable")

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
                    Log.w(TAG, "Settings file is corrupt and was reset to defaults with local-only mode on.", corruption)
                    preferencesOf(SETTINGS_KEY to SettingsCodec.encode(SettingsCodec.AFTER_DATA_LOSS))
                },
                scope = scope,
                produceFile = { appContext.preferencesDataStoreFile(fileName) },
            )
        }
    }
}
