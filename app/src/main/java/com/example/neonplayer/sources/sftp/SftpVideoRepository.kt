package com.example.neonplayer.sources.sftp

import android.content.Context
import android.util.Log
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
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.userauth.UserAuthException

private const val CONNECT_TIMEOUT_MS = 20_000L

class SftpVideoRepository(private val context: Context) {

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
                RemoteDeleteResult.Success
            } catch (timeout: TimeoutCancellationException) {
                RemoteDeleteResult.Error(context.getString(R.string.smb_error_timeout))
            } catch (error: SFTPException) {
                if (error.statusCode == StatusCode.PERMISSION_DENIED) {
                    RemoteDeleteResult.PermissionDenied
                } else {
                    RemoteDeleteResult.Error(mapError(error))
                }
            } catch (error: Exception) {
                RemoteDeleteResult.Error(mapError(error))
            }
        }

    private fun performDelete(config: RemoteServerConfig, video: RemoteVideo) {
        val password = credentialStore.getPassword(config.id).orEmpty()
        connectedSshClient(config, password).use { ssh ->
            ssh.newSFTPClient().use { sftp ->
                sftp.rm(video.remotePath)
            }
        }
    }

    private fun fetchEntries(config: RemoteServerConfig, path: String): RemoteListResult.Success {
        val password = credentialStore.getPassword(config.id).orEmpty()
        connectedSshClient(config, password).use { ssh ->
            ssh.newSFTPClient().use { sftp ->
                // Path vazio ("raiz"/diretório padrão) é resolvido de formas diferentes por cada
                // servidor SFTP: o protocolo não garante que "" vire o diretório home do usuário —
                // alguns servidores respondem NO_SUCH_FILE para uma string vazia. "." é a convenção
                // universalmente aceita para "diretório atual" e evita esse falso "caminho não encontrado".
                val effectivePath = path.ifBlank { "." }
                Log.d("SftpDebug", "fetchEntries: requestedPath='$path' effectivePath='$effectivePath'")
                val entries = sftp.ls(effectivePath).filterNot { isDotEntry(it.name) }
                Log.d("SftpDebug", "fetchEntries: got ${entries.size} entries: ${entries.map { it.name }}")
                val folders = entries
                    .asSequence()
                    .filter { it.isDirectory }
                    .map { BrowseFolder(name = it.name, path = joinSftpPath(path, it.name)) }
                    .sortedBy { it.name.lowercase() }
                    .toList()
                    .let { subfolders ->
                        // Entrada ".." sintética: deixa o SFTP resolver o caminho pai (mesma
                        // convenção de um cliente SFTP normal), permitindo subir acima da raiz
                        // configurada do servidor em vez de travar nela.
                        listOf(BrowseFolder(name = "..", path = joinSftpPath(path, ".."), isParent = true)) + subfolders
                    }
                val videos = entries
                    .asSequence()
                    .filter { it.isRegularFile }
                    .filter { hasVideoExtension(it.name) }
                    .map { entry ->
                        RemoteVideo(
                            serverId = config.id,
                            protocol = ServerProtocol.SFTP,
                            remotePath = joinSftpPath(path, entry.name),
                            displayName = entry.name,
                            sizeBytes = entry.attributes.size,
                            // FileAttributes.mtime é em segundos desde a época (SFTP v3), não milissegundos.
                            dateModifiedMs = entry.attributes.mtime * 1000L,
                        )
                    }
                    .toList()
                return RemoteListResult.Success(folders, videos)
            }
        }
    }

    private fun mapError(error: Exception): String = when {
        error is UnknownHostException ->
            context.getString(R.string.smb_error_unknown_host)

        error is ConnectException || error is SocketTimeoutException ->
            context.getString(R.string.smb_error_connection)

        error is UserAuthException ->
            context.getString(R.string.smb_error_auth)

        error is SFTPException -> when (error.statusCode) {
            StatusCode.PERMISSION_DENIED -> context.getString(R.string.smb_error_access_denied)
            StatusCode.NO_SUCH_FILE -> context.getString(R.string.smb_error_path_not_found)
            else -> context.getString(R.string.smb_error_generic, error.message.orEmpty())
        }

        else -> context.getString(R.string.smb_error_generic, error.message.orEmpty())
    }
}

internal fun joinSftpPath(basePath: String, fileName: String): String =
    if (basePath.isBlank()) fileName else "${basePath.trimEnd('/')}/$fileName"
