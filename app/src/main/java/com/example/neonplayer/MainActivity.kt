package com.example.neonplayer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.neonplayer.browse.SourceBrowserScreen
import com.example.neonplayer.favorites.CollectionDetailScreen
import com.example.neonplayer.favorites.FavoriteCollection
import com.example.neonplayer.favorites.FavoritesScreen
import com.example.neonplayer.player.VideoBrowserScreen
import com.example.neonplayer.player.VideoPlayerScreen
import com.example.neonplayer.remote.RemoteServerFormScreen
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.PlaybackResumeStore
import com.example.neonplayer.sources.SortPreferences
import com.example.neonplayer.sources.SourceRef
import com.example.neonplayer.sources.ftp.FtpVideoRepository
import com.example.neonplayer.sources.local.LocalVideoRepository
import com.example.neonplayer.sources.remote.RemoteListResult
import com.example.neonplayer.sources.remote.RemoteServerRepository
import com.example.neonplayer.sources.remote.ServerProtocol
import com.example.neonplayer.sources.sftp.SftpVideoRepository
import com.example.neonplayer.sources.smb.SmbVideoRepository
import com.example.neonplayer.sources.sortedByOption
import com.example.neonplayer.ui.theme.NeonPlayerTheme
import kotlinx.coroutines.flow.first

private sealed interface Screen {
    data class VideoList(val initialSource: SourceRef? = null, val initialPath: String? = null) : Screen
    data object Favorites : Screen
    data object SourceBrowser : Screen
    data class Player(
        val videos: List<PlayableVideo>,
        val startIndex: Int,
        val startPositionMs: Long = 0L,
        val autoPlay: Boolean = true,
        val canPersistResume: Boolean = false,
    ) : Screen
    data class RemoteServerForm(val serverId: String?) : Screen
    data class CollectionDetail(val collection: FavoriteCollection) : Screen
}

/**
 * Resolve a pilha de telas inicial a partir do último estado salvo em [PlaybackResumeStore] — usado
 * para reabrir o app depois de fechado de verdade (processo encerrado, não só minimizado) exatamente
 * onde o usuário parou: na pasta que estava navegando, ou no player pausado no vídeo/posição salvos.
 *
 * Sempre devolve pelo menos uma tela de nível superior (ver [isTopLevel]) na base da pilha — quando
 * a restauração cai no player, a lista da pasta correspondente entra por baixo dele. Sem isso, abrir
 * o app direto no player deixava a pilha com um único item; ao voltar (seta ou gesto do sistema),
 * [NeonPlayerApp] removia esse único item e ficava com uma pilha vazia, derrubando o app.
 */
private suspend fun resolveInitialScreen(context: Context): List<Screen> {
    val state = PlaybackResumeStore(context).currentState() ?: return listOf(Screen.VideoList())

    val source: SourceRef = if (state.sourceIsLocal) {
        SourceRef.Local
    } else {
        val serverId = state.remoteServerId ?: return listOf(Screen.VideoList())
        SourceRef.Remote(serverId)
    }
    val savedPath = state.folderPath.takeIf { it.isNotBlank() }
    val browseScreen = Screen.VideoList(source, savedPath)
    val videoSourceId = state.videoSourceId
    val videoId = state.videoId
    if (videoSourceId == null || videoId == null) {
        return listOf(browseScreen)
    }

    // Havia um vídeo em reprodução — tenta relistar a pasta salva para reabrir o player nele,
    // pausado, na posição salva; se a pasta não existir mais (servidor fora do ar, pasta apagada)
    // ou o vídeo não estiver mais lá (excluído/movido), cai para simplesmente abrir a listagem
    // daquela pasta, que já tem tratamento de erro/retry.
    return runCatching {
        val sortOption = SortPreferences(context).sortOptionFlow.first()
        val videos = browseFolderForResume(context, source, state.folderPath).sortedByOption(sortOption)
        val index = videos.indexOfFirst { it.sourceId == videoSourceId && it.videoId == videoId }
        if (index >= 0) {
            listOf(
                browseScreen,
                Screen.Player(videos, index, startPositionMs = state.positionMs, autoPlay = false, canPersistResume = true),
            )
        } else {
            listOf(browseScreen)
        }
    }.getOrElse { listOf(browseScreen) }
}

private suspend fun browseFolderForResume(context: Context, source: SourceRef, path: String): List<PlayableVideo> =
    when (source) {
        SourceRef.Local -> LocalVideoRepository(context).browse(path).videos
        is SourceRef.Remote -> {
            val config = RemoteServerRepository(context).getServer(source.serverId)
            if (config == null) {
                emptyList()
            } else {
                val result = when (config.protocol) {
                    ServerProtocol.SMB -> SmbVideoRepository(context).listVideos(config, path)
                    ServerProtocol.SFTP -> SftpVideoRepository(context).listVideos(config, path)
                    ServerProtocol.FTP -> FtpVideoRepository(context).listVideos(config, path)
                }
                (result as? RemoteListResult.Success)?.videos ?: emptyList()
            }
        }
    }

private fun isTopLevel(screen: Screen): Boolean =
    screen is Screen.VideoList || screen is Screen.Favorites || screen is Screen.SourceBrowser

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeonPlayerTheme {
                NeonPlayerApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun NeonPlayerApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var initialScreens by remember { mutableStateOf<List<Screen>?>(null) }
    LaunchedEffect(Unit) { initialScreens = resolveInitialScreen(context.applicationContext) }

    val resolvedInitialScreens = initialScreens
    if (resolvedInitialScreens == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val backStack = remember(resolvedInitialScreens) { mutableStateListOf<Screen>().apply { addAll(resolvedInitialScreens) } }
    val current = backStack.last()
    val isTopLevel = isTopLevel(current)

    // Nunca deixa a pilha ficar vazia — resolveInitialScreen já garante uma base de nível superior,
    // mas manter essa checagem aqui evita repetir o crash (pilha vazia -> backStack.last() explode)
    // se algum caminho futuro empurrar uma tela sem uma base por baixo.
    fun popBackStack() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    BackHandler(enabled = !isTopLevel) {
        popBackStack()
    }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (isTopLevel) {
                NeonBottomNavigation(
                    current = current,
                    onSelect = { screen ->
                        backStack.clear()
                        backStack.add(screen)
                    },
                )
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)

        when (current) {
            is Screen.VideoList -> VideoBrowserScreen(
                modifier = contentModifier,
                initialSource = current.initialSource,
                initialPath = current.initialPath,
                onVideoClick = { videos, index -> backStack.add(Screen.Player(videos, index, canPersistResume = true)) },
            )

            is Screen.Favorites -> FavoritesScreen(
                modifier = contentModifier,
                onVideoClick = { videos, index -> backStack.add(Screen.Player(videos, index)) },
                onCollectionClick = { collection -> backStack.add(Screen.CollectionDetail(collection)) },
            )

            is Screen.CollectionDetail -> CollectionDetailScreen(
                modifier = contentModifier,
                collection = current.collection,
                onBack = { popBackStack() },
                onVideoClick = { videos, index -> backStack.add(Screen.Player(videos, index)) },
            )

            is Screen.SourceBrowser -> SourceBrowserScreen(
                modifier = contentModifier,
                onOpenLocalVideos = {
                    backStack.clear()
                    backStack.add(Screen.VideoList(SourceRef.Local))
                },
                onAddServer = { backStack.add(Screen.RemoteServerForm(serverId = null)) },
                onEditServer = { serverId -> backStack.add(Screen.RemoteServerForm(serverId)) },
                onOpenServer = { serverId ->
                    backStack.clear()
                    backStack.add(Screen.VideoList(SourceRef.Remote(serverId)))
                },
            )

            is Screen.Player -> VideoPlayerScreen(
                modifier = contentModifier,
                videos = current.videos,
                startIndex = current.startIndex,
                startPositionMs = current.startPositionMs,
                autoPlay = current.autoPlay,
                canPersistResume = current.canPersistResume,
                onBack = { popBackStack() },
            )

            is Screen.RemoteServerForm -> RemoteServerFormScreen(
                modifier = contentModifier,
                serverId = current.serverId,
                onBack = { popBackStack() },
                onSaved = { popBackStack() },
            )
        }
    }
}

@Composable
private fun NeonBottomNavigation(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier, containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = current is Screen.VideoList,
            onClick = { onSelect(Screen.VideoList()) },
            icon = { Icon(imageVector = Icons.Filled.Movie, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_videos)) },
            colors = neonNavigationItemColors(),
        )
        NavigationBarItem(
            selected = current is Screen.Favorites,
            onClick = { onSelect(Screen.Favorites) },
            icon = { Icon(imageVector = Icons.Filled.Star, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_favorites)) },
            colors = neonNavigationItemColors(),
        )
        NavigationBarItem(
            selected = current is Screen.SourceBrowser,
            onClick = { onSelect(Screen.SourceBrowser) },
            icon = { Icon(imageVector = Icons.Filled.Folder, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_browse)) },
            colors = neonNavigationItemColors(),
        )
    }
}

/** Estilo "chato" do VLC: ícone/rótulo tingidos de laranja quando selecionados, sem pílula de fundo. */
@Composable
private fun neonNavigationItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    indicatorColor = Color.Transparent,
)
