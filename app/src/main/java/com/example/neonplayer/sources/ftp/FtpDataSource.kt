package com.example.neonplayer.sources.ftp

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.remoteRemotePath
import com.example.neonplayer.sources.remote.remoteServerId
import java.io.IOException
import java.io.InputStream
import org.apache.commons.net.ftp.FTPClient

/**
 * [androidx.media3.datasource.DataSource] que lê um vídeo hospedado num servidor FTP.
 *
 * O protocolo FTP não suporta leitura posicional dentro de uma conexão de dados já aberta como
 * SMB/SFTP — em vez disso, cada [open] reconecta e usa `REST` ([FTPClient.setRestartOffset]) para
 * pedir ao servidor que o próximo `RETR` comece na posição desejada. Isso é suficiente porque o
 * ExoPlayer chama [open] de novo a cada seek (não pede leitura posicional dentro de uma sessão já
 * aberta), mas faz de cada seek uma nova conexão — mais lento que SMB/SFTP, é uma limitação real
 * do protocolo, não desta implementação.
 */
@OptIn(UnstableApi::class)
class FtpDataSource(
    private val serverRepository: RemoteServerRepository,
    private val credentialStore: RemoteCredentialStore,
) : BaseDataSource(/* isNetwork= */ true) {

    private var client: FTPClient? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val serverId = dataSpec.uri.remoteServerId
        val remotePath = dataSpec.uri.remoteRemotePath
        if (serverId == null || remotePath == null) {
            throw IOException("URI de vídeo FTP inválida: ${dataSpec.uri}")
        }
        val config = serverRepository.getServerBlocking(serverId)
            ?: throw IOException("Servidor FTP não configurado (id=$serverId)")
        val password = credentialStore.getPassword(serverId).orEmpty()

        try {
            val client = connectedFtpClient(config, password)
            this.client = client

            val fileSize = client.listFiles(remotePath).firstOrNull { it != null }?.size
                ?: throw IOException("Arquivo remoto não encontrado: $remotePath")

            client.setRestartOffset(dataSpec.position)
            val input = client.retrieveFileStream(remotePath)
                ?: throw IOException("Falha ao iniciar leitura do arquivo remoto via FTP")
            this.input = input

            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileSize - dataSpec.position
            }
            if (bytesRemaining < 0) {
                throw IOException("Posição solicitada além do fim do arquivo remoto")
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Falha ao abrir vídeo remoto via FTP: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val currentInput = input ?: throw IOException("Fluxo FTP não está aberto")
        val bytesRead = try {
            currentInput.read(buffer, offset, bytesToRead)
        } catch (e: Exception) {
            throw IOException("Falha ao ler vídeo remoto via FTP: ${e.message}", e)
        }
        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        input?.let { runCatching { it.close() } }
        input = null
        client?.let { c ->
            runCatching { c.completePendingCommand() }
            runCatching { if (c.isConnected) c.disconnect() }
        }
        client = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
