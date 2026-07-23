package com.carplayer.music.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Caratulas del disco, leidas BAJO DEMANDA.
 *
 * Regla de oro para el Allwinner T3: nunca leer metadatos de toda la biblioteca.
 * Solo se abre el archivo que esta sonando, y una vez leido se guarda una miniatura
 * de 320px en disco. La segunda vez que suena esa cancion, la imagen sale del cache
 * en milisegundos sin volver a abrir el MP3.
 *
 * Consumo: una sola imagen chica en RAM a la vez (~2-3 MB). El cache en disco se
 * poda solo cuando pasa de un techo razonable.
 */
object CoverArt {

    private const val TAG = "CoverArt"
    private const val TARGET_PX = 320
    private const val MAX_CACHE_FILES = 300

    private fun dir(c: Context): File {
        val d = File(c.cacheDir, "covers")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun keyFor(path: String): String =
        "cover_" + path.hashCode().toString().replace("-", "n") + ".jpg"

    /**
     * Devuelve la caratula de [path], o null si el archivo no tiene ninguna.
     * Nunca lanza: si algo falla, devuelve null y la UI usa el placeholder.
     */
    suspend fun load(context: Context, path: String): Bitmap? = withContext(Dispatchers.IO) {
        val cacheFile = File(dir(context), keyFor(path))

        // 1) Cache en disco
        if (cacheFile.exists()) {
            try {
                BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { return@withContext it }
            } catch (_: Throwable) {
            }
        }

        // 2) Leer del archivo de audio (lo caro, una sola vez por cancion)
        val raw = extractEmbedded(path) ?: return@withContext null
        val scaled = downscale(raw)
        if (scaled != raw) raw.recycle()

        // 3) Guardar en cache para la proxima vez
        try {
            FileOutputStream(cacheFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            trim(context)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cachear la caratula: ${e.message}")
        }
        scaled
    }

    private fun extractEmbedded(path: String): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            val bytes = mmr.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Throwable) {
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Throwable) {
            }
        }
    }

    /** Reduce la imagen a TARGET_PX por el lado mayor, manteniendo proporcion. */
    private fun downscale(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val maxSide = maxOf(w, h)
        if (maxSide <= TARGET_PX) return src
        val scale = TARGET_PX.toFloat() / maxSide
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    /** Poda el cache si se paso del techo: borra las mas viejas. */
    private fun trim(context: Context) {
        val files = dir(context).listFiles() ?: return
        if (files.size <= MAX_CACHE_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHE_FILES)
            .forEach { it.delete() }
    }

    fun clear(context: Context) {
        try {
            dir(context).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
