package com.example.neonplayer.sources.remote

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import com.example.neonplayer.sources.ftp.connectedFtpClient
import com.example.neonplayer.sources.sftp.connectedSshClient
import com.example.neonplayer.sources.smb.newSmbClient
import com.example.neonplayer.sources.smb.splitShareAndPath
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val THUMBNAIL_TIMEOUT_MS = 15_000L
private const val THUMBNAIL_FRAME_TIME_US = 1_000_000L

/**
 * Limite de download para gerar miniatura via FTP, que ao contrário de SMB/SFTP não suporta
 * leitura posicional barata dentro de uma conexão de dados (cada seek reabriria a conexão — ver
 * [com.example.neonplayer.sources.ftp.FtpDataSource]). Baixamos só o início do arquivo: cobre o
 * caso comum de vídeo "otimizado para streaming" (moov no começo); vídeo gravado por câmera com
 * moov no fim do arquivo não gera miniatura por FTP — limitação aceita do protocolo, não um bug.
 */
private const val FTP_THUMBNAIL_DOWNLOAD_CAP_BYTES = 12L * 1024 * 1024

/**
 * Extrai um frame do vídeo remoto para servir de miniatura. SMB/SFTP usam leitura posicional
 * ([MediaDataSource]) para evitar baixar o arquivo inteiro; FTP baixa um prefixo limitado do
 * arquivo para um arquivo temporário (ver [FTP_THUMBNAIL_DOWNLOAD_CAP_BYTES]).
 */
suspend fun fetchRemoteThumbnail(
    context: Context,
    config: RemoteServerConfig,
    password: String,
    video: RemoteVideo,
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        withTimeout(THUMBNAIL_TIMEOUT_MS) {
            runInterruptible {
                when (video.protocol) {
                    ServerProtocol.SMB -> fetchSmbThumbnail(config, password, video)
                    ServerProtocol.SFTP -> fetchSftpThumbnail(config, password, video)
                    ServerProtocol.FTP -> fetchFtpThumbnail(context, config, password, video)
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: Exception) {
        null
    }
}

private fun extractFrame(dataSource: MediaDataSource): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(dataSource)
        retriever.getFrameAtTime(THUMBNAIL_FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private fun extractFrame(path: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.getFrameAtTime(THUMBNAIL_FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private class PositionedMediaDataSource(
    private val size: Long,
    private val readAt: (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
) : MediaDataSource() {
    override fun getSize(): Long = size

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val toRead = minOf(length.toLong(), size - position).toInt()
        return readAt.invoke(position, buffer, offset, toRead)
    }

    override fun close() {}
}

private fun fetchSmbThumbnail(config: RemoteServerConfig, password: String, video: RemoteVideo): Bitmap? {
    val (shareName, _) = splitShareAndPath(config.path)
    newSmbClient().use { client ->
        val connection = client.connect(config.host, config.port)
        val authContext = AuthenticationContext(config.username, password.toCharArray(), null)
        connection.authenticate(authContext).use { session ->
            val share = session.connectShare(shareName) as DiskShare
            share.use { diskShare ->
                val file = diskShare.openFile(
                    video.remotePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null,
                )
                return try {
                    val size = file.getFileInformation(FileStandardInformation::class.java).endOfFile
                    extractFrame(
                        PositionedMediaDataSource(size) { position, buffer, offset, length ->
                            val read = file.read(buffer, position, offset, length)
                            if (read == -1) -1 else read
                        },
                    )
                } finally {
                    runCatching { file.close() }
                }
            }
        }
    }
}

private fun fetchSftpThumbnail(config: RemoteServerConfig, password: String, video: RemoteVideo): Bitmap? {
    connectedSshClient(config, password).use { ssh ->
        ssh.newSFTPClient().use { sftp ->
            val file = sftp.open(video.remotePath)
            return try {
                val size = file.length()
                extractFrame(
                    PositionedMediaDataSource(size) { position, buffer, offset, length ->
                        val read = file.read(position, buffer, offset, length)
                        if (read == -1) -1 else read
                    },
                )
            } finally {
                runCatching { file.close() }
            }
        }
    }
}

private fun fetchFtpThumbnail(context: Context, config: RemoteServerConfig, password: String, video: RemoteVideo): Bitmap? {
    val client = connectedFtpClient(config, password)
    val tempFile = File.createTempFile("thumb", ".mp4", context.cacheDir)
    try {
        val input = client.retrieveFileStream(video.remotePath) ?: return null
        input.use { stream ->
            tempFile.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var totalRead = 0L
                while (totalRead < FTP_THUMBNAIL_DOWNLOAD_CAP_BYTES) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    totalRead += read
                }
            }
        }
        // Abortar a transferência é esperado já que paramos antes do fim do arquivo (arquivo maior
        // que o limite de download) — não tratar como erro.
        runCatching { client.completePendingCommand() }
        return extractFrame(tempFile.absolutePath)
    } finally {
        tempFile.delete()
        runCatching {
            if (client.isConnected) {
                client.logout()
                client.disconnect()
            }
        }
    }
}
