package com.example.neonplayer.sources.smb

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
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val CONNECT_TIMEOUT_MS = 20_000L
private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L

class SmbVideoRepository(private val context: Context) {

    private val credentialStore = RemoteCredentialStore(context)

    /** [path] é o subcaminho dentro do compartilhamento (share) configurado, não um caminho absoluto. */
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
            } catch (error: SMBApiException) {
                if (error.status == NtStatus.STATUS_ACCESS_DENIED) {
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
        val (shareName, _) = splitShareAndPath(config.path)
        newSmbClient().use { client ->
            val connection = client.connect(config.host, config.port)
            val authContext = AuthenticationContext(config.username, password.toCharArray(), null)
            connection.authenticate(authContext).use { session ->
                val share = session.connectShare(shareName) as DiskShare
                share.use { diskShare ->
                    diskShare.rm(video.remotePath)
                }
            }
        }
    }

    private fun fetchEntries(config: RemoteServerConfig, path: String): RemoteListResult.Success {
        val password = credentialStore.getPassword(config.id).orEmpty()
        val (shareName, _) = splitShareAndPath(config.path)
        if (shareName.isBlank()) {
            error(context.getString(R.string.smb_error_missing_share))
        }
        newSmbClient().use { client ->
            val connection = client.connect(config.host, config.port)
            val authContext = AuthenticationContext(config.username, password.toCharArray(), null)
            connection.authenticate(authContext).use { session ->
                val share = session.connectShare(shareName) as DiskShare
                share.use { diskShare ->
                    val entries = diskShare.list(path)
                    val folders = entries
                        .asSequence()
                        .filter { it.fileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0L }
                        .filterNot { isDotEntry(it.fileName) }
                        .map { BrowseFolder(name = it.fileName, path = joinSmbPath(path, it.fileName)) }
                        .sortedBy { it.name.lowercase() }
                        .toList()
                    val videos = entries
                        .asSequence()
                        .filter { it.fileAttributes and FILE_ATTRIBUTE_DIRECTORY == 0L }
                        .filter { hasVideoExtension(it.fileName) }
                        .map { entry ->
                            RemoteVideo(
                                serverId = config.id,
                                protocol = ServerProtocol.SMB,
                                remotePath = joinSmbPath(path, entry.fileName),
                                displayName = entry.fileName,
                                sizeBytes = entry.endOfFile,
                                dateModifiedMs = entry.lastWriteTime.toEpochMillis(),
                            )
                        }
                        .toList()
                    return RemoteListResult.Success(folders, videos)
                }
            }
        }
    }

    private fun mapError(error: Exception): String = when {
        error is UnknownHostException ->
            context.getString(R.string.smb_error_unknown_host)

        error is ConnectException || error is SocketTimeoutException ->
            context.getString(R.string.smb_error_connection)

        error is SMBApiException -> when (error.status) {
            NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED ->
                context.getString(R.string.smb_error_auth)

            NtStatus.STATUS_ACCESS_DENIED ->
                context.getString(R.string.smb_error_access_denied)

            NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH ->
                context.getString(R.string.smb_error_share_not_found)

            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ->
                context.getString(R.string.smb_error_path_not_found)

            else -> context.getString(R.string.smb_error_generic, error.message.orEmpty())
        }

        else -> context.getString(R.string.smb_error_generic, error.message.orEmpty())
    }
}
