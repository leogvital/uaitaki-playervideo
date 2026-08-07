package com.example.neonplayer.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neonplayer.R
import com.example.neonplayer.player.VideoListRow
import com.example.neonplayer.player.formatSubtitle
import com.example.neonplayer.sources.PlayableVideo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collection: FavoriteCollection,
    onBack: () -> Unit,
    onVideoClick: (List<PlayableVideo>, Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(collection.id) { viewModel.load(collection) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(collection.name, maxLines = 1) },
                actions = {
                    IconButton(
                        onClick = { if (uiState.videos.isNotEmpty()) onVideoClick(uiState.videos, 0) },
                        enabled = uiState.videos.isNotEmpty(),
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = stringResource(R.string.play_all))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator()
                uiState.videos.isEmpty() -> Text(stringResource(R.string.collection_empty))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(uiState.videos, key = { _, video -> video.sourceId + video.videoId }) { index, video ->
                        VideoListRow(
                            video = video,
                            subtitle = formatSubtitle(video),
                            onClick = { onVideoClick(uiState.videos, index) },
                            onToggleFavorite = { viewModel.toggleFavorite(video) },
                            onDeleteClick = null,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
