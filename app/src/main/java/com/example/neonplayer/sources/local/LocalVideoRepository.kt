package com.example.neonplayer.sources.local

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.neonplayer.sources.BrowseFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DeleteVideoResult {
    data object Success : DeleteVideoResult
    data class RequiresConfirmation(val intentSender: IntentSender) : DeleteVideoResult
    data object Failed : DeleteVideoResult
}

data class LocalBrowseResult(val folders: List<BrowseFolder>, val videos: List<LocalVideo>)

class LocalVideoRepository(private val context: Context) {

    /** Todos os vídeos do dispositivo, de qualquer pasta — usado pelos Favoritos, que precisa achar um vídeo favoritado sem saber em qual pasta ele está. */
    suspend fun listAllVideos(): List<LocalVideo> = withContext(Dispatchers.IO) {
        queryAll().map { it.first }
    }

    /**
     * Lista o conteúdo de uma pasta: subpastas diretas + vídeos diretamente nela, usando
     * [MediaStore.MediaColumns.RELATIVE_PATH] para agrupar (não é acesso a sistema de arquivos —
     * continua tudo via MediaStore, sem SAF/permissões extras). [folderPath] vazio = raiz do
     * armazenamento de mídia.
     */
    suspend fun browse(folderPath: String): LocalBrowseResult = withContext(Dispatchers.IO) {
        val normalized = normalizeFolderPath(folderPath)
        val folderNames = sortedSetOf<String>()
        val videos = mutableListOf<LocalVideo>()

        for ((video, relativePath) in queryAll()) {
            if (!relativePath.startsWith(normalized)) continue
            val remainder = relativePath.removePrefix(normalized)
            if (remainder.isEmpty()) {
                videos += video
            } else {
                val nextSegment = remainder.substringBefore('/')
                if (nextSegment.isNotEmpty()) folderNames += nextSegment
            }
        }

        val folders = folderNames.map { BrowseFolder(name = it, path = normalized + it + "/") }
        LocalBrowseResult(folders, videos)
    }

    /** Todos os vídeos dentro de [folderPath] e qualquer subpasta, recursivamente — uma única consulta ao MediaStore (não uma por nível). */
    suspend fun browseRecursive(folderPath: String): List<LocalVideo> = withContext(Dispatchers.IO) {
        val normalized = normalizeFolderPath(folderPath)
        queryAll().filter { (_, relativePath) -> relativePath.startsWith(normalized) }.map { it.first }
    }

    private fun normalizeFolderPath(folderPath: String): String =
        if (folderPath.isBlank()) "" else folderPath.trim('/') + "/"

    private fun queryAll(): List<Pair<LocalVideo, String>> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val results = mutableListOf<Pair<LocalVideo, String>>()
        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val relativePathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                results += buildVideo(cursor, collection, idColumn, nameColumn, durationColumn, sizeColumn, dateModifiedColumn) to
                    (cursor.getString(relativePathColumn) ?: "")
            }
        }
        return results
    }

    private fun buildVideo(
        cursor: Cursor,
        collection: Uri,
        idColumn: Int,
        nameColumn: Int,
        durationColumn: Int,
        sizeColumn: Int,
        dateModifiedColumn: Int,
    ): LocalVideo {
        val id = cursor.getLong(idColumn)
        val uri = ContentUris.withAppendedId(collection, id)
        return LocalVideo(
            id = id,
            uri = uri,
            displayName = cursor.getString(nameColumn) ?: uri.toString(),
            durationMs = cursor.getLong(durationColumn),
            sizeBytes = cursor.getLong(sizeColumn),
            // MediaStore.DATE_MODIFIED é em segundos desde a época, não milissegundos.
            dateModifiedMs = cursor.getLong(dateModifiedColumn) * 1000L,
        )
    }

    /**
     * Tenta excluir diretamente; se o SO exigir consentimento do usuário para excluir mídia que o
     * app não criou (comum a partir do Android 10 com armazenamento com escopo), retorna o
     * [IntentSender] do sistema a ser lançado — a confirmação do usuário nesse fluxo já realiza a
     * exclusão, sem necessidade de chamar [deleteVideo] novamente.
     */
    suspend fun deleteVideo(video: LocalVideo): DeleteVideoResult = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(video.uri, null, null)
            if (rows > 0) DeleteVideoResult.Success else DeleteVideoResult.Failed
        } catch (securityException: SecurityException) {
            val intentSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri)).intentSender
            } else {
                (securityException as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            }
            intentSender?.let { DeleteVideoResult.RequiresConfirmation(it) } ?: DeleteVideoResult.Failed
        }
    }
}
