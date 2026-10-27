package com.example.svet

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Сигнал «свет дали»: звук будильника (по кругу) + вибрация. */
object Alarm {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopInternal() }

    @Synchronized
    fun start(context: Context) {
        if (player != null) return
        val c = context.applicationContext

        // Если громкость будильника выкручена в ноль — поднимаем до ~70%, иначе проспишь.
        try {
            val am = c.getSystemService(AudioManager::class.java)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val want = (max * 0.7).toInt().coerceAtLeast(1)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < want) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0)
            }
        } catch (_: Exception) {
        }

        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(c, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (_: Exception) {
            player = null
        }

        try {
            val v = c.getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
            vibrator = v
        } catch (_: Exception) {
        }

        // На всякий случай сам замолчит через 10 минут.
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, 10 * 60 * 1000L)
    }

    fun stop() {
        main.removeCallbacks(autoStop)
        stopInternal()
    }

    @Synchronized
    private fun stopInternal() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }
}
