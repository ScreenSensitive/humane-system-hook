package com.penumbraos.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * SPIKE (de-risk for Spotify/librespot): prove that our injected code in the music
 * process can push raw PCM to the Pin's speaker via AudioTrack.
 *
 * Why this matters: librespot streams decoded PCM, and we have to get that to the
 * speaker. Raw ALSA (/dev/snd) is NOT an option — the shell uid isn't in the `audio`
 * group — so the only route is Android's AudioTrack, which an app process (this one,
 * humane.experience.music) can use. If this tone plays, the librespot->speaker path
 * (librespot PCM -> pipe -> this process -> AudioTrack) is viable.
 *
 * Trigger (music process must be injected — say "play" once or wait for boot-persist):
 *   adb shell am broadcast -a com.penumbraos.hook.PCM_TEST
 * Plays a 2s 440 Hz tone. Pure test code, gated on the broadcast — affects nothing else.
 */
object SpotifyAudioSpike {
    private const val TAG = "PenumbraHook"
    @Volatile private var registered = false

    fun install() {
        if (registered) return
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context ?: run {
                    Log.w(TAG, "  AudioSpike: no app context yet"); return
                }
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    Log.w(TAG, "  AudioSpike: PCM_TEST received -> playing tone")
                    Thread {
                        runCatching { playTone() }
                            .onFailure { Log.e(TAG, "  AudioSpike play failed: ${it.message}", it) }
                    }.start()
                }
            }, IntentFilter("com.penumbraos.hook.PCM_TEST"))
            registered = true
            Log.w(TAG, "  SpotifyAudioSpike: PCM_TEST receiver registered (music process)")
        } catch (t: Throwable) {
            Log.e(TAG, "  SpotifyAudioSpike register failed: ${t.message}")
        }
    }

    private fun playTone() {
        val sr = 44100
        val secs = 2
        val freq = 440.0
        val n = sr * secs
        val buf = ShortArray(n) { i ->
            (sin(2.0 * PI * freq * i / sr) * 0.3 * Short.MAX_VALUE).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(buf, 0, buf.size)
        track.play()
        Log.w(TAG, "  AudioSpike: AudioTrack.play() 440Hz ${secs}s (state=${track.playState})")
        Thread.sleep((secs * 1000 + 300).toLong())
        runCatching { track.stop(); track.release() }
        Log.w(TAG, "  AudioSpike: done")
    }
}
