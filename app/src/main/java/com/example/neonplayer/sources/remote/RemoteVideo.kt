package com.example.neonplayer.sources.remote

import android.net.Uri
import com.example.neonplayer.sources.PlayableVideo

data class RemoteVideo(
    val serverId: String,
    val protocol: ServerProtocol,
    val remotePath: String,
    override val displayName: String,
    override val sizeBytes: Long,
    override val dateModifiedMs: Long,
    override val isFavorite: Boolean = false,
) : PlayableVideo {
    override val sourceId: String get() = serverId
    override val videoId: String get() = remotePath
    override val playbackUri: Uri get() = remotePlaybackUri(protocol, serverId, remotePath)
    // Duração exigiria ler metadados do arquivo remoto (não disponível na listagem de diretório).
    override val durationMs: Long get() = 0L
}
