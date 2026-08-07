package com.example.neonplayer.sources

import com.example.neonplayer.sources.ftp.FtpVideoRepository
import com.example.neonplayer.sources.local.LocalVideoRepository
import com.example.neonplayer.sources.remote.RemoteListResult
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.sftp.SftpVideoRepository
import com.example.neonplayer.sources.smb.SmbVideoRepository

/**
 * Junta todos os vídeos de uma pasta e suas subpastas, recursivamente, para qualquer fonte —
 * usado por "reproduzir tudo" e pelas coleções de favoritos por pasta. Local resolve numa única
 * consulta ao MediaStore ([LocalVideoRepository.browseRecursive]); remoto não tem listagem
 * recursiva no protocolo, então percorre pasta por pasta reaproveitando o `listVideos` já
 * existente. Erro numa subpasta (ex: servidor cai no meio do passeio) só interrompe aquele ramo —
 * devolve o que já tinha juntado até ali, em vez de falhar tudo.
 */
class RecursiveVideoFetcher(
    private val localVideoRepository: LocalVideoRepository,
    private val remoteServerRepository: RemoteServerRepository,
    private val smbRepository: SmbVideoRepository,
    private val sftpRepository: SftpVideoRepository,
    private val ftpRepository: FtpVideoRepository,
) {
    suspend fun fetchAll(source: SourceRef, rootPath: String): List<PlayableVideo> = when (source) {
        SourceRef.Local -> localVideoRepository.browseRecursive(rootPath)
        is SourceRef.Remote -> fetchRemoteRecursive(source.serverId, rootPath)
    }

    private suspend fun fetchRemoteRecursive(serverId: String, rootPath: String): List<PlayableVideo> {
        val config = remoteServerRepository.getServer(serverId) ?: return emptyList()
        val videos = mutableListOf<PlayableVideo>()

        suspend fun walk(path: String) {
            val result = when (config.protocol) {
                ServerProtocol.SMB -> smbRepository.listVideos(config, path)
                ServerProtocol.SFTP -> sftpRepository.listVideos(config, path)
                ServerProtocol.FTP -> ftpRepository.listVideos(config, path)
            }
            if (result is RemoteListResult.Success) {
                videos += result.videos
                for (folder in result.folders) walk(folder.path)
            }
        }

        walk(rootPath)
        return videos
    }
}
