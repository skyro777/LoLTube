# LoLTube

**LoLTube** is a native Android app (Android 9+) that catalogs IMDb movies & series and plays them through free public streaming sources — with a playback engine built specifically for **smooth, buffer-free 4K** and **full aspect-ratio control** (no more black bars). Works on phones, tablets and **Android TV with the D-pad remote**.

> Personal-use project. LoLTube hosts no content — it only resolves streams from third-party public embed players (vidfast.vc / vidcore.io / mapple.fun) the same way a browser would, then plays the resulting HLS/DASH manifest in its own native player.

## Downloads

Grab the APK from the [**Latest Build** release](../../releases/tag/latest):

- **`LoLTube-release.apk`** — signed release build, best playback performance (this is the one you want)
- `LoLTube-debug.apk` — diagnostics build

Every push to `main` rebuilds it automatically (GitHub Actions).

## What's new in v0.4.0

- **Fixed the v0.3 playback crash** (`ERROR_CODE_FAILED_RUNTIME_CHECK`). Root cause: the auto-subtitle attach restarted the media item ~4 s into playback, cancelling in-flight segment loads — that race inside OkHttp/CacheDataSource threw a runtime exception which Media3 treats as fatal. Subtitles now ship **with the first media item before playback starts** (no restart at all), and the whole data pipeline runs through a new crash-proof `SafeDataSource` that converts any runtime-exception race into a **retriable** error instead of a fatal one.
- **Self-healing player** — if playback still ever errors, the recovery ladder kicks in automatically: re-prepare at position → rebuild in fail-safe mode (direct network, no cache) → switch source. 20 s of clean playback re-arms it. You should never see a dead player screen again.
- **Bigger no-buffer engine (the "disk flywheel")** — cache up to 800 MB (auto-fitted to free disk), **4 parallel prefetch workers** (was 3) keeping **4 minutes** of media warm ahead of the playhead (was 90 s), plus a 3 s heartbeat. The player's RAM buffer is 30–120 s, but the disk keeps filling far beyond it — every segment becomes a local disk read, which is what actually removes 4K rebuffering.
- **16 requests per host** (OkHttp dispatcher + connection pool): the player plus the prefetch workers now stream from the same CDN concurrently instead of queueing behind OkHttp's default 5.
- **Memory-aware buffer budgets** — the byte target now respects the device's memory class (no more silently swapping on low-RAM TVs).
- **Root-cause logging** — playback errors now log the full exception chain + stack frames to the in-app diagnostics log, so any future issue is diagnosable from the copied log.txt.

## What was new in v0.3.0

- Universal aspect-ratio fill on every video (surface-level scaling that codecs can't ignore), self-healing immersive fullscreen (status bar re-hides on resume/focus), subtitle pipeline rework, no-buffer engine v2 (segment disk cache + parallel prefetch). *Withdrawn within hours: playback crashed with ERROR_CODE_FAILED_RUNTIME_CHECK — fixed in v0.4.0 above.*

## Why it plays 4K smoothly (when the websites stutter)

The sites' in-browser players use `hls.js` with MSE, small buffers (~8–40 s / ~24 MB) and aggressive adaptive switching — on a 16–20 Mbps 4K tier that means constant rebuffering on any network dip. LoLTube does it natively:

| Problem on the websites | LoLTube fix |
|---|---|
| Small MSE buffer (12–24 MB ≈ seconds of 4K) | **Media3/ExoPlayer with 30–90 s time-based buffer** (profile configurable down to low-RAM devices) |
| ABR oscillation (constant up/down switching = decoder reconfig stalls) | Hysteresis-heavy adaptive selection (25 s up-switch guard, 0.62 bandwidth fraction) or a hard **4K lock** mode |
| Browser software pipeline, no codec priority | **Hardware-decoder-only** selection with 4K capability check, `SurfaceView` output |
| One HTTP connection at a time | **OkHttp transport**: HTTP/2 multiplexing, connection pooling, keep-alive |
| No recovery when a signed manifest URL expires mid-movie | Auto-detects the failure and **re-resolves + resumes** from the same position |
| Buffering storms | Counts rebuffers; 3 stalls in 60 s while in 4K-lock mode → auto-downgrades to adaptive with a notice |

## Aspect ratio (the missing setting)

- **Auto (default):** measures the video's real aspect ratio (from the decoder, including anamorphic pixel ratio) and your display's ratio. If they differ by more than ~2%, the video is **stretched to fill the screen — zero black bars** (like you asked). 16:9 content on a 16:9 panel stays pixel-perfect.
- **Fit** — letterbox/pillarbox with black bars
- **Stretch** — always fill, ignore ratio
- **Zoom** — fill and crop edges

Switchable live from the player controls (and persisted as the default in Settings).

## TV remote support

- Full D-pad navigation on every screen (browsable cards, focus scaling/highlight)
- Player: **OK/Enter** shows controls, **Left/Right** seek ±15 s (controls hidden) or scrub, **Media Play/Pause** toggles, auto-hiding overlay, big touch targets
- Android TV leanback launcher banner, `uses-leanback` declared (optional feature — still installs on phones)

## How stream resolution works

1. You pick a title (catalog from Stremio's public **Cinemeta** API — keyless, includes IMDb ids + TMDB ids + episode lists).
2. LoLTube loads the provider's embed page (`vidfast.vc/movie/tt…`, `vidcore.io/…`, `mapple.fun/watch/movie/{tmdb}…`) inside a **hidden, muted WebView** with popups and ad-redirects blocked.
3. An injected hook (document-start) watches the page's own `XMLHttpRequest`/`fetch`/media-element traffic and captures the real `.m3u8`/`.mpd`/`.mp4` URLs — the page performs all of its own token/crypto work, nothing is forged.
4. Candidates are verified natively (correct headers, master-playlist detection) and the best one is handed to the native player.
5. Subtitles come from the provider's token-free `wyzie` endpoint (SRT/VTT), auto-selected in your language; Mapple's `/client-stream` fragment proxy is handled transparently by an OkHttp interceptor.

If every provider fails, you can fall back to **Open in WebView** (the site's own player, popups still blocked).

## Features

- Trending/top movies & series, genre browsing, infinite scroll, search (IMDb suggestion engine)
- Details with ratings, cast, awards; season/episode browser with thumbnails
- Continue watching + resume, watched-episode checkmarks, auto-play next episode (with 8 s countdown you can cancel)
- Quality: Auto (up to 4K) / 4K lock / 1080p / 720p caps — switchable live
- Subtitles: multi-language download, timing handled by Media3; audio-track switching for multi-audio sources
- Live playback stats overlay (resolution, bitrate, dropped frames, rebuffers, buffer depth)
- Source priority ordering (Vidfast → Vidcore → Mapple by default), per-session switching with auto-failover
- Diagnostics log screen (Settings → Diagnostics) showing exactly what the resolver captured

## Building

```bash
git clone https://github.com/skyro777/LoLTube
cd LoLTube
./gradlew assembleRelease
```

Requirements: JDK 17, any Android SDK with platform 34. CI signs release APKs with the keystore stored in the `LOLTUBE_KEYSTORE_BASE64` secret — the same key is used for every release, so new builds install over old ones without losing settings/watch progress.

- `minSdk 28` (Android 9) · `targetSdk 34` · Kotlin 2.0 · Jetpack Compose · Media3 1.4.1

## Troubleshooting

- **"No video requests captured"** — the provider's page was slow or blocked. Retry, or switch source in the player's Source menu; check Settings → Diagnostics.
- **Mapple unavailable** — Mapple needs the TMDB id (fetched automatically from Cinemeta) and is pickier about connections; Vidfast/Vidcore are more reliable.
- **Buffering on old devices** — Settings → Buffer profile → *Low memory*, and/or cap quality at 1080p (software decode can't keep up with 4K on very old chips).
- Subtitles don't load — the `wyzie` endpoint sometimes rate-limits; reselect from the Subs menu.
- **Reporting a playback problem** — copy the diagnostics log (Settings → Diagnostics): since v0.4.0 it contains the full error chain and stack frames, which pinpoints the exact cause.

## Legal

For personal experimentation only. The maintainers are not responsible for what third-party providers serve. If you own content that appears via these providers, contact the providers.
