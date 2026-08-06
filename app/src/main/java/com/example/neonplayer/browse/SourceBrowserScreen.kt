package com.example.neonplayer.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.neonplayer.R
import com.example.neonplayer.sources.remote.RemoteServerConfig

/**
 * Tela raiz da aba "Procurar" — inspirada na tela de mesmo nome do VLC para Android: seções
 * "Armazenamento" (fontes locais) e "Rede" (servidores SMB/SFTP/FTP configurados), cada fonte
 * como uma linha que leva à sua listagem de vídeos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowserScreen(
    onOpenLocalVideos: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceBrowserViewModel = viewModel(),
) {
    val servers by viewModel.servers.collectAsState()
    var pendingDelete by remember { mutableStateOf<RemoteServerConfig?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browse_title)) },
                actions = {
                    IconButton(onClick = onAddServer) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.remote_add_server),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.browse_storage_section))
            ListItem(
                headlineContent = { Text(stringResource(R.string.browse_local_videos)) },
                leadingContent = {
                    Icon(imageVector = Icons.Filled.Folder, contentDescription = null)
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.clickable { onOpenLocalVideos() },
            )
            HorizontalDivider()

            SectionHeader(stringResource(R.string.browse_network_section))
            if (servers.isEmpty()) {
                Text(
                    text = stringResource(R.string.remote_no_servers),
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            } else {
                servers.forEach { server ->
                    ListItem(
                        leadingContent = {
                            Icon(imageVector = Icons.Filled.Dns, contentDescription = null)
                        },
                        headlineContent = { Text(server.name) },
                        supportingContent = {
                            Column {
                                AssistChip(onClick = {}, enabled = false, label = { Text(server.protocol.label) })
                                Text("${server.host}:${server.port}/${server.path}")
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onEditServer(server.id) }) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.remote_edit_server),
                                    )
                                }
                                IconButton(onClick = { pendingDelete = server }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.remote_delete_server),
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                        modifier = Modifier.clickable { onOpenServer(server.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.remote_delete_server_title)) },
            text = { Text(stringResource(R.string.remote_delete_server_message, server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteServer(server)
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
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
