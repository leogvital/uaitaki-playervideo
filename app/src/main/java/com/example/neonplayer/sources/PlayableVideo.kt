package com.example.neonplayer.sources

import android.net.Uri

/** Identifica a fonte de armazenamento local ao dispositivo (MediaStore). */
const val SOURCE_LOCAL = "local"

/**
 * Um vídeo reproduzível pelo player, vindo de qualquer fonte de armazenamento (local, SMB, ...).
 *
 * [sourceId] identifica de qual fonte de armazenamento o vídeo veio — usado para separar
 * favoritos por fonte, já que um favorito "pertence" ao local de onde veio. [videoId] identifica
 * o vídeo de forma única dentro dessa fonte. [sizeBytes]/[dateModifiedMs] existem em toda fonte
 * (sistema de arquivos) e permitem ordenação unificada; [durationMs] só é conhecido para vídeos
 * locais (metadados de mídia não são lidos de fontes remotas só para listar) — vale 0 quando
 * desconhecido.
 */
interface PlayableVideo {
    val sourceId: String
    val videoId: String
    val displayName: String
    val playbackUri: Uri
    val sizeBytes: Long
    val dateModifiedMs: Long
    val durationMs: Long
    val isFavorite: Boolean
}

/** Chave estável para cache (miniaturas, etc) que muda se o arquivo de origem mudar. */
val PlayableVideo.thumbnailCacheKey: String
    get() = "$sourceId:$videoId:$sizeBytes:$dateModifiedMs"
