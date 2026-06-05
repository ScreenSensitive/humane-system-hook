package com.penumbraos.hook

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.math.abs

/**
 * Spotify engine, hosted in ironman (always-alive). Runs the bundled librespot binary as a
 * logged-in Connect device "Ai Pin" (pipe backend -> stdout PCM) and exposes the current track
 * as a SEEKABLE local WAV file over HTTP, so the Humane music app's ExoPlayer can play it on the
 * SAME native pipeline as NewPipe (MusicHooks returns playbackUri = http://127.0.0.1:27089/t/<uri>?ms=<dur>).
 *
 * Why a temp FILE and not a live stream: ExoPlayer treats the URL as a seekable resource — it
 * closes the connection when its buffer fills and REOPENS with a byte Range to continue/seek.
 * A live pipe can't satisfy that (every reopen would restart librespot mid-track and land bytes
 * at the wrong offset = screech). So a single reader decodes the track into a growing temp WAV
 * file once; HTTP handlers serve byte ranges out of that file (blocking until the file has filled
 * to the requested offset). Reopen/seek/pause all just re-read the file -> no restart, correct
 * bytes. playUri is issued only when the track actually CHANGES (not on a reopen of the same uri).
 */
object SpotifyControl {
    private const val TAG = "PenumbraHook"
    const val PORT = 27089
    private const val SAMPLE_RATE = 44100
    private const val BYTES_PER_SEC = SAMPLE_RATE * 2 * 2   // 16-bit stereo
    private const val WAV_HEADER = 44

    @Volatile private var appCtx: Context? = null
    @Volatile private var proc: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var serverStarted = false
    @Volatile private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    // Current track's buffer file. The single reader writes librespot PCM into [fillOut] (after
    // the 44-byte header); handlers read [fillFile] up to [fillBytes]. fillLock guards swap/write.
    @Volatile private var currentUri: String? = null
    @Volatile private var fillFile: File? = null
    @Volatile private var fillOut: RandomAccessFile? = null
    @Volatile private var fillBytes: Long = 0          // PCM bytes written (excludes header)
    @Volatile private var expectedPcm: Long = 0        // total PCM bytes for the track
    @Volatile private var fillDone: Boolean = false
    @Volatile private var pipeTotal: Long = 0          // cumulative bytes read from librespot stdout
    private val fillLock = Any()
    private val trackLock = Any()                      // serializes track switches

    fun install(cl: ClassLoader) {
        if (serverStarted) return
        try {
            appCtx = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context
            startServer()
            serverStarted = true
        } catch (t: Throwable) {
            Log.e(TAG, "  SpotifyControl install failed: ${t.message}")
        }
    }

    private fun cacheDir(): File =
        File(appCtx?.cacheDir ?: File("/sdcard/aipin_spotify_cache"), "spstream").apply { mkdirs() }

    // ── HTTP server ────────────────────────────────────────────────────────────

    private fun startServer() {
        Thread {
            // The hook loads into multiple ironman processes; whichever BINDS :27089 first is the
            // sole engine owner (no SO_REUSEADDR -> others fail) so we never run duplicate "Ai Pin".
            val ss = ServerSocket()
            try {
                ss.bind(InetSocketAddress("127.0.0.1", PORT))
            } catch (t: Throwable) {
                Log.w(TAG, "  SpotifyControl: :$PORT owned by another process — not the engine owner")
                return@Thread
            }
            Log.w(TAG, "  SpotifyControl: engine owner, stream server on 127.0.0.1:$PORT")
            killStrayLibrespot()
            runCatching { cacheDir().listFiles()?.forEach { it.delete() } }   // clear old buffers
            SpotifyApi.readConfig()?.let { cfg -> runCatching { ensureLibrespotRunning(cfg) } }
            while (true) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: continue
                Thread { runCatching { handle(sock) }.onFailure { Log.e(TAG, "  Spotify handle: ${it.message}") } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun killStrayLibrespot() {
        runCatching {
            File("/proc").listFiles { f -> f.isDirectory && f.name.all(Char::isDigit) }?.forEach { d ->
                val cmd = runCatching { File(d, "cmdline").readText() }.getOrNull() ?: return@forEach
                if (cmd.contains("libspotify.so")) {
                    d.name.toIntOrNull()?.let { android.os.Process.killProcess(it); Log.w(TAG, "  SpotifyControl: killed stray librespot pid=$it") }
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val ins = s.getInputStream()
            val req = StringBuilder(); val b = ByteArray(1); var prevNl = false
            while (true) {
                if (ins.read(b) < 0) break
                req.append(b[0].toInt().toChar())
                if (b[0].toInt() == '\n'.code) { if (prevNl) break; prevNl = true } else if (b[0].toInt() != '\r'.code) prevNl = false
                if (req.length > 8192) break
            }
            val text = req.toString()
            val line = text.lineSequence().firstOrNull().orEmpty()
            val path = line.split(' ').getOrNull(1) ?: run { error(s, "404 Not Found"); return }
            if (!path.startsWith("/t/")) { error(s, "404 Not Found"); return }
            val qIdx = path.indexOf('?')
            val uri = URLDecoder.decode(path.substring(3, if (qIdx >= 0) qIdx else path.length), "UTF-8")
            val ms = if (qIdx >= 0) Regex("ms=(\\d+)").find(path.substring(qIdx))?.groupValues?.get(1)?.toLongOrNull() ?: 0L else 0L
            val rangeStart = Regex("(?i)Range:\\s*bytes=(\\d+)-").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            Log.w(TAG, "  Spotify stream req: $uri (${ms}ms) range=$rangeStart")

            if (SpotifyApi.readConfig() == null) { error(s, "503 Service Unavailable"); return }
            if (!ensureTrack(uri, ms)) { error(s, "503 Service Unavailable"); return }

            val file = fillFile ?: run { error(s, "503 Service Unavailable"); return }
            val total = WAV_HEADER + expectedPcm                 // full WAV size
            val out = s.getOutputStream()
            if (rangeStart in 1 until total) {
                out.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/wav\r\nAccept-Ranges: bytes\r\n" +
                    "Content-Range: bytes $rangeStart-${total - 1}/$total\r\nContent-Length: ${total - rangeStart}\r\nConnection: close\r\n\r\n").toByteArray())
            } else {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nAccept-Ranges: bytes\r\n" +
                    "Content-Length: $total\r\nConnection: close\r\n\r\n").toByteArray())
            }
            serveFrom(out, file, if (rangeStart in 1 until total) rangeStart else 0L, total)
        }
    }

    /** Serve bytes [start, total) from the growing buffer file, waiting for the fill to reach them. */
    private fun serveFrom(out: OutputStream, file: File, start: Long, total: Long) {
        var sent = 0L
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                var pos = start
                val buf = ByteArray(32768)
                while (pos < total) {
                    if (file != fillFile) break                      // track switched -> stop
                    val avail = WAV_HEADER + fillBytes
                    if (pos < avail) {
                        val want = minOf(buf.size.toLong(), avail - pos, total - pos).toInt()
                        val n = raf.read(buf, 0, want); if (n < 0) break
                        out.write(buf, 0, n); pos += n; sent += n
                    } else if (fillDone) break else Thread.sleep(40)
                }
                out.flush()
            }
            Log.w(TAG, "  Spotify served $sent bytes from ${file.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "  Spotify serve closed: ${t.message}")
        }
    }

    /** Switch librespot to [uri] and start filling a fresh buffer — but ONLY if the track actually
     *  changed. A reopen/seek of the SAME uri just re-reads the existing file (no librespot restart). */
    private fun ensureTrack(uri: String, ms: Long): Boolean {
        synchronized(trackLock) {
            if (uri == currentUri && fillFile != null) return true
            val cfg = SpotifyApi.readConfig() ?: return false
            ensureLibrespotRunning(cfg)
            val device = SpotifyApi.waitForAiPin(6) ?: run { Log.e(TAG, "  Spotify: Ai Pin not online"); return false }
            Log.w(TAG, "  Spotify: device=$device, playUri $uri")
            SpotifyApi.playUri(device, uri)
            syncToTrack(uri)

            val pcm = if (ms > 0) (ms * BYTES_PER_SEC / 1000) and 3L.inv() else Long.MAX_VALUE / 2
            val nf = File(cacheDir(), "sp_${abs(uri.hashCode())}.wav")
            val raf = RandomAccessFile(nf, "rw"); raf.setLength(0); raf.write(wavHeader(pcm))
            val old = fillFile
            synchronized(fillLock) {
                runCatching { fillOut?.close() }
                fillOut = raf; fillFile = nf; fillBytes = 0; fillDone = false; expectedPcm = pcm; currentUri = uri
            }
            old?.let { if (it != nf) runCatching { it.delete() } }
            Log.w(TAG, "  Spotify: filling ${nf.name} (${pcm} pcm bytes)")
            return true
        }
    }

    private fun syncToTrack(uri: String) {
        repeat(24) {
            val cur = SpotifyApi.currentlyPlaying()
            if (cur != null && cur.first == uri && cur.second < 2500) return
            Thread.sleep(250)
        }
        Log.w(TAG, "  Spotify: sync timeout for $uri (filling anyway)")
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val ch = 2; val bits = 16; val byteRate = SAMPLE_RATE * ch * bits / 8
        val h = java.nio.ByteBuffer.allocate(WAV_HEADER).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((dataBytes + 36).coerceAtMost(0xFFFFFFFFL).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(ch.toShort())
        h.putInt(SAMPLE_RATE); h.putInt(byteRate); h.putShort((ch * bits / 8).toShort()); h.putShort(bits.toShort())
        h.put("data".toByteArray()); h.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return h.array()
    }

    private fun error(s: Socket, status: String) =
        runCatching { s.getOutputStream().write("HTTP/1.1 $status\r\nConnection: close\r\n\r\n".toByteArray()) }

    // ── librespot host ───────────────────────────────────────────────────────

    @Synchronized
    private fun ensureLibrespotRunning(cfg: JSONObject) {
        if (proc?.isAlive == true) return
        val binary = findLibrespot(cfg) ?: run { Log.e(TAG, "  Spotify: librespot binary not found"); return }
        acquireMulticastLock()
        val cache = cfg.optString("cache", "/sdcard/aipin_spotify_cache").also { File(it).mkdirs() }
        val cmd = mutableListOf(
            binary.absolutePath, "--name", cfg.optString("name", "Ai Pin"),
            "--backend", "pipe", "--bitrate", cfg.optInt("bitrate", 320).toString(),
            "--cache", cache, "--disable-audio-cache",
            // Autoplay OFF: we drive the queue track-by-track (Humane requests each track's URL).
            // With autoplay following the client setting, librespot streams its OWN extra tracks
            // into the continuous pipe between our requests; that foreign PCM bleeds into the next
            // track's buffer at a non-frame-aligned offset -> screech. Off = one track per playUri.
            "--autoplay", "off",
        )
        Log.w(TAG, "  Spotify: launching librespot (native-tls)")
        val tmpDir = File(appCtx?.cacheDir ?: File("/sdcard/aipin_spotify_cache"), "lstmp").apply { mkdirs() }
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        pb.environment()["TMPDIR"] = tmpDir.absolutePath
        val p = pb.start(); proc = p
        Thread { runCatching { p.errorStream.bufferedReader().forEachLine { Log.w(TAG, "  [librespot] $it") } } }.start()
        startReader(p)
    }

    /** Single reader: librespot stdout PCM -> the current track's buffer file (after its header). */
    private fun startReader(p: Process) {
        runCatching { readerThread?.interrupt() }
        val reader = Thread {
            val buf = ByteArray(16384)
            runCatching {
                val ins = p.inputStream
                while (!Thread.currentThread().isInterrupted) {
                    val n = ins.read(buf); if (n < 0) break
                    if (n <= 0) continue
                    synchronized(fillLock) {
                        val raf = fillOut
                        if (raf != null && !fillDone && fillBytes < expectedPcm) {
                            // On a FRESH track file, skip 0–3 bytes so the first kept byte sits on a
                            // 4-byte S16-stereo frame boundary in the continuous pipe. Without this a
                            // non-aligned start swaps L/R + hi/lo bytes -> screech for the whole track.
                            val off = if (fillBytes == 0L) ((4 - (pipeTotal % 4)) % 4).toInt().coerceAtMost(n) else 0
                            val take = minOf((n - off).toLong(), expectedPcm - fillBytes).toInt()
                            if (take > 0) { raf.write(buf, off, take); fillBytes += take }
                            if (fillBytes >= expectedPcm) fillDone = true
                        }
                    }
                    pipeTotal += n
                }
            }
            Log.w(TAG, "  Spotify: reader stopped")
        }.apply { isDaemon = true }
        readerThread = reader; reader.start()
    }

    private fun findLibrespot(cfg: JSONObject): File? {
        cfg.optString("binary").takeIf { it.isNotBlank() }?.let { val f = File(it); if (f.canExecute()) return f }
        for (abi in listOf("arm64-v8a", "arm64")) {
            val f = File("/data/app/com.penumbraos.hook-injected/lib/$abi/libspotify.so")
            if (f.exists()) return f
        }
        File("/data/app").listFiles()?.filter { it.name.startsWith("com.penumbraos.hook") }?.forEach { dir ->
            File(dir, "lib").listFiles()?.forEach { abi -> File(abi, "libspotify.so").takeIf { it.exists() }?.let { return it } }
        }
        return null
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) return
            val wifi = appCtx?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
            multicastLock = wifi.createMulticastLock("aipin-spotify").apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}
    }
}
