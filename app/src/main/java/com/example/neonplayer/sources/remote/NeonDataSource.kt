package com.example.neonplayer.sources.remote

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.example.neonplayer.sources.ftp.FtpDataSource
import com.example.neonplayer.sources.sftp.SftpDataSource
import com.example.neonplayer.sources.smb.SmbDataSource

/**
 * [DataSource] que roteia por esquema de URI: vídeos remotos (ver [remotePlaybackUri], um esquema
 * por protocolo) vão para o [DataSource] do protocolo correspondente; qualquer outra URI (ex:
 * `content://` de vídeos locais) usa o [DefaultDataSource] padrão do Media3. Isso permite que o
 * mesmo [androidx.media3.exoplayer.ExoPlayer] reproduza vídeos locais e remotos (SMB/SFTP/FTP)
 * sem trocar de instância.
 */
@OptIn(UnstableApi::class)
class NeonDataSource(
    private val context: Context,
    private val serverRepository: RemoteServerRepository,
    private val credentialStore: RemoteCredentialStore,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val dataSource: DataSource = when (dataSpec.uri.remoteProtocolOrNull()) {
            ServerProtocol.SMB -> SmbDataSource(serverRepository, credentialStore)
            ServerProtocol.SFTP -> SftpDataSource(serverRepository, credentialStore)
            ServerProtocol.FTP -> FtpDataSource(serverRepository, credentialStore)
            null -> DefaultDataSource.Factory(context).createDataSource()
        }
        listeners.forEach(dataSource::addTransferListener)
        delegate = dataSource
        return dataSource.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(delegate) { "NeonDataSource.read() chamado antes de open()" }.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.getUri()

    override fun close() {
        delegate?.close()
    }
}

@OptIn(UnstableApi::class)
class NeonDataSourceFactory(
    private val context: Context,
    private val serverRepository: RemoteServerRepository,
    private val credentialStore: RemoteCredentialStore,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        NeonDataSource(context.applicationContext, serverRepository, credentialStore)
}
