package com.example.neonplayer.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neonplayer.R
import com.example.neonplayer.player.VideoGridCard
import com.example.neonplayer.player.VideoListRow
import com.example.neonplayer.player.formatSubtitle
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SortDirection
import com.example.neonplayer.sources.SortField
import com.example.neonplayer.sources.SortOption
import com.example.neonplayer.sources.VideoViewMode
import com.example.neonplayer.sources.sortedByOption

/**
 * Favoritos agregados de todas as fontes, divididos em seções por origem (igual à navegação de
 * vídeos ter suas seções em "Procurar") — não usa [com.example.neonplayer.player.FolderListRow]
 * pois favoritos nunca representam pastas, só vídeos já achatados.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onVideoClick: (List<PlayableVideo>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var viewMode by rememberSaveable { mutableStateOf(VideoViewMode.LIST) }
    val sortOption = SortOption(SortField.DATE, SortDirection.DESCENDING)

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title)) },
                actions = {
                    IconButton(onClick = { viewMode = if (viewMode == VideoViewMode.LIST) VideoViewMode.GRID else VideoViewMode.LIST }) {
                        Icon(
                            imageVector = if (viewMode == VideoViewMode.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = stringResource(R.string.toggle_view_mode),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            val sortedVideos = uiState.videos.sortedByOption(sortOption)
            val groups = sortedVideos.groupBy { it.sourceId }
                .map { (sourceId, videos) -> (uiState.sourceLabels[sourceId] ?: sourceId) to videos }

            when {
                uiState.isLoading -> CircularProgressIndicator()

                sortedVideos.isEmpty() -> Text(stringResource(R.string.favorites_empty))

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (uiState.partialErrorMessage != null) {
                        Text(
                            text = uiState.partialErrorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (viewMode == VideoViewMode.LIST) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            groups.forEach { (label, videos) ->
                                item(key = "header:$label") { SectionHeader(label) }
                                itemsIndexed(videos, key = { _, video -> video.sourceId + video.videoId }) { _, video ->
                                    VideoListRow(
                                        video = video,
                                        subtitle = formatSubtitle(video),
                                        onClick = { onVideoClick(sortedVideos, sortedVideos.indexOf(video)) },
                                        onToggleFavorite = { viewModel.toggleFavorite(video) },
                                        onDeleteClick = null,
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp),
                        ) {
                            groups.forEach { (label, videos) ->
                                item(span = { GridItemSpan(maxLineSpan) }, key = "header:$label") {
                                    SectionHeader(label)
                                }
                                gridItemsIndexed(videos, key = { _, video -> video.sourceId + video.videoId }) { _, video ->
                                    VideoGridCard(
                                        video = video,
                                        subtitle = formatSubtitle(video),
                                        onClick = { onVideoClick(sortedVideos, sortedVideos.indexOf(video)) },
                                        onToggleFavorite = { viewModel.toggleFavorite(video) },
                                        onDeleteClick = null,
                                        modifier = Modifier.padding(4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
