package com.carplayer.music.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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

        /** Estilos de dibujo. El espectro es el mismo: cambia solo como se pinta. */
        // 16 estilos, en el orden pedido por el usuario
        const val STYLE_LED_SEG = 0     // Segmentos LED con reflejo
        const val STYLE_BARS = 1        // Barras solidas
        const val STYLE_MIRROR = 2      // Espejo (reflejo desde el centro vertical)
        const val STYLE_LINE = 3        // Linea (contorno del espectro)
        const val STYLE_DOTS = 4        // Puntos (circulos)
        const val STYLE_FILLED = 5      // Onda rellena hacia abajo
        const val STYLE_PEAK = 6        // Barras con pico (peak hold)
        const val STYLE_LED_FLAT = 7    // LED plano sin reflejo
        const val STYLE_RAINBOW = 8     // Arcoiris horizontal
        const val STYLE_RADIAL = 9      // Radial circular
        const val STYLE_MATRIX = 10     // Matriz LED con degradado y reflejo
        const val STYLE_DOTGRID = 11    // Puntos grid por columna
        const val STYLE_WAVE_MIRROR = 12// Onda espejo horizontal con glow
        const val STYLE_CENTER = 13     // Barras crecen desde el centro
        const val STYLE_FLAMES = 14     // Llamas
        const val STYLE_PULSE = 15      // Ondas latido (circulos concentricos)
        const val STYLE_WATER = 16      // Ondas que salen del centro como agua
        const val STYLE_STARS = 17      // Estrellas que titilan
        const val STYLE_SPIRAL = 18     // Espiral que gira
        const val STYLE_PARTICLES = 19  // Particulas que reaccionan
        const val STYLE_COUNT = 20

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
    private val wavePath = Path()
    private var style = STYLE_BARS
    private var palette = PALETTE
    private var reactive = 0
    private var neon = false
    private var neonColor = 0xFF22D3EE.toInt()
    private var frame = false
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val frameRect = RectF()
    private val reactiveColors = IntArray(BARS)   // color por barra, recalculado por cuadro
    private var cx = 0f
    private var cy = 0f
    private var baseRadius = 0f
    private var maxRay = 0f
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

    // Estado para estrellas / particulas / espiral (fijo, sin asignaciones por cuadro)
    private var spin = 0f
    private val starX = FloatArray(40)
    private val starY = FloatArray(40)
    private val starPhase = FloatArray(40) { (it * 0.37f) }
    private var starsReady = false

    /**
     * Cambia la paleta del visualizador.
     * [reactiveMode]: 0 degradado fijo, 1 late con la intensidad, 2 termometro.
     */
    fun setPalette(colors: IntArray, reactiveMode: Int = 0) {
        if (colors.size < 2) return
        palette = colors
        reactive = reactiveMode
        rebuildShader()
        invalidate()
    }

    /** Mezcla lineal entre dos colores ARGB. t va de 0 a 1. */
    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val a = ((c1 ushr 24) + (((c2 ushr 24) - (c1 ushr 24)) * u)).toInt()
        val r = (((c1 shr 16) and 0xFF) + ((((c2 shr 16) and 0xFF) - ((c1 shr 16) and 0xFF)) * u)).toInt()
        val g = (((c1 shr 8) and 0xFF) + ((((c2 shr 8) and 0xFF) - ((c1 shr 8) and 0xFF)) * u)).toInt()
        val b = ((c1 and 0xFF) + (((c2 and 0xFF) - (c1 and 0xFF)) * u)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Color de una rampa de N colores en la posicion t (0..1). */
    private fun sampleRamp(ramp: IntArray, t: Float): Int {
        if (ramp.size == 1) return ramp[0]
        val x = (t.coerceIn(0f, 1f)) * (ramp.size - 1)
        val i = x.toInt().coerceIn(0, ramp.size - 2)
        return lerpColor(ramp[i], ramp[i + 1], x - i)
    }

    /**
     * Un solo shader horizontal para las 24 barras: cada una toma el color que le
     * corresponde segun su posicion. Cuesta lo mismo que pintarlas de un color plano.
     */
    private fun rebuildShader() {
        if (width == 0) return
        if (reactive != 0) {
            // En modo reactivo el color lo pone cada barra: sin shader global.
            barPaint.shader = null
            return
        }
        barPaint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            palette, null,
            Shader.TileMode.CLAMP
        )
    }

    /** Recalcula el color de cada barra segun el modo reactivo activo. */
    private fun computeReactiveColors() {
        when (reactive) {
            1 -> {
                // Late con la intensidad: promedio del espectro -> punto en la rampa,
                // el mismo color para todas las barras en ese cuadro.
                var sum = 0f
                for (i in 0 until BARS) sum += current[i]
                val energy = (sum / BARS) * 1.8f
                val col = sampleRamp(palette, energy)
                for (i in 0 until BARS) reactiveColors[i] = col
            }
            2 -> {
                // Termometro: cada barra toma su color segun su propia altura.
                for (i in 0 until BARS) {
                    reactiveColors[i] = sampleRamp(palette, current[i])
                }
            }
        }
    }

    /** Cambia el estilo de dibujo. Devuelve el estilo aplicado. */
    fun setStyle(newStyle: Int): Int {
        style = ((newStyle % STYLE_COUNT) + STYLE_COUNT) % STYLE_COUNT
        starsReady = false
        if (style == STYLE_WAVE_MIRROR) {
            barPaint.style = Paint.Style.STROKE
            barPaint.strokeWidth = height * 0.045f
            barPaint.strokeJoin = Paint.Join.ROUND
            barPaint.strokeCap = Paint.Cap.ROUND
        } else {
            barPaint.style = Paint.Style.FILL
        }
        invalidate()
        return style
    }

    fun currentStyle(): Int = style

    /** Marco de neon alrededor del area del visualizador. */
    fun setFrame(on: Boolean) {
        frame = on
        invalidate()
    }

    /**
     * Efecto neon (resplandor). setShadowLayer es CARO en la Mali-400, por eso es
     * opcional y viene apagado. Al activarse se desactiva la aceleracion por hardware
     * de esta vista, porque el blur de sombra no esta soportado en HW en Android viejo.
     */
    fun setNeon(on: Boolean) {
        neon = on
        if (on) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            barPaint.setShadowLayer(barWidth.coerceAtLeast(6f) * 0.9f, 0f, 0f, neonColor)
        } else {
            barPaint.clearShadowLayer()
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        invalidate()
    }

    /** Color del glow del neon y del marco. Se suele pasar el acento de la paleta. */
    fun setNeonColor(color: Int) {
        neonColor = color
        framePaint.color = color
        if (neon) barPaint.setShadowLayer(barWidth.coerceAtLeast(6f) * 0.9f, 0f, 0f, neonColor)
        invalidate()
    }

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
        cx = w / 2f
        cy = h / 2f
        baseRadius = kotlin.math.min(w, h) * 0.16f
        maxRay = kotlin.math.min(w, h) * 0.32f
        if (style == STYLE_WAVE_MIRROR) barPaint.strokeWidth = h * 0.045f
        framePaint.strokeWidth = kotlin.math.max(3f, w * 0.006f)
        rebuildShader()
    }

    override fun onDraw(canvas: Canvas) {
        if (reactive != 0) computeReactiveColors()
        when (style) {
            STYLE_LED_SEG -> drawLed(canvas, reflect = true, gradient = false)
            STYLE_MIRROR -> drawMirror(canvas)
            STYLE_LINE -> drawLine(canvas)
            STYLE_DOTS -> drawDots(canvas)
            STYLE_FILLED -> drawFilled(canvas)
            STYLE_PEAK -> drawBars(canvas, withPeak = true)
            STYLE_LED_FLAT -> drawLed(canvas, reflect = false, gradient = false)
            STYLE_RAINBOW -> drawRainbow(canvas)
            STYLE_RADIAL -> drawCircle(canvas)
            STYLE_MATRIX -> drawLed(canvas, reflect = true, gradient = true)
            STYLE_DOTGRID -> drawDotGrid(canvas)
            STYLE_WAVE_MIRROR -> drawWave(canvas)
            STYLE_CENTER -> drawMirror(canvas)
            STYLE_FLAMES -> drawFlames(canvas)
            STYLE_PULSE -> drawPulse(canvas)
            STYLE_WATER -> drawWater(canvas)
            STYLE_STARS -> drawStars(canvas)
            STYLE_SPIRAL -> drawSpiral(canvas)
            STYLE_PARTICLES -> drawParticles(canvas)
            else -> drawBars(canvas)
        }
        if (frame) drawFrame(canvas)
    }

    /** Ondas que salen del centro como agua: circulos suaves que se expanden. */
    private fun drawWater(canvas: Canvas) {
        var energy = 0f
        for (i in 0 until BARS) energy += current[i]
        energy /= BARS
        spin += 0.02f + energy * 0.06f
        val cxp = width / 2f
        val cyp = height / 2f
        val maxR = kotlin.math.min(width, height) * 0.5f
        val old = barPaint.style
        barPaint.style = Paint.Style.STROKE
        barPaint.strokeWidth = kotlin.math.max(2f, maxR * 0.015f)
        val rings = 7
        for (r in 0 until rings) {
            val base = ((r / rings.toFloat()) + spin * 0.15f) % 1f
            val radius = maxR * base
            val bandVal = current[(r * BARS / rings).coerceIn(0, BARS - 1)]
            val rr = radius * (1f + bandVal * 0.25f)
            barPaint.color = if (reactive != 0) reactiveColors[(r * BARS / rings).coerceIn(0, BARS - 1)]
                             else sampleRamp(palette, base)
            barPaint.alpha = (200 * (1f - base)).toInt().coerceIn(25, 220)
            if (neon) barPaint.setShadowLayer(barPaint.strokeWidth * 3f, 0f, 0f, barPaint.color)
            canvas.drawCircle(cxp, cyp, rr, barPaint)
        }
        barPaint.alpha = 255
        barPaint.style = old
        if (reactive == 0) rebuildShader()
    }

    /** Estrellas que titilan: puntos fijos cuyo brillo late con las bandas. */
    private fun drawStars(canvas: Canvas) {
        if (!starsReady && width > 0) {
            val rnd = java.util.Random(12345)   // posiciones estables entre cuadros
            for (k in starX.indices) {
                starX[k] = rnd.nextFloat() * width
                starY[k] = rnd.nextFloat() * height
            }
            starsReady = true
        }
        val old = barPaint.style
        barPaint.style = Paint.Style.FILL
        for (k in starX.indices) {
            starPhase[k] += 0.05f + (k % 4) * 0.01f
            val band = current[k % BARS]
            val twinkle = (kotlin.math.sin(starPhase[k].toDouble()).toFloat() * 0.5f + 0.5f)
            val bright = (0.25f + band * 0.75f) * (0.4f + twinkle * 0.6f)
            val radius = (2f + band * 8f) * (0.6f + twinkle * 0.4f)
            barPaint.color = if (reactive != 0) reactiveColors[k % BARS]
                             else sampleRamp(palette, twinkle)
            barPaint.alpha = (255 * bright).toInt().coerceIn(20, 255)
            if (neon) barPaint.setShadowLayer(radius * 1.5f, 0f, 0f, barPaint.color)
            canvas.drawCircle(starX[k], starY[k], radius, barPaint)
        }
        barPaint.alpha = 255
        barPaint.style = old
        if (reactive == 0) rebuildShader()
    }

    /** Espiral que gira: puntos en brazo espiral, el radio late con la banda. */
    private fun drawSpiral(canvas: Canvas) {
        var energy = 0f
        for (i in 0 until BARS) energy += current[i]
        energy /= BARS
        spin += 0.03f + energy * 0.08f
        val cxp = width / 2f
        val cyp = height / 2f
        val maxR = kotlin.math.min(width, height) * 0.46f
        val old = barPaint.style
        barPaint.style = Paint.Style.FILL
        val points = BARS * 2
        for (k in 0 until points) {
            val t = k / points.toFloat()
            val angle = t * 6.28318f * 3f + spin      // 3 vueltas
            val band = current[k % BARS]
            val radius = maxR * t * (0.7f + band * 0.6f)
            val px = cxp + kotlin.math.cos(angle.toDouble()).toFloat() * radius
            val py = cyp + kotlin.math.sin(angle.toDouble()).toFloat() * radius
            val dotR = (2f + band * 7f)
            barPaint.color = if (reactive != 0) reactiveColors[k % BARS] else sampleRamp(palette, t)
            if (neon) barPaint.setShadowLayer(dotR * 1.5f, 0f, 0f, barPaint.color)
            canvas.drawCircle(px, py, dotR, barPaint)
        }
        barPaint.style = old
        if (reactive == 0) rebuildShader()
    }

    /** Particulas: suben desde abajo segun la energia, como chispas. */
    private fun drawParticles(canvas: Canvas) {
        if (!starsReady && width > 0) {
            val rnd = java.util.Random(999)
            for (k in starX.indices) {
                starX[k] = rnd.nextFloat() * width
                starY[k] = rnd.nextFloat()          // 0..1 = altura relativa
            }
            starsReady = true
        }
        var energy = 0f
        for (i in 0 until BARS) energy += current[i]
        energy /= BARS
        val h = height.toFloat()
        val old = barPaint.style
        barPaint.style = Paint.Style.FILL
        for (k in starX.indices) {
            // sube segun energia; al pasar arriba, reaparece abajo
            starY[k] -= (0.004f + energy * 0.03f) * (0.5f + (k % 5) * 0.15f)
            if (starY[k] < 0f) starY[k] += 1f
            val band = current[k % BARS]
            val py = starY[k] * h
            val radius = 2f + band * 9f
            barPaint.color = if (reactive != 0) reactiveColors[k % BARS] else sampleRamp(palette, 1f - starY[k])
            barPaint.alpha = (255 * (0.4f + band * 0.6f)).toInt().coerceIn(30, 255)
            if (neon) barPaint.setShadowLayer(radius * 1.4f, 0f, 0f, barPaint.color)
            canvas.drawCircle(starX[k], py, radius, barPaint)
        }
        barPaint.alpha = 255
        barPaint.style = old
        if (reactive == 0) rebuildShader()
    }

    private fun drawFrame(canvas: Canvas) {
        val inset = framePaint.strokeWidth
        frameRect.set(inset, inset, width - inset, height - inset)
        val r = height * 0.06f
        if (neon) framePaint.setShadowLayer(framePaint.strokeWidth * 2.2f, 0f, 0f, neonColor)
        else framePaint.clearShadowLayer()
        canvas.drawRoundRect(frameRect, r, r, framePaint)
    }

    /** Puntos: una esfera por banda que sube y baja como burbuja. */
    private fun drawDots(canvas: Canvas) {
        val h = height.toFloat()
        val radius = barWidth * 0.42f
        var x = barWidth / 2f
        for (i in 0 until BARS) {
            if (reactive != 0) {
                barPaint.color = reactiveColors[i]
                if (neon) barPaint.setShadowLayer(radius, 0f, 0f, reactiveColors[i])
            }
            val cy = h - (radius + current[i] * (h - radius * 2))
            canvas.drawCircle(x, cy, radius, barPaint)
            x += barWidth + gap
        }
    }

    /** Espejo: cada barra crece desde el centro hacia arriba y hacia abajo. */
    private fun drawMirror(canvas: Canvas) {
        val h = height.toFloat()
        val mid = h / 2f
        val minH = h * 0.03f
        var x = 0f
        for (i in 0 until BARS) {
            if (reactive != 0) {
                barPaint.color = reactiveColors[i]
                if (neon) barPaint.setShadowLayer(barWidth * 0.9f, 0f, 0f, reactiveColors[i])
            }
            val half = max(minH, current[i] * mid)
            barRect.set(x, mid - half, x + barWidth, mid + half)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)
            x += barWidth + gap
        }
    }

    private fun drawBars(canvas: Canvas, withPeak: Boolean = false) {
        val h = height.toFloat()
        val minH = h * 0.06f
        var x = 0f
        for (i in 0 until BARS) {
            if (reactive != 0) {
                barPaint.color = reactiveColors[i]
                if (neon) barPaint.setShadowLayer(barWidth * 0.9f, 0f, 0f, reactiveColors[i])
            }
            val bh = max(minH, current[i] * h)
            barRect.set(x, h - bh, x + barWidth, h)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)

            if (withPeak) {
                val p = peak[i]
                if (p > 0.03f) {
                    val py = h - p * h
                    barRect.set(x, py, x + barWidth, py + minH)
                    canvas.drawRoundRect(barRect, corner, corner, peakPaint)
                }
            }
            x += barWidth + gap
        }
    }

    /** LED apilado. reflect = reflejo tenue abajo; gradient = degradado vertical. */
    private fun drawLed(canvas: Canvas, reflect: Boolean, gradient: Boolean) {
        val h = height.toFloat()
        val segs = 14
        val segH = h / segs
        val padY = segH * 0.20f
        var x = 0f
        for (i in 0 until BARS) {
            val lit = (current[i] * segs).toInt().coerceIn(0, segs)
            for (sIdx in 0 until lit) {
                if (reactive != 0) barPaint.color = reactiveColors[i]
                else if (gradient) barPaint.color = sampleRamp(palette, sIdx / segs.toFloat())
                val top = h - (sIdx + 1) * segH + padY
                barRect.set(x, top, x + barWidth, top + segH - padY * 2)
                canvas.drawRoundRect(barRect, corner * 0.4f, corner * 0.4f, barPaint)
                if (reflect && sIdx < 4) {
                    val a = barPaint.alpha
                    barPaint.alpha = 40
                    val ry = (sIdx + 1) * segH - padY
                    barRect.set(x, ry - segH + padY * 2, x + barWidth, ry)
                    canvas.drawRoundRect(barRect, corner * 0.4f, corner * 0.4f, barPaint)
                    barPaint.alpha = a
                }
            }
            x += barWidth + gap
        }
        if (gradient && reactive == 0) rebuildShader()
    }

    /** Arcoiris horizontal: cada barra un color fijo del circulo cromatico. */
    private fun drawRainbow(canvas: Canvas) {
        val h = height.toFloat()
        val minH = h * 0.06f
        var x = 0f
        barPaint.shader = null
        for (i in 0 until BARS) {
            val hue = i / BARS.toFloat() * 300f
            barPaint.color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
            if (neon) barPaint.setShadowLayer(barWidth * 0.8f, 0f, 0f, barPaint.color)
            val bh = max(minH, current[i] * h)
            barRect.set(x, h - bh, x + barWidth, h)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)
            x += barWidth + gap
        }
        rebuildShader()
    }

    /** Onda rellena hacia abajo: contorno del espectro relleno. */
    private fun drawFilled(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val step = w / (BARS - 1)
        wavePath.reset()
        wavePath.moveTo(0f, h)
        wavePath.lineTo(0f, h - current[0] * h)
        for (i in 1 until BARS) {
            val px = i * step
            val py = h - current[i] * h
            val prevX = (i - 1) * step
            val prevY = h - current[i - 1] * h
            wavePath.quadTo(prevX, prevY, (prevX + px) / 2f, (prevY + py) / 2f)
        }
        wavePath.lineTo(w, h - current[BARS - 1] * h)
        wavePath.lineTo(w, h)
        wavePath.close()
        val old = barPaint.style
        barPaint.style = Paint.Style.FILL
        if (reactive != 0) barPaint.color = reactiveColors[BARS / 2]
        canvas.drawPath(wavePath, barPaint)
        barPaint.style = old
    }

    /** Puntos grid: matriz de circulos que se encienden por columna. */
    private fun drawDotGrid(canvas: Canvas) {
        val h = height.toFloat()
        val rows = 10
        val rowH = h / rows
        val radius = barWidth * 0.30f
        var x = barWidth / 2f
        for (i in 0 until BARS) {
            val lit = (current[i] * rows).toInt().coerceIn(0, rows)
            for (r in 0 until rows) {
                val cy = h - (r + 0.5f) * rowH
                if (r < lit) {
                    if (reactive != 0) barPaint.color = reactiveColors[i]
                    barPaint.alpha = 255
                } else {
                    barPaint.color = 0xFF1B2530.toInt()
                    barPaint.alpha = 120
                }
                canvas.drawCircle(x, cy, radius, barPaint)
            }
            barPaint.alpha = 255
            x += barWidth + gap
        }
        if (reactive == 0) rebuildShader()
    }

    /** Llamas: columnas tipo fuego que se afinan hacia la punta. */
    private fun drawFlames(canvas: Canvas) {
        val h = height.toFloat()
        val minH = h * 0.05f
        var x = 0f
        val old = barPaint.style
        barPaint.style = Paint.Style.FILL
        for (i in 0 until BARS) {
            if (reactive != 0) barPaint.color = reactiveColors[i]
            val bh = max(minH, current[i] * h)
            val topY = h - bh
            wavePath.reset()
            wavePath.moveTo(x, h)
            wavePath.lineTo(x + barWidth, h)
            wavePath.lineTo(x + barWidth * 0.72f, topY + bh * 0.15f)
            wavePath.quadTo(x + barWidth * 0.5f, topY - bh * 0.08f, x + barWidth * 0.28f, topY + bh * 0.15f)
            wavePath.close()
            if (neon && reactive != 0) barPaint.setShadowLayer(barWidth, 0f, 0f, reactiveColors[i])
            canvas.drawPath(wavePath, barPaint)
            x += barWidth + gap
        }
        barPaint.style = old
    }

    /** Ondas latido: circulos concentricos que laten con el volumen global. */
    private fun drawPulse(canvas: Canvas) {
        var energy = 0f
        for (i in 0 until BARS) energy += current[i]
        energy /= BARS
        val cxp = width / 2f
        val cyp = height / 2f
        val maxR = kotlin.math.min(width, height) * 0.48f
        val old = barPaint.style
        barPaint.style = Paint.Style.STROKE
        barPaint.strokeWidth = kotlin.math.max(3f, maxR * 0.02f)
        val rings = 5
        for (r in 0 until rings) {
            val phase = r / rings.toFloat()
            val radius = (maxR * (phase + energy * 0.5f)) % maxR
            barPaint.color = if (reactive != 0) reactiveColors[(r * BARS / rings).coerceIn(0, BARS - 1)]
                             else sampleRamp(palette, phase)
            barPaint.alpha = (255 * (1f - radius / maxR)).toInt().coerceIn(30, 255)
            if (neon) barPaint.setShadowLayer(barPaint.strokeWidth * 3f, 0f, 0f, barPaint.color)
            canvas.drawCircle(cxp, cyp, radius, barPaint)
        }
        barPaint.alpha = 255
        barPaint.style = old
        if (reactive == 0) rebuildShader()
    }

    /**
     * Onda espejada: una linea gruesa que ondula sobre el centro y su reflejo abajo.
     * Se reutiliza el mismo Path en cada cuadro (reset no reserva memoria nueva).
     */
    private fun drawWave(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val mid = h / 2f
        val step = w / (BARS - 1)
        val amp = h * 0.42f

        if (reactive != 0) barPaint.color = reactiveColors[BARS / 2]
        for (side in 0..1) {
            val dir = if (side == 0) -1f else 1f
            wavePath.reset()
            wavePath.moveTo(0f, mid + dir * current[0] * amp)
            for (i in 1 until BARS) {
                val px = i * step
                val py = mid + dir * current[i] * amp
                val prevX = (i - 1) * step
                val prevY = mid + dir * current[i - 1] * amp
                // Curva suave entre puntos: evita el aspecto de sierra
                wavePath.quadTo(prevX, prevY, (prevX + px) / 2f, (prevY + py) / 2f)
            }
            wavePath.lineTo(w, mid + dir * current[BARS - 1] * amp)
            canvas.drawPath(wavePath, barPaint)
        }
    }

    private fun drawLine(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val step = w / (BARS - 1)
        val old = barPaint.style
        val oldW = barPaint.strokeWidth
        barPaint.style = Paint.Style.STROKE
        barPaint.strokeWidth = h * 0.03f
        barPaint.strokeJoin = Paint.Join.ROUND
        if (reactive != 0) barPaint.color = reactiveColors[BARS / 2]
        wavePath.reset()
        wavePath.moveTo(0f, h - current[0] * h)
        for (i in 1 until BARS) {
            val px = i * step
            val py = h - current[i] * h
            val prevX = (i - 1) * step
            val prevY = h - current[i - 1] * h
            wavePath.quadTo(prevX, prevY, (prevX + px) / 2f, (prevY + py) / 2f)
        }
        wavePath.lineTo(w, h - current[BARS - 1] * h)
        canvas.drawPath(wavePath, barPaint)
        barPaint.style = old
        barPaint.strokeWidth = oldW
    }

    /** Iris radial: las barras salen del centro como rayos y laten con la musica. */
    private fun drawCircle(canvas: Canvas) {
        val sweep = 360f / BARS
        val thickness = baseRadius * 0.30f
        canvas.save()
        canvas.translate(cx, cy)
        for (i in 0 until BARS) {
            if (reactive != 0) barPaint.color = reactiveColors[i]
            val len = baseRadius * 0.15f + current[i] * maxRay
            barRect.set(-thickness / 2f, -(baseRadius + len), thickness / 2f, -baseRadius)
            canvas.drawRoundRect(barRect, thickness / 2f, thickness / 2f, barPaint)
            canvas.rotate(sweep)
        }
        canvas.restore()
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
