package com.example.neonplayer.sources.remote

import android.net.Uri

/**
 * Vídeos remotos não têm uma URI nativa que o ExoPlayer entenda — usamos um esquema próprio por
 * protocolo (`serverId` na autoridade, caminho remoto como query param) que [NeonDataSource]
 * reconhece e roteia para o [androidx.media3.datasource.DataSource] do protocolo correto.
 */
private fun schemeFor(protocol: ServerProtocol): String = "neonplayer-${protocol.name.lowercase()}"

internal fun remotePlaybackUri(protocol: ServerProtocol, serverId: String, remotePath: String): Uri =
    Uri.Builder()
        .scheme(schemeFor(protocol))
        .authority(serverId)
        .appendQueryParameter("path", remotePath)
        .build()

internal fun Uri.remoteProtocolOrNull(): ServerProtocol? =
    ServerProtocol.entries.find { schemeFor(it) == scheme }

internal val Uri.remoteServerId: String? get() = authority

internal val Uri.remoteRemotePath: String? get() = getQueryParameter("path")
