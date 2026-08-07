package com.example.neonplayer.player

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neonplayer.R
import com.example.neonplayer.favorites.FavoriteCollection
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SourceRef
import com.example.neonplayer.sources.VideoViewMode
import com.example.neonplayer.sources.sortedByOption
import com.example.neonplayer.ui.SortMenuButton
import com.example.neonplayer.ui.pullToRefreshAtEnd
import kotlinx.coroutines.launch

private val requiredVideoPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoBrowserScreen(
    initialSource: SourceRef?,
    onVideoClick: (List<PlayableVideo>, Int) -> Unit,
    modifier: Modifier = Modifier,
    initialPath: String? = null,
    viewModel: VideoBrowserViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val sourceOptions by viewModel.sourceOptions.collectAsState()
    val canNavigateUp by viewModel.canNavigateUp.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val isFetchingPlayAll by viewModel.isFetchingPlayAll.collectAsState()
    val selectionModeActive by viewModel.selectionModeActive.collectAsState()
    val selectedFolders by viewModel.selectedFolders.collectAsState()
    val collections by viewModel.collections.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<PlayableVideo?>(null) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var showCollectionChooser by remember { mutableStateOf(false) }
    var showCollectionNameDialog by remember { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(VideoViewMode.LIST) }
    val sortOption by viewModel.sortOption.collectAsState()

    // Recarrega sempre que a tela volta a ficar visível (ex: voltando do player depois de excluir
    // um vídeo) — sem isso, trocar de tela e voltar para a mesma fonte não atualizava a lista.
    // initialPath só é usado nesta primeira chamada (restauração ao abrir o app — ver MainActivity);
    // navegação manual depois disso passa por navigateInto/navigateUp normalmente.
    LaunchedEffect(initialSource) {
        if (initialSource != null) viewModel.selectSource(initialSource, initialPath) else viewModel.refresh()
    }

    BackHandler(enabled = canNavigateUp || selectionModeActive) {
        if (selectionModeActive) viewModel.cancelFolderSelection() else viewModel.navigateUp()
    }

    val deleteIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onSystemDeleteConfirmed()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VideoBrowserEvent.RequestSystemDeleteConfirmation ->
                    deleteIntentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())

                is VideoBrowserEvent.DeleteFailed ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(event.message) }

                is VideoBrowserEvent.StartPlayback -> onVideoClick(event.videos.sortedByOption(sortOption), 0)

                VideoBrowserEvent.PlayAllEmpty ->
                    coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(R.string.play_all_empty)) }
            }
        }
    }

    val isLocalSource = selectedSource is SourceRef.Local
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredVideoPermission) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.refresh()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val onResumeCheck = rememberUpdatedState {
        hasPermission =
            ContextCompat.checkSelfPermission(context, requiredVideoPermission) == PackageManager.PERMISSION_GRANTED
        if (!isLocalSource || hasPermission) viewModel.refresh()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResumeCheck.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val selectedLabel = sourceOptions.find { it.ref == selectedSource }?.label
        ?: stringResource(R.string.browse_local_videos)

    Scaffold(
        modifier = modifier,
        topBar = {
            if (selectionModeActive) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.cancelFolderSelection() }) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.browse_cancel_selection))
                        }
                    },
                    title = { Text(stringResource(R.string.browse_selection_count, selectedFolders.size)) },
                    actions = {
                        IconButton(
                            enabled = selectedFolders.isNotEmpty(),
                            onClick = { showCollectionChooser = true },
                        ) {
                            Icon(imageVector = Icons.Filled.Check, contentDescription = stringResource(R.string.browse_create_collection))
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        if (canNavigateUp) {
                            IconButton(onClick = { viewModel.navigateUp() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.navigate_up),
                                )
                            }
                        }
                    },
                    title = {
                        Column {
                            Box {
                                Row(
                                    modifier = Modifier.clickable { sourceMenuExpanded = true },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(selectedLabel, maxLines = 1)
                                    Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
                                }
                                DropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                                    sourceOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                sourceMenuExpanded = false
                                                viewModel.selectSource(option.ref)
                                            },
                                        )
                                    }
                                }
                            }
                            if (canNavigateUp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.browse_navigate_up_shortcut),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable { viewModel.navigateUp() },
                                    )
                                    Text(
                                        text = formatBreadcrumbPath(currentPath),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (isFetchingPlayAll) {
                            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp))
                        } else {
                            IconButton(onClick = { viewModel.playAllInCurrentFolder() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = stringResource(R.string.play_all),
                                )
                            }
                        }
                        if (uiState.folders.isNotEmpty()) {
                            IconButton(onClick = { viewModel.enterFolderSelectionMode() }) {
                                Icon(
                                    imageVector = Icons.Filled.Checklist,
                                    contentDescription = stringResource(R.string.browse_select_folders),
                                )
                            }
                        }
                        IconButton(onClick = { viewMode = if (viewMode == VideoViewMode.LIST) VideoViewMode.GRID else VideoViewMode.LIST }) {
                            Icon(
                                imageVector = if (viewMode == VideoViewMode.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = stringResource(R.string.toggle_view_mode),
                            )
                        }
                        SortMenuButton(sortOption = sortOption, onSortOptionChange = viewModel::setSortOption)
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            val folders = uiState.folders
            val sortedVideos = uiState.videos.sortedByOption(sortOption)

            // Semeadas com a posição salva no ViewModel para não voltar ao topo da lista ao
            // retornar do player ou depois de excluir um vídeo (ver VideoBrowserViewModel).
            val listState = rememberLazyListState(viewModel.listScrollIndex, viewModel.listScrollOffset)
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                    .collect { (index, offset) ->
                        viewModel.listScrollIndex = index
                        viewModel.listScrollOffset = offset
                    }
            }
            val gridState = rememberLazyGridState(viewModel.listScrollIndex, viewModel.listScrollOffset)
            LaunchedEffect(gridState) {
                snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                    .collect { (index, offset) ->
                        viewModel.listScrollIndex = index
                        viewModel.listScrollOffset = offset
                    }
            }

            when {
                isLocalSource && !hasPermission -> PermissionRequest(
                    onRequestPermission = { permissionLauncher.launch(requiredVideoPermission) },
                )

                uiState.isLoading -> CircularProgressIndicator()

                uiState.errorMessage != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(text = uiState.errorMessage.orEmpty(), textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.remote_retry))
                    }
                }

                folders.isEmpty() && sortedVideos.isEmpty() -> Text(stringResource(R.string.no_videos_found))

                viewMode == VideoViewMode.LIST -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pullToRefreshAtEnd(
                            canScrollForward = { listState.canScrollForward },
                            enabled = !uiState.isLoading,
                            onRefresh = viewModel::refresh,
                        ),
                ) {
                    items(folders, key = { "folder:" + it.path }) { folder ->
                        FolderListRow(
                            folder = folder,
                            onClick = {
                                if (selectionModeActive) viewModel.toggleFolderSelection(folder) else viewModel.navigateInto(folder)
                            },
                            selectionMode = selectionModeActive,
                            selected = folder.path in selectedFolders,
                            onLongClick = { viewModel.toggleFolderSelection(folder) },
                        )
                        HorizontalDivider()
                    }
                    itemsIndexed(sortedVideos, key = { _, video -> video.sourceId + video.videoId }) { index, video ->
                        VideoListRow(
                            video = video,
                            subtitle = formatSubtitle(video),
                            onClick = { onVideoClick(sortedVideos, index) },
                            onToggleFavorite = { viewModel.toggleFavorite(video) },
                            onDeleteClick = { pendingDelete = video },
                        )
                        HorizontalDivider()
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .pullToRefreshAtEnd(
                            canScrollForward = { gridState.canScrollForward },
                            enabled = !uiState.isLoading,
                            onRefresh = viewModel::refresh,
                        ),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(folders, key = { "folder:" + it.path }) { folder ->
                        FolderGridCard(
                            folder = folder,
                            onClick = {
                                if (selectionModeActive) viewModel.toggleFolderSelection(folder) else viewModel.navigateInto(folder)
                            },
                            selectionMode = selectionModeActive,
                            selected = folder.path in selectedFolders,
                            onLongClick = { viewModel.toggleFolderSelection(folder) },
                        )
                    }
                    gridItemsIndexed(sortedVideos, key = { _, video -> video.sourceId + video.videoId }) { index, video ->
                        VideoGridCard(
                            video = video,
                            subtitle = formatSubtitle(video),
                            onClick = { onVideoClick(sortedVideos, index) },
                            onToggleFavorite = { viewModel.toggleFavorite(video) },
                            onDeleteClick = { pendingDelete = video },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_video_title)) },
            text = { Text(stringResource(R.string.delete_video_message, video.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteVideo(video)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showCollectionChooser) {
        AlertDialog(
            onDismissRequest = { showCollectionChooser = false },
            title = { Text(stringResource(R.string.collection_chooser_title)) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.collection_chooser_new)) },
                        leadingContent = { Icon(imageVector = Icons.Filled.Add, contentDescription = null) },
                        modifier = Modifier.clickable {
                            showCollectionChooser = false
                            showCollectionNameDialog = true
                        },
                    )
                    collections.forEach { collection ->
                        ListItem(
                            headlineContent = { Text(collection.name, maxLines = 1) },
                            supportingContent = { Text(stringResource(R.string.collection_folder_count, collection.folders.size)) },
                            leadingContent = { Icon(imageVector = Icons.Filled.Folder, contentDescription = null) },
                            modifier = Modifier.clickable {
                                showCollectionChooser = false
                                viewModel.addSelectionToCollection(collection.id)
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCollectionChooser = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showCollectionNameDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCollectionNameDialog = false },
            title = { Text(stringResource(R.string.collection_name_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.collection_name_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.createCollectionFromSelection(name.trim())
                        showCollectionNameDialog = false
                    },
                ) {
                    Text(stringResource(R.string.collection_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCollectionNameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Formata o caminho relativo da pasta atual para exibição no breadcrumb (ex.: "Filmes/Viagem/" -> "/Filmes/Viagem"). */
private fun formatBreadcrumbPath(path: String): String = "/" + path.trim('/')

@Composable
private fun PermissionRequest(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_required_message),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text(stringResource(R.string.grant_permission))
        }
    }
}
