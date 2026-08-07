package com.example.neonplayer.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.neonplayer.R
import com.example.neonplayer.sources.PlayableVideo
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONTROLS_AUTO_HIDE_MS = 5_000L

private enum class OrientationLockMode { AUTO, PORTRAIT, LANDSCAPE }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun VideoPlayerScreen(
    videos: List<PlayableVideo>,
    startIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startPositionMs: Long = 0L,
    autoPlay: Boolean = true,
    canPersistResume: Boolean = false,
    viewModel: VideoPlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(videos, startIndex) {
        viewModel.setPlaylist(videos, startIndex, startPositionMs, autoPlay, canPersistResume)
    }

    // Ao sair da tela do player por qualquer caminho (seta de voltar, gesto do sistema, playlist
    // esvaziada por exclusão), o vídeo deixa de estar "em reprodução" — se essa playlist é
    // rastreada para retomada (ver VideoPlayerViewModel.canPersistResume), limpa só essa parte do
    // estado salvo, preservando a pasta.
    DisposableEffect(Unit) {
        onDispose { viewModel.clearResumePlaybackIfApplicable() }
    }

    // Estado do controle de proporção/tela cheia.
    var resizeMode by rememberSaveable { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Estado do travamento de orientação — aplicado à Activity via LaunchedEffect abaixo.
    var orientationLockMode by rememberSaveable { mutableStateOf(OrientationLockMode.AUTO) }
    val initialOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    LaunchedEffect(orientationLockMode) {
        activity?.requestedOrientation = when (orientationLockMode) {
            OrientationLockMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            OrientationLockMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLockMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // Brilho (lado esquerdo) e volume (lado direito) — valores iniciais lidos do sistema.
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var volumeFraction by remember { mutableFloatStateOf(0.5f) }
    val initialBrightness = remember { activity?.window?.attributes?.screenBrightness ?: -1f }
    LaunchedEffect(Unit) {
        val current = activity?.window?.attributes?.screenBrightness ?: -1f
        brightness = if (current in 0f..1f) current else 0.5f
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeFraction = if (maxVolume > 0) currentVolume.toFloat() / maxVolume else 0.5f
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                val attrs = act.window.attributes
                attrs.screenBrightness = initialBrightness
                act.window.attributes = attrs
                act.requestedOrientation = initialOrientation
            }
        }
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    var isSeekDragging by remember { mutableStateOf(false) }
    var seekDragStartMs by remember { mutableStateOf(0L) }
    var seekDragTotalPx by remember { mutableFloatStateOf(0f) }

    var isVerticalDragging by remember { mutableStateOf(false) }
    var verticalDragIsLeft by remember { mutableStateOf(true) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var pendingDelete by remember { mutableStateOf(false) }

    // Ferramentas (barras de topo/rodapé) somem sozinhas após 5s em reprodução; um toque simples
    // na tela mostra/esconde. hideTimerKey reinicia a contagem sem trocar a visibilidade (ex: ao
    // apertar um botão com os controles já visíveis).
    var controlsVisible by remember { mutableStateOf(true) }
    var hideTimerKey by remember { mutableStateOf(0) }
    fun keepControlsVisible() {
        controlsVisible = true
        hideTimerKey++
    }
    LaunchedEffect(controlsVisible, uiState.isPlaying, hideTimerKey) {
        if (controlsVisible && uiState.isPlaying) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Não deixar a tela escurecer/bloquear enquanto o vídeo está em reprodução.
    DisposableEffect(uiState.isPlaying) {
        val window = activity?.window
        if (uiState.isPlaying) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val deleteIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onSystemDeleteConfirmed()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VideoPlayerEvent.RequestSystemDeleteConfirmation ->
                    deleteIntentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())

                is VideoPlayerEvent.DeleteFailed ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(event.message) }

                is VideoPlayerEvent.PlaybackError ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(event.message) }

                VideoPlayerEvent.PlaylistEmpty -> onBack()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it },
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    useController = false
                    player = viewModel.player
                }
            },
            update = { it.setResizeMode(resizeMode) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoom,
                    scaleY = zoom,
                    translationX = panOffset.x,
                    translationY = panOffset.y,
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .playerGestures(
                    isZoomed = { zoom > 1f },
                    onSingleTap = {
                        controlsVisible = !controlsVisible
                        if (controlsVisible) hideTimerKey++
                    },
                    onDoubleTap = { isLeftSide -> if (isLeftSide) viewModel.seekBack() else viewModel.seekForward() },
                    onSeekDrag = { deltaPx ->
                        if (!isSeekDragging) {
                            isSeekDragging = true
                            seekDragStartMs = uiState.currentPositionMs
                            seekDragTotalPx = 0f
                        }
                        seekDragTotalPx += deltaPx
                    },
                    onSeekDragEnd = {
                        if (isSeekDragging && boxSize.width > 0 && uiState.durationMs > 0) {
                            val fraction = seekDragTotalPx / boxSize.width.toFloat()
                            val target = (seekDragStartMs + (fraction * uiState.durationMs).toLong())
                                .coerceIn(0L, uiState.durationMs)
                            viewModel.seekToPosition(target)
                        }
                        isSeekDragging = false
                    },
                    onVerticalDrag = { isLeftSide, deltaPx ->
                        isVerticalDragging = true
                        verticalDragIsLeft = isLeftSide
                        val heightPx = boxSize.height.toFloat().coerceAtLeast(1f)
                        val fractionDelta = -deltaPx / heightPx
                        if (isLeftSide) {
                            brightness = (brightness + fractionDelta).coerceIn(0.01f, 1f)
                            activity?.let { act ->
                                val attrs = act.window.attributes
                                attrs.screenBrightness = brightness
                                act.window.attributes = attrs
                            }
                        } else {
                            volumeFraction = (volumeFraction + fractionDelta).coerceIn(0f, 1f)
                            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeFraction * maxVolume).roundToInt(), 0)
                        }
                    },
                    onVerticalDragEnd = { isVerticalDragging = false },
                    onPan = { delta ->
                        if (zoom > 1f) {
                            val maxX = boxSize.width * (zoom - 1f) / 2f
                            val maxY = boxSize.height * (zoom - 1f) / 2f
                            panOffset = Offset(
                                (panOffset.x + delta.x).coerceIn(-maxX, maxX),
                                (panOffset.y + delta.y).coerceIn(-maxY, maxY),
                            )
                        }
                    },
                    onPinchZoom = { zoomChange ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                        if (zoom <= 1f) panOffset = Offset.Zero
                    },
                ),
        )

        if (isSeekDragging && uiState.durationMs > 0) {
            val fraction = seekDragTotalPx / boxSize.width.toFloat().coerceAtLeast(1f)
            val previewMs = (seekDragStartMs + (fraction * uiState.durationMs).toLong()).coerceIn(0L, uiState.durationMs)
            Text(
                text = "${formatTimeMs(previewMs)} / ${formatTimeMs(uiState.durationMs)}",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else if (isVerticalDragging) {
            val value = if (verticalDragIsLeft) brightness else volumeFraction
            Column(
                modifier = Modifier
                    .align(if (verticalDragIsLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                )
                Text(text = "${(value * 100).roundToInt()}%", color = Color.White)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = { keepControlsVisible(); viewModel.toggleFavorite() }) {
                    Icon(
                        imageVector = if (uiState.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(if (uiState.isFavorite) R.string.remove_favorite else R.string.add_favorite),
                        tint = if (uiState.isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
                IconButton(onClick = { keepControlsVisible(); pendingDelete = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_video),
                        tint = Color.White,
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp))

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(text = uiState.currentTitle, color = Color.White)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(text = formatTimeMs(uiState.currentPositionMs), color = Color.White)
                    Slider(
                        value = uiState.currentPositionMs.toFloat().coerceIn(0f, uiState.durationMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = { keepControlsVisible(); viewModel.seekToPosition(it.toLong()) },
                        valueRange = 0f..uiState.durationMs.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(text = formatTimeMs(uiState.durationMs), color = Color.White)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { keepControlsVisible(); viewModel.toggleShuffle() }) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = stringResource(R.string.toggle_shuffle),
                            tint = if (uiState.isShuffleEnabled) MaterialTheme.colorScheme.primary else Color.White,
                        )
                    }
                    IconButton(onClick = { keepControlsVisible(); viewModel.skipToPrevious() }, enabled = uiState.hasPrevious) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = stringResource(R.string.skip_previous),
                            tint = Color.White.copy(alpha = if (uiState.hasPrevious) 1f else 0.3f),
                        )
                    }
                    IconButton(onClick = { keepControlsVisible(); viewModel.togglePlayPause() }) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (uiState.isPlaying) R.string.pause else R.string.play),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { keepControlsVisible(); viewModel.skipToNext() }, enabled = uiState.hasNext) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = stringResource(R.string.skip_next),
                            tint = Color.White.copy(alpha = if (uiState.hasNext) 1f else 0.3f),
                        )
                    }
                    IconButton(
                        onClick = {
                            keepControlsVisible()
                            resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            } else {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Icons.Filled.AspectRatio else Icons.Filled.Fullscreen,
                            contentDescription = stringResource(R.string.toggle_resize_mode),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = {
                            keepControlsVisible()
                            orientationLockMode = when (orientationLockMode) {
                                OrientationLockMode.AUTO -> OrientationLockMode.PORTRAIT
                                OrientationLockMode.PORTRAIT -> OrientationLockMode.LANDSCAPE
                                OrientationLockMode.LANDSCAPE -> OrientationLockMode.AUTO
                            }
                        },
                    ) {
                        val (icon, description) = when (orientationLockMode) {
                            OrientationLockMode.AUTO -> Icons.Filled.ScreenRotation to stringResource(R.string.orientation_lock_auto)
                            OrientationLockMode.PORTRAIT -> Icons.Filled.ScreenLockPortrait to stringResource(R.string.orientation_lock_portrait)
                            OrientationLockMode.LANDSCAPE -> Icons.Filled.ScreenLockLandscape to stringResource(R.string.orientation_lock_landscape)
                        }
                        Icon(imageVector = icon, contentDescription = description, tint = Color.White)
                    }
                }
            }
        }
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(R.string.delete_video_title)) },
            text = { Text(stringResource(R.string.delete_video_message, uiState.currentTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCurrentVideo()
                    pendingDelete = false
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
