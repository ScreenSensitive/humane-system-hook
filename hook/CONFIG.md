# Hook configuration & voice controls

These hook modules add native voice, music, and announce features to the Ai Pin.
Configuration lives in three JSON files on the device's `/sdcard`, read at runtime
by the hook. They're written by the companion control app (or by hand). No value is
compiled into the APK — credentials/tokens are read from the device at runtime only.

| File | Purpose |
|------|---------|
| `/sdcard/aipin_voice_config.json` | Feature toggles (announce, hands-free, music source/quality) |
| `/sdcard/aipin_spotify.json` | Spotify API credentials + tokens |
| `/sdcard/aipin_playlists.json` | Imported playlists |

All toggles are centralized in [`VoiceConfig.kt`](src/main/kotlin/com/penumbraos/hook/VoiceConfig.kt),
which caches the file with a 2s TTL. A missing file or key falls back to the listed default.

---

## 1. `aipin_voice_config.json` — feature toggles

```json
{
  "announce_caller": false,
  "announce_text":   false,
  "auto_listen":     false,
  "music_source":    "youtube",
  "stream_quality":  2,
  "radio_autoplay":  true
}
```

| Key | Type | Default | Effect | Settable by voice? |
|-----|------|---------|--------|--------------------|
| `announce_caller` | bool | `false` | Speak the caller's name on an incoming call (`DialerHooks`). | App toggle (voice toggle planned) |
| `announce_text` | bool | `false` | Speak incoming text messages aloud (`IronmanHooks` → `NotificationReader`). | App toggle (voice toggle planned) |
| `auto_listen` | bool | `false` | Hands-free follow-ups: re-open the mic after a compose prompt ("Send it?" / "What would you like to say?") so you can answer without tapping (`AutoListenHooks`). | No — app toggle |
| `music_source` | string | `"youtube"` | Search/playback backend: `youtube`, `ytmusic`, `soundcloud`, `spotify`. | **Yes** — see voice commands below |
| `stream_quality` | int 0–2 | `2` | Audio bitrate for NewPipe/PipePipe streams: `0`=low (fastest), `1`=medium, `2`=high. The control app's quality buttons write this. | No — app toggle |
| `radio_autoplay` | bool | `true` | After a requested song ends, continue into a radio of similar tracks. | No — app toggle |

---

## 2. `aipin_spotify.json` — Spotify (API key + auth)

Spotify uses **your own** Spotify Developer app for the Web API (search, device, play,
now-playing). Audio itself is streamed by a bundled **librespot**; the Web API only
controls it. Set `music_source` to `spotify` (by voice or config) to use it.

```json
{
  "client_id":     "<your Spotify app client id>",
  "client_secret": "<your Spotify app client secret>",
  "refresh_token": "<OAuth refresh token>",
  "access_token":  "<filled in / refreshed automatically>",

  "name":    "Ai Pin",
  "bitrate":  320,
  "cache":   "/sdcard/aipin_spotify_cache",
  "binary":  ""
}
```

**Required keys** (Web API auth):

| Key | Notes |
|-----|-------|
| `client_id` / `client_secret` | From your Spotify Developer app |
| `refresh_token` | From a one-time OAuth authorization-code flow (Premium required) |
| `access_token` | Filled/refreshed automatically by the hook |

**Optional librespot keys** (audio engine — [`SpotifyPlayer.kt`](src/main/kotlin/com/penumbraos/hook/SpotifyPlayer.kt); all have defaults):

| Key | Default | Notes |
|-----|---------|-------|
| `name` | `"Ai Pin"` | Spotify Connect device name librespot advertises |
| `bitrate` | `320` | librespot stream bitrate (kbps): 96 / 160 / 320 |
| `cache` | `/sdcard/aipin_spotify_cache` | librespot cache dir (auto-created) |
| `binary` | bundled | Override path to the librespot binary; defaults to the `libspotify.so` shipped inside the hook APK's native lib dir |

**Setup (one-time):**
1. Create an app at the Spotify Developer Dashboard → copy its **Client ID** and **Client Secret**.
2. Complete the OAuth **authorization-code** flow once (the companion control app does this)
   to obtain a **refresh token**. Premium is required for playback control.
3. Write all four keys to `/sdcard/aipin_spotify.json`.

**How auth works at runtime** ([`SpotifyApi.kt`](src/main/kotlin/com/penumbraos/hook/SpotifyApi.kt)):
- Requests use the `access_token` as a Bearer token.
- On a `401`, the hook POSTs to `https://accounts.spotify.com/api/token` with
  `grant_type=refresh_token` (HTTP Basic auth of `client_id:client_secret`), gets a fresh
  `access_token`, **writes it back** to the file, and retries the request once.
- Both the ironman process (`SpotifyControl`) and the music process (`MusicHooks`) read the
  file independently, so token refresh is shared via the file.

---

## 3. `aipin_playlists.json` — playlists

Imported playlists used by `"play <name> playlist"`. Written by the control app.

---

## Voice commands

Spoken commands are recognized natively (regex, no cloud) and intercepted before they reach
the device's dead SYNAPSE/LLM path. Music commands live in
[`MusicIntentHook.kt`](src/main/kotlin/com/penumbraos/hook/MusicIntentHook.kt).

### Switch music source (the one toggle you can set by voice)
> "switch to spotify" · "change source to soundcloud" · "use youtube music" · "go to youtube"

Writes `music_source` on-device and confirms aloud ("Switched music to SoundCloud."). No app needed.

### Play
> "play <song>" · "play songs by <artist>" · "play <name> playlist" · "play music" (top-hits radio)
> "play radio of <song>" · "play more like this" / "play similar"

### Playback controls
> pause · resume / continue · next / skip · previous / go back
> repeat one / repeat all / repeat off · shuffle on / shuffle off

### Messaging & read-back (other modules)
> "text mom hello" / "send message to <contact> saying <body>" (`VoiceComposeHooks`, `ComposePatternsHook`)
> "read messages from <contact>" / "what did <contact> say" · "catch me up" (`VoiceReadHooks`, `NotificationReader`)
> at the confirm prompt: "send" / "yes" to send, "edit" to re-dictate, "cancel" to discard

> **Note:** `announce_caller`, `announce_text`, `auto_listen`, and `stream_quality` are **not**
> voice-settable today — they're toggled through the companion control app (or by editing
> `aipin_voice_config.json`). Only `music_source` can currently be changed by voice.
>
> **Planned:** a voice command to toggle the announcer (`announce_caller` / `announce_text`)
> on/off hands-free is coming in a future update.
