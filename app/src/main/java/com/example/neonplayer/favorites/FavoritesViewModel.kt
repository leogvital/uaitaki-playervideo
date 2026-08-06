package com.example.neonplayer.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neonplayer.R
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SOURCE_LOCAL
import com.example.neonplayer.sources.ftp.FtpVideoRepository
import com.example.neonplayer.sources.local.LocalVideoRepository
import com.example.neonplayer.sources.remote.RemoteListResult
import com.example.neonplayer.sources.remote.RemoteServerConfig
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.sftp.SftpVideoRepository
import com.example.neonplayer.sources.smb.SmbVideoRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val videos: List<PlayableVideo> = emptyList(),
    val isLoading: Boolean = false,
    val partialErrorMessage: String? = null,
    val sourceLabels: Map<String, String> = emptyMap(),
)

private data class SourceFetchResult(val videos: List<PlayableVideo>, val failed: Boolean)

class FavoritesViewModel(application: Application) : AndroidViewModel(application) {

    private val favoritesRepository = FavoritesRepository(application)
    private val localVideoRepository = LocalVideoRepository(application)
    private val remoteServerRepository = RemoteServerRepository(application)
    private val smbRepository = SmbVideoRepository(application)
    private val sftpRepository = SftpVideoRepository(application)
    private val ftpRepository = FtpVideoRepository(application)

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, partialErrorMessage = null)

            val servers = remoteServerRepository.serversFlow.first()
            val sourceLabels = buildMap {
                put(SOURCE_LOCAL, getApplication<Application>().getString(R.string.browse_local_videos))
                servers.forEach { put(it.id, it.name) }
            }

            val localResult = fetchLocalFavorites()
            val remoteResults = fetchRemoteFavorites(servers)
            val allResults = listOf(localResult) + remoteResults

            _uiState.value = FavoritesUiState(
                videos = allResults.flatMap { it.videos },
                isLoading = false,
                partialErrorMessage = if (allResults.any { it.failed }) {
                    getApplication<Application>().getString(R.string.favorites_partial_error)
                } else {
                    null
                },
                sourceLabels = sourceLabels,
            )
        }
    }

    private suspend fun fetchLocalFavorites(): SourceFetchResult {
        val favIds = favoritesRepository.favoritesFlow(SOURCE_LOCAL).first()
        if (favIds.isEmpty()) return SourceFetchResult(emptyList(), failed = false)
        return runCatching { localVideoRepository.listAllVideos() }
            .fold(
                onSuccess = { videos ->
                    SourceFetchResult(
                        videos = videos.filter { it.videoId in favIds }.map { it.copy(isFavorite = true) },
                        failed = false,
                    )
                },
                onFailure = { SourceFetchResult(emptyList(), failed = true) },
            )
    }

    private suspend fun fetchRemoteFavorites(servers: List<RemoteServerConfig>): List<SourceFetchResult> = coroutineScope {
        servers.map { config ->
            async {
                val favIds = favoritesRepository.favoritesFlow(config.id).first()
                if (favIds.isEmpty()) return@async SourceFetchResult(emptyList(), failed = false)
                fetchRemoteFavoritesForServer(config, favIds)
            }
        }.let { deferreds -> deferreds.map { it.await() } }
    }

    /**
     * Como a listagem remota agora é por pasta (não mais uma varredura recursiva do servidor
     * inteiro), achar os vídeos favoritados de um servidor significa consultar só as pastas que
     * contêm algum favorito — extraídas do próprio caminho salvo em cada id de favorito — em vez
     * de percorrer a árvore inteira do compartilhamento.
     */
    private suspend fun fetchRemoteFavoritesForServer(config: RemoteServerConfig, favIds: Set<String>): SourceFetchResult {
        val idsByFolder = favIds.groupBy { it.substringBeforeLast('/', "") }
        val videos = mutableListOf<PlayableVideo>()
        var anyFailed = false

        for ((folder, idsInFolder) in idsByFolder) {
            val result = when (config.protocol) {
                ServerProtocol.SMB -> smbRepository.listVideos(config, folder)
                ServerProtocol.SFTP -> sftpRepository.listVideos(config, folder)
                ServerProtocol.FTP -> ftpRepository.listVideos(config, folder)
            }
            when (result) {
                is RemoteListResult.Success ->
                    videos += result.videos.filter { it.remotePath in idsInFolder }.map { it.copy(isFavorite = true) }

                is RemoteListResult.Error -> anyFailed = true
            }
        }
        return SourceFetchResult(videos, anyFailed)
    }

    fun toggleFavorite(video: PlayableVideo) {
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(video.sourceId, video.videoId)
            refresh()
        }
    }
}
