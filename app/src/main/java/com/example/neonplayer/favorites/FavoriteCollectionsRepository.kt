package com.example.neonplayer.favorites

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.favoriteCollectionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorite_collections")
private val COLLECTIONS_KEY = stringPreferencesKey("collections")

/** Coleções de favoritos por pasta, persistidas como JSON via DataStore — mesmo padrão do [com.example.neonplayer.sources.remote.RemoteServerRepository]. */
class FavoriteCollectionsRepository(private val context: Context) {

    val collectionsFlow: Flow<List<FavoriteCollection>> = context.favoriteCollectionsDataStore.data.map { prefs ->
        prefs[COLLECTIONS_KEY]?.let(::decode) ?: emptyList()
    }

    suspend fun createCollection(name: String, folders: List<CollectionFolderRef>) {
        val collection = FavoriteCollection(id = UUID.randomUUID().toString(), name = name, folders = folders)
        context.favoriteCollectionsDataStore.edit { prefs ->
            val current = prefs[COLLECTIONS_KEY]?.let(::decode) ?: emptyList()
            prefs[COLLECTIONS_KEY] = encode(current + collection)
        }
    }

    suspend fun deleteCollection(id: String) {
        context.favoriteCollectionsDataStore.edit { prefs ->
            val current = prefs[COLLECTIONS_KEY]?.let(::decode) ?: emptyList()
            prefs[COLLECTIONS_KEY] = encode(current.filterNot { it.id == id })
        }
    }

    private fun encode(collections: List<FavoriteCollection>): String {
        val array = JSONArray()
        collections.forEach { collection ->
            val foldersArray = JSONArray()
            collection.folders.forEach { folder ->
                foldersArray.put(
                    JSONObject().apply {
                        put("sourceId", folder.sourceId)
                        put("path", folder.path)
                        put("label", folder.label)
                    },
                )
            }
            array.put(
                JSONObject().apply {
                    put("id", collection.id)
                    put("name", collection.name)
                    put("folders", foldersArray)
                },
            )
        }
        return array.toString()
    }

    private fun decode(json: String): List<FavoriteCollection> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val foldersArray = obj.getJSONArray("folders")
            val folders = (0 until foldersArray.length()).map { j ->
                val folderObj = foldersArray.getJSONObject(j)
                CollectionFolderRef(
                    sourceId = folderObj.getString("sourceId"),
                    path = folderObj.getString("path"),
                    label = folderObj.getString("label"),
                )
            }
            FavoriteCollection(id = obj.getString("id"), name = obj.getString("name"), folders = folders)
        }
    }
}
