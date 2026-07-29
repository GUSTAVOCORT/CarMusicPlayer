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
        Palette("Fuego", 0xFFF87171.toInt(),
            intArrayOf(0xFF7F1D1D.toInt(), 0xFFEF4444.toInt(), 0xFFF97316.toInt(), 0xFFFACC15.toInt())),
        Palette("Neon Cyan", 0xFF22D3EE.toInt(),
            intArrayOf(0xFF0E7490.toInt(), 0xFF22D3EE.toInt(), 0xFF67E8F9.toInt())),
        Palette("Verde acido", 0xFFA3E635.toInt(),
            intArrayOf(0xFF4D7C0F.toInt(), 0xFFA3E635.toInt(), 0xFFECFCCB.toInt())),
        Palette("Magenta", 0xFFF472B6.toInt(),
            intArrayOf(0xFFBE185D.toInt(), 0xFFF472B6.toInt(), 0xFFFBCFE8.toInt())),
        Palette("Hielo", 0xFFBFDBFE.toInt(),
            intArrayOf(0xFF3B82F6.toInt(), 0xFFBFDBFE.toInt(), 0xFFFFFFFF.toInt())),
        Palette("Atardecer", 0xFFFB923C.toInt(),
            intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt(), 0xFFFB923C.toInt(), 0xFFFDE047.toInt())),
        Palette("Blanco puro", 0xFFFFFFFF.toInt(),
            intArrayOf(0xFF9CA3AF.toInt(), 0xFFF9FAFB.toInt(), 0xFFFFFFFF.toInt())),
        Palette("Arcoiris", 0xFF22D3EE.toInt(),
            intArrayOf(0xFFEF4444.toInt(), 0xFFF97316.toInt(), 0xFFFACC15.toInt(), 0xFF22C55E.toInt(),
                       0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFFEC4899.toInt())),
        Palette("Lava", 0xFFF97316.toInt(),
            intArrayOf(0xFF450A0A.toInt(), 0xFFB91C1C.toInt(), 0xFFF97316.toInt(), 0xFFFDE047.toInt())),
        Palette("Oceano", 0xFF2DD4BF.toInt(),
            intArrayOf(0xFF0F766E.toInt(), 0xFF2DD4BF.toInt(), 0xFF38BDF8.toInt(), 0xFF818CF8.toInt())),
        Palette("Tropical", 0xFF34D399.toInt(),
            intArrayOf(0xFF059669.toInt(), 0xFF34D399.toInt(), 0xFFFDE047.toInt(), 0xFFFB923C.toInt())),
        Palette("Chicle", 0xFFF9A8D4.toInt(),
            intArrayOf(0xFFEC4899.toInt(), 0xFFF9A8D4.toInt(), 0xFF93C5FD.toInt())),
        Palette("Cyberpunk", 0xFFF000B8.toInt(),
            intArrayOf(0xFF00F0FF.toInt(), 0xFFF000B8.toInt(), 0xFFFCEE0A.toInt())),
        Palette("Esmeralda", 0xFF10B981.toInt(),
            intArrayOf(0xFF064E3B.toInt(), 0xFF10B981.toInt(), 0xFF6EE7B7.toInt())),
        Palette("Oro rosa", 0xFFECC0A8.toInt(),
            intArrayOf(0xFFB76E79.toInt(), 0xFFECC0A8.toInt(), 0xFFFCE7DE.toInt())),
        Palette("Ambar", 0xFFFBBF24.toInt(),
            intArrayOf(0xFFB45309.toInt(), 0xFFFBBF24.toInt(), 0xFFFDE68A.toInt())),
        Palette("Violeta neon", 0xFFA78BFA.toInt(),
            intArrayOf(0xFF6D28D9.toInt(), 0xFFA78BFA.toInt(), 0xFFDDD6FE.toInt())),
        Palette("Rojo sangre", 0xFFDC2626.toInt(),
            intArrayOf(0xFF450A0A.toInt(), 0xFF991B1B.toInt(), 0xFFDC2626.toInt(), 0xFFF87171.toInt())),
        Palette("Menta", 0xFF6EE7B7.toInt(),
            intArrayOf(0xFF14B8A6.toInt(), 0xFF6EE7B7.toInt(), 0xFFECFDF5.toInt())),
        Palette("Galaxia", 0xFF8B5CF6.toInt(),
            intArrayOf(0xFF1E1B4B.toInt(), 0xFF6D28D9.toInt(), 0xFFDB2777.toInt(), 0xFF38BDF8.toInt())),
        Palette("Reactiva (late con la musica)", 0xFF22D3EE.toInt(),
            intArrayOf(0xFF22D3EE.toInt(), 0xFFF472B6.toInt()), reactive = 1),
        Palette("Termometro (color por altura)", 0xFF34D399.toInt(),
            intArrayOf(0xFF2563EB.toInt(), 0xFF34D399.toInt(), 0xFFFDE047.toInt(), 0xFFEF4444.toInt()), reactive = 2)
    )

    fun get(index: Int): Palette = ALL[index.coerceIn(0, ALL.size - 1)]

    fun names(): Array<String> = Array(ALL.size) { ALL[it].name }
}
