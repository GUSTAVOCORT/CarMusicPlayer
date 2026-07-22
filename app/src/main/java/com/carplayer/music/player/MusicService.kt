package com.carplayer.music.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.carplayer.music.ui.MainActivity

/**
 * Foreground Service de reproduccion.
 *
 * Al ser un MediaSessionService con notificacion activa, Android 6/7 no lo mata
 * cuando el usuario abre Waze o Google Maps, y el audio no se corta.
 */
@UnstableApi
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Guarda la posicion cada 5 s para poder retomar al encender el auto. */
    private val saveTicker = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Buffers recortados: el default (50 s) reserva demasiada RAM para 2 GB DDR3.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 5_000,
                /* maxBufferMs = */ 15_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .setTargetBufferBytes(2 * 1024 * 1024)   // 2 MB techo
            .build()

        val renderers = DefaultRenderersFactory(this)
            // Sin video: no se instancian renderers de imagen ni se toca la Mali-400.
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekForwardIncrementMs(10_000)
            .setSeekBackIncrementMs(10_000)
            .build()

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(
                eventTime: AnalyticsListener.EventTime,
                audioSessionId: Int
            ) {
                // El visualizador de la UI y el ecualizador se enganchan a este id.
                PlayerBus.audioSessionId.value = audioSessionId
                AudioFx.attach(this@MusicService, audioSessionId)
            }
        })

        val openUi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveState(immediate = !isPlaying)   // al pausar, al disco ya mismo
                if (isPlaying) {
                    handler.removeCallbacks(saveTicker)
                    handler.postDelayed(saveTicker, 5_000)
                } else {
                    handler.removeCallbacks(saveTicker)
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                saveState()
            }
        })

        // IMPORTANTE: en vez de esperar el evento onAudioSessionIdChanged (que en varios
        // equipos no llega nunca), generamos el id nosotros y se lo imponemos al player.
        // Asi el visualizador y el ecualizador tienen un id valido desde el segundo cero.
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val forcedId = am.generateAudioSessionId()
            if (forcedId != AudioManager.ERROR) {
                player.audioSessionId = forcedId
                PlayerBus.audioSessionId.value = forcedId
                AudioFx.attach(this, forcedId)
            }
        } catch (e: Exception) {
            android.util.Log.w("MusicService", "No se pudo fijar el audioSessionId: " + e.message)
        }

        mediaSession = MediaSession.Builder(this, player)
            .setId("car_player_session")
            .setSessionActivity(openUi)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private fun saveState(immediate: Boolean = false) {
        val p = mediaSession?.player ?: return
        if (p.mediaItemCount == 0) return
        PlaybackStore.savePosition(
            this,
            p.currentMediaItemIndex,
            p.currentPosition.coerceAtLeast(0L),
            p.isPlaying,
            immediate
        )
        PlaybackStore.saveMediaId(this, p.currentMediaItem?.mediaId)
    }

    /** Si el usuario cierra la app desde recientes, seguimos sonando salvo que este en pausa. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveState(immediate = true)
        val player = mediaSession?.player ?: return stopSelf()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveState(immediate = true)
        handler.removeCallbacks(saveTicker)
        AudioFx.release()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        PlayerBus.audioSessionId.value = 0
        super.onDestroy()
    }
}
