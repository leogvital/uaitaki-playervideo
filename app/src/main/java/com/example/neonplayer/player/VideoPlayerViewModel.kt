package com.example.neonplayer.player

import android.app.Application
import android.content.IntentSender
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.neonplayer.R
import com.example.neonplayer.favorites.FavoritesRepository
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.ftp.FtpVideoRepository
import com.example.neonplayer.sources.local.DeleteVideoResult
import com.example.neonplayer.sources.local.LocalVideo
import com.example.neonplayer.sources.local.LocalVideoRepository
import com.example.neonplayer.sources.remote.NeonDataSourceFactory
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteDeleteResult
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.RemoteVideo
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.sftp.SftpVideoRepository
import com.example.neonplayer.sources.smb.SmbVideoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SEEK_STEP_MS = 10_000L
private const val POSITION_POLL_INTERVAL_MS = 500L

data class VideoPlayerUiState(
    val currentTitle: String = "",
    val isPlaying: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val isFavorite: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffleEnabled: Boolean = false,
)

sealed interface VideoPlayerEvent {
    data class RequestSystemDeleteConfirmation(val intentSender: IntentSender) : VideoPlayerEvent
    data class DeleteFailed(val message: String) : VideoPlayerEvent
    data object DeleteSucceeded : VideoPlayerEvent
}

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val favoritesRepository = FavoritesRepository(application)
    private val localVideoRepository = LocalVideoRepository(application)
    private val remoteServerRepository = RemoteServerRepository(application)
    private val remoteCredentialStore = RemoteCredentialStore(application)
    private val smbRepository = SmbVideoRepository(application)
    private val sftpRepository = SftpVideoRepository(application)
    private val ftpRepository = FtpVideoRepository(application)

    val player: ExoPlayer = buildPlayer(application, remoteServerRepository, remoteCredentialStore)

    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VideoPlayerEvent>()
    val events: SharedFlow<VideoPlayerEvent> = _events.asSharedFlow()

    private var playlist: List<PlayableVideo> = emptyList()
    private var favoriteIds: Set<String> = emptySet()
    private var favoritesJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentItemState()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _uiState.value = _uiState.value.copy(isShuffleEnabled = shuffleModeEnabled)
            }
        })

        // ExoPlayer não emite atualizações contínuas de posição — poll periódico é o jeito
        // recomendado pelo Media3 de alimentar uma barra de progresso.
        viewModelScope.launch {
            while (true) {
                delay(POSITION_POLL_INTERVAL_MS)
                val duration = player.duration
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = player.currentPosition.coerceAtLeast(0),
                    durationMs = if (duration == C.TIME_UNSET) 0L else duration,
                )
            }
        }
    }

    fun setPlaylist(videos: List<PlayableVideo>, startIndex: Int) {
        playlist = videos
        val mediaItems = videos.map { video ->
            MediaItem.Builder()
                .setUri(video.playbackUri)
                .setMediaId(video.videoId)
                .build()
        }
        player.setMediaItems(mediaItems, startIndex, 0)
        player.prepare()
        player.playWhenReady = true
        updateCurrentItemState()

        favoritesJob?.cancel()
        val sourceId = videos.firstOrNull()?.sourceId
        favoritesJob = sourceId?.let {
            viewModelScope.launch {
                favoritesRepository.favoritesFlow(it).collect { ids ->
                    favoriteIds = ids
                    updateCurrentItemState()
                }
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipToNext() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
    }

    fun seekBack() {
        player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
    }

    fun seekForward() {
        val duration = player.duration
        val target = player.currentPosition + SEEK_STEP_MS
        player.seekTo(if (duration == C.TIME_UNSET) target else target.coerceAtMost(duration))
    }

    /** Avança/retrocede um deslocamento relativo à posição atual — usado pelo arraste horizontal de scrub, diferente do seekBack/seekForward de duplo toque. */
    fun seekBy(deltaMs: Long) {
        val duration = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        val clamped = if (duration == C.TIME_UNSET) target else target.coerceAtMost(duration)
        player.seekTo(clamped)
        _uiState.value = _uiState.value.copy(currentPositionMs = clamped)
    }

    /** Salta para uma posição absoluta — usado ao arrastar a barra de progresso. */
    fun seekToPosition(positionMs: Long) {
        val duration = player.duration
        val target = positionMs.coerceAtLeast(0).let { if (duration == C.TIME_UNSET) it else it.coerceAtMost(duration) }
        player.seekTo(target)
        _uiState.value = _uiState.value.copy(currentPositionMs = target)
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun toggleFavorite() {
        val video = playlist.getOrNull(player.currentMediaItemIndex) ?: return
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(video.sourceId, video.videoId)
        }
    }

    /** Exclui o vídeo em reprodução, respeitando o mesmo fluxo por fonte usado na lista de navegação (ver [VideoBrowserViewModel.deleteVideo]). */
    fun deleteCurrentVideo() {
        val video = playlist.getOrNull(player.currentMediaItemIndex) ?: return
        viewModelScope.launch {
            when (video) {
                is LocalVideo -> when (val result = localVideoRepository.deleteVideo(video)) {
                    DeleteVideoResult.Success -> _events.emit(VideoPlayerEvent.DeleteSucceeded)
                    is DeleteVideoResult.RequiresConfirmation ->
                        _events.emit(VideoPlayerEvent.RequestSystemDeleteConfirmation(result.intentSender))

                    DeleteVideoResult.Failed -> _events.emit(
                        VideoPlayerEvent.DeleteFailed(getApplication<Application>().getString(R.string.error_deleting_video)),
                    )
                }

                is RemoteVideo -> {
                    val config = remoteServerRepository.getServer(video.serverId)
                    if (config == null) {
                        _events.emit(
                            VideoPlayerEvent.DeleteFailed(getApplication<Application>().getString(R.string.smb_error_server_not_found)),
                        )
                        return@launch
                    }
                    val result = when (config.protocol) {
                        ServerProtocol.SMB -> smbRepository.deleteVideo(config, video)
                        ServerProtocol.SFTP -> sftpRepository.deleteVideo(config, video)
                        ServerProtocol.FTP -> ftpRepository.deleteVideo(config, video)
                    }
                    when (result) {
                        RemoteDeleteResult.Success -> _events.emit(VideoPlayerEvent.DeleteSucceeded)
                        RemoteDeleteResult.PermissionDenied -> _events.emit(
                            VideoPlayerEvent.DeleteFailed(getApplication<Application>().getString(R.string.remote_error_permission_denied)),
                        )

                        is RemoteDeleteResult.Error -> _events.emit(VideoPlayerEvent.DeleteFailed(result.message))
                    }
                }

                else -> Unit
            }
        }
    }

    /** Chamado após o usuário confirmar a exclusão no diálogo do sistema (fluxo de escopo de armazenamento local). */
    fun onSystemDeleteConfirmed() {
        viewModelScope.launch { _events.emit(VideoPlayerEvent.DeleteSucceeded) }
    }

    private fun updateCurrentItemState() {
        val index = player.currentMediaItemIndex
        val video = playlist.getOrNull(index)
        val duration = player.duration
        _uiState.value = _uiState.value.copy(
            currentTitle = video?.displayName.orEmpty(),
            hasPrevious = player.hasPreviousMediaItem(),
            hasNext = player.hasNextMediaItem(),
            isFavorite = video != null && video.videoId in favoriteIds,
            currentPositionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = if (duration == C.TIME_UNSET) 0L else duration,
            isShuffleEnabled = player.shuffleModeEnabled,
        )
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}

@OptIn(UnstableApi::class)
private fun buildPlayer(
    application: Application,
    remoteServerRepository: RemoteServerRepository,
    remoteCredentialStore: RemoteCredentialStore,
): ExoPlayer {
    val dataSourceFactory = NeonDataSourceFactory(application, remoteServerRepository, remoteCredentialStore)
    val mediaSourceFactory = DefaultMediaSourceFactory(application).setDataSourceFactory(dataSourceFactory)
    return ExoPlayer.Builder(application)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
}
