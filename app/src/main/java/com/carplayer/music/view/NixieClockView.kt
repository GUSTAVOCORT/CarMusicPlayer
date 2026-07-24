package com.carplayer.music.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/**
 * Reloj estilo Nixie (tubos de neon naranja de los años 60) dibujado con Canvas.
 *
 * Los digitos se forman con segmentos, como un display de 7 segmentos pero con el
 * brillo calido y el resplandor de los tubos reales. Se refresca una vez por segundo,
 * no en cada cuadro: es practicamente gratis para el T3.
 */
class NixieClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        private val GLOW = Color.parseColor("#FF6A1A")   // naranja tubo
        private val LIT = Color.parseColor("#FFB166")    // segmento encendido
        private val DIM = Color.parseColor("#2A1206")    // segmento apagado

        // Mapa de 7 segmentos por digito: a,b,c,d,e,f,g
        private val SEG = arrayOf(
            booleanArrayOf(true, true, true, true, true, true, false),      // 0
            booleanArrayOf(false, true, true, false, false, false, false),  // 1
            booleanArrayOf(true, true, false, true, true, false, true),     // 2
            booleanArrayOf(true, true, true, true, false, false, true),     // 3
            booleanArrayOf(false, true, true, false, false, true, true),    // 4
            booleanArrayOf(true, false, true, true, false, true, true),     // 5
            booleanArrayOf(true, false, true, true, true, true, true),      // 6
            booleanArrayOf(true, true, true, false, false, false, false),   // 7
            booleanArrayOf(true, true, true, true, true, true, true),       // 8
            booleanArrayOf(true, true, true, true, false, true, true)       // 9
        )
    }

    var neon: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val seg = RectF()

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            // Se re-agenda al comienzo del proximo segundo
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val c = Calendar.getInstance()
        var hh = c.get(Calendar.HOUR)
        if (hh == 0) hh = 12
        val mm = c.get(Calendar.MINUTE)

        val digits = intArrayOf(hh / 10, hh % 10, -1, mm / 10, mm % 10)  // -1 = dos puntos

        val h = height.toFloat()
        val digitW = h * 0.5f
        val gap = digitW * 0.25f
        val colonW = digitW * 0.4f
        val totalW = digitW * 4 + colonW + gap * 4
        var x = (width - totalW) / 2f
        val thick = digitW * 0.12f
        paint.strokeWidth = thick

        for (d in digits) {
            if (d == -1) {
                drawColon(canvas, x + colonW / 2f, h, thick)
                x += colonW + gap
            } else {
                drawDigit(canvas, d, x, h * 0.2f, digitW, h * 0.6f, thick)
                x += digitW + gap
            }
        }
    }

    private fun drawDigit(canvas: Canvas, digit: Int, left: Float, top: Float, w: Float, h: Float, t: Float) {
        val map = SEG[digit]
        val midY = top + h / 2f
        val right = left + w
        val bottom = top + h

        // a=arriba b=der-arriba c=der-abajo d=abajo e=izq-abajo f=izq-arriba g=medio
        segH(canvas, left, top, right, map[0], t)
        segV(canvas, right, top, midY, map[1], t)
        segV(canvas, right, midY, bottom, map[2], t)
        segH(canvas, left, bottom, right, map[3], t)
        segV(canvas, left, midY, bottom, map[4], t)
        segV(canvas, left, top, midY, map[5], t)
        segH(canvas, left, midY, right, map[6], t)
    }

    private fun stylePaint(on: Boolean) {
        if (on) {
            paint.color = LIT
            if (neon) paint.setShadowLayer(paint.strokeWidth * 1.4f, 0f, 0f, GLOW)
            else paint.clearShadowLayer()
        } else {
            paint.color = DIM
            paint.clearShadowLayer()
        }
    }

    private fun segH(canvas: Canvas, x1: Float, y: Float, x2: Float, on: Boolean, t: Float) {
        stylePaint(on)
        canvas.drawLine(x1 + t, y, x2 - t, y, paint)
    }

    private fun segV(canvas: Canvas, x: Float, y1: Float, y2: Float, on: Boolean, t: Float) {
        stylePaint(on)
        canvas.drawLine(x, y1 + t, x, y2 - t, paint)
    }

    private fun drawColon(canvas: Canvas, cx: Float, h: Float, t: Float) {
        stylePaint(true)
        canvas.drawCircle(cx, h * 0.38f, t * 0.6f, paint)
        canvas.drawCircle(cx, h * 0.62f, t * 0.6f, paint)
        paint.clearShadowLayer()
    }
}
