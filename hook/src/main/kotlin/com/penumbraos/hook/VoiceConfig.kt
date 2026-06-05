package com.penumbraos.hook

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Reads the voice feature flags the aipin control app already writes to
 * /sdcard/aipin_voice_config.json (announce_caller / announce_text). Reused as-is
 * so the existing app/python toggles keep working — no new toggle store. Cached
 * with a short TTL so per-event reads are cheap. Missing file/key => false.
 */
object VoiceConfig {
    private const val TAG = "PenumbraHook"
    private const val PATH = "/sdcard/aipin_voice_config.json"
    private const val TTL_MS = 2000L

    @Volatile private var cache: JSONObject = JSONObject()
    @Volatile private var cachedAt = 0L

    private fun config(): JSONObject {
        val now = System.currentTimeMillis()
        if (now - cachedAt < TTL_MS) return cache
        cache = try {
            val f = File(PATH)
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (t: Throwable) {
            Log.e(TAG, "VoiceConfig read failed: ${t.message}")
            JSONObject()
        }
        cachedAt = now
        return cache
    }

    fun announceCaller(): Boolean = flag("announce_caller")
    fun announceText(): Boolean = flag("announce_text")

    /** Hands-free follow-ups: auto re-open the mic after a compose prompt (default off). */
    fun autoListen(): Boolean = flag("auto_listen")

    /** Music stream quality: 0=low (fastest), 1=medium, 2=high (default). */
    fun streamQuality(): Int = try {
        config().optInt("stream_quality", 2).coerceIn(0, 2)
    } catch (t: Throwable) { 2 }

    /** Music search source: "youtube" (default), "ytmusic", "soundcloud", or "spotify". */
    fun musicSource(): String = try {
        config().optString("music_source", "youtube").lowercase()
    } catch (t: Throwable) { "youtube" }

    /** Set the music source (read-modify-write, preserving all other keys; invalidates cache). */
    fun setMusicSource(source: String): Boolean = try {
        val f = File(PATH)
        val obj = if (f.exists()) JSONObject(f.readText()) else JSONObject()
        obj.put("music_source", source)
        f.writeText(obj.toString())
        cachedAt = 0L
        true
    } catch (t: Throwable) {
        Log.e(TAG, "VoiceConfig setMusicSource failed: ${t.message}"); false
    }

    /** When true (default), "play <song>" continues into a radio of similar tracks after the song. */
    fun radioAutoplay(): Boolean = try {
        config().optBoolean("radio_autoplay", true)
    } catch (t: Throwable) { true }

    private fun flag(key: String): Boolean = try {
        config().optBoolean(key, false)
    } catch (t: Throwable) {
        false
    }
}
