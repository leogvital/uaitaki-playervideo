package com.example.neonplayer.player

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neonplayer.R
import com.example.neonplayer.favorites.FavoritesRepository
import com.example.neonplayer.sources.BrowseFolder
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SourceRef
import com.example.neonplayer.sources.favoriteSourceId
import com.example.neonplayer.sources.ftp.FtpVideoRepository
import com.example.neonplayer.sources.local.DeleteVideoResult
import com.example.neonplayer.sources.local.LocalVideo
import com.example.neonplayer.sources.local.LocalVideoRepository
import com.example.neonplayer.sources.remote.RemoteDeleteResult
import com.example.neonplayer.sources.remote.RemoteListResult
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.RemoteVideo
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.sftp.SftpVideoRepository
import com.example.neonplayer.sources.smb.SmbVideoRepository
import com.example.neonplayer.sources.smb.splitShareAndPath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SourceOption(val ref: SourceRef, val label: String)

data class VideoBrowserUiState(
    val folders: List<BrowseFolder> = emptyList(),
    val videos: List<PlayableVideo> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface VideoBrowserEvent {
    data class RequestSystemDeleteConfirmation(val intentSender: IntentSender) : VideoBrowserEvent
    data class DeleteFailed(val message: String) : VideoBrowserEvent
}

class VideoBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val localVideoRepository = LocalVideoRepository(application)
    private val remoteServerRepository = RemoteServerRepository(application)
    private val smbRepository = SmbVideoRepository(application)
    private val sftpRepository = SftpVideoRepository(application)
    private val ftpRepository = FtpVideoRepository(application)
    private val favoritesRepository = FavoritesRepository(application)

    private val _selectedSource = MutableStateFlow<SourceRef>(SourceRef.Local)
    val selectedSource: StateFlow<SourceRef> = _selectedSource.asStateFlow()

    val sourceOptions: StateFlow<List<SourceOption>> = remoteServerRepository.serversFlow
        .map { servers ->
            listOf(SourceOption(SourceRef.Local, application.getString(R.string.browse_local_videos))) +
                servers.map { SourceOption(SourceRef.Remote(it.id), it.name) }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            listOf(SourceOption(SourceRef.Local, application.getString(R.string.browse_local_videos))),
        )

    /** Pilha de pastas visitadas na fonte atual — o primeiro elemento é sempre a raiz dessa fonte. */
    private val folderStack = mutableListOf("")

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _canNavigateUp = MutableStateFlow(false)
    val canNavigateUp: StateFlow<Boolean> = _canNavigateUp.asStateFlow()

    private val rawFolders = MutableStateFlow<List<BrowseFolder>>(emptyList())
    private val rawVideos = MutableStateFlow<List<PlayableVideo>>(emptyList())
    private val isLoading = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val _events = MutableSharedFlow<VideoBrowserEvent>()
    val events: SharedFlow<VideoBrowserEvent> = _events.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<VideoBrowserUiState> = combine(
        rawFolders,
        rawVideos,
        _selectedSource.flatMapLatest { favoritesRepository.favoritesFlow(it.favoriteSourceId) },
        isLoading,
        errorMessage,
    ) { folders, videos, favoriteIds, loading, error ->
        VideoBrowserUiState(
            folders = folders,
            videos = videos.map { applyFavorite(it, favoriteIds) },
            isLoading = loading,
            errorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoBrowserUiState())

    private fun applyFavorite(video: PlayableVideo, favoriteIds: Set<String>): PlayableVideo = when (video) {
        is LocalVideo -> video.copy(isFavorite = video.videoId in favoriteIds)
        is RemoteVideo -> video.copy(isFavorite = video.videoId in favoriteIds)
        else -> video
    }

    /** Troca a fonte selecionada, volta para a raiz dela e recarrega. Ignorado se já for a fonte atual. */
    fun selectSource(source: SourceRef) {
        if (source == _selectedSource.value) return
        _selectedSource.value = source
        viewModelScope.launch {
            val root = rootPathFor(source)
            folderStack.clear()
            folderStack.add(root)
            _currentPath.value = root
            _canNavigateUp.value = false
            refresh()
        }
    }

    private suspend fun rootPathFor(source: SourceRef): String = when (source) {
        SourceRef.Local -> ""
        is SourceRef.Remote -> {
            val config = remoteServerRepository.getServer(source.serverId)
            when (config?.protocol) {
                ServerProtocol.SMB -> splitShareAndPath(config.path).second
                else -> config?.path.orEmpty()
            }
        }
    }

    fun navigateInto(folder: BrowseFolder) {
        folderStack.add(folder.path)
        _currentPath.value = folder.path
        _canNavigateUp.value = folderStack.size > 1
        refresh()
    }

    /** @return true se subiu um nível (havia para onde voltar), false se já estava na raiz da fonte. */
    fun navigateUp(): Boolean {
        if (folderStack.size <= 1) return false
        folderStack.removeAt(folderStack.lastIndex)
        _currentPath.value = folderStack.last()
        _canNavigateUp.value = folderStack.size > 1
        refresh()
        return true
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading.value = true
            errorMessage.value = null
            val path = _currentPath.value
            when (val source = _selectedSource.value) {
                SourceRef.Local -> {
                    runCatching { localVideoRepository.browse(path) }
                        .onSuccess { result ->
                            rawFolders.value = result.folders
                            rawVideos.value = result.videos
                            isLoading.value = false
                        }
                        .onFailure {
                            isLoading.value = false
                            errorMessage.value = getApplication<Application>().getString(R.string.error_loading_videos)
                        }
                }

                is SourceRef.Remote -> {
                    val config = remoteServerRepository.getServer(source.serverId)
                    if (config == null) {
                        isLoading.value = false
                        errorMessage.value = getApplication<Application>().getString(R.string.smb_error_server_not_found)
                        return@launch
                    }
                    val result = when (config.protocol) {
                        ServerProtocol.SMB -> smbRepository.listVideos(config, path)
                        ServerProtocol.SFTP -> sftpRepository.listVideos(config, path)
                        ServerProtocol.FTP -> ftpRepository.listVideos(config, path)
                    }
                    when (result) {
                        is RemoteListResult.Success -> {
                            rawFolders.value = result.folders
                            rawVideos.value = result.videos
                            isLoading.value = false
                        }

                        is RemoteListResult.Error -> {
                            isLoading.value = false
                            errorMessage.value = result.message
                        }
                    }
                }
            }
        }
    }

    fun toggleFavorite(video: PlayableVideo) {
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(video.sourceId, video.videoId)
        }
    }

    fun deleteVideo(video: PlayableVideo) {
        viewModelScope.launch {
            when (val source = _selectedSource.value) {
                SourceRef.Local -> {
                    val localVideo = video as? LocalVideo ?: return@launch
                    when (val result = localVideoRepository.deleteVideo(localVideo)) {
                        DeleteVideoResult.Success -> refresh()
                        is DeleteVideoResult.RequiresConfirmation ->
                            _events.emit(VideoBrowserEvent.RequestSystemDeleteConfirmation(result.intentSender))

                        DeleteVideoResult.Failed -> _events.emit(
                            VideoBrowserEvent.DeleteFailed(
                                getApplication<Application>().getString(R.string.error_deleting_video),
                            ),
                        )
                    }
                }

                is SourceRef.Remote -> {
                    val remoteVideo = video as? RemoteVideo ?: return@launch
                    val config = remoteServerRepository.getServer(source.serverId) ?: return@launch
                    val result = when (config.protocol) {
                        ServerProtocol.SMB -> smbRepository.deleteVideo(config, remoteVideo)
                        ServerProtocol.SFTP -> sftpRepository.deleteVideo(config, remoteVideo)
                        ServerProtocol.FTP -> ftpRepository.deleteVideo(config, remoteVideo)
                    }
                    when (result) {
                        RemoteDeleteResult.Success -> refresh()
                        RemoteDeleteResult.PermissionDenied -> _events.emit(
                            VideoBrowserEvent.DeleteFailed(
                                getApplication<Application>().getString(R.string.remote_error_permission_denied),
                            ),
                        )

                        is RemoteDeleteResult.Error -> _events.emit(VideoBrowserEvent.DeleteFailed(result.message))
                    }
                }
            }
        }
    }

    /** Chamado após o usuário confirmar a exclusão no diálogo do sistema (fluxo de escopo de armazenamento local). */
    fun onSystemDeleteConfirmed() {
        refresh()
    }
}
