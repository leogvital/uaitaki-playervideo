package com.example.neonplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.example.neonplayer.browse.SourceBrowserScreen
import com.example.neonplayer.favorites.FavoritesScreen
import com.example.neonplayer.player.VideoBrowserScreen
import com.example.neonplayer.player.VideoPlayerScreen
import com.example.neonplayer.remote.RemoteServerFormScreen
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SourceRef
import com.example.neonplayer.ui.theme.NeonPlayerTheme

private sealed interface Screen {
    data class VideoList(val initialSource: SourceRef? = null) : Screen
    data object Favorites : Screen
    data object SourceBrowser : Screen
    data class Player(val videos: List<PlayableVideo>, val startIndex: Int) : Screen
    data class RemoteServerForm(val serverId: String?) : Screen
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
    val backStack = remember { mutableStateListOf<Screen>(Screen.VideoList()) }
    val current = backStack.last()
    val isTopLevel = isTopLevel(current)

    BackHandler(enabled = !isTopLevel) {
        backStack.removeAt(backStack.lastIndex)
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
                onVideoClick = { videos, index -> backStack.add(Screen.Player(videos, index)) },
            )

            is Screen.Favorites -> FavoritesScreen(
                modifier = contentModifier,
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
                onBack = { backStack.removeAt(backStack.lastIndex) },
            )

            is Screen.RemoteServerForm -> RemoteServerFormScreen(
                modifier = contentModifier,
                serverId = current.serverId,
                onBack = { backStack.removeAt(backStack.lastIndex) },
                onSaved = { backStack.removeAt(backStack.lastIndex) },
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
