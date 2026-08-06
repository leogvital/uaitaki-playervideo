package com.example.neonplayer.sources.ftp

import android.content.Context
import com.example.neonplayer.R
import com.example.neonplayer.sources.BrowseFolder
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteDeleteResult
import com.example.neonplayer.sources.remote.RemoteListResult
import com.example.neonplayer.sources.remote.RemoteServerConfig
import com.example.neonplayer.sources.remote.RemoteVideo
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.remote.hasVideoExtension
import com.example.neonplayer.sources.remote.isDotEntry
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.commons.net.ftp.FTPReply

private const val CONNECT_TIMEOUT_MS = 20_000L

class FtpVideoRepository(private val context: Context) {

    private val credentialStore = RemoteCredentialStore(context)

    suspend fun listVideos(config: RemoteServerConfig, path: String): RemoteListResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { runInterruptible { fetchEntries(config, path) } }
        } catch (timeout: TimeoutCancellationException) {
            RemoteListResult.Error(context.getString(R.string.smb_error_timeout))
        } catch (error: Exception) {
            RemoteListResult.Error(mapError(error))
        }
    }

    suspend fun deleteVideo(config: RemoteServerConfig, video: RemoteVideo): RemoteDeleteResult =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(CONNECT_TIMEOUT_MS) { runInterruptible { performDelete(config, video) } }
            } catch (timeout: TimeoutCancellationException) {
                RemoteDeleteResult.Error(context.getString(R.string.smb_error_timeout))
            } catch (error: Exception) {
                RemoteDeleteResult.Error(mapError(error))
            }
        }

    /**
     * Ao contrário do smbj/sshj, o commons-net não lança uma exceção tipada de permissão negada:
     * `deleteFile` retorna `false` e o código/mensagem de resposta do servidor (FTP 550 cobre
     * tanto "não encontrado" quanto "permissão negada", dependendo do servidor — RFC 959 não
     * distingue os dois de forma confiável) é a única pista disponível.
     */
    private fun performDelete(config: RemoteServerConfig, video: RemoteVideo): RemoteDeleteResult {
        val password = credentialStore.getPassword(config.id).orEmpty()
        val client = connectedFtpClient(config, password)
        try {
            if (client.deleteFile(video.remotePath)) return RemoteDeleteResult.Success
            val code = client.replyCode
            val message = client.replyString.orEmpty()
            return if (code == FTPReply.FILE_UNAVAILABLE ||
                message.contains("permission", ignoreCase = true) ||
                message.contains("denied", ignoreCase = true)
            ) {
                RemoteDeleteResult.PermissionDenied
            } else {
                RemoteDeleteResult.Error(context.getString(R.string.smb_error_generic, message))
            }
        } finally {
            runCatching {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            }
        }
    }

    private fun fetchEntries(config: RemoteServerConfig, path: String): RemoteListResult.Success {
        val password = credentialStore.getPassword(config.id).orEmpty()
        val client = connectedFtpClient(config, password)
        try {
            val entries = client.listFiles(path).filterNotNull().filterNot { isDotEntry(it.name) }
            val folders = entries
                .filter { it.isDirectory }
                .map { BrowseFolder(name = it.name, path = joinFtpPath(path, it.name)) }
                .sortedBy { it.name.lowercase() }
            val videos = entries
                .filter { it.isFile }
                .filter { hasVideoExtension(it.name) }
                .map { entry ->
                    RemoteVideo(
                        serverId = config.id,
                        protocol = ServerProtocol.FTP,
                        remotePath = joinFtpPath(path, entry.name),
                        displayName = entry.name,
                        sizeBytes = entry.size,
                        dateModifiedMs = entry.timestampInstant?.toEpochMilli() ?: 0L,
                    )
                }
            return RemoteListResult.Success(folders, videos)
        } finally {
            runCatching {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            }
        }
    }

    private fun mapError(error: Exception): String = when {
        error is UnknownHostException ->
            context.getString(R.string.smb_error_unknown_host)

        error is ConnectException || error is SocketTimeoutException ->
            context.getString(R.string.smb_error_connection)

        error is FtpCommandException -> when (error.replyCode) {
            FTPReply.NOT_LOGGED_IN -> context.getString(R.string.smb_error_auth)
            FTPReply.FILE_UNAVAILABLE -> context.getString(R.string.smb_error_path_not_found)
            else -> context.getString(R.string.smb_error_generic, error.message.orEmpty())
        }

        else -> context.getString(R.string.smb_error_generic, error.message.orEmpty())
    }
}

internal fun joinFtpPath(basePath: String, fileName: String): String =
    if (basePath.isBlank()) fileName else "${basePath.trimEnd('/')}/$fileName"
