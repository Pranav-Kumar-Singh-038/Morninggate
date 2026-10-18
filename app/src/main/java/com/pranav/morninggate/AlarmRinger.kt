package com.pranav.morninggate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * The wake alarm: a looped alarm-stream ringtone, a repeating vibration and a
 * partial wake lock. Idempotent — [GateService] owns the lifecycle; the alarm
 * screen uses it as a fallback when the service couldn't be started.
 */
object AlarmRinger {

    @Volatile
    private var ringing = false

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var audioManager: AudioManager? = null
    private var savedAlarmVolume = -1

    fun isRinging(): Boolean = ringing

    fun start(context: Context) {
        if (ringing) return
        ringing = true
        val app = context.applicationContext

        // Make sure the alarm stream can actually be heard; restored on stop.
        try {
            val am = app.getSystemService(AudioManager::class.java)
            if (am != null) {
                audioManager = am
                if (savedAlarmVolume < 0) savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0
                )
            }
        } catch (t: Throwable) {
            Logx.add("alarm volume: ${t.javaClass.simpleName}")
        }

        try {
            val uri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Logx.add("alarm sound failed: ${t.javaClass.simpleName}")
        }

        try {
            vibrator = app.getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0))
        } catch (t: Throwable) {
            Logx.add("alarm vibrate failed: ${t.javaClass.simpleName}")
        }

        try {
            val pm = app.getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morninggate:wake_alarm")
            wakeLock?.acquire(30L * 60L * 1000L)
        } catch (t: Throwable) {
            Logx.add("alarm wakelock failed: ${t.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!ringing && player == null && vibrator == null && wakeLock == null) return
        ringing = false

        try {
            player?.stop()
        } catch (_: Throwable) {
        }
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null

        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null

        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null

        try {
            if (savedAlarmVolume >= 0) {
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            }
        } catch (_: Throwable) {
        }
        audioManager = null
        savedAlarmVolume = -1
    }
}
