package com.carplayer.music.scanner

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * Cache del indice de canciones.
 *
 * Estrategia "mostrar primero, verificar despues":
 *  - Al abrir, se carga este archivo y la lista aparece al instante.
 *  - En paralelo se reescanea el pendrive; si el resultado difiere, se actualiza.
 *
 * Formato: texto plano separado por tabuladores. 2.000 canciones ~= 200 KB
 * y se lee en menos de 100 ms incluso en el Allwinner T3.
 */
object IndexCache {

    private const val TAG = "IndexCache"
    private const val FILE = "music_index.tsv"
    private const val SEP = "\t"

    private fun file(c: Context) = File(c.filesDir, FILE)

    suspend fun load(c: Context): List<Song>? = withContext(Dispatchers.IO) {
        val f = file(c)
        if (!f.exists() || f.length() == 0L) return@withContext null
        try {
            val out = ArrayList<Song>(2048)
            BufferedReader(FileReader(f), 32 * 1024).use { r ->
                var line: String? = r.readLine()
                while (line != null) {
                    val p = line.split(SEP)
                    if (p.size >= 4) {
                        val path = p[0]
                        val parent = path.substringBeforeLast('/', "/")
                        out.add(
                            Song(
                                id = path.hashCode().toLong(),
                                title = p[1],
                                artist = p[2],
                                path = path,
                                folderPath = parent,
                                folderName = parent.substringAfterLast('/'),
                                durationMs = p[3].toLongOrNull() ?: 0L
                            )
                        )
                    }
                    line = r.readLine()
                }
            }
            Log.i(TAG, "Cache cargado: ${out.size} pistas")
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            Log.w(TAG, "Cache ilegible, se descarta: ${e.message}")
            null
        }
    }

    suspend fun save(c: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        try {
            BufferedWriter(FileWriter(file(c)), 32 * 1024).use { w ->
                for (s in songs) {
                    // Los tabuladores dentro de un nombre romperian el formato
                    w.write(s.path.replace(SEP, " "))
                    w.write(SEP)
                    w.write(s.title.replace(SEP, " "))
                    w.write(SEP)
                    w.write(s.artist.replace(SEP, " "))
                    w.write(SEP)
                    w.write(s.durationMs.toString())
                    w.newLine()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar el cache: ${e.message}")
        }
    }

    /** Comparacion barata: misma cantidad y mismas rutas en el mismo orden. */
    fun sameContent(a: List<Song>, b: List<Song>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i].path != b[i].path) return false
        return true
    }

    fun clear(c: Context) {
        try {
            file(c).delete()
        } catch (_: Exception) {
        }
    }
}
