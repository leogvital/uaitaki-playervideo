package com.example.neonplayer.player

import android.app.Application
import android.content.IntentSender
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.neonplayer.R
import com.example.neonplayer.favorites.FavoritesRepository
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.PlaybackResumeStore
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

/** Intervalo entre gravações da posição de retomada — não precisa ser a cada poll (500ms), só o bastante para não perder muito progresso se o app for encerrado de repente. */
private const val RESUME_SAVE_INTERVAL_TICKS = (5_000L / POSITION_POLL_INTERVAL_MS).toInt()

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
    /** A fila ficou vazia após uma exclusão — não há mais nada para tocar, então a tela deve fechar. */
    data object PlaylistEmpty : VideoPlayerEvent
    data class PlaybackError(val message: String) : VideoPlayerEvent
}

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val favoritesRepository = FavoritesRepository(application)
    private val localVideoRepository = LocalVideoRepository(application)
    private val remoteServerRepository = RemoteServerRepository(application)
    private val remoteCredentialStore = RemoteCredentialStore(application)
    private val smbRepository = SmbVideoRepository(application)
    private val sftpRepository = SftpVideoRepository(application)
    private val ftpRepository = FtpVideoRepository(application)
    private val playbackResumeStore = PlaybackResumeStore(application)

    val player: ExoPlayer = buildPlayer(application, remoteServerRepository, remoteCredentialStore)

    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VideoPlayerEvent>()
    val events: SharedFlow<VideoPlayerEvent> = _events.asSharedFlow()

    private var playlist: List<PlayableVideo> = emptyList()
    private var favoriteIds: Set<String> = emptySet()
    private var favoritesJob: Job? = null

    /**
     * Só é `true` quando a playlist veio da navegação principal por pastas (ver
     * [VideoBrowserViewModel]) — é o único fluxo em que a posição de retomada salva sempre
     * corresponde à mesma pasta salva por [VideoBrowserViewModel.refresh]. Favoritos/coleções
     * misturam vídeos de fontes/pastas diferentes numa única playlist, então não têm uma "pasta"
     * única para reabrir depois — tocar um vídeo por esses fluxos não grava/limpa retomada.
     */
    private var canPersistResume = false
    private var resumeSaveTickCounter = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                if (!isPlaying) persistResumeProgress()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentItemState()
                persistResumeProgress()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _uiState.value = _uiState.value.copy(isShuffleEnabled = shuffleModeEnabled)
            }

            override fun onPlayerError(error: PlaybackException) {
                val failedTitle = playlist.getOrNull(player.currentMediaItemIndex)?.displayName.orEmpty()
                val hasNext = player.hasNextMediaItem()
                val message = getApplication<Application>().getString(
                    if (hasNext) R.string.playback_error_skipping else R.string.playback_error_last_item,
                    failedTitle,
                )
                viewModelScope.launch { _events.emit(VideoPlayerEvent.PlaybackError(message)) }
                if (hasNext) skipToNext()
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
                resumeSaveTickCounter++
                if (resumeSaveTickCounter >= RESUME_SAVE_INTERVAL_TICKS) {
                    resumeSaveTickCounter = 0
                    persistResumeProgress()
                }
            }
        }
    }

    /**
     * @param startPositionMs posição inicial dentro do vídeo em [startIndex] — só usado ao
     * restaurar a reprodução salva (ver [MainActivity]); navegação normal sempre começa do 0.
     * @param autoPlay `false` só na restauração — reabre pausado no lugar salvo, não tocando
     * sozinho (comportamento combinado com o usuário).
     */
    fun setPlaylist(
        videos: List<PlayableVideo>,
        startIndex: Int,
        startPositionMs: Long = 0L,
        autoPlay: Boolean = true,
        canPersistResume: Boolean = false,
    ) {
        playlist = videos
        this.canPersistResume = canPersistResume
        resumeSaveTickCounter = 0
        val mediaItems = videos.map { video ->
            MediaItem.Builder()
                .setUri(video.playbackUri)
                .setMediaId(video.videoId)
                .build()
        }
        player.setMediaItems(mediaItems, startIndex, startPositionMs)
        player.prepare()
        player.playWhenReady = autoPlay
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
                    DeleteVideoResult.Success -> removeCurrentFromPlaylist()
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
                        RemoteDeleteResult.Success -> removeCurrentFromPlaylist()
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
        viewModelScope.launch { removeCurrentFromPlaylist() }
    }

    /**
     * Remove o vídeo atual da fila e do player após uma exclusão bem-sucedida, deixando o próximo
     * item tocando em seguida — em vez de sempre sair da tela do player, que era o comportamento
     * anterior mesmo quando ainda havia mais vídeos na fila.
     */
    private fun removeCurrentFromPlaylist() {
        val index = player.currentMediaItemIndex
        if (index !in playlist.indices) return
        playlist = playlist.toMutableList().apply { removeAt(index) }
        player.removeMediaItem(index)
        if (playlist.isEmpty()) {
            viewModelScope.launch { _events.emit(VideoPlayerEvent.PlaylistEmpty) }
        } else {
            updateCurrentItemState()
        }
    }

    private fun persistResumeProgress() {
        if (!canPersistResume) return
        val video = playlist.getOrNull(player.currentMediaItemIndex) ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        viewModelScope.launch { playbackResumeStore.updatePlaybackProgress(video.sourceId, video.videoId, position) }
    }

    /** Chamado ao sair da tela do player (por qualquer motivo) — o vídeo deixou de estar "em reprodução", então não faz mais sentido reabrir o app direto nele. */
    fun clearResumePlaybackIfApplicable() {
        if (!canPersistResume) return
        viewModelScope.launch { playbackResumeStore.clearPlayback() }
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
    // Buffers bem maiores que o padrão do Media3 (pensado para mídia local rápida) — junto com o
    // ReadAheadDataSource (ver NeonDataSource), é o que faz a reprodução remota via SMB/SFTP/FTP se
    // comportar como streaming: acumula bastante mídia à frente e exige mais buffer reconstituído
    // após um travamento antes de retomar, absorvendo picos de latência/instabilidade de rede.
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 30_000,
            /* maxBufferMs = */ 90_000,
            /* bufferForPlaybackMs = */ 3_000,
            /* bufferForPlaybackAfterRebufferMs = */ 6_000,
        )
        .build()
    return ExoPlayer.Builder(application)
        .setMediaSourceFactory(mediaSourceFactory)
        .setLoadControl(loadControl)
        .build()
}
