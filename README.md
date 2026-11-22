# LoLTube

**LoLTube** is a native Android app (Android 9+) that catalogs IMDb movies & series and plays them through free public streaming sources — with a playback engine built specifically for **smooth, buffer-free 4K** and **full aspect-ratio control** (no more black bars). Works on phones, tablets and **Android TV with the D-pad remote**.

> Personal-use project. LoLTube hosts no content — it only resolves streams from third-party public embed players (vidfast.vc / vidcore.io / mapple.fun) the same way a browser would, then plays the resulting HLS/DASH manifest in its own native player.

## Downloads

Grab the APK from the [**Latest Build** release](../../releases/tag/latest):

- **`LoLTube-release.apk`** — signed release build, best playback performance (this is the one you want)
- `LoLTube-debug.apk` — diagnostics build

Every push to `main` rebuilds it automatically (GitHub Actions).

## What's new in v0.2.0

- **Android 9 support** — minSdk lowered to 28 so older TVs install it.
- **Fixed silent audio** — the hardware-decoder filter no longer strips software audio decoders (that's what muted playback).
- **True fullscreen** — the player now draws edge-to-edge under a hidden status bar (no more top black strip), extends into display cutouts, and **auto-rotates to landscape** on phones/tablets.
- **Gestures** — swipe up/down on the **left half to change brightness**, on the **right half for volume**, horizontally to scrub; double-tap left/right edges = ±10 s. D-pad up/down = volume on TV.
- **Search fixed** — now powered by the same Cinemeta backend that feeds the home screen (with IMDb suggest as fallback), works on every tab.
- **Episode fix** — resuming a mostly-watched episode restarts it instead of rolling into the next one (S1E1 stays S1E1).
- **Vidcore is the default source** (vidfast's CDN is currently slower); old installs migrate automatically.
- **Stronger anti-buffering** — warm-started ABR (persists your measured bandwidth between sessions), deeper back-buffer (30 s), 45 s read timeout for slow CDN nodes, and automatic 1080p fallback if your line genuinely can't sustain 4K. Optional **Deep 4K buffer profile (45–120 s)** in Settings.
- **Subtitles** — the stream's own embedded subtitle tracks now appear in the Subs menu (perfectly synced), alongside multi-language downloads.
- **Modernized UI** — hero carousel, redesigned details/settings, animated focus, new theme.

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

Requirements: JDK 17, any Android SDK with platform 34. The release signing keystore is committed (`keystore/loltube-release.p12`, password `loltube`) so CI and local builds produce consistently-signed, upgradable APKs. Rotate it if you fork this for public distribution.

- `minSdk 29` (Android 10) · `targetSdk 34` · Kotlin 2.0 · Jetpack Compose · Media3 1.4.1

## Troubleshooting

- **"No video requests captured"** — the provider's page was slow or blocked. Retry, or switch source in the player's Source menu; check Settings → Diagnostics.
- **Mapple unavailable** — Mapple needs the TMDB id (fetched automatically from Cinemeta) and is pickier about connections; Vidfast/Vidcore are more reliable.
- **Buffering on old devices** — Settings → Buffer profile → *Low memory*, and/or cap quality at 1080p (software decode can't keep up with 4K on very old chips).
- Subtitles don't load — the `wyzie` endpoint sometimes rate-limits; reselect from the Subs menu.

## Legal

For personal experimentation only. The maintainers are not responsible for what third-party providers serve. If you own content that appears via these providers, contact the providers.
