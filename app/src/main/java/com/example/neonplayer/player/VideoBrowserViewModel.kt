package com.example.neonplayer.player

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.neonplayer.R
import com.example.neonplayer.favorites.CollectionFolderRef
import com.example.neonplayer.favorites.FavoriteCollection
import com.example.neonplayer.favorites.FavoriteCollectionsRepository
import com.example.neonplayer.favorites.FavoritesRepository
import com.example.neonplayer.sources.BrowseFolder
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.PlaybackResumeStore
import com.example.neonplayer.sources.RecursiveVideoFetcher
import com.example.neonplayer.sources.SortDirection
import com.example.neonplayer.sources.SortField
import com.example.neonplayer.sources.SortOption
import com.example.neonplayer.sources.SortPreferences
import com.example.neonplayer.sources.SourceRef
import com.example.neonplayer.sources.favoriteSourceId
import com.example.neonplayer.sources.ftp.FtpVideoRepository
import com.example.neonplayer.sources.local.DeleteVideoResult
import com.example.neonplayer.sources.local.LocalVideo
import com.example.neonplayer.sources.local.LocalVideoRepository
import com.example.neonplayer.sources.remote.RemoteCredentialStore
import com.example.neonplayer.sources.remote.RemoteDeleteResult
import com.example.neonplayer.sources.remote.RemoteListResult
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.RemoteVideo
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.sftp.SftpVideoRepository
import com.example.neonplayer.sources.smb.SmbVideoRepository
import com.example.neonplayer.sources.smb.splitShareAndPath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Miniaturas geradas ao mesmo tempo pelo pré-carregamento em segundo plano de uma pasta (ver [VideoBrowserViewModel.prefetchThumbnails]). */
private const val THUMBNAIL_PREFETCH_CONCURRENCY = 3

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
    data class StartPlayback(val videos: List<PlayableVideo>) : VideoBrowserEvent
    data object PlayAllEmpty : VideoBrowserEvent
}

class VideoBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val localVideoRepository = LocalVideoRepository(application)
    private val remoteServerRepository = RemoteServerRepository(application)
    private val smbRepository = SmbVideoRepository(application)
    private val sftpRepository = SftpVideoRepository(application)
    private val ftpRepository = FtpVideoRepository(application)
    private val favoritesRepository = FavoritesRepository(application)
    private val favoriteCollectionsRepository = FavoriteCollectionsRepository(application)
    private val sortPreferences = SortPreferences(application)
    private val playbackResumeStore = PlaybackResumeStore(application)
    private val remoteCredentialStore = RemoteCredentialStore(application)

    /** Pré-carregamento de miniaturas da pasta atual — cancelado a cada nova navegação, ver [prefetchThumbnails]. */
    private var thumbnailPrefetchJob: Job? = null

    val sortOption: StateFlow<SortOption> = sortPreferences.sortOptionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortOption(SortField.DATE, SortDirection.DESCENDING))

    fun setSortOption(option: SortOption) {
        viewModelScope.launch { sortPreferences.setSortOption(option) }
    }
    private val recursiveVideoFetcher = RecursiveVideoFetcher(
        localVideoRepository, remoteServerRepository, smbRepository, sftpRepository, ftpRepository,
    )

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

    /**
     * Posição de scroll da listagem atual, lida uma única vez ao (re)criar a tela e atualizada
     * continuamente por ela — sobrevive à ida-e-volta pro Player porque este ViewModel não é
     * recriado (é efetivamente singleton por Activity via [androidx.lifecycle.viewmodel.compose.viewModel]),
     * só a `LazyListState`/`LazyGridState` da composable é que se perde ao desmontar a tela.
     */
    var listScrollIndex: Int = 0
    var listScrollOffset: Int = 0

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

    private val _isFetchingPlayAll = MutableStateFlow(false)
    val isFetchingPlayAll: StateFlow<Boolean> = _isFetchingPlayAll.asStateFlow()

    /** Ativado pelo botão "Selecionar pastas" da barra de ferramentas ou por toque-e-segure numa pasta. */
    private val _selectionModeActive = MutableStateFlow(false)
    val selectionModeActive: StateFlow<Boolean> = _selectionModeActive.asStateFlow()

    /** Pastas marcadas para virar uma nova coleção (ou entrar numa já existente) — só existe dentro do nível atual (limpa ao navegar/trocar fonte). */
    private val _selectedFolders = MutableStateFlow<Set<String>>(emptySet())
    val selectedFolders: StateFlow<Set<String>> = _selectedFolders.asStateFlow()

    val collections: Flow<List<FavoriteCollection>> = favoriteCollectionsRepository.collectionsFlow

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

    /**
     * Troca a fonte selecionada e recarrega. Ignorado se já for a fonte atual. [initialPath], se
     * informado (usado só ao restaurar a última pasta navegada no início do app — ver
     * [MainActivity]), abre direto nessa pasta em vez da raiz; a pilha de navegação nesse caso só
     * tem 2 níveis (raiz + pasta restaurada) em vez do caminho completo intermediário — uma
     * simplificação aceitável, já que a navegação normal a partir daí funciona como sempre.
     */
    fun selectSource(source: SourceRef, initialPath: String? = null) {
        if (source == _selectedSource.value) return
        _selectedSource.value = source
        cancelFolderSelection()
        resetScrollPosition()
        viewModelScope.launch {
            val root = rootPathFor(source)
            val startPath = initialPath?.takeIf { it.isNotBlank() && it != root } ?: root
            folderStack.clear()
            folderStack.add(root)
            if (startPath != root) folderStack.add(startPath)
            _currentPath.value = startPath
            _canNavigateUp.value = folderStack.size > 1
            refresh()
        }
    }

    private fun resetScrollPosition() {
        listScrollIndex = 0
        listScrollOffset = 0
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
        cancelFolderSelection()
        resetScrollPosition()
        refresh()
    }

    /** @return true se subiu um nível (havia para onde voltar), false se já estava na raiz da fonte. */
    fun navigateUp(): Boolean {
        if (folderStack.size <= 1) return false
        folderStack.removeAt(folderStack.lastIndex)
        _currentPath.value = folderStack.last()
        _canNavigateUp.value = folderStack.size > 1
        cancelFolderSelection()
        resetScrollPosition()
        refresh()
        return true
    }

    /** Liga o modo de seleção de pastas a partir do botão da barra de ferramentas, sem nenhuma pasta marcada ainda. */
    fun enterFolderSelectionMode() {
        _selectionModeActive.value = true
    }

    /** Alterna a seleção de uma pasta — também liga o modo de seleção, para o atalho de toque-e-segure funcionar sem passar pelo botão da barra de ferramentas. */
    fun toggleFolderSelection(folder: BrowseFolder) {
        _selectionModeActive.value = true
        _selectedFolders.value = if (folder.path in _selectedFolders.value) {
            _selectedFolders.value - folder.path
        } else {
            _selectedFolders.value + folder.path
        }
    }

    fun cancelFolderSelection() {
        _selectionModeActive.value = false
        _selectedFolders.value = emptySet()
    }

    private fun selectedFolderRefs(): List<CollectionFolderRef> {
        val sourceId = _selectedSource.value.favoriteSourceId
        return _selectedFolders.value.map { path ->
            val label = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
            CollectionFolderRef(sourceId = sourceId, path = path, label = label)
        }
    }

    /** Cria uma nova coleção de favoritos a partir das pastas marcadas na fonte atual. */
    fun createCollectionFromSelection(name: String) {
        val folders = selectedFolderRefs()
        if (folders.isEmpty()) return
        viewModelScope.launch {
            favoriteCollectionsRepository.createCollection(name, folders)
            cancelFolderSelection()
        }
    }

    /** Adiciona as pastas marcadas a uma coleção de favoritos já existente. */
    fun addSelectionToCollection(collectionId: String) {
        val folders = selectedFolderRefs()
        if (folders.isEmpty()) return
        viewModelScope.launch {
            favoriteCollectionsRepository.addFoldersToCollection(collectionId, folders)
            cancelFolderSelection()
        }
    }

    /** Junta (recursivamente) todos os vídeos da pasta atual e inicia a reprodução como uma única lista. */
    fun playAllInCurrentFolder() {
        viewModelScope.launch {
            _isFetchingPlayAll.value = true
            val videos = recursiveVideoFetcher.fetchAll(_selectedSource.value, _currentPath.value)
            _isFetchingPlayAll.value = false
            if (videos.isEmpty()) {
                _events.emit(VideoBrowserEvent.PlayAllEmpty)
            } else {
                _events.emit(VideoBrowserEvent.StartPlayback(videos))
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            isLoading.value = true
            errorMessage.value = null
            val path = _currentPath.value
            val source = _selectedSource.value
            // Salva a localização de navegação a cada recarga (inclusive a inicial) — é o que
            // permite reabrir o app depois de fechado direto nessa fonte/pasta (ver MainActivity).
            playbackResumeStore.saveBrowseLocation(source, path)
            when (source) {
                SourceRef.Local -> {
                    runCatching { localVideoRepository.browse(path) }
                        .onSuccess { result ->
                            rawFolders.value = result.folders
                            rawVideos.value = result.videos
                            isLoading.value = false
                            prefetchThumbnails(result.videos)
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
                            prefetchThumbnails(result.videos)
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

    /**
     * Gera (e cacheia em disco) a miniatura de todo vídeo da pasta que acabou de carregar, não só
     * os visíveis na tela — é o que faz reabrir uma pasta já visitada (ou rolar por ela) parecer
     * instantâneo, ao custo de gerar isso uma vez em segundo plano quando a pasta é aberta.
     * Cancelado ao navegar para outra pasta/fonte (não faz sentido continuar gerando miniatura de
     * uma pasta que o usuário já não está olhando). Reaproveita [loadOrGenerateThumbnail] — a mesma
     * função usada pela miniatura visível na tela — que já evita gerar a mesma miniatura duas vezes
     * caso as duas peçam ao mesmo tempo.
     */
    private fun prefetchThumbnails(videos: List<PlayableVideo>) {
        thumbnailPrefetchJob?.cancel()
        thumbnailPrefetchJob = viewModelScope.launch {
            val semaphore = Semaphore(THUMBNAIL_PREFETCH_CONCURRENCY)
            videos.map { video ->
                async {
                    semaphore.withPermit {
                        loadOrGenerateThumbnail(getApplication(), video, remoteServerRepository, remoteCredentialStore)
                    }
                }
            }.awaitAll()
        }
    }

    fun toggleFavorite(video: PlayableVideo) {
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(video.sourceId, video.videoId)
        }
    }

    /** Vídeo aguardando confirmação do diálogo do sistema (escopo de armazenamento local) — guardado aqui para permitir remoção otimista da lista quando a confirmação chegar (ver [onSystemDeleteConfirmed]). */
    private var pendingSystemDeleteVideo: PlayableVideo? = null

    fun deleteVideo(video: PlayableVideo) {
        viewModelScope.launch {
            when (val source = _selectedSource.value) {
                SourceRef.Local -> {
                    val localVideo = video as? LocalVideo ?: return@launch
                    when (val result = localVideoRepository.deleteVideo(localVideo)) {
                        DeleteVideoResult.Success -> removeVideoLocally(localVideo)
                        is DeleteVideoResult.RequiresConfirmation -> {
                            pendingSystemDeleteVideo = localVideo
                            _events.emit(VideoBrowserEvent.RequestSystemDeleteConfirmation(result.intentSender))
                        }

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
                        RemoteDeleteResult.Success -> removeVideoLocally(remoteVideo)
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

    /**
     * Remove o vídeo da lista em memória sem relistar a fonte inteira — uma exclusão remota já
     * fez uma viagem de rede (connect+auth+delete); relistar em seguida (connect+auth+list) só
     * para refletir uma remoção que já sabemos que aconteceu dobra o tempo de espera à toa.
     */
    private fun removeVideoLocally(video: PlayableVideo) {
        rawVideos.value = rawVideos.value.filterNot { it.videoId == video.videoId }
    }

    /** Chamado após o usuário confirmar a exclusão no diálogo do sistema (fluxo de escopo de armazenamento local). */
    fun onSystemDeleteConfirmed() {
        val video = pendingSystemDeleteVideo
        pendingSystemDeleteVideo = null
        if (video != null) removeVideoLocally(video) else refresh()
    }
}
