package com.example.neonplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SOURCE_LOCAL
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.RemoteVideo
import com.example.neonplayer.sources.remote.fetchRemoteThumbnail
import com.example.neonplayer.sources.thumbnailCacheKey
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

internal const val THUMBNAIL_WIDTH = 320
internal const val THUMBNAIL_HEIGHT = 180

/**
 * Escopo próprio (não ligado a nenhuma tela/ViewModel) para gerar miniaturas — deliberado: uma
 * geração já em andamento (cara, principalmente a remota) deve poder terminar e alimentar o cache
 * mesmo que quem pediu (um item que saiu de tela, ou uma tela fechada) já tenha desistido. Quem
 * chama [loadOrGenerateThumbnail] só espera (`await`) o resultado; cancelar essa espera não cancela
 * a geração em si.
 */
private val thumbnailGeneratorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Gerações em andamento, por chave de cache — evita que o mesmo vídeo seja gerado 2x em paralelo quando o pré-carregamento em segundo plano (ver [com.example.neonplayer.player.VideoBrowserViewModel]) e a composable visível pedem a mesma miniatura ao mesmo tempo. */
private val inFlightGenerations = ConcurrentHashMap<String, Deferred<Bitmap?>>()

/**
 * Busca uma miniatura do cache em disco/memória ([ThumbnailStore]); se não existir, gera (local via
 * [android.content.ContentResolver.loadThumbnail], remota via [fetchRemoteThumbnail]) e guarda no
 * cache antes de devolver. Usado tanto pela composable [VideoThumbnail] (exibição sob demanda)
 * quanto pelo pré-carregamento em segundo plano da pasta atual — é o único lugar que realmente gera
 * uma miniatura nova, para que as duas chamadoras nunca dupliquem trabalho.
 */
suspend fun loadOrGenerateThumbnail(
    context: Context,
    video: PlayableVideo,
    remoteServerRepository: RemoteServerRepository,
    remoteCredentialStore: RemoteCredentialStore,
): Bitmap? {
    val cacheKey = video.thumbnailCacheKey
    ThumbnailStore.load(context, cacheKey)?.let { return it }

    val deferred = inFlightGenerations.computeIfAbsent(cacheKey) {
        thumbnailGeneratorScope.async {
            try {
                val generated = generateThumbnail(context, video, remoteServerRepository, remoteCredentialStore)
                if (generated != null) ThumbnailStore.store(context, cacheKey, generated)
                generated
            } finally {
                inFlightGenerations.remove(cacheKey)
            }
        }
    }
    return deferred.await()
}

private suspend fun generateThumbnail(
    context: Context,
    video: PlayableVideo,
    remoteServerRepository: RemoteServerRepository,
    remoteCredentialStore: RemoteCredentialStore,
): Bitmap? {
    if (video.sourceId == SOURCE_LOCAL) {
        return runCatching {
            context.contentResolver.loadThumbnail(video.playbackUri, Size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT), null)
        }.getOrNull()
    }

    val remoteVideo = video as? RemoteVideo ?: return null
    val config = remoteServerRepository.getServer(remoteVideo.serverId) ?: return null
    val password = remoteCredentialStore.getPassword(remoteVideo.serverId).orEmpty()
    return fetchRemoteThumbnail(context, config, password, remoteVideo)
}
