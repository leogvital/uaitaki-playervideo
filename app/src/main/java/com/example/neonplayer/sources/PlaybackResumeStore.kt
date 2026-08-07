package com.example.neonplayer.sources

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.playbackResumeDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_resume")
private val RESUME_STATE_KEY = stringPreferencesKey("resume_state")

/**
 * Última posição de navegação/reprodução do usuário — fonte, pasta e (se estava no player) o
 * vídeo e posição exatos. Existe para reabrir o app depois de fechado de verdade (processo
 * encerrado pelo sistema, não só minimizado — quando o ViewModel também some) exatamente onde o
 * usuário parou: [MainActivity] lê isso na abertura do app para escolher a tela inicial.
 */
data class PlaybackResumeState(
    val sourceIsLocal: Boolean,
    val remoteServerId: String?,
    val folderPath: String,
    val videoSourceId: String?,
    val videoId: String?,
    val positionMs: Long,
)

class PlaybackResumeStore(private val context: Context) {

    val resumeStateFlow: Flow<PlaybackResumeState?> = context.playbackResumeDataStore.data.map { prefs ->
        prefs[RESUME_STATE_KEY]?.let(::decode)
    }

    suspend fun currentState(): PlaybackResumeState? = resumeStateFlow.first()

    /**
     * Chamado sempre que a listagem muda de fonte/pasta (inclusive na carga inicial). Se a
     * fonte+pasta são as mesmas já salvas, preserva o vídeo/posição em andamento; senão reseta
     * esses campos — um vídeo salvo só faz sentido junto da pasta de onde ele veio.
     */
    suspend fun saveBrowseLocation(source: SourceRef, folderPath: String) {
        context.playbackResumeDataStore.edit { prefs ->
            val current = prefs[RESUME_STATE_KEY]?.let(::decode)
            val sameLocation = current != null &&
                current.sourceIsLocal == (source is SourceRef.Local) &&
                current.remoteServerId == (source as? SourceRef.Remote)?.serverId &&
                current.folderPath == folderPath
            prefs[RESUME_STATE_KEY] = encode(
                PlaybackResumeState(
                    sourceIsLocal = source is SourceRef.Local,
                    remoteServerId = (source as? SourceRef.Remote)?.serverId,
                    folderPath = folderPath,
                    videoSourceId = if (sameLocation) current?.videoSourceId else null,
                    videoId = if (sameLocation) current?.videoId else null,
                    positionMs = if (sameLocation) current?.positionMs ?: 0L else 0L,
                ),
            )
        }
    }

    /** Atualiza só o vídeo/posição em reprodução, mantendo a fonte/pasta já salva (ver [saveBrowseLocation]). Não faz nada se ainda não há nenhum estado salvo. */
    suspend fun updatePlaybackProgress(videoSourceId: String, videoId: String, positionMs: Long) {
        context.playbackResumeDataStore.edit { prefs ->
            val current = prefs[RESUME_STATE_KEY]?.let(::decode) ?: return@edit
            prefs[RESUME_STATE_KEY] = encode(
                current.copy(videoSourceId = videoSourceId, videoId = videoId, positionMs = positionMs),
            )
        }
    }

    /** Limpa só a parte "vídeo" do estado, preservando a pasta — chamado ao sair da tela do player (o vídeo deixou de estar "em reprodução"). */
    suspend fun clearPlayback() {
        context.playbackResumeDataStore.edit { prefs ->
            val current = prefs[RESUME_STATE_KEY]?.let(::decode) ?: return@edit
            prefs[RESUME_STATE_KEY] = encode(current.copy(videoSourceId = null, videoId = null, positionMs = 0L))
        }
    }

    private fun encode(state: PlaybackResumeState): String = JSONObject().apply {
        put("sourceIsLocal", state.sourceIsLocal)
        put("remoteServerId", state.remoteServerId ?: JSONObject.NULL)
        put("folderPath", state.folderPath)
        put("videoSourceId", state.videoSourceId ?: JSONObject.NULL)
        put("videoId", state.videoId ?: JSONObject.NULL)
        put("positionMs", state.positionMs)
    }.toString()

    private fun decode(json: String): PlaybackResumeState? = try {
        val obj = JSONObject(json)
        fun stringOrNull(key: String): String? = if (obj.isNull(key)) null else obj.getString(key)
        PlaybackResumeState(
            sourceIsLocal = obj.getBoolean("sourceIsLocal"),
            remoteServerId = stringOrNull("remoteServerId"),
            folderPath = obj.getString("folderPath"),
            videoSourceId = stringOrNull("videoSourceId"),
            videoId = stringOrNull("videoId"),
            positionMs = obj.getLong("positionMs"),
        )
    } catch (e: Exception) {
        null
    }
}
