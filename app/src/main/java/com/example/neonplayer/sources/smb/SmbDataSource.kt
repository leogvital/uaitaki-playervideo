package com.example.neonplayer.sources.smb

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
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.util.EnumSet

/**
 * [androidx.media3.datasource.DataSource] que lê um vídeo hospedado num compartilhamento SMB,
 * usando leitura posicional ([SmbFile.read] com offset) para suportar seek/scrubbing do player
 * sem precisar baixar o arquivo inteiro.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(
    private val serverRepository: RemoteServerRepository,
    private val credentialStore: RemoteCredentialStore,
) : BaseDataSource(/* isNetwork= */ true) {

    private var client: SMBClient? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var file: SmbFile? = null
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
            throw IOException("URI de vídeo SMB inválida: ${dataSpec.uri}")
        }
        val config = serverRepository.getServerBlocking(serverId)
            ?: throw IOException("Servidor SMB não configurado (id=$serverId)")
        val password = credentialStore.getPassword(serverId).orEmpty()
        val (shareName, _) = splitShareAndPath(config.path)

        try {
            val client = newSmbClient()
            this.client = client
            val connection = client.connect(config.host, config.port)
            val authContext = AuthenticationContext(config.username, password.toCharArray(), null)
            val session = connection.authenticate(authContext)
            this.session = session
            val share = session.connectShare(shareName) as DiskShare
            this.share = share
            val file = share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            this.file = file

            val fileSize = file.getFileInformation(FileStandardInformation::class.java).endOfFile
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
            throw IOException("Falha ao abrir vídeo remoto via SMB: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()
        val currentFile = file ?: throw IOException("Arquivo SMB não está aberto")
        val bytesRead = try {
            currentFile.read(buffer, position, offset, bytesToRead)
        } catch (e: Exception) {
            throw IOException("Falha ao ler vídeo remoto via SMB: ${e.message}", e)
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
        share?.let { runCatching { it.close() } }
        share = null
        session?.let { runCatching { it.close() } }
        session = null
        client?.let { runCatching { it.close() } }
        client = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
