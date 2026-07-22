package com.carplayer.music.ui

/**
 * Paletas de color de la app.
 *
 * Se aplican en caliente pintando las vistas por codigo, sin recrear la Activity:
 * en el Allwinner T3 un recreate() implica volver a inflar todo el layout y se nota.
 */
object Palettes {

    class Palette(
        val name: String,
        val accent: Int,       // color principal: iconos, barra, textos de apoyo
        val bars: IntArray,    // degradado de izquierda a derecha del visualizador
        // reactive = 0 fijo | 1 late con la intensidad | 2 termometro por altura
        val reactive: Int = 0
    )

    val ALL = arrayOf(
        Palette(
            "Neon multicolor",
            0xFF22D3EE.toInt(),
            intArrayOf(
                0xFF22D3EE.toInt(), 0xFF34D399.toInt(), 0xFFFDE047.toInt(),
                0xFFFB923C.toInt(), 0xFFF472B6.toInt(), 0xFFA78BFA.toInt()
            )
        ),
        Palette(
            "Cian clasico",
            0xFF22D3EE.toInt(),
            intArrayOf(0xFF0E7490.toInt(), 0xFF22D3EE.toInt(), 0xFF67E8F9.toInt())
        ),
        Palette(
            "Ambar tablero",
            0xFFFBBF24.toInt(),
            intArrayOf(0xFFB45309.toInt(), 0xFFFBBF24.toInt(), 0xFFFDE68A.toInt())
        ),
        Palette(
            "Verde ruta",
            0xFF34D399.toInt(),
            intArrayOf(0xFF047857.toInt(), 0xFF34D399.toInt(), 0xFFA7F3D0.toInt())
        ),
        Palette(
            "Rojo deportivo",
            0xFFF87171.toInt(),
            intArrayOf(0xFF991B1B.toInt(), 0xFFF87171.toInt(), 0xFFFBBF24.toInt())
        ),
        Palette(
            "Hielo",
            0xFFBFDBFE.toInt(),
            intArrayOf(0xFF3B82F6.toInt(), 0xFFBFDBFE.toInt(), 0xFFFFFFFF.toInt())
        ),
        Palette(
            "Reactiva (late con la musica)",
            0xFF22D3EE.toInt(),
            intArrayOf(0xFF22D3EE.toInt(), 0xFFF472B6.toInt()),
            reactive = 1
        ),
        Palette(
            "Termometro (color por altura)",
            0xFF34D399.toInt(),
            intArrayOf(0xFF2563EB.toInt(), 0xFF34D399.toInt(), 0xFFFDE047.toInt(), 0xFFEF4444.toInt()),
            reactive = 2
        )
    )

    fun get(index: Int): Palette = ALL[index.coerceIn(0, ALL.size - 1)]

    fun names(): Array<String> = Array(ALL.size) { ALL[it].name }
}
