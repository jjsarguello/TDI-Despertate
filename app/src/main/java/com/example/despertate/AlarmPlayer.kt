package com.example.despertate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Reproduce alarma.wav en bucle mientras el estado sea PELIGRO y la corta al salir.
 * Usa el canal de alarma, así suena aunque el volumen multimedia esté bajo
 * (pero depende del volumen de ALARMA y del modo No molestar).
 * Llamar siempre desde el hilo principal.
 */
class AlarmPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Último problema al cargar o reproducir; null si todo bien. */
    var lastError: String? = null
        private set

    /** Volumen actual del canal de alarma, 0..100. */
    fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (max <= 0) return 0
        return audioManager.getStreamVolume(AudioManager.STREAM_ALARM) * 100 / max
    }

    fun setActive(active: Boolean) {
        if (active) start() else stop()
    }

    private fun start() {
        if (player?.isPlaying == true) return
        stop()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = MediaPlayer.create(context, R.raw.alarma, attrs, AudioManager.AUDIO_SESSION_ID_GENERATE)
        if (p == null) {
            lastError = "No se pudo cargar alarma.wav"
            return
        }
        p.setOnErrorListener { _, what, extra ->
            lastError = "Error de audio ($what/$extra)"
            true
        }
        p.isLooping = true
        p.start()
        player = p
        lastError = null
    }

    fun stop() {
        val p = player ?: return
        player = null
        try {
            if (p.isPlaying) p.stop()
        } catch (_: IllegalStateException) {
        }
        p.release()
    }
}
