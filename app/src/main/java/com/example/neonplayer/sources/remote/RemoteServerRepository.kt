package com.example.neonplayer.sources.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val Context.remoteServersDataStore: DataStore<Preferences> by preferencesDataStore(name = "remote_servers")
private val SERVERS_KEY = stringPreferencesKey("servers")

/** Servidores remotos (SMB/SFTP/FTP) configurados pelo usuário, persistidos como JSON via DataStore. */
class RemoteServerRepository(private val context: Context) {

    val serversFlow: Flow<List<RemoteServerConfig>> = context.remoteServersDataStore.data.map { prefs ->
        prefs[SERVERS_KEY]?.let(::decode) ?: emptyList()
    }

    suspend fun getServer(id: String): RemoteServerConfig? = serversFlow.first().find { it.id == id }

    /** Leitura bloqueante para uso nos [androidx.media3.datasource.DataSource] customizados, que rodam em threads de IO do ExoPlayer. */
    fun getServerBlocking(id: String): RemoteServerConfig? = runBlocking { getServer(id) }

    suspend fun saveServer(server: RemoteServerConfig) {
        context.remoteServersDataStore.edit { prefs ->
            val current = prefs[SERVERS_KEY]?.let(::decode) ?: emptyList()
            val updated = current.filterNot { it.id == server.id } + server
            prefs[SERVERS_KEY] = encode(updated)
        }
    }

    suspend fun deleteServer(id: String) {
        context.remoteServersDataStore.edit { prefs ->
            val current = prefs[SERVERS_KEY]?.let(::decode) ?: emptyList()
            prefs[SERVERS_KEY] = encode(current.filterNot { it.id == id })
        }
    }

    private fun encode(servers: List<RemoteServerConfig>): String {
        val array = JSONArray()
        servers.forEach { server ->
            array.put(
                JSONObject().apply {
                    put("id", server.id)
                    put("protocol", server.protocol.name)
                    put("name", server.name)
                    put("host", server.host)
                    put("port", server.port)
                    put("username", server.username)
                    put("path", server.path)
                },
            )
        }
        return array.toString()
    }

    private fun decode(json: String): List<RemoteServerConfig> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            RemoteServerConfig(
                id = obj.getString("id"),
                protocol = ServerProtocol.valueOf(obj.getString("protocol")),
                name = obj.getString("name"),
                host = obj.getString("host"),
                port = obj.getInt("port"),
                username = obj.getString("username"),
                path = obj.getString("path"),
            )
        }
    }
}
