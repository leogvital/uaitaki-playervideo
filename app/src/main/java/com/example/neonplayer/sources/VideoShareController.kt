package com.example.neonplayer.sources

import android.net.Uri
import com.example.neonplayer.sources.local.LocalVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ShareUiState {
    data object Idle : ShareUiState

    /** [progress] vai de 0 a 1, ou null quando o tamanho do arquivo remoto não é conhecido. */
    data class Preparing(val progress: Float?) : ShareUiState
    data class Ready(val uri: Uri, val mimeType: String, val displayName: String) : ShareUiState
    data class Failed(val message: String) : ShareUiState
}

/**
 * Wrapper fino sobre [VideoShareRepository] que expõe o progresso como [StateFlow] para a UI —
 * vídeo local resolve na hora (pula direto para [ShareUiState.Ready], sem passar por
 * [ShareUiState.Preparing], que faria a caixa de progresso só piscar); vídeo remoto passa por
 * [ShareUiState.Preparing] enquanto é baixado (ver [VideoShareRepository]).
 */
class VideoShareController(
    private val scope: CoroutineScope,
    private val repository: VideoShareRepository,
) {
    private val _state = MutableStateFlow<ShareUiState>(ShareUiState.Idle)
    val state: StateFlow<ShareUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun share(video: PlayableVideo) {
        job?.cancel()
        job = scope.launch {
            if (video !is LocalVideo) _state.value = ShareUiState.Preparing(progress = null)
            val result = repository.prepareForShare(video) { progress ->
                _state.value = ShareUiState.Preparing(progress)
            }
            _state.value = when (result) {
                is VideoShareResult.Ready -> ShareUiState.Ready(result.uri, result.mimeType, video.displayName)
                is VideoShareResult.Error -> ShareUiState.Failed(result.message)
            }
        }
    }

    /** Chamado pela UI depois de disparar o Intent.ACTION_SEND (estado [ShareUiState.Ready] só deve ser consumido uma vez) ou de o usuário fechar o diálogo de erro. */
    fun consume() {
        _state.value = ShareUiState.Idle
    }

    /** Cancela um download remoto em andamento (usuário fechou o diálogo de progresso). */
    fun cancel() {
        job?.cancel()
        _state.value = ShareUiState.Idle
    }
}
