package com.example.neonplayer.favorites

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.RecursiveVideoFetcher
import com.example.neonplayer.sources.SOURCE_LOCAL
import com.example.neonplayer.sources.SourceRef
import com.example.neonplayer.sources.ftp.FtpVideoRepository
import com.example.neonplayer.sources.local.LocalVideo
import com.example.neonplayer.sources.local.LocalVideoRepository
import com.example.neonplayer.sources.SortDirection
import com.example.neonplayer.sources.SortField
import com.example.neonplayer.sources.SortOption
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.RemoteVideo
import com.example.neonplayer.sources.sftp.SftpVideoRepository
import com.example.neonplayer.sources.smb.SmbVideoRepository
import com.example.neonplayer.sources.sortedByOption
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class CollectionDetailUiState(
    val videos: List<PlayableVideo> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * Junta (recursivamente) os vídeos de todas as pastas de uma [FavoriteCollection], possivelmente
 * espalhadas por fontes diferentes (local + um ou mais servidores) — por isso busca cada pasta
 * concorrentemente em vez de sequencialmente.
 */
class CollectionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val recursiveVideoFetcher = RecursiveVideoFetcher(
        LocalVideoRepository(application),
        RemoteServerRepository(application),
        SmbVideoRepository(application),
        SftpVideoRepository(application),
        FtpVideoRepository(application),
    )
    private val favoritesRepository = FavoritesRepository(application)

    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    private var loadedCollection: FavoriteCollection? = null

    /** Sempre recarrega — a tela é remontada toda vez que se volta a ela (ex: depois de excluir um vídeo no player), então não há por que reaproveitar um resultado antigo. */
    fun load(collection: FavoriteCollection) {
        loadedCollection = collection
        fetch(collection)
    }

    fun toggleFavorite(video: PlayableVideo) {
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(video.sourceId, video.videoId)
            loadedCollection?.let { fetch(it) }
        }
    }

    private fun fetch(collection: FavoriteCollection) {
        viewModelScope.launch {
            _uiState.value = CollectionDetailUiState(isLoading = true)
            val videos = coroutineScope {
                collection.folders
                    .map { folder ->
                        async {
                            val source = if (folder.sourceId == SOURCE_LOCAL) SourceRef.Local else SourceRef.Remote(folder.sourceId)
                            recursiveVideoFetcher.fetchAll(source, folder.path)
                        }
                    }
                    .awaitAll()
                    .flatten()
                    .sortedByOption(SortOption(SortField.NAME, SortDirection.ASCENDING))
            }
            _uiState.value = CollectionDetailUiState(videos = applyFavorites(videos), isLoading = false)
        }
    }

    private suspend fun applyFavorites(videos: List<PlayableVideo>): List<PlayableVideo> {
        val favoriteIdsBySource = videos.map { it.sourceId }.distinct()
            .associateWith { sourceId -> favoritesRepository.favoritesFlow(sourceId).first() }
        return videos.map { video ->
            val isFavorite = video.videoId in favoriteIdsBySource[video.sourceId].orEmpty()
            when (video) {
                is LocalVideo -> video.copy(isFavorite = isFavorite)
                is RemoteVideo -> video.copy(isFavorite = isFavorite)
                else -> video
            }
        }
    }
}
