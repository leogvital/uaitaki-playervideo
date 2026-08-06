package com.example.neonplayer.sources.sftp

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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient

/**
 * [androidx.media3.datasource.DataSource] que lê um vídeo hospedado num servidor SFTP, usando
 * leitura posicional ([RemoteFile.read] com offset) para suportar seek/scrubbing do player sem
 * baixar o arquivo inteiro.
 */
@OptIn(UnstableApi::class)
class SftpDataSource(
    private val serverRepository: RemoteServerRepository,
    private val credentialStore: RemoteCredentialStore,
) : BaseDataSource(/* isNetwork= */ true) {

    private var ssh: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var file: RemoteFile? = null
    private var uri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val serverId = dataSpec.uri.remoteServerId
        val remotePath = dataSpec.uri.remoteRemotePath
        if (serverId == null || remotePath == null) {
            throw IOException("URI de vídeo SFTP inválida: ${dataSpec.uri}")
        }
        val config = serverRepository.getServerBlocking(serverId)
            ?: throw IOException("Servidor SFTP não configurado (id=$serverId)")
        val password = credentialStore.getPassword(serverId).orEmpty()

        try {
            val ssh = connectedSshClient(config, password)
            this.ssh = ssh
            val sftp = ssh.newSFTPClient()
            this.sftp = sftp
            val file = sftp.open(remotePath)
            this.file = file

            val fileSize = file.length()
            position = dataSpec.position
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
            throw IOException("Falha ao abrir vídeo remoto via SFTP: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val currentFile = file ?: throw IOException("Arquivo SFTP não está aberto")
        val bytesRead = try {
            currentFile.read(position, buffer, offset, bytesToRead)
        } catch (e: Exception) {
            throw IOException("Falha ao ler vídeo remoto via SFTP: ${e.message}", e)
        }
        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        position += bytesRead
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        file?.let { runCatching { it.close() } }
        file = null
        sftp?.let { runCatching { it.close() } }
        sftp = null
        ssh?.let { runCatching { it.close() } }
        ssh = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
