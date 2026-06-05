package com.penumbraos.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Spotify audio engine for the Pin (music process). Runs the cross-compiled librespot
 * binary as a headless Spotify Connect speaker and streams its raw PCM to the Pin's
 * speaker via AudioTrack — the only viable audio route (the shell uid isn't in the
 * `audio` group, so raw ALSA is out; an app process like this one CAN use AudioTrack,
 * proven by [SpotifyAudioSpike]).
 *
 * librespot runs with the `pipe` backend writing PCM to stdout (44.1 kHz, 16-bit,
 * stereo, little-endian — its default). We exec it, pump stdout into a streaming
 * AudioTrack, and let Spotify Connect (driven from the app/voice via the Web API)
 * control what plays. We don't pass a track on the CLI — librespot is the speaker;
 * playback is started by targeting this device through the Web API.
 *
 * Config the control app writes to /sdcard/aipin_spotify.json:
 *   { "binary": "<abs path to librespot, in an exec-capable dir>",
 *     "access_token": "<Spotify Premium OAuth token>",
 *     "name": "Ai Pin", "bitrate": 320, "cache": "<writable dir>" }
 * (Binary path is config-driven so we can settle the exec location on-device without a
 * rebuild. librespot can't auth without a Premium token.)
 *
 * Triggers (runtime-registered receivers): com.penumbraos.hook.SPOTIFY_START / _STOP.
 */
object SpotifyPlayer {
    private const val TAG = "PenumbraHook"
    private const val CONFIG_PATH = "/sdcard/aipin_spotify.json"

    private const val SAMPLE_RATE = 44100
    @Volatile private var registered = false
    @Volatile private var proc: Process? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var pumpThread: Thread? = null
    @Volatile private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    fun install() {
        if (registered) return
        try {
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context ?: run {
                    Log.w(TAG, "  SpotifyPlayer: no app context yet"); return
                }
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    Thread { runCatching { start() }.onFailure { Log.e(TAG, "  Spotify start failed: ${it.message}", it) } }.start()
                }
            }, IntentFilter("com.penumbraos.hook.SPOTIFY_START"))
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { runCatching { stop() } }
            }, IntentFilter("com.penumbraos.hook.SPOTIFY_STOP"))
            registered = true
            Log.w(TAG, "  SpotifyPlayer: START/STOP receivers registered")
        } catch (t: Throwable) {
            Log.e(TAG, "  SpotifyPlayer register failed: ${t.message}")
        }
    }

    @Synchronized
    private fun start() {
        val cfg = readConfig() ?: JSONObject()   // config optional — zeroconf needs none
        val binary = findLibrespot(cfg)
            ?: run { Log.e(TAG, "  Spotify: librespot binary not found (libspotify.so in hook lib dir)"); return }
        Log.w(TAG, "  Spotify: using librespot at ${binary.absolutePath}")

        stop()  // tear down any prior session
        acquireMulticastLock()  // so Android delivers the phone's mDNS query -> "Ai Pin" is discoverable

        val cache = cfg.optString("cache", "/sdcard/aipin_spotify_cache").also { File(it).mkdirs() }
        val cmd = mutableListOf(
            binary.absolutePath,
            "--name", cfg.optString("name", "Ai Pin"),
            "--backend", "pipe",          // raw PCM to stdout
            "--device", "-",              // stdout
            "--bitrate", cfg.optInt("bitrate", 320).toString(),
            "--cache", cache,
            "--disable-audio-cache"
        )
        // With a Premium access token -> direct login; without -> zeroconf mode (advertise
        // on wifi, the user's Spotify app authenticates by selecting "Ai Pin").
        val token = cfg.optString("access_token")
        if (token.isNotBlank()) { cmd += "--access-token"; cmd += token; Log.w(TAG, "  Spotify: token login") }
        else Log.w(TAG, "  Spotify: zeroconf mode (pick 'Ai Pin' in your Spotify app)")
        Log.w(TAG, "  Spotify: launching librespot (${cfg.optString("name", "Ai Pin")})")
        val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
        proc = p
        // librespot logs to stderr — drain it to logcat so we can debug auth/streaming.
        Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { Log.w(TAG, "  [librespot] $it") } }
        }.start()
        startAudioPump(p)
    }

    /** Read PCM from librespot stdout and stream it into AudioTrack (44.1k/16-bit/stereo). */
    private fun startAudioPump(p: Process) {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        val pump = Thread {
            val buf = ByteArray(minBuf)
            try {
                val ins = p.inputStream
                while (!Thread.currentThread().isInterrupted) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) t.write(buf, 0, n)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "  Spotify audio pump ended: ${e.message}")
            }
            Log.w(TAG, "  Spotify: audio pump stopped")
        }
        pumpThread = pump
        pump.start()
        Log.w(TAG, "  Spotify: AudioTrack streaming started (min buf=$minBuf)")
    }

    @Synchronized
    private fun stop() {
        runCatching { pumpThread?.interrupt() }; pumpThread = null
        runCatching { proc?.destroy() }; proc = null
        runCatching { track?.let { it.stop(); it.release() } }; track = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }; multicastLock = null
    }

    /** Hold a Wi-Fi multicast lock so Android delivers inbound mDNS to this process (needed for
     *  zeroconf Connect discovery). Best-effort — needs CHANGE_WIFI_MULTICAST_STATE; harmless if denied. */
    private fun acquireMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) return
            val ctx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context ?: return
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
            multicastLock = wifi.createMulticastLock("aipin-spotify").apply { setReferenceCounted(false); acquire() }
            Log.w(TAG, "  Spotify: multicast lock acquired")
        } catch (t: Throwable) {
            Log.w(TAG, "  Spotify: multicast lock unavailable (${t.message}) — zeroconf may not discover")
        }
    }

    private fun readConfig(): JSONObject? = runCatching {
        val f = File(CONFIG_PATH)
        if (f.exists()) JSONObject(f.readText()) else null
    }.getOrNull()

    /**
     * Locate the librespot binary. We ship it as libspotify.so inside the hook APK's
     * jniLibs, so Android extracts it (0755, executable) to the hook's nativeLibraryDir
     * under the hook's /data/app/...-injected/lib/(abi)/ — same place the Frida gadget
     * loads from. Config "binary" can override for testing.
     */
    private fun findLibrespot(cfg: JSONObject): File? {
        cfg.optString("binary").takeIf { it.isNotBlank() }?.let {
            val f = File(it); if (f.canExecute()) return f
        }
        val dataApp = File("/data/app")
        val hookDirs = dataApp.listFiles()?.filter { it.name.startsWith("com.penumbraos.hook") } ?: emptyList()
        for (dir in hookDirs) {
            File(dir, "lib").listFiles()?.forEach { abi ->
                val f = File(abi, "libspotify.so")
                if (f.exists()) return f
            }
        }
        return null
    }
}
