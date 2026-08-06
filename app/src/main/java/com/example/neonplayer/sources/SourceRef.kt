package com.example.neonplayer.sources

/** Uma fonte de vídeo selecionável: o armazenamento local ou um servidor remoto configurado. */
sealed interface SourceRef {
    data object Local : SourceRef
    data class Remote(val serverId: String) : SourceRef
}

val SourceRef.favoriteSourceId: String
    get() = when (this) {
        SourceRef.Local -> SOURCE_LOCAL
        is SourceRef.Remote -> serverId
    }
