package com.example.neonplayer.sources

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sortPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "sort_preferences")

private val SORT_FIELD_KEY = stringPreferencesKey("last_sort_field")
private val SORT_DIRECTION_KEY = stringPreferencesKey("last_sort_direction")

private val DEFAULT_SORT_OPTION = SortOption(SortField.DATE, SortDirection.DESCENDING)

/**
 * Guarda a última ordenação escolhida pelo usuário — uma única preferência global, reaproveitada
 * em qualquer tela que liste vídeos (navegação por pasta, favoritos, coleções), para que trocar de
 * tela, tocar um vídeo ou reabrir o app não volte a ordenação para um padrão.
 */
class SortPreferences(private val context: Context) {

    val sortOptionFlow: Flow<SortOption> = context.sortPreferencesDataStore.data.map { prefs ->
        val field = prefs[SORT_FIELD_KEY]?.let { runCatching { SortField.valueOf(it) }.getOrNull() }
        val direction = prefs[SORT_DIRECTION_KEY]?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() }
        if (field != null && direction != null) SortOption(field, direction) else DEFAULT_SORT_OPTION
    }

    suspend fun setSortOption(option: SortOption) {
        context.sortPreferencesDataStore.edit { prefs ->
            prefs[SORT_FIELD_KEY] = option.field.name
            prefs[SORT_DIRECTION_KEY] = option.direction.name
        }
    }
}
