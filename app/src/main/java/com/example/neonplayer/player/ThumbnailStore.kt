package com.example.neonplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache de miniaturas em duas camadas: memória (rápido, perdida ao matar o processo) e disco em
 * [Context.getCacheDir] (sobrevive ao fechar o app — importante para vídeos remotos, cuja
 * miniatura exige abrir uma conexão de rede para gerar). A chave já embute tamanho e data de
 * modificação do vídeo (ver [thumbnailCacheKeyOf]), então não é preciso invalidar manualmente: um
 * arquivo alterado gera uma chave/nome de arquivo em disco diferente.
 */
object ThumbnailStore {
    private val memoryCache = LruCache<String, Bitmap>(120)

    suspend fun load(context: Context, key: String): Bitmap? {
        memoryCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = diskFile(context, key)
            if (!file.exists()) return@withContext null
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                ?.also { memoryCache.put(key, it) }
        }
    }

    suspend fun store(context: Context, key: String, bitmap: Bitmap) {
        memoryCache.put(key, bitmap)
        withContext(Dispatchers.IO) {
            runCatching {
                diskFile(context, key).outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
        }
    }

    private fun diskFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.jpg")
    }
}
