package com.outofthewhale.booklight

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Reading positions, kept across restarts.
 *
 * One JSON document under one key. The transformations live on [Progress] and
 * are pure; this only carries them to disk.
 */
class ProgressStore(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey(KEY)
    private val json = Json { ignoreUnknownKeys = true }

    val progress: Flow<Progress> = dataStore.data.map { decode(it[key]) }

    suspend fun read(): Progress = progress.first()

    suspend fun update(transform: (Progress) -> Progress) {
        dataStore.edit { preferences ->
            val next = transform(decode(preferences[key]))
            preferences[key] = json.encodeToString(next)
        }
    }

    suspend fun save(bookId: String, position: Position, at: Long) =
        update { it.with(bookId, position, at) }

    /**
     * A document that will not parse is treated as no progress rather than
     * thrown. Losing a bookmark is a small harm; refusing to open the library
     * is a large one.
     */
    private fun decode(raw: String?): Progress {
        if (raw.isNullOrBlank()) return Progress()
        return runCatching { json.decodeFromString<Progress>(raw) }.getOrDefault(Progress())
    }

    private companion object {
        const val KEY = "progress"
    }
}
