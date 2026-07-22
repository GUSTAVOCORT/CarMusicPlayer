package com.carplayer.music.player

import android.content.Context

/**
 * Guarda "donde quedo" la reproduccion para restaurarla al volver a encender el auto.
 *
 * Se usa SharedPreferences y no una base de datos: son 5 valores sueltos,
 * se escribe con commit asincrono y no cuesta ni memoria ni arranque.
 */
object PlaybackStore {

    private const val PREFS = "playback_state"
    private const val K_SOURCE = "source"        // "ALL" o la ruta de la carpeta
    private const val K_INDEX = "index"          // posicion dentro de la cola
    private const val K_POSITION = "position_ms"
    private const val K_PLAYING = "was_playing"
    private const val K_SHUFFLE = "shuffle"
    private const val K_EQ = "eq_preset"
    private const val K_MODE = "screen_mode"
    private const val K_MEDIA_ID = "media_id"
    private const val K_RESTORE_PENDING = "restore_pending"
    private const val K_VIS_STYLE = "vis_style"

    const val SOURCE_ALL = "ALL"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** La Activity avisa que cola cargo, para poder reconstruirla despues. */
    fun setSource(c: Context, source: String, shuffle: Boolean) {
        prefs(c).edit()
            .putString(K_SOURCE, source)
            .putBoolean(K_SHUFFLE, shuffle)
            .apply()
    }

    /**
     * El Service guarda el avance cada pocos segundos.
     *
     * [immediate] usa commit() en vez de apply(): escribe al disco antes de devolver.
     * Se usa al pausar o al morir el proceso, porque en un auto la corriente se corta
     * de golpe y un apply() pendiente se pierde.
     */
    fun savePosition(
        c: Context,
        index: Int,
        positionMs: Long,
        playing: Boolean,
        immediate: Boolean = false
    ) {
        val e = prefs(c).edit()
            .putInt(K_INDEX, index)
            .putLong(K_POSITION, positionMs)
            .putBoolean(K_PLAYING, playing)
        if (immediate) e.commit() else e.apply()
    }

    /** Ruta exacta de la cancion: sobrevive aunque la cola se arme distinta. */
    fun mediaId(c: Context): String? = prefs(c).getString(K_MEDIA_ID, null)

    fun saveMediaId(c: Context, path: String?) {
        prefs(c).edit().putString(K_MEDIA_ID, path).apply()
    }

    /**
     * Cortacircuitos anti-bucle.
     *
     * Se marca una bandera ANTES de restaurar y se borra cuando termino bien.
     * Si al abrir la app la bandera sigue puesta, el intento anterior mato el
     * proceso: se descarta el estado y se arranca limpio en vez de morir
     * una y otra vez. Evita tener que borrar datos a mano.
     */
    fun restorePending(c: Context): Boolean = prefs(c).getBoolean(K_RESTORE_PENDING, false)

    fun beginRestore(c: Context) {
        prefs(c).edit().putBoolean(K_RESTORE_PENDING, true).commit()
    }

    fun endRestore(c: Context) {
        prefs(c).edit().putBoolean(K_RESTORE_PENDING, false).commit()
    }

    fun forget(c: Context) {
        prefs(c).edit()
            .remove(K_SOURCE)
            .remove(K_MEDIA_ID)
            .remove(K_INDEX)
            .remove(K_POSITION)
            .remove(K_PLAYING)
            .putBoolean(K_RESTORE_PENDING, false)
            .commit()
    }

    /** Estilo del visualizador: 0 barras, 1 onda, 2 circulo. */
    fun visualStyle(c: Context): Int = prefs(c).getInt(K_VIS_STYLE, 0)

    fun setVisualStyle(c: Context, style: Int) {
        prefs(c).edit().putInt(K_VIS_STYLE, style).apply()
    }

    fun source(c: Context): String? = prefs(c).getString(K_SOURCE, null)
    fun index(c: Context): Int = prefs(c).getInt(K_INDEX, 0)
    fun position(c: Context): Long = prefs(c).getLong(K_POSITION, 0L)
    fun wasPlaying(c: Context): Boolean = prefs(c).getBoolean(K_PLAYING, false)
    fun shuffle(c: Context): Boolean = prefs(c).getBoolean(K_SHUFFLE, false)

    /** 0 = normal, 1 = sin lista, 2 = pantalla completa */
    fun screenMode(c: Context): Int = prefs(c).getInt(K_MODE, 0)
    fun setScreenMode(c: Context, mode: Int) {
        prefs(c).edit().putInt(K_MODE, mode).apply()
    }

    fun eqPreset(c: Context): Int = prefs(c).getInt(K_EQ, 0)
    fun setEqPreset(c: Context, preset: Int) {
        prefs(c).edit().putInt(K_EQ, preset).apply()
    }
}
