package com.penumbraos.hook

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

/**
 * Native "play <song>" recognition (ironman). Without the cloud, "play X" isn't
 * recognized as a music command — it falls through to the dead SYNAPSE assistant
 * ("I'm an AI assistant, I can't play music"), so it never reaches the music
 * experience or our NewPipe provider (MusicHooks).
 *
 * We after-hook RegexInterpreter.interpret and, when nothing else matched, emit a
 * native PlayMusicAction with the Track field set (nameForModel="Track" ->
 * PlayMusic.track() -> MediaManagerPlayMediaResolver -> queryWithTrackName, which
 * MusicHooks intercepts in the music process and serves from NewPipe/YouTube).
 *
 * Same emit path as VoiceComposeHooks/VoiceReadHooks (ActionContentFactory.of +
 * SynapseChatTurnUtils), which is proven to route actions to their experience.
 */
object MusicIntentHook {
    private const val TAG = "PenumbraHook"

    private val PLAY = listOf(
        Regex("^(?:can you |please |hey )?play (?:the song |the track |the |some |me )?(.+)$"),
        Regex("^(?:can you |please )?put on (?:some |the )?(.+)$"),
    )
    // Playlist phrasing — checked BEFORE PLAY so "play workout playlist" routes to the
    // Playlist field (queryWithPlaylistName), never a song-name search.
    private val PLAYLIST = listOf(
        Regex("^(?:can you |please |hey )?(?:play|put on|start) (?:my |the )?(.+) playlist$"),
        Regex("^(?:can you |please |hey )?(?:play|put on|start) (?:the )?playlist (.+)$"),
    )
    // "play artist X" / "play songs by X" -> PlayMusic(Artist) (not a track search).
    private val ARTIST = listOf(
        Regex("^(?:can you |please |hey )?play (?:songs|music|tracks|some music|something) by (.+)$"),
        Regex("^(?:can you |please |hey )?play (?:the )?artist (.+)$"),
        Regex("^(?:can you |please |hey )?play (.+?)'s (?:songs|music|tracks|stuff)$"),
    )
    // Radio / autoplay of the current track -> PlayCurrentTrackRadio (related streams).
    private val RADIO = setOf(
        "play radio", "start radio", "radio", "play more like this", "play similar",
        "play more songs like this", "play similar songs", "play a radio", "autoplay",
        "keep playing similar", "play songs like this",
    )
    // "play radio of <song>" / "play more like <song>" -> radio seeded by a NAMED song.
    // The music side handles a "radio:<seed>" track name (search seed + related streams).
    private val RADIO_OF = listOf(
        Regex("^(?:can you |please |hey )?(?:play|start) (?:a |the )?radio (?:of|for|based on|from|using) (.+)$"),
        Regex("^(?:can you |please |hey )?play (?:more|songs|stuff|tracks) (?:like|similar to) (.+)$"),
    )
    // Bare "play music" with no target -> a popular-music radio (continuous autoplay),
    // instead of falling through to the LLM ("I can't play music").
    private val PLAY_GENERIC = setOf(
        "play music", "play some music", "play me some music", "play me music",
        "play some tunes", "play tunes", "play some songs", "play songs", "play something",
    )
    // Don't treat these as a song title ("play" with no/again etc.).
    private val SKIP = setOf(
        "play", "play it", "play that", "play again",
        "play next", "play previous", "play the next song",
    )

    // Voice media controls -> native no-arg MUSIC actions. Only fire if no native
    // result already handled the utterance (afterHook result==null guard).
    private val CONTROLS: Map<String, String> = buildMap {
        for (p in listOf("pause", "pause music", "pause the music", "pause song", "pause the song")) put(p, "PauseMusic")
        for (p in listOf("resume", "resume music", "unpause", "continue", "continue playing", "keep playing", "resume the music")) put(p, "ResumeMusic")
        for (p in listOf("next", "next song", "next track", "skip", "skip song", "skip this", "skip this song", "skip track")) put(p, "NextTrack")
        for (p in listOf("previous", "previous song", "previous track", "go back", "last song", "play the last song", "go back a song")) put(p, "PreviousTrack")
    }

    // "repeat (all/one/off)" -> broadcast to the music process (MediaManager.setRepeat).
    private val REPEAT: Map<String, String> = buildMap {
        for (p in listOf("repeat one", "repeat this", "repeat this song", "repeat track",
            "repeat current song", "repeat song", "loop this", "loop this song", "loop song")) put(p, "RepeatOne")
        for (p in listOf("repeat", "repeat on", "repeat all", "repeat all songs", "repeat all tracks",
            "loop", "loop all", "turn on repeat", "enable repeat", "repeat mode on")) put(p, "RepeatAll")
        for (p in listOf("repeat off", "turn off repeat", "stop repeating", "disable repeat",
            "no repeat", "stop repeat", "unloop", "repeat mode off")) put(p, "RepeatOff")
    }

    // "shuffle on/off" -> broadcast to the music process (MediaManager.setShuffle).
    private val SHUFFLE: Map<String, Boolean> = buildMap {
        for (p in listOf("shuffle", "shuffle on", "turn on shuffle", "enable shuffle",
            "shuffle my music", "shuffle the music", "shuffle mode on")) put(p, true)
        for (p in listOf("shuffle off", "turn off shuffle", "disable shuffle", "stop shuffling",
            "stop shuffle", "unshuffle", "shuffle mode off", "no shuffle")) put(p, false)
    }

    fun install(cl: ClassLoader) {
        try {
            val regexInterpreter =
                cl.loadClass("humaneinternal.system.intent.interpreters.regex.RegexInterpreter")
            val eventsSnapshot = cl.loadClass("humaneinternal.system.intent.EventsSnapshot")
            val situation = cl.loadClass("humaneinternal.system.intent.situation.Situation")
            val interpret = regexInterpreter.getDeclaredMethod("interpret", eventsSnapshot, situation)
            interpret.isAccessible = true

            XposedBridge.hookMethod(interpret, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.result != null) return   // let native results win
                        val events = param.args.getOrNull(0) ?: return
                        val interpreter = param.thisObject ?: return
                        val raw = normalizedUtterance(interpreter, events) ?: return
                        val u = raw.lowercase().trim().trimEnd('.', '?', '!').trim()
                        if (u.isEmpty()) return

                        // Media controls (no-arg) first: "pause"/"next"/"previous"/"resume".
                        val control = CONTROLS[u]
                        if (control != null) {
                            val ev = VoiceActions.events(cl, control) ?: return
                            param.result = ev
                            Log.w(TAG, "MusicIntent: \"$u\" -> $control")
                            return
                        }

                        // Shuffle on/off -> broadcast to the music process; narrate confirmation.
                        val shuf = SHUFFLE[u]
                        if (shuf != null) {
                            try {
                                val ctx = Class.forName("android.app.ActivityThread")
                                    .getMethod("currentApplication").invoke(null) as? android.content.Context
                                ctx?.sendBroadcast(
                                    android.content.Intent("com.penumbraos.hook.SHUFFLE")
                                        .setPackage("humane.experience.music")
                                        .putExtra("on", shuf)
                                )
                            } catch (t: Throwable) {
                                Log.e(TAG, "  shuffle broadcast failed: ${t.message}")
                            }
                            val sev = VoiceActions.narrate(cl, if (shuf) "Shuffle on." else "Shuffle off.") ?: return
                            param.result = sev
                            Log.w(TAG, "MusicIntent: \"$u\" -> shuffle=$shuf")
                            return
                        }

                        // Repeat one/all/off -> broadcast to the music process; narrate confirmation.
                        val rep = REPEAT[u]
                        if (rep != null) {
                            try {
                                val ctx = Class.forName("android.app.ActivityThread")
                                    .getMethod("currentApplication").invoke(null) as? android.content.Context
                                ctx?.sendBroadcast(
                                    android.content.Intent("com.penumbraos.hook.REPEAT")
                                        .setPackage("humane.experience.music")
                                        .putExtra("mode", rep)
                                )
                            } catch (t: Throwable) {
                                Log.e(TAG, "  repeat broadcast failed: ${t.message}")
                            }
                            val msg = when (rep) {
                                "RepeatOne" -> "Repeating this song."
                                "RepeatAll" -> "Repeat all."
                                else -> "Repeat off."
                            }
                            val rev = VoiceActions.narrate(cl, msg) ?: return
                            param.result = rev
                            Log.w(TAG, "MusicIntent: \"$u\" -> repeat=$rep")
                            return
                        }

                        // "switch to spotify / use youtube music / change source to soundcloud"
                        // -> flip music_source on-device (no app needed); narrate confirmation.
                        val source = matchSource(u)
                        if (source != null) {
                            val ok = VoiceConfig.setMusicSource(source)
                            param.result = VoiceActions.narrate(cl,
                                if (ok) "Switched music to ${sourceLabel(source)}." else "Couldn't switch the music source.") ?: return
                            Log.w(TAG, "MusicIntent: \"$u\" -> music_source=$source")
                            return
                        }

                        // NOTE: Spotify is no longer special-cased here. It rides the SAME
                        // native PlayMusic path as NewPipe (below) — MusicHooks' TidalProvider
                        // hook serves Spotify tracks + a local librespot stream URL when
                        // music_source==spotify, so now-playing/queue/controls are all native.

                        // Radio / autoplay of the current track.
                        if (u in RADIO) {
                            val rev = VoiceActions.events(cl, "PlayCurrentTrackRadio") ?: return
                            param.result = rev
                            Log.w(TAG, "MusicIntent: \"$u\" -> PlayCurrentTrackRadio")
                            return
                        }

                        // Radio seeded by a NAMED song: "play radio of <song>".
                        for (r in RADIO_OF) {
                            val seed = r.find(u)?.groupValues?.getOrNull(1)
                                ?.trim()?.trimEnd('.', '?', '!')?.trim()
                            if (!seed.isNullOrEmpty()) {
                                val rev = VoiceActions.events(cl, "PlayMusic", mapOf("Track" to "radio:$seed")) ?: return
                                param.result = rev
                                Log.w(TAG, "MusicIntent: \"$u\" -> PlayMusic(Track='radio:$seed')")
                                return
                            }
                        }

                        // Bare "play music" -> popular-music radio (autoplay), not the LLM.
                        if (u in PLAY_GENERIC) {
                            val gev = VoiceActions.events(cl, "PlayMusic", mapOf("Track" to "radio:top hits")) ?: return
                            param.result = gev
                            Log.w(TAG, "MusicIntent: \"$u\" -> PlayMusic(Track='radio:top hits')")
                            return
                        }

                        // Playlist before song-name, so it's never a track search.
                        val playlist = matchPlaylist(u)
                        if (playlist != null) {
                            val pOut = VoiceActions.events(cl, "PlayMusic", mapOf("Playlist" to playlist)) ?: return
                            param.result = pOut
                            Log.w(TAG, "MusicIntent: \"$u\" -> PlayMusic(Playlist='$playlist')")
                            return
                        }

                        // "play artist X" / "play songs by X" -> Artist field.
                        val artist = matchArtist(u)
                        if (artist != null) {
                            val aev = VoiceActions.events(cl, "PlayMusic", mapOf("Artist" to artist)) ?: return
                            param.result = aev
                            Log.w(TAG, "MusicIntent: \"$u\" -> PlayMusic(Artist='$artist')")
                            return
                        }

                        if (u in SKIP) return
                        val song = matchPlay(u) ?: return
                        val out = VoiceActions.events(cl, "PlayMusic", mapOf("Track" to song)) ?: return
                        param.result = out
                        Log.w(TAG, "MusicIntent: \"$u\" -> PlayMusic(Track='$song')")
                    } catch (t: Throwable) {
                        Log.e(TAG, "MusicIntent afterHook failed (non-fatal): ${t.message}")
                    }
                }
            })
            Log.w(TAG, "  MusicIntentHook installed on RegexInterpreter.interpret")
        } catch (t: Throwable) {
            Log.e(TAG, "  MusicIntentHook install failed: ${t.message}")
        }
    }

    // "switch/change/set/use ... [music] [source] [to] <name>" -> canonical source.
    private val SOURCE_RE = Regex(
        "^(?:can you |please |hey )?(?:switch|change|set|use|play from|put on|go to)" +
        "(?:\\s+(?:the|my))?(?:\\s+music)?(?:\\s+(?:source|service|app))?" +
        "(?:\\s+(?:to|on|from|over to|back to))?\\s+" +
        "(spotify|youtube music|yt music|ytmusic|youtube|yt|soundcloud|sound cloud)$")

    private fun matchSource(u: String): String? = when (SOURCE_RE.find(u)?.groupValues?.getOrNull(1)?.trim()) {
        "spotify" -> "spotify"
        "youtube music", "yt music", "ytmusic" -> "ytmusic"
        "youtube", "yt" -> "youtube"
        "soundcloud", "sound cloud" -> "soundcloud"
        else -> null
    }

    private fun sourceLabel(s: String): String = when (s) {
        "spotify" -> "Spotify"; "ytmusic" -> "YouTube Music"; "soundcloud" -> "SoundCloud"; else -> "YouTube"
    }

    private fun matchPlaylist(u: String): String? {
        for (re in PLAYLIST) {
            val m = re.find(u) ?: continue
            val name = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    private fun matchArtist(u: String): String? {
        for (re in ARTIST) {
            val m = re.find(u) ?: continue
            val name = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    private fun matchPlay(u: String): String? {
        for (re in PLAY) {
            val m = re.find(u) ?: continue
            val song = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (song.isNotEmpty() && song !in SKIP) return song
        }
        return null
    }

    private fun normalizedUtterance(interpreter: Any, events: Any): String? {
        return try {
            val current = events.javaClass.getMethod("getCurrent").invoke(events) ?: return null
            val m = findMethod(interpreter.javaClass, "normalizeUtteranceFromChatTurn", 1) ?: return null
            m.isAccessible = true
            m.invoke(interpreter, current) as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun findMethod(clazz: Class<*>, name: String, paramCount: Int): Method? {
        var c: Class<*>? = clazz
        while (c != null) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == paramCount }
                ?.let { return it }
            c = c.superclass
        }
        return null
    }
}
