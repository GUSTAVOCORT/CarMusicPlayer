package com.carplayer.music.scanner

/**
 * Modelo minimo e inmutable. Sin bitmaps ni objetos pesados:
 * 3.000 canciones ~= 600 KB de RAM.
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val folderPath: String,
    val folderName: String,
    val durationMs: Long
)

/**
 * Nodo de navegacion de la lista (carpeta o cancion).
 * Se usa un solo RecyclerView para ambos tipos -> menos objetos y menos vistas.
 */
sealed class BrowserItem {
    data class Folder(val path: String, val name: String, val count: Int) : BrowserItem()
    data class Track(val song: Song, val indexInQueue: Int) : BrowserItem()
    object UpDir : BrowserItem()
}

/**
 * Indice global cargado una sola vez. Singleton liviano: un solo ArrayList<Song>
 * compartido entre Activity y Service, sin copias.
 */
object MusicIndex {
    @Volatile
    var all: List<Song> = emptyList()
        private set

    /** Mapa carpeta -> indices dentro de [all]. Solo enteros, no duplica objetos. */
    @Volatile
    var byFolder: Map<String, IntArray> = emptyMap()
        private set

    fun publish(songs: List<Song>) {
        all = songs
        byFolder = songs.withIndex()
            .groupBy({ it.value.folderPath }, { it.index })
            .mapValues { (_, list) -> list.toIntArray() }
    }

    fun clear() {
        all = emptyList()
        byFolder = emptyMap()
    }
}
