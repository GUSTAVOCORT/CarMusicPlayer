package com.carplayer.music.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * Ecualizador espectral dibujado a mano sobre Canvas.
 *
 * Reglas de optimizacion aplicadas para el Allwinner T3 / Mali-400:
 *  - CERO asignaciones dentro de onDraw (Paint, RectF y arrays son campos).
 *  - Refresco fijado a 30 FPS con un Runnable propio, no con invalidate() en el callback
 *    de la Visualizer (que dispara hasta 60 veces por segundo).
 *  - captureSize minimo (128 bytes) -> FFT barata.
 *  - El bucle se detiene solo cuando la View no esta visible o no hay audio.
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private const val TAG = "AudioVisualizer"
        private const val BARS = 24
        private const val FRAME_MS = 33L          // ~30 FPS
        private const val RISE = 0.55f            // suavizado de subida
        private const val FALL = 0.12f            // suavizado de bajada
        private const val PEAK_FALL = 0.010f
        private const val SILENCE_TIMEOUT_MS = 1500L

        /** Paleta neon repartida a lo ancho: graves cian -> agudos violeta. */
        private val PALETTE = intArrayOf(
            0xFF22D3EE.toInt(),   // cian
            0xFF34D399.toInt(),   // verde
            0xFFFDE047.toInt(),   // amarillo
            0xFFFB923C.toInt(),   // naranja
            0xFFF472B6.toInt(),   // rosa
            0xFFA78BFA.toInt()    // violeta
        )
    }

    // --- Estado del espectro (arrays fijos, nunca se reasignan) ---
    private val target = FloatArray(BARS)
    private val current = FloatArray(BARS)
    private val peak = FloatArray(BARS)

    // --- Pintura ---
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EAFDFF")
    }
    private val barRect = RectF()
    private var barWidth = 0f
    private var gap = 0f
    private var corner = 0f

    private var visualizer: Visualizer? = null
    private var sessionId = 0
    private var running = false
    private var lastError: String? = null
    private var usedOutputMix = false

    // --- Modo de respaldo ---
    // Muchos head units chinos capan el efecto Visualizer en el firmware: el objeto
    // se crea pero nunca entrega datos. Si detectamos silencio de datos mientras la
    // musica suena, generamos el movimiento nosotros para que las barras no queden muertas.
    private var lastRealDataMs = 0L
    private var syntheticMode = false
    private val phase = FloatArray(BARS) { it * 0.7f }
    private var audioPlaying = false

    /** La Activity avisa si hay musica sonando (para el modo de respaldo). */
    fun setPlaying(playing: Boolean) {
        audioPlaying = playing
        if (playing) start() else stop()
    }

    /** Bucle de render a 30 FPS. Se auto-reprograma solo mientras haga falta. */
    private val frameLoop = object : Runnable {
        override fun run() {
            if (!running) return
            val moved = smooth()
            if (moved) invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    // ------------------------------------------------------------------ API publica

    /**
     * Engancha el visualizador a la sesion de audio de ExoPlayer.
     * Requiere permiso RECORD_AUDIO concedido.
     */
    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return
        release()
        sessionId = audioSessionId
        if (!open(audioSessionId)) {
            // Respaldo: sesion 0 = mezcla general de salida. En Android nuevo suele estar
            // bloqueada, pero en muchos head units con Android 6/7 funciona igual.
            usedOutputMix = open(0)
        }
        if (visualizer == null) {
            Log.w(TAG, "Sin visualizador real, se usara el modo sintetico. $lastError")
            syntheticMode = true
        }
        start()
    }

    private fun open(id: Int): Boolean {
        try {
            visualizer = Visualizer(id).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]  // 128 -> lo mas barato
                val rate = Visualizer.getMaxCaptureRate().coerceAtMost(20_000) // 20 Hz
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(v: Visualizer?, wf: ByteArray?, sr: Int) = Unit
                        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, sr: Int) {
                            if (fft != null) consumeFft(fft)
                        }

                    },
                    rate,
                    /* waveform = */ false,
                    /* fft = */ true
                )
                enabled = true
            }
            lastError = null
            return true
        } catch (e: Throwable) {
            lastError = e.javaClass.simpleName + ": " + e.message
            try {
                visualizer?.release()
            } catch (_: Throwable) {
            }
            visualizer = null
            return false
        }
    }

    /** Texto de diagnostico: se muestra al mantener presionado el ecualizador. */
    fun debugInfo(): String = buildString {
        append("sesion=").append(sessionId)
        append(" | visualizador=").append(if (visualizer != null) "OK" else "NO")
        if (usedOutputMix) append(" (mezcla general)")
        append(" | modo=").append(if (syntheticMode) "sintetico" else "real")
        lastError?.let { append(" | ").append(it) }
    }

    fun release() {
        stop()
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        }
        visualizer = null
        sessionId = 0
        java.util.Arrays.fill(target, 0f)
    }

    fun start() {
        if (running) return
        if (lastRealDataMs == 0L) lastRealDataMs = System.currentTimeMillis()
        running = true
        postDelayed(frameLoop, FRAME_MS)
    }

    fun stop() {
        running = false
        removeCallbacks(frameLoop)
    }

    // ------------------------------------------------------------------ Procesado FFT

    /**
     * Convierte los 128 bytes de FFT en [BARS] magnitudes normalizadas.
     * Reparto logaritmico de bandas: los graves ocupan pocas barras y los agudos muchas,
     * que es como lo percibe el oido.
     */
    private fun consumeFft(fft: ByteArray) {
        val n = fft.size / 2
        var bin = 1
        for (i in 0 until BARS) {
            // limite superior de la banda i (crecimiento exponencial)
            val hi = (n * Math.pow((i + 1) / BARS.toDouble(), 2.2)).toInt().coerceAtLeast(bin + 1)
            var sum = 0f
            var count = 0
            var k = bin
            while (k < hi && k < n) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                sum += hypot(re, im)
                count++
                k++
            }
            bin = hi
            val avg = if (count > 0) sum / count else 0f
            // escala logaritmica -> 0..1
            val db = ln((avg + 1f).toDouble()).toFloat() / 5.2f
            val v = db.coerceIn(0f, 1f)
            target[i] = v
            if (v > 0.02f) {
                lastRealDataMs = System.currentTimeMillis()
                syntheticMode = false
            }
        }
    }

    /**
     * Genera un espectro creible sin datos reales: cada barra oscila con su propia
     * frecuencia y algo de azar, con mas energia en los graves. Cuesta ~24 senos
     * por cuadro, es decir nada para el T3.
     */
    private fun synthesize() {
        for (i in 0 until BARS) {
            phase[i] += 0.18f + (i % 5) * 0.035f
            val base = kotlin.math.sin(phase[i].toDouble()).toFloat() * 0.5f + 0.5f
            val weight = 1f - (i / BARS.toFloat()) * 0.55f    // graves mas altos
            val jitter = (Math.random().toFloat() - 0.5f) * 0.25f
            target[i] = ((base * weight) + jitter).coerceIn(0.05f, 1f)
        }
    }

    /** Interpolacion asimetrica: sube rapido, cae suave. Devuelve true si algo cambio. */
    private fun smooth(): Boolean {
        var moved = false

        // Sin datos reales durante 1,5 s con musica sonando -> respaldo sintetico
        if (audioPlaying && System.currentTimeMillis() - lastRealDataMs > SILENCE_TIMEOUT_MS) {
            syntheticMode = true
        }
        if (syntheticMode && audioPlaying) synthesize()

        for (i in 0 until BARS) {
            val t = target[i]
            val c = current[i]
            val next = if (t > c) c + (t - c) * RISE else c + (t - c) * FALL
            if (kotlin.math.abs(next - c) > 0.001f) {
                current[i] = next
                moved = true
            }
            if (next > peak[i]) {
                peak[i] = next
            } else if (peak[i] > 0f) {
                peak[i] = max(0f, peak[i] - PEAK_FALL)
                moved = true
            }
            // decaimiento natural si el callback dejo de llegar (pausa)
            if (!syntheticMode) target[i] = t * 0.94f
        }
        return moved
    }

    // ------------------------------------------------------------------ Dibujo

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gap = w * 0.012f
        barWidth = (w - gap * (BARS - 1)) / BARS
        corner = barWidth * 0.25f
        // Un solo shader horizontal para las 24 barras: cada una toma el color que le
        // corresponde segun su posicion. Cuesta lo mismo que pintarlas de un color plano.
        barPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            PALETTE, null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val minH = h * 0.06f
        var x = 0f
        for (i in 0 until BARS) {
            val bh = max(minH, current[i] * h)
            barRect.set(x, h - bh, x + barWidth, h)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)

            val p = peak[i]
            if (p > 0.03f) {
                val py = h - p * h
                barRect.set(x, py, x + barWidth, py + minH)
                canvas.drawRoundRect(barRect, corner, corner, peakPaint)
            }
            x += barWidth + gap
        }
    }

    // ------------------------------------------------------------------ Ciclo de vida

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Pantalla apagada o app en segundo plano -> no gastamos CPU dibujando.
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}
