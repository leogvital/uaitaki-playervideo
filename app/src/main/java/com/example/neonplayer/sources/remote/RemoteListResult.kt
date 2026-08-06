package com.example.neonplayer.sources.remote

import com.example.neonplayer.sources.BrowseFolder

sealed interface RemoteListResult {
    data class Success(val folders: List<BrowseFolder>, val videos: List<RemoteVideo>) : RemoteListResult
    data class Error(val message: String) : RemoteListResult
}

/** Extensões de arquivo tratadas como vídeo em qualquer protocolo remoto. */
internal val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts", "mpg", "mpeg",
)

internal fun hasVideoExtension(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

/** "." e ".." não são entradas navegáveis de verdade — algumas bibliotecas os incluem na listagem, outras não. */
internal fun isDotEntry(name: String): Boolean = name == "." || name == ".."
