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
            "Violeta",
            0xFFA78BFA.toInt(),
            intArrayOf(0xFF6D28D9.toInt(), 0xFFA78BFA.toInt(), 0xFFDDD6FE.toInt())
        ),
        Palette(
            "Rosa neon",
            0xFFF472B6.toInt(),
            intArrayOf(0xFFBE185D.toInt(), 0xFFF472B6.toInt(), 0xFFFBCFE8.toInt())
        ),
        Palette(
            "Atardecer",
            0xFFFB923C.toInt(),
            intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt(), 0xFFFB923C.toInt(), 0xFFFDE047.toInt())
        ),
        Palette(
            "Oceano",
            0xFF2DD4BF.toInt(),
            intArrayOf(0xFF0F766E.toInt(), 0xFF2DD4BF.toInt(), 0xFF38BDF8.toInt(), 0xFF818CF8.toInt())
        ),
        Palette(
            "Lima",
            0xFFA3E635.toInt(),
            intArrayOf(0xFF4D7C0F.toInt(), 0xFFA3E635.toInt(), 0xFFECFCCB.toInt())
        ),
        Palette(
            "Blanco puro",
            0xFFFFFFFF.toInt(),
            intArrayOf(0xFF9CA3AF.toInt(), 0xFFF9FAFB.toInt(), 0xFFFFFFFF.toInt())
        ),
        Palette(
            "Fuego",
            0xFFF87171.toInt(),
            intArrayOf(0xFF7F1D1D.toInt(), 0xFFEF4444.toInt(), 0xFFF97316.toInt(), 0xFFFACC15.toInt())
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
