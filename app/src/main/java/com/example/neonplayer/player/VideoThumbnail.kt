package com.example.neonplayer.player

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SOURCE_LOCAL
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.RemoteVideo
import com.example.neonplayer.sources.remote.fetchRemoteThumbnail
import com.example.neonplayer.sources.thumbnailCacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val THUMBNAIL_WIDTH = 320
private const val THUMBNAIL_HEIGHT = 180

/**
 * Espera antes de *gerar* uma miniatura nova (só no cache miss — uma já em cache aparece na hora,
 * sem espera nenhuma). Ao rolar rápido por uma lista longa, cada item que passa pela tela chega a
 * compor por um instante; sem essa espera, cada um desses disparava uma geração (conexão de rede
 * inteira, no caso remoto) que era cancelada quase imediatamente ao sair de tela — desperdiçando o
 * pouco de concorrência disponível (ver [com.example.neonplayer.sources.remote.fetchRemoteThumbnail])
 * e atrasando justamente os itens em que o usuário parou para olhar. Como o Compose cancela a
 * `LaunchedEffect` de qualquer item que sai da composição (rolou pra fora da tela), um item só
 * chega a de fato gerar a miniatura se continuar visível pelos [THUMBNAIL_GENERATION_DEBOUNCE_MS]
 * inteiros — ou seja, só o que está parado na tela agora.
 */
private const val THUMBNAIL_GENERATION_DEBOUNCE_MS = 3_000L

/**
 * Miniatura de um vídeo, com cache em memória+disco ([ThumbnailStore]). Vídeos locais usam
 * [android.content.ContentResolver.loadThumbnail] (API 29+); vídeos remotos (SMB/SFTP/FTP) geram a
 * miniatura via [fetchRemoteThumbnail], que abre a conexão remota e extrai um frame — caro, mas
 * feito uma única vez por vídeo graças ao cache em disco.
 */
@Composable
fun VideoThumbnail(video: PlayableVideo, modifier: Modifier = Modifier) {
    val isLocal = video.sourceId == SOURCE_LOCAL
    val cacheKey = video.thumbnailCacheKey
    val context = LocalContext.current
    val remoteServerRepository = remember { RemoteServerRepository(context) }
    val remoteCredentialStore = remember { RemoteCredentialStore(context) }
    var bitmap by remember(cacheKey) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(cacheKey) {
        val cached = ThumbnailStore.load(context, cacheKey)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }

        delay(THUMBNAIL_GENERATION_DEBOUNCE_MS)

        val generated = if (isLocal) {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        video.playbackUri,
                        Size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT),
                        null,
                    )
                }.getOrNull()
            }
        } else {
            val remoteVideo = video as? RemoteVideo
            val config = remoteVideo?.let { remoteServerRepository.getServer(it.serverId) }
            if (remoteVideo != null && config != null) {
                val password = remoteCredentialStore.getPassword(remoteVideo.serverId).orEmpty()
                fetchRemoteThumbnail(context, config, password, remoteVideo)
            } else {
                null
            }
        }

        if (generated != null) {
            ThumbnailStore.store(context, cacheKey, generated)
            bitmap = generated
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
