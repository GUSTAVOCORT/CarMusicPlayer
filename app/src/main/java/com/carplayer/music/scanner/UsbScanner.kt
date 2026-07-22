package com.carplayer.music.scanner

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Escaner de audio para head units chinos.
 *
 * Estrategia doble porque el firmware de estos equipos suele montar el USB en rutas
 * que el MediaScanner de Android NO indexa:
 *   1) Consulta rapida a MediaStore (lo ya indexado, milisegundos).
 *   2) Recorrido directo de los puntos de montaje OTG tipicos.
 * Se deduplica por ruta canonica.
 *
 * Todo corre en Dispatchers.IO y respeta la cancelacion de la corrutina.
 */
object UsbScanner {

    private const val TAG = "UsbScanner"
    private const val MAX_DEPTH = 8

    private val AUDIO_EXT = hashSetOf(
        "mp3", "flac", "m4a", "aac", "wav", "ogg", "opus", "wma", "mp4a"
    )

    /** Puntos de montaje que usan los T3/T7 de Allwinner para OTG y SD. */
    private val CANDIDATE_ROOTS = arrayOf(
        "/storage",
        "/mnt/media_rw",
        "/mnt/usb_storage",
        "/mnt/usbhost",
        "/mnt/usbhost1",
        "/mnt/sdcard/usbStorageA",
        "/mnt/extsd",
        "/udisk"
    )

    /** Carpetas que nunca contienen musica del usuario: se podan para ganar velocidad. */
    private val SKIP_DIRS = hashSetOf(
        "android", "lost.dir", "lost+found", ".thumbnails", ".trashed",
        "system", "data", "cache", "proc", "sys", "dev"
    )

    suspend fun scan(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val seen = HashSet<String>(4096)
        val out = ArrayList<Song>(2048)

        queryMediaStore(context, seen, out)
        coroutineContext.ensureActive()

        for (root in resolveRoots(context)) {
            coroutineContext.ensureActive()
            walk(root, 0, seen, out)
        }

        // Orden natural: carpeta, luego nombre. Un solo sort sobre la lista final.
        out.sortWith(compareBy({ it.folderPath }, { it.title.lowercase() }))
        Log.i(TAG, "Escaneo terminado: ${out.size} pistas")
        out
    }

    // ------------------------------------------------------------------ MediaStore

    private fun queryMediaStore(context: Context, seen: HashSet<String>, out: MutableList<Song>) {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                cols,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    val path = c.getString(iData) ?: continue
                    if (!seen.add(path)) continue
                    val f = File(path)
                    if (!f.exists()) continue
                    out.add(
                        Song(
                            id = c.getLong(iId),
                            title = c.getString(iTitle) ?: f.nameWithoutExtension,
                            artist = c.getString(iArtist) ?: "",
                            path = path,
                            folderPath = f.parent ?: "/",
                            folderName = f.parentFile?.name ?: "/",
                            durationMs = c.getLong(iDur)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore no disponible: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ Filesystem

    private fun resolveRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()

        // Rutas que el propio sistema nos concede (funcionan sin permisos especiales)
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            // .../Android/data/<pkg>/files -> subimos 4 niveles hasta la raiz del volumen
            var f: File? = dir
            repeat(4) { f = f?.parentFile }
            f?.let { if (it.canRead()) roots.add(it) }
        }

        Environment.getExternalStorageDirectory()?.let { if (it.canRead()) roots.add(it) }

        for (path in CANDIDATE_ROOTS) {
            val base = File(path)
            if (!base.isDirectory || !base.canRead()) continue
            val children = base.listFiles()
            if (children.isNullOrEmpty()) {
                roots.add(base)
            } else {
                children.forEach { if (it.isDirectory && it.canRead()) roots.add(it) }
            }
        }
        return roots.toList()
    }

    /** Recorrido iterativo (sin recursion profunda -> menos stack, menos GC). */
    private suspend fun walk(root: File, startDepth: Int, seen: HashSet<String>, out: MutableList<Song>) {
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to startDepth)

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (dir, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) continue

            val children = try { dir.listFiles() } catch (e: SecurityException) { null } ?: continue

            for (f in children) {
                val name = f.name
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (name.lowercase() in SKIP_DIRS) continue
                    if (f.canRead()) stack.addLast(f to depth + 1)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in AUDIO_EXT) continue
                    if (f.length() < 32 * 1024L) continue          // descarta basura/ringtones rotos
                    val path = try { f.canonicalPath } catch (e: Exception) { f.absolutePath }
                    if (!seen.add(path)) continue
                    out.add(
                        Song(
                            id = path.hashCode().toLong(),
                            title = f.nameWithoutExtension,
                            artist = "",
                            path = path,
                            folderPath = f.parent ?: "/",
                            folderName = dir.name,
                            // No se lee metadata aqui a proposito: MediaMetadataRetriever
                            // sobre 2.000 archivos congelaria el T3 varios minutos.
                            durationMs = 0L
                        )
                    )
                }
            }
        }
    }
}
