package com.carplayer.music.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Ecualizador de audio REAL (el que se escucha), no el visual.
 *
 * Vive en el Service, no en la Activity: si viviera en la UI, al cerrar la pantalla
 * se liberaria el efecto y el sonido volveria a plano.
 *
 * En vez de usar los presets del fabricante (que en los head units chinos suelen
 * estar vacios o traer nombres raros), se aplica una curva de ganancia propia
 * calculada sobre las bandas reales que reporte el equipo.
 */
object AudioFx {

    private const val TAG = "AudioFx"

    const val FLAT = 0
    const val BASS = 1
    const val VOICE = 2
    const val ROCK = 3

    /** Ganancia en decibeles por rango de frecuencia: graves / medios / agudos. */
    private val CURVES = arrayOf(
        floatArrayOf(0f, 0f, 0f),        // Plano
        floatArrayOf(7f, -1f, 2f),       // Graves (parlantes chicos de auto)
        floatArrayOf(-3f, 4f, 2f),       // Voz / podcast
        floatArrayOf(5f, -2f, 5f)        // Rock (curva en V)
    )

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var sessionId = 0

    fun attach(context: Context, audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        try {
            eq = Equalizer(0, audioSessionId).apply { enabled = true }
            bass = BassBoost(0, audioSessionId).apply { enabled = true }
            apply(context, PlaybackStore.eqPreset(context))
        } catch (e: Exception) {
            Log.w(TAG, "Ecualizador no disponible en este equipo: ${e.message}")
            eq = null
            bass = null
        }
    }

    fun apply(context: Context, preset: Int) {
        PlaybackStore.setEqPreset(context, preset)
        val curve = CURVES.getOrNull(preset) ?: return
        val equalizer = eq ?: return
        try {
            val bands = equalizer.numberOfBands.toInt()
            val range = equalizer.bandLevelRange   // [min, max] en milibeles
            val min = range[0].toInt()
            val max = range[1].toInt()

            for (b in 0 until bands) {
                val hz = equalizer.getCenterFreq(b.toShort()) / 1000  // uHz -> Hz
                val db = when {
                    hz < 400 -> curve[0]
                    hz < 3000 -> curve[1]
                    else -> curve[2]
                }
                val mb = (db * 100).toInt().coerceIn(min, max)
                equalizer.setBandLevel(b.toShort(), mb.toShort())
            }

            // Refuerzo extra de graves solo en los presets que lo piden
            val strength = when (preset) {
                BASS -> 800
                ROCK -> 500
                else -> 0
            }
            bass?.let { if (it.strengthSupported) it.setStrength(strength.toShort()) }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo aplicar el preset: ${e.message}")
        }
    }

    fun release() {
        try {
            eq?.release()
            bass?.release()
        } catch (_: Exception) {
        }
        eq = null
        bass = null
        sessionId = 0
    }
}
