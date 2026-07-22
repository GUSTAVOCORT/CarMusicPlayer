package com.carplayer.music.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Canal minimo Service -> UI. Un solo Int; evita bindear un Service adicional
 * o mandar custom commands solo para leer el audioSessionId.
 */
object PlayerBus {
    val audioSessionId = MutableStateFlow(0)
}
