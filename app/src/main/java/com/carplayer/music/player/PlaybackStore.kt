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

    const val SOURCE_ALL = "ALL"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** La Activity avisa que cola cargo, para poder reconstruirla despues. */
    fun setSource(c: Context, source: String, shuffle: Boolean) {
        prefs(c).edit()
            .putString(K_SOURCE, source)
            .putBoolean(K_SHUFFLE, shuffle)
            .apply()
    }

    /** El Service guarda el avance cada pocos segundos. */
    fun savePosition(c: Context, index: Int, positionMs: Long, playing: Boolean) {
        prefs(c).edit()
            .putInt(K_INDEX, index)
            .putLong(K_POSITION, positionMs)
            .putBoolean(K_PLAYING, playing)
            .apply()
    }

    fun source(c: Context): String? = prefs(c).getString(K_SOURCE, null)
    fun index(c: Context): Int = prefs(c).getInt(K_INDEX, 0)
    fun position(c: Context): Long = prefs(c).getLong(K_POSITION, 0L)
    fun wasPlaying(c: Context): Boolean = prefs(c).getBoolean(K_PLAYING, false)
    fun shuffle(c: Context): Boolean = prefs(c).getBoolean(K_SHUFFLE, false)

    fun eqPreset(c: Context): Int = prefs(c).getInt(K_EQ, 0)
    fun setEqPreset(c: Context, preset: Int) {
        prefs(c).edit().putInt(K_EQ, preset).apply()
    }
}
