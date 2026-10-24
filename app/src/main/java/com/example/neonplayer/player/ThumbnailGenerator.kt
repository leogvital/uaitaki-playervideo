package com.example.neonplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val THUMBNAIL_WIDTH = 320
internal const val THUMBNAIL_HEIGHT = 180

/**
 * Instante do vídeo usado para a miniatura, tanto local quanto remota (ver [fetchRemoteThumbnail])
 * — 10s em vez do início, que em muitos vídeos é um frame preto (fade-in/logo).
 */
internal const val THUMBNAIL_FRAME_TIME_US = 10_000_000L

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
 * Teto global (não por pasta) de decodificações simultâneas — [thumbnailGeneratorScope] roda numa
 * escopo próprio que sobrevive a quem pediu desistir (ver docs acima), então navegar rápido por
 * várias pastas (cada uma limitando só a própria leva a 3 gerações concorrentes, ver
 * `THUMBNAIL_PREFETCH_CONCURRENCY`) podia empilhar dezenas de [MediaMetadataRetriever]/decodificações
 * remotas rodando ao mesmo tempo, sem nenhum limite entre pastas — pico de CPU/memória nativa que
 * também competia com o ExoPlayer durante a reprodução, plausível causa de crash por pressão de
 * memória. Este semáforo é a autoridade única (usado pelo único lugar que gera de fato), então vale
 * para qualquer chamador, de qualquer pasta.
 */
private const val MAX_CONCURRENT_GENERATIONS = 2
private val generationSemaphore = Semaphore(MAX_CONCURRENT_GENERATIONS)

/**
 * Busca uma miniatura do cache em disco/memória ([ThumbnailStore]); se não existir, gera (local via
 * [generateLocalThumbnail], remota via [fetchRemoteThumbnail]) e guarda no cache antes de devolver.
 * Usado tanto pela composable [VideoThumbnail] (exibição sob demanda)
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
                val generated = generationSemaphore.withPermit {
                    generateThumbnail(context, video, remoteServerRepository, remoteCredentialStore)
                }
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
        return generateLocalThumbnail(context, video.playbackUri)
    }

    val remoteVideo = video as? RemoteVideo ?: return null
    val config = remoteServerRepository.getServer(remoteVideo.serverId) ?: return null
    val password = remoteCredentialStore.getPassword(remoteVideo.serverId).orEmpty()
    return fetchRemoteThumbnail(context, config, password, remoteVideo)
}

/**
 * Extrai o frame em [THUMBNAIL_FRAME_TIME_US] diretamente, em vez de
 * [android.content.ContentResolver.loadThumbnail] (que não permite escolher o instante e não raro
 * caía num frame preto/logo do início do vídeo). `OPTION_CLOSEST_SYNC` pega o frame-chave mais
 * próximo sem decodificar nada depois dele — mesma troca de velocidade do lado remoto (ver
 * [fetchRemoteThumbnail]) — e `getScaledFrameAtTime` já decodifica direto na resolução alvo, então
 * não fica mais lento que o `loadThumbnail` anterior mesmo sem o cache do MediaProvider (que só
 * ajudava se outro app já tivesse gerado a miniatura daquele vídeo antes).
 */
private fun generateLocalThumbnail(context: Context, uri: Uri): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.getScaledFrameAtTime(
            THUMBNAIL_FRAME_TIME_US,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            THUMBNAIL_WIDTH,
            THUMBNAIL_HEIGHT,
        )
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}
