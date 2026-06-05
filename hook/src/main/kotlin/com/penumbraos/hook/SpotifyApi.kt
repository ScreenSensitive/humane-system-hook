package com.penumbraos.hook

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shared Spotify Web API client (used by SpotifyControl in ironman AND MusicHooks in the music
 * process — each reads /sdcard/aipin_spotify.json independently). Handles token refresh on 401.
 * Only the Web API (search / device / play / currently-playing); the actual audio is librespot.
 */
object SpotifyApi {
    private const val TAG = "PenumbraHook"
    const val CONFIG = "/sdcard/aipin_spotify.json"

    data class Track(val uri: String, val title: String, val artist: String, val durationMs: Int, val art: String?)

    fun isConfigured(): Boolean = File(CONFIG).exists()

    fun readConfig(): JSONObject? = runCatching {
        val f = File(CONFIG); if (f.exists()) JSONObject(f.readText()) else null
    }.getOrNull()

    private class Unauthorized : Exception()

    /** Run [block] with a token; on 401 refresh once and retry. */
    private fun <T> withToken(block: (String) -> T): T? {
        val cfg = readConfig() ?: return null
        return try {
            block(cfg.optString("access_token"))
        } catch (e: Unauthorized) {
            val fresh = refresh(cfg) ?: return null
            try { block(fresh) } catch (t: Throwable) { Log.e(TAG, "  SpotifyApi retry: ${t.message}"); null }
        } catch (t: Throwable) {
            Log.e(TAG, "  SpotifyApi: ${t.message}"); null
        }
    }

    private fun refresh(cfg: JSONObject): String? {
        val id = cfg.optString("client_id"); val secret = cfg.optString("client_secret"); val rt = cfg.optString("refresh_token")
        if (id.isBlank() || secret.isBlank() || rt.isBlank()) return null
        return try {
            val conn = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Basic ${Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)}")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.use { it.write("grant_type=refresh_token&refresh_token=${URLEncoder.encode(rt, "UTF-8")}".toByteArray()) }
            val code = conn.responseCode
            val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code !in 200..299) { Log.e(TAG, "  SpotifyApi refresh $code"); return null }
            val tok = JSONObject(txt).optString("access_token").ifBlank { null } ?: return null
            cfg.put("access_token", tok); runCatching { File(CONFIG).writeText(cfg.toString()) }
            tok
        } catch (t: Throwable) { Log.e(TAG, "  SpotifyApi refresh failed: ${t.message}"); null }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code == 401) throw Unauthorized()
        if (code == 204) return JSONObject()
        if (code !in 200..299) throw RuntimeException("GET $url -> $code: ${txt.take(120)}")
        return if (txt.isBlank()) JSONObject() else JSONObject(txt)
    }

    private fun parseTrack(o: JSONObject): Track {
        val artists = o.optJSONArray("artists")
        val artist = if (artists != null && artists.length() > 0) artists.getJSONObject(0).optString("name") else ""
        val art = o.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        return Track(o.optString("uri"), o.optString("name"), artist, o.optInt("duration_ms"), art)
    }

    /** Search tracks; returns up to [limit] tracks with metadata. */
    fun searchTracks(query: String, limit: Int = 20): List<Track> = withToken { token ->
        val body = getJson("https://api.spotify.com/v1/search?q=${enc(query)}&type=track&limit=$limit", token)
        val items = body.optJSONObject("tracks")?.optJSONArray("items") ?: JSONArray()
        (0 until items.length()).map { parseTrack(items.getJSONObject(it)) }.filter { it.uri.isNotBlank() }
    } ?: emptyList()

    /** Batch-resolve track metadata by spotify uris/ids (for Recents). Up to 50 per call. */
    fun tracksByIds(uris: List<String>): List<Track> = withToken { token ->
        val ids = uris.map { it.substringAfterLast(':') }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return@withToken emptyList()
        val out = ArrayList<Track>()
        ids.chunked(50).forEach { chunk ->
            val body = runCatching { getJson("https://api.spotify.com/v1/tracks?ids=${enc(chunk.joinToString(","))}", token) }.getOrNull()
            val arr = body?.optJSONArray("tracks") ?: return@forEach
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(parseTrack(it)) }
        }
        out.filter { it.uri.isNotBlank() }
    } ?: emptyList()

    /** Tracks of the first playlist matching [name] (user's own first, else public search). */
    fun playlistTracks(name: String, limit: Int = 50): List<Track> = withToken { token ->
        val pid = findPlaylistId(name, token) ?: return@withToken emptyList()
        val body = getJson("https://api.spotify.com/v1/playlists/$pid/tracks?limit=$limit", token)
        val items = body.optJSONArray("items") ?: JSONArray()
        (0 until items.length()).mapNotNull { items.getJSONObject(it).optJSONObject("track")?.let(::parseTrack) }
            .filter { it.uri.isNotBlank() }
    } ?: emptyList()

    private fun findPlaylistId(name: String, token: String): String? {
        val mine = runCatching { getJson("https://api.spotify.com/v1/me/playlists?limit=50", token) }.getOrNull()?.optJSONArray("items")
        if (mine != null) {
            val want = name.lowercase()
            for (i in 0 until mine.length()) {
                val p = mine.getJSONObject(i)
                if (p.optString("name").lowercase().contains(want)) return p.optString("id")
            }
        }
        val pub = runCatching { getJson("https://api.spotify.com/v1/search?q=${enc(name)}&type=playlist&limit=1", token) }.getOrNull()
        return pub?.optJSONObject("playlists")?.optJSONArray("items")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
    }

    /** Artist's top tracks (market US). */
    fun artistTopTracks(name: String, limit: Int = 20): List<Track> = withToken { token ->
        val s = getJson("https://api.spotify.com/v1/search?q=${enc(name)}&type=artist&limit=1", token)
        val artistId = s.optJSONObject("artists")?.optJSONArray("items")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
            ?: return@withToken emptyList()
        val body = getJson("https://api.spotify.com/v1/artists/$artistId/top-tracks?market=US", token)
        val items = body.optJSONArray("tracks") ?: JSONArray()
        (0 until items.length()).map { parseTrack(items.getJSONObject(it)) }.take(limit).filter { it.uri.isNotBlank() }
    } ?: emptyList()

    /** id of the "Ai Pin" Connect device (librespot), waiting up to [tries] for it to appear. */
    fun waitForAiPin(tries: Int = 12): String? = withToken { token ->
        repeat(tries) {
            val arr = runCatching { getJson("https://api.spotify.com/v1/me/player/devices", token) }.getOrNull()?.optJSONArray("devices")
            if (arr != null) for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                if (d.optString("name").equals("Ai Pin", true)) return@withToken d.optString("id")
            }
            Thread.sleep(1000)
        }
        null
    }

    /** Start playback of [uri] on [deviceId]. */
    fun playUri(deviceId: String, uri: String): Boolean = withToken { token ->
        val conn = URL("https://api.spotify.com/v1/me/player/play?device_id=${enc(deviceId)}").openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(JSONObject().put("uris", JSONArray().put(uri)).toString().toByteArray()) }
        val code = conn.responseCode; conn.disconnect()
        if (code == 401) throw Unauthorized()
        code in 200..299
    } ?: false

    fun pause(): Boolean = simplePut("https://api.spotify.com/v1/me/player/pause")
    fun resume(): Boolean = simplePut("https://api.spotify.com/v1/me/player/play")
    fun next(): Boolean = simplePost("https://api.spotify.com/v1/me/player/next")
    fun previous(): Boolean = simplePost("https://api.spotify.com/v1/me/player/previous")

    /** (uri, progressMs, isPlaying) of the current playback, or null. */
    fun currentlyPlaying(): Triple<String, Int, Boolean>? = withToken { token ->
        val body = getJson("https://api.spotify.com/v1/me/player/currently-playing", token)
        val item = body.optJSONObject("item") ?: return@withToken null
        Triple(item.optString("uri"), body.optInt("progress_ms"), body.optBoolean("is_playing"))
    }

    private fun simplePut(url: String): Boolean = withToken { token -> method(url, "PUT", token) } ?: false
    private fun simplePost(url: String): Boolean = withToken { token -> method(url, "POST", token) } ?: false

    private fun method(url: String, m: String, token: String): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = m
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (m == "PUT" || m == "POST") { conn.doOutput = true; conn.outputStream.use { it.write(ByteArray(0)) } }
        val code = conn.responseCode; conn.disconnect()
        if (code == 401) throw Unauthorized()
        return code in 200..299
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
