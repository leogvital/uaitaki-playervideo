package com.example.neonplayer.favorites

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

class FavoritesRepository(private val context: Context) {

    private fun keyFor(sourceId: String) = stringSetPreferencesKey("favorites_$sourceId")

    fun favoritesFlow(sourceId: String): Flow<Set<String>> =
        context.favoritesDataStore.data.map { prefs -> prefs[keyFor(sourceId)] ?: emptySet() }

    suspend fun toggleFavorite(sourceId: String, videoId: String) {
        context.favoritesDataStore.edit { prefs ->
            val key = keyFor(sourceId)
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (videoId in current) current - videoId else current + videoId
        }
    }
}
