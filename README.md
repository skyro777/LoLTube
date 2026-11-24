# LoLTube

**LoLTube** is a native Android app (Android 9+) that catalogs IMDb movies & series and plays them through free public streaming sources — with a playback engine built specifically for **smooth, buffer-free 4K** and **full aspect-ratio control** (no more black bars). Works on phones, tablets and **Android TV with the D-pad remote**.

> Personal-use project. LoLTube hosts no content — it only resolves streams from third-party public embed players (vidfast.vc / vidcore.io / mapple.fun) the same way a browser would, then plays the resulting HLS/DASH manifest in its own native player.

## Downloads

Grab the APK from the [**Latest Build** release](../../releases/tag/latest):

- **`LoLTube-release.apk`** — signed release build, best playback performance (this is the one you want)
- `LoLTube-debug.apk` — diagnostics build

Every push to `main` rebuilds it automatically (GitHub Actions).

## What's new in v0.8.0

- **True 4K confirmed working on all three sources** (v0.7's real 4K lock: the top rendition is *pinned* the moment you switch the setting — no silent 1080p anymore).
- **Turbo prefetch REMOVED — it was the buffering.** Long testing on a 25–30 Mbps line showed the parallel 4-worker read-ahead engine caused the stalls on **all three sources** (extra connections + concurrent cache writes fighting the player's own loader). With it gone, vidcore/vidfast/mapple all play 4K without buffering. The passive disk cache + Media3 RAM buffer remain — that combination is what actually sustains 4K.
- **Deep 4K is now the default (and recommended).** It simply spends more RAM to keep 45–120 s of 4K buffered, so nothing ever stalls. On 2 GB+ RAM phones/TVs it costs nothing you'd miss; use *Balanced* only on genuinely low-RAM devices. See [Buffer profiles](#buffer-profiles-deep-4k-recommended).
- **Storage management (the 500 MB cache fix).** LoLTube now **erases the video cache, subtitle files, images and WebView data when the app closes** — never while playing (the cache is the engine while you watch). Also a Settings → Storage card: live cache size, *Clear caches now*, and an auto-clean toggle. Fresh WebView state each session is also what fixes vidcore/vidfast "stopped loading until I cleared data".
- **Manual stretch.** Some movies have black bars **burned into the picture** (16:9 frame with cinemascope inside — no player can auto-detect that, which is why Auto-fill looked broken on those). New vertical + horizontal stretch sliders (100–160%) work on top of any aspect mode: Settings → Playback → Manual stretch, or the **Stretch** button right in the player (±5% steps, instant). Auto-fill also got tighter (0.5% threshold).
- **Subtitle sync: ±60 seconds, millisecond precision.** Each source's copy is trimmed differently and drift can reach a minute — the old ±250 ms stepper is replaced by a **drag slider (−60 s … +60 s)** with fine buttons (±0.1 s / ±1 s / ±5 s) in Settings → Subtitle sync, per source. The in-player Sync menu now offers ±0.1 s … ±5 s steps too. All values persist per source.
- **Richer home screen, auto-updating.** Top Movies/Series + **Top Rated** (IMDb-rating catalog) + **New Releases** (current-year catalog) rows, all fetched in parallel; rows and Continue Watching refresh every time you come back to the app.
- **New app icon** — the custom logo is now the launcher icon (adaptive, full-bleed under any launcher mask) and the TV banner.

### What was new in v0.7.0

- **Real 4K lock** (`setForceHighestSupportedBitrate`): the top rendition is pinned regardless of ABR bandwidth estimates — 4K starts on the first segment and switches instantly mid-playback. This fixed the "looks crunched / deep-4K and balanced look identical" issue (the old code only raised a *ceiling* and the 16 Mbps 2160p variant never won on a 25–30 Mbps line).
- **Site-style subtitle pipeline**: sidecar SRT/VTT parsed in-app, rendered by LoLTube's own overlay (video untouched) with size/color/background settings, per-source sync delays, subtitles that stay on across source switches.
- Self-healing, crash-proof playback stack (SafeDataSource/SafeRenderers, codec-aware recovery ladder), in-app log viewer with Copy/Share.

<details><summary>Older changelog (v0.3–v0.6)</summary>

- **v0.6.0** — fixed the `ERROR_CODE_FAILED_RUNTIME_CHECK` root cause: `file://` subtitle sidecars fed to OkHttpDataSource (`HttpUrl.parse` → "Malformed URL" → fatal). Scheme-routed `DefaultDataSource` in front of the cache pipeline.
- **v0.5.0** — crash-safe renderer layer (codec-surface switch bug patched at renderer level), honest 4K pre-selection from the harvested master playlist vs the device's codecs, codec-aware self-healing ladder, copyable diagnostics, site-native subtitle priority, source-failure tracking.
- **v0.4.0** — crash-proof `SafeDataSource`, subtitles shipped with the first MediaItem (no restart race), memory-aware buffer budgets, root-cause logging.
- **v0.3.0** — aspect fill + no-buffer engine v2 (withdrawn: the RUNTIME_CHECK crash, fixed in v0.4).

</details>

## Buffer profiles (Deep 4K recommended)

Buffer profiles never change **what you see** — with the 4K lock the visual quality is identical in every profile. They change **how much is buffered ahead** so playback never stalls:

| Profile | RAM buffer | Best for |
|---|---|---|
| **Deep 4K — recommended (default)** | 45–120 s | 2 GB+ RAM phones/TVs. 4K (16–25 Mbps) rides through network dips without a single stall. Costs more RAM — on a 2 GB device Android may kill background apps sooner, but playback itself is smoother than anything shallower. |
| Smooth 4K | 30–90 s | ~3–4 GB RAM devices where you want headroom for other apps. |
| Balanced | 20–60 s | Low-RAM devices / older TV boxes. |
| Low memory | 10–30 s | Very constrained devices; 4K may rebuffer on dips. |

**Why RAM matters here:** a time-based buffer at 4K bitrate is physically 180–450 MB of in-flight media. Deep 4K's budget is clamped to half the device's memory class, so it asks for exactly as much as the device can actually give.

## Why it plays 4K smoothly (when the websites stutter)

The sites' in-browser players use `hls.js` with MSE, small buffers (~8–40 s / ~24 MB) and aggressive adaptive switching — on a 16–20 Mbps 4K tier that means constant rebuffering on any network dip. LoLTube does it natively:

| Problem on the websites | LoLTube fix |
|---|---|
| Small MSE buffer (12–24 MB ≈ seconds of 4K) | **Media3/ExoPlayer time-based buffers** — 45–120 s on Deep 4K (configurable per profile) |
| ABR oscillation (constant up/down switching = decoder reconfig stalls) | Hysteresis-heavy adaptive selection, or the hard **4K lock** that pins the top rendition |
| Browser software pipeline, no codec priority | **Hardware-decoder selection** with per-variant 4K capability checks, `SurfaceView` output |
| One HTTP connection at a time | **OkHttp transport**: HTTP/2 multiplexing, connection pooling, keep-alive |
| No recovery when a signed manifest URL expires mid-movie | Auto-detects the failure and **re-resolves + resumes** from the same position |
| Buffering storms | Counts rebuffers; stalls in 60 s while adaptive → auto-downgrades with a notice (the 4K lock holds quality and reconnects instead) |
| Segments re-fetched after seeks | **Disk cache (up to 800 MB, LRU)** the player's own reads flow through — seek-backs are instant disk reads |

> History note: v0.4–v0.7 also ran a 4-worker "turbo prefetch" that downloaded ahead of the playhead into that disk cache. Long-session testing proved it counter-productive on all three sources (it fights the player's own loader), so v0.8 removed it — playback only got smoother.

## Aspect ratio (the missing setting)

- **Auto (default):** measures the video's real aspect ratio (from the decoder, including anamorphic pixel ratio) and your display's ratio. If they differ by more than ~0.5%, the video is **stretched to fill the screen — zero black bars**. 16:9 content on a 16:9 panel stays pixel-perfect.
- **Fit** — letterbox/pillarbox with black bars
- **Stretch** — always fill, ignore ratio
- **Zoom** — fill and crop edges
- **Manual stretch (v0.8):** vertical + horizontal sliders (100–160%) applied on top of any mode. Use vertical when a movie still shows bars in Auto (the bars are baked into the frame — e.g. a cinemascope film on a 16:9 transfer), horizontal for odd screens/phones.

Switchable live from the player controls (aspect + stretch buttons, persisted as defaults in Settings).

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

- Popular + Top Rated + New Releases movies & series (six catalog feeds, fetched in parallel, refreshed on every app resume), genre browsing, infinite scroll, search (Cinemeta + IMDb suggestion engine)
- Details with ratings, cast, awards; season/episode browser with thumbnails
- Continue watching + resume, watched-episode checkmarks, auto-play next episode (with 8 s countdown you can cancel)
- Quality: Auto (up to 4K) / **4K lock** / 1080p / 720p caps — switchable live
- Subtitles: site-native track listing, multi-language download, **app-rendered overlay with size/color/background settings and ±60 s per-source sync (ms precision)**; audio-track switching for multi-audio sources
- Aspect control incl. **manual V/H stretch**; live playback stats overlay (resolution, bitrate, dropped frames, rebuffers, buffer depth)
- **Storage management**: auto-clean on app close (toggleable), manual "clear caches" with live size display
- Source priority ordering (Vidcore → Vidfast → Mapple by default), per-session switching with auto-failover
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
- **Buffering on old devices** — Settings → Buffer profile → *Balanced*/*Low memory*, and/or cap quality at 1080p (software decode can't keep up with 4K on very old chips).
- **A source stopped loading** — v0.8's auto-clean resets the WebView profile on every app close; if it happens mid-session, Settings → Storage → *Clear caches now*, then retry.
- **Subtitles out of sync** — Settings → Subtitle sync (slider, per source), or the player's Sync button (±0.1–5 s steps). Values stick per source.
- Subtitles don't load — the `wyzie` endpoint sometimes rate-limits; reselect from the Subs menu.
- **Reporting a playback problem** — copy the diagnostics log (Settings → Diagnostics): it contains the full error chain and stack frames, which pinpoints the exact cause.

## Legal

For personal experimentation only. The maintainers are not responsible for what third-party providers serve. If you own content that appears via these providers, contact the providers.
