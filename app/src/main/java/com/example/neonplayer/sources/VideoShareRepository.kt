package com.example.neonplayer.sources

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.example.neonplayer.R
import com.example.neonplayer.sources.local.LocalVideo
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface VideoShareResult {
    data class Ready(val uri: Uri, val mimeType: String) : VideoShareResult
    data class Error(val message: String) : VideoShareResult
}

/** Subpasta de [Context.getCacheDir] exposta via FileProvider (ver res/xml/file_paths.xml). */
private const val SHARE_CACHE_DIR = "shared_videos"

private const val COPY_BUFFER_BYTES = 256 * 1024

/**
 * Prepara um [PlayableVideo] para ser compartilhado com outro app via `Intent.ACTION_SEND`.
 *
 * Vídeo local já é um `content://` do MediaStore, exportável diretamente. Vídeo remoto
 * (SMB/SFTP/FTP) usa um esquema de URI interno que só [com.example.neonplayer.sources.remote.NeonDataSource]
 * entende — nenhum outro app consegue abri-lo — então é baixado inteiro para um arquivo temporário
 * no cache do app (reaproveitando o mesmo [dataSourceFactory] usado na reprodução, o que evita
 * duplicar a lógica de conexão/autenticação por protocolo) e reexposto como `content://` via
 * [FileProvider].
 */
class VideoShareRepository(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
) {
    @OptIn(UnstableApi::class)
    suspend fun prepareForShare(video: PlayableVideo, onProgress: (Float?) -> Unit): VideoShareResult =
        withContext(Dispatchers.IO) {
            if (video is LocalVideo) {
                val mimeType = context.contentResolver.getType(video.uri) ?: "video/*"
                return@withContext VideoShareResult.Ready(video.uri, mimeType)
            }
            downloadForShare(video, onProgress)
        }

    @OptIn(UnstableApi::class)
    private fun downloadForShare(video: PlayableVideo, onProgress: (Float?) -> Unit): VideoShareResult {
        val shareDir = File(context.cacheDir, SHARE_CACHE_DIR).apply { mkdirs() }
        // Só um compartilhamento remoto por vez faz sentido na UI — limpa o que sobrou de uma
        // tentativa anterior em vez de deixar o cache crescer sem limite.
        shareDir.listFiles()?.forEach { it.delete() }
        val fileName = video.displayName.replace('/', '_').ifBlank { "video.mp4" }
        val destFile = File(shareDir, fileName)

        val dataSource = dataSourceFactory.createDataSource()
        return try {
            val length = dataSource.open(DataSpec(video.playbackUri))
            FileOutputStream(destFile).use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var totalRead = 0L
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                    totalRead += read
                    onProgress(if (length > 0) (totalRead.toFloat() / length).coerceIn(0f, 1f) else null)
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
            VideoShareResult.Ready(uri, "video/*")
        } catch (e: Exception) {
            destFile.delete()
            VideoShareResult.Error(context.getString(R.string.share_error_download_failed))
        } finally {
            dataSource.close()
        }
    }
}
