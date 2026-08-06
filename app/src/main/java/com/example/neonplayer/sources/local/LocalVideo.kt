package com.example.neonplayer.sources.local

import android.net.Uri
import com.example.neonplayer.sources.PlayableVideo
import com.example.neonplayer.sources.SOURCE_LOCAL

data class LocalVideo(
    val id: Long,
    val uri: Uri,
    override val displayName: String,
    override val durationMs: Long,
    override val sizeBytes: Long,
    override val dateModifiedMs: Long,
    override val isFavorite: Boolean = false,
) : PlayableVideo {
    override val sourceId: String get() = SOURCE_LOCAL
    override val videoId: String get() = id.toString()
    override val playbackUri: Uri get() = uri
}
