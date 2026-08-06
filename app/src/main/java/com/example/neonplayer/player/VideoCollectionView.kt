package com.example.neonplayer.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.neonplayer.R
import com.example.neonplayer.sources.BrowseFolder
import com.example.neonplayer.sources.PlayableVideo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.pow

/**
 * Linha/card de pasta e de vídeo, compartilhados entre a navegação (local + remoto, que também
 * lista subpastas) e a aba de favoritos (que agrupa vídeos de várias fontes em seções, sem
 * pastas). Ficam neste arquivo em vez de um `VideoCollectionView` monolítico porque cada tela
 * precisa montar seu próprio `LazyColumn`/`LazyVerticalGrid` (a de favoritos intercala cabeçalhos
 * de seção entre os itens, o que não dá pra fazer dentro de um componente genérico só com uma
 * lista plana).
 */
@Composable
fun FolderListRow(folder: BrowseFolder, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(imageVector = Icons.Filled.Folder, contentDescription = stringResource(R.string.open_folder))
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
fun FolderGridCard(folder: BrowseFolder, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.Folder, contentDescription = stringResource(R.string.open_folder))
            Text(
                text = folder.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
fun VideoListRow(
    video: PlayableVideo,
    subtitle: String,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(video.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle) },
        leadingContent = {
            VideoThumbnail(
                video = video,
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (video.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(
                            if (video.isFavorite) R.string.remove_favorite else R.string.add_favorite,
                        ),
                        tint = if (video.isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                if (onDeleteClick != null) {
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete_video),
                        )
                    }
                }
            }
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
fun VideoGridCard(
    video: PlayableVideo,
    subtitle: String,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box {
            VideoThumbnail(
                video = video,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
            IconButton(onClick = onToggleFavorite, modifier = Modifier.padding(4.dp)) {
                Icon(
                    imageVector = if (video.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(
                        if (video.isFavorite) R.string.remove_favorite else R.string.add_favorite,
                    ),
                    tint = if (video.isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(video.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                if (onDeleteClick != null) {
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete_video),
                        )
                    }
                }
            }
        }
    }
}

private val dateFormatter = SimpleDateFormat("dd/MM/yyyy", Locale.Builder().setLanguage("pt").setRegion("BR").build())

fun formatSubtitle(video: PlayableVideo, sourceLabel: String? = null): String {
    val parts = mutableListOf<String>()
    parts += formatFileSize(video.sizeBytes)
    if (video.durationMs > 0) parts += formatDuration(video.durationMs)
    if (video.dateModifiedMs > 0) parts += dateFormatter.format(video.dateModifiedMs)
    if (sourceLabel != null) parts += sourceLabel
    return parts.joinToString(" · ")
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups)
    return "%.1f %s".format(value, units[digitGroups])
}
