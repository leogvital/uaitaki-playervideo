package com.example.neonplayer.favorites

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neonplayer.R
import com.example.neonplayer.player.VideoGridCard
import com.example.neonplayer.player.VideoListRow
import com.example.neonplayer.player.formatSubtitle
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.VideoViewMode
import com.example.neonplayer.sources.sortedByOption
import com.example.neonplayer.ui.SortMenuButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onVideoClick: (List<PlayableVideo>, Int) -> Unit,
    onCollectionClick: (FavoriteCollection) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = viewModel(),
    collectionsViewModel: CollectionsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val collections by collectionsViewModel.collections.collectAsState(initial = emptyList())
    var viewMode by rememberSaveable { mutableStateOf(VideoViewMode.LIST) }
    var pendingDeleteCollection by remember { mutableStateOf<FavoriteCollection?>(null) }
    val sortOption by viewModel.sortOption.collectAsState()

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
                    SortMenuButton(sortOption = sortOption, onSortOptionChange = viewModel::setSortOption)
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

                sortedVideos.isEmpty() && collections.isEmpty() -> Text(stringResource(R.string.favorites_empty))

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
                            if (collections.isNotEmpty()) {
                                item(key = "header:collections") { SectionHeader(stringResource(R.string.favorites_collections_section)) }
                                itemsIndexed(collections, key = { _, collection -> "collection:" + collection.id }) { _, collection ->
                                    CollectionRow(
                                        collection = collection,
                                        onClick = { onCollectionClick(collection) },
                                        onDeleteClick = { pendingDeleteCollection = collection },
                                    )
                                    HorizontalDivider()
                                }
                            }
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
                            if (collections.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "header:collections") {
                                    SectionHeader(stringResource(R.string.favorites_collections_section))
                                }
                                collections.forEach { collection ->
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "collection:" + collection.id) {
                                        CollectionRow(
                                            collection = collection,
                                            onClick = { onCollectionClick(collection) },
                                            onDeleteClick = { pendingDeleteCollection = collection },
                                        )
                                    }
                                }
                            }
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

    pendingDeleteCollection?.let { collection ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCollection = null },
            title = { Text(stringResource(R.string.collection_delete_title)) },
            text = { Text(stringResource(R.string.collection_delete_message, collection.name)) },
            confirmButton = {
                TextButton(onClick = {
                    collectionsViewModel.deleteCollection(collection.id)
                    pendingDeleteCollection = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCollection = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CollectionRow(
    collection: FavoriteCollection,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.collection_folder_count, collection.folders.size)) },
        leadingContent = { Icon(imageVector = Icons.Filled.Folder, contentDescription = null) },
        trailingContent = {
            IconButton(onClick = onDeleteClick) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = stringResource(R.string.collection_delete))
            }
        },
        modifier = modifier.clickable(onClick = onClick),
    )
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
