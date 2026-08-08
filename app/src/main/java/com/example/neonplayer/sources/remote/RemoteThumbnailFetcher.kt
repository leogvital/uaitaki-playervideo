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
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import net.schmizz.sshj.SSHClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Bem mais curto que antes (era 15s) — inspirado no thumbnailer do VLC
 * (`modules/misc/medialibrary/Thumbnailer.cpp`, que usa 3s): uma miniatura que não sai rápido deve
 * desistir logo, não segurar por 15s uma das poucas vagas de concorrência ([MAX_CONCURRENT_THUMBNAILS_PER_SERVER])
 * enquanto outros itens visíveis esperam a vez.
 */
private const val THUMBNAIL_TIMEOUT_MS = 6_000L
private const val SESSION_IDLE_TIMEOUT_MS = 60_000L
private const val MAX_CONCURRENT_THUMBNAILS_PER_SERVER = 3

/**
 * Reaproveita a conexão/sessão SMB (autenticada) e o cliente SSH por servidor entre chamadas
 * sucessivas de [fetchRemoteThumbnail] — sem isso, cada miniatura de uma pasta com vários vídeos
 * abria conexão + autenticava do zero, sendo esse round-trip (não a leitura do frame em si) o
 * principal motivo da lentidão do preview em fontes remotas. Sessões ociosas por mais de
 * [SESSION_IDLE_TIMEOUT_MS] são fechadas na próxima chamada.
 */
private object RemoteSessionCache {
    private class Entry<T>(val value: T, @Volatile var lastUsedAt: Long = System.currentTimeMillis())

    private val smbSessions = ConcurrentHashMap<String, Entry<Session>>()
    private val sshClients = ConcurrentHashMap<String, Entry<SSHClient>>()

    @Synchronized
    fun smbSession(config: RemoteServerConfig, password: String): Session {
        evictIdle()
        smbSessions[config.id]?.let { entry ->
            if (entry.value.connection.isConnected) {
                entry.lastUsedAt = System.currentTimeMillis()
                return entry.value
            }
            runCatching { entry.value.close() }
        }
        val client = newSmbClient()
        val connection = client.connect(config.host, config.port)
        val authContext = AuthenticationContext(config.username, password.toCharArray(), null)
        val session = connection.authenticate(authContext)
        smbSessions[config.id] = Entry(session)
        return session
    }

    @Synchronized
    fun invalidateSmb(serverId: String) {
        smbSessions.remove(serverId)?.let { runCatching { it.value.close() } }
    }

    @Synchronized
    fun sshClient(config: RemoteServerConfig, password: String): SSHClient {
        evictIdle()
        sshClients[config.id]?.let { entry ->
            if (entry.value.isConnected) {
                entry.lastUsedAt = System.currentTimeMillis()
                return entry.value
            }
            runCatching { entry.value.close() }
        }
        val client = connectedSshClient(config, password)
        sshClients[config.id] = Entry(client)
        return client
    }

    @Synchronized
    fun invalidateSsh(serverId: String) {
        sshClients.remove(serverId)?.let { runCatching { it.value.close() } }
    }

    private fun evictIdle() {
        val now = System.currentTimeMillis()
        smbSessions.entries.removeIf { (_, entry) ->
            (now - entry.lastUsedAt > SESSION_IDLE_TIMEOUT_MS).also { idle -> if (idle) runCatching { entry.value.close() } }
        }
        sshClients.entries.removeIf { (_, entry) ->
            (now - entry.lastUsedAt > SESSION_IDLE_TIMEOUT_MS).also { idle -> if (idle) runCatching { entry.value.close() } }
        }
    }
}

/** Limita quantas miniaturas do mesmo servidor são buscadas ao mesmo tempo — rolar uma lista grande não deve abrir dezenas de operações simultâneas no mesmo host. */
private val thumbnailSemaphores = ConcurrentHashMap<String, Semaphore>()
private fun semaphoreFor(serverId: String): Semaphore =
    thumbnailSemaphores.computeIfAbsent(serverId) { Semaphore(MAX_CONCURRENT_THUMBNAILS_PER_SERVER) }
// 5s em vez do início do vídeo — muitos vídeos abrem com um frame preto (fade-in/logo).
private const val THUMBNAIL_FRAME_TIME_US = 5_000_000L

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
    semaphoreFor(config.id).withPermit {
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
}

/**
 * `OPTION_CLOSEST_SYNC` (frame-chave mais próximo, sem decodificar nada depois dele) em vez de
 * `OPTION_CLOSEST` (decodifica exatamente o frame de [THUMBNAIL_FRAME_TIME_US], o que exige
 * decodificar tudo desde o frame-chave anterior) — a mesma escolha do "fast seek" do thumbnailer do
 * VLC (`VLC_THUMBNAILER_SEEK_FAST` em `modules/misc/medialibrary/Thumbnailer.cpp`). Em vídeo remoto
 * cada frame decodificado é mais dados lidos pela rede; essa troca é a que mais reduz o tempo de
 * geração. Contrapartida aceita: em vídeos com frames-chave muito espaçados (>5s), pode cair de
 * volta no frame 0 (preto/logo) em vez do frame mais próximo de 5s — velocidade importa mais aqui.
 */
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

/** ~256 KB por bloco — grande o bastante pra cobrir o cabeçalho do contêiner e um frame-chave numa só ida à rede. */
private const val THUMBNAIL_READ_CHUNK_BYTES = 256 * 1024

/** Blocos mantidos simultaneamente — ver motivo na doc de [BufferedPositionedReader]. */
private const val THUMBNAIL_READ_CHUNK_CACHE_SIZE = 3

/**
 * `MediaMetadataRetriever` lê um contêiner de vídeo (moov/atoms) fazendo dezenas de leituras
 * posicionais pequenas e espalhadas pelo arquivo — sem buffer, cada uma virava uma ida e volta de
 * rede inteira (com toda a latência do protocolo SMB/SFTP), o principal motivo da miniatura remota
 * ser lenta. Cada leitura pedida é servida de um bloco de [THUMBNAIL_READ_CHUNK_BYTES] em memória
 * sempre que cai dentro de um; só busca um bloco novo na rede quando a posição pedida sai de todos
 * os blocos guardados. Guarda os últimos [THUMBNAIL_READ_CHUNK_CACHE_SIZE] blocos (não só o
 * último) porque o padrão típico de leitura desses contêineres pula entre o começo do arquivo
 * (cabeçalho) e o fim (onde o índice `moov` costuma ficar em vídeo gravado por câmera, não
 * otimizado para streaming) — com um único bloco, cada pulo descartava e buscava tudo de novo.
 */
private class BufferedPositionedReader(
    private val size: Long,
    private val readAt: (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
) {
    private class Chunk(val start: Long, val bytes: ByteArray, val length: Int)

    /** Mais recentemente usado primeiro. */
    private val chunks = ArrayDeque<Chunk>(THUMBNAIL_READ_CHUNK_CACHE_SIZE)

    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val toRead = minOf(length.toLong(), size - position).toInt()

        val hit = chunks.firstOrNull { position >= it.start && position + toRead <= it.start + it.length }
        if (hit != null) {
            System.arraycopy(hit.bytes, (position - hit.start).toInt(), buffer, offset, toRead)
            chunks.remove(hit)
            chunks.addFirst(hit)
            return toRead
        }

        val newChunkCapacity = maxOf(THUMBNAIL_READ_CHUNK_BYTES, toRead)
        val newChunkMaxLength = minOf(newChunkCapacity.toLong(), size - position).toInt()
        val newBytes = ByteArray(newChunkCapacity)
        val actuallyRead = readAt(position, newBytes, 0, newChunkMaxLength)
        if (actuallyRead <= 0) return actuallyRead

        chunks.addFirst(Chunk(position, newBytes, actuallyRead))
        if (chunks.size > THUMBNAIL_READ_CHUNK_CACHE_SIZE) chunks.removeLast()

        val copyLength = minOf(toRead, actuallyRead)
        System.arraycopy(newBytes, 0, buffer, offset, copyLength)
        return copyLength
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
    return try {
        fetchSmbThumbnailUsing(RemoteSessionCache.smbSession(config, password), shareName, video)
    } catch (e: Exception) {
        // A sessão em cache pode ter caído (servidor fechou a conexão) — descarta e tenta uma vez
        // mais com uma sessão nova antes de desistir.
        RemoteSessionCache.invalidateSmb(config.id)
        fetchSmbThumbnailUsing(RemoteSessionCache.smbSession(config, password), shareName, video)
    }
}

private fun fetchSmbThumbnailUsing(session: Session, shareName: String, video: RemoteVideo): Bitmap? {
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
            val reader = BufferedPositionedReader(size) { position, buffer, offset, length ->
                val read = file.read(buffer, position, offset, length)
                if (read == -1) -1 else read
            }
            extractFrame(PositionedMediaDataSource(size, reader::read))
        } finally {
            runCatching { file.close() }
        }
    }
}

private fun fetchSftpThumbnail(config: RemoteServerConfig, password: String, video: RemoteVideo): Bitmap? {
    return try {
        fetchSftpThumbnailUsing(RemoteSessionCache.sshClient(config, password), video)
    } catch (e: Exception) {
        RemoteSessionCache.invalidateSsh(config.id)
        fetchSftpThumbnailUsing(RemoteSessionCache.sshClient(config, password), video)
    }
}

private fun fetchSftpThumbnailUsing(ssh: SSHClient, video: RemoteVideo): Bitmap? {
    ssh.newSFTPClient().use { sftp ->
        val file = sftp.open(video.remotePath)
        return try {
            val size = file.length()
            val reader = BufferedPositionedReader(size) { position, buffer, offset, length ->
                val read = file.read(position, buffer, offset, length)
                if (read == -1) -1 else read
            }
            extractFrame(PositionedMediaDataSource(size, reader::read))
        } finally {
            runCatching { file.close() }
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
