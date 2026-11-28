# LoLTube

**LoLTube** is a native Android app (Android 9+) that catalogs IMDb movies & series and plays them through free public streaming sources — with a playback engine built specifically for **smooth, buffer-free 4K** and **full aspect-ratio control** (no more black bars). Works on phones, tablets and **Android TV with the D-pad remote**.

> Personal-use project. LoLTube hosts no content — it only resolves streams from third-party public embed players (vidfast.vc / vidcore.io / mapple.fun) the same way a browser would, then plays the resulting HLS/DASH manifest in its own native player.

## Downloads

Grab the APK from the [**Latest Build** release](../../releases/tag/latest):

- **`LoLTube-release.apk`** — signed release build, best playback performance (this is the one you want)
- `LoLTube-debug.apk` — diagnostics build

Every push to `main` rebuilds it automatically (GitHub Actions).

## What's new in v0.12.0 — subtitles that actually arrive, mapple under direct control

v0.11 fixed the switching and the waiting. The user's new subtitle-failure log pointed at one precise thing — and a live experiment on the exact URLs from that log proved it:

- **Series subtitles: the token was stale.** The app captured the `/wyzie` listing during the harvest and downloaded the track at attach time — **75 s later**. Re-testing the exact token URL from the failure log returned **404**, while a **fresh** listing's token for the same track returned **200** with a valid ASS file: those token URLs **rotate server-side**. The fix is three layers, each immune to the staleness:
  1. **Immediate download** — the moment a listing lands (the wyzie proxy or mapple's server action), the site's own pick (UTF-8-first / non-HI / your-language, the sites' algorithm) is downloaded **while the token is seconds old** and ships with the stream as a site-synced body.
  2. **In-page fetch** — the harvester also asks the *page itself* to fetch that track (the page's own origin/cookies/UA — the exact context the site's player uses) and hands the body straight to the app. For mapple's server-action listings this runs *inside* the page, before playback even starts.
  3. **Fresh-listing fallback** — if both miss, the attach path **re-fetches the listing right then** (brand-new tokens) and downloads on the same breath. 4→10 s budget, and the failure log now prints the **HTTP status** (404 expired / 403 challenged / 429 rate-limited — no more guessing).
- **Mapple's 13 sources are now under DIRECT control** (the "check them all, fast" ask). Instead of seeding a localStorage order and waiting for mapple's first-online-wins rotation (60–150 s), the harvester injects a probe into the page that **piggybacks the page's own session tokens** and resolves **every source itself** — `POST /api/encrypt` per source → fetch the master playlist → parse the RESOLUTION ladder — three sources in parallel, 4K-flagged first (s25, mapple, s2, s28, s19, s16, s10, then the rest). The harvest exits as soon as a **4K source is verified** (~10–30 s typical) or the full table lands; the resolver then natively verifies the best source's manifest and **plays the winner** — not whatever the rotation happened to find. Offline sources are reported per-name in the log (`[MappleProbe] source s27 offline/failed`). The probe also solves mapple's Proof-of-Work challenge via the page's own `pow-worker.js` when needed, and everything degrades gracefully to the old rotation-wait if mapple rotates its API shape.
- **Poster load speed.** The catalog poster CDN ships no usable cache headers and Coil's default disk cache respected them — every cold start re-downloaded ~120 posters. The app now ships a custom image loader that caches posters on disk regardless of the headers (second launch renders home instantly), with a bigger memory share for focus-scrolling and a subtle crossfade.
- Cinemeta search cancellation noise is gone from the logs (cancelled searches were logged as errors while you typed).
- The buffer engine, playback pipeline, and everything that made 4K movies play without buffering remain untouched.

## What's new in v0.11.0 — the series rescue

Movies were playing 4K buffer-free, but series were a different animal. The fresh logs (log.txt + subtitlenotloadlog.txt) plus live probing of all three sites pinned three root causes — none of them in the buffer engine (untouched, as ordered):

- **The switching loop (log.txt, S3E11):** Vidcore has **no stream server-side** for many series (its final stream POST 404s — the site itself serves nothing; verified live). Vidfast serves that series from `sun2.warmsquare.top`, which ships a **mix of live and born-dead segment grants** (401 `expired` bodies on first fetch — the site's own player stalls on them too; nothing app-side can fix a dead grant). The loop: the episode had saved progress > 5 min, so every 401 hit the *"stream link expired — reconnect"* path → full re-resolve from the **top** of the provider order (Vidcore first!) → Vidcore fails → Vidfast "verifies" fine (the master does) but 401s in the player → each successful resolve **reset the one-shot flag** → repeat forever. Mapple never got a turn because Vidfast always "verified". **Fixes:** series now route **Vidfast-first** (Vidcore demoted last for series — movies keep the saved order), providers that prove dead for an episode are **remembered** (the router no longer forgets who failed after each resolve), a stream that is rejected by the player twice before any playback marks its provider failed and hands the episode to the next source, the auto-reconnect is **capped** (3 per playback, reset only by real playback/episode change/Retry), and **vidfast/vidcore series embeds now use the TMDB id** (works better than IMDb for series — verified live that both ids return the page, TMDB resolves the stream more reliably).
- **Mapple's 13 sources are now actually waited for and controlled.** The saved page showed the full source list (s25 Poseidon, mapple Zeus, s2 Athena, s28 Astraeus, s27 Hermes, s10 Prometheus, s19 Apollo, s4 Hera, s12 Persephone, s6 Aphrodite, s7 Demeter, s8 Dionysus, s16 Ares — several 4K-confirmed) and its rotation engine (per-source timeouts 5–25 s, worst-case exhaustion ≈ 2 minutes — the app's 45 s harvest gave up mid-rotation, which is "doesn't even wait for mapple"). Now: the mapple harvest runs up to **150 s for series / 75 s for movies** and exits only when the page's video is actually **playing** (the only trustworthy "a source works" signal — a captured manifest alone can be a dead source already rotated away), and the harvester **seeds mapple's own `mapple_source_orderv3` localStorage** with the 4K-confirmed sources first, plus clears `mapple_retry_count` — full control over all 13 sources, quality-first.
- **The quality hunt:** when a series tops out below 4K on the first source (e.g. 720p — read from the actual manifest ladder), the app gives **Mapple one dedicated probe** and switches if its copy is genuinely better (the 1080p copy of a 720p series hides in one of mapple's sources). The loader shows it live: *"Tops at 720p here — checking Mapple for a better copy…"*.
- **The 4K label is honest now.** The quality menu read the manifest ladder's real ceiling: "4K lock — top rendition (this source tops at 720p)", "Auto — adaptive (up to 720p on this source)" — no more "4K" on series that aren't in 4K. The button shows "Max 720p" in that case (the lock still pins the top rendition — the best the source actually serves).
- **Series subtitles — "not available" fixed.** The English subtitle for the failing series is a full **ASS/SSA script** (`[Script Info]` + `Dialogue:` events, zero `-->` lines) — the app's subtitle-shape check rejected the HTTP-200 download as "not a subtitle", so the menu said nothing was available while all three sites listed it. Now: **ASS/SSA is parsed natively** (centisecond timestamps, comma-safe field split, `{\…}` tag stripping, `\N` line breaks), the site-synced capture accepts ASS bodies too, dead tracks (listings contain 404 ones — verified live) **fall through to the next track of the same language**, and the download-failure log prints the **full URL** (the old truncated log masqueraded as a "-e0" suffix and sent an earlier fix hunting in the wrong direction).
- The buffer engine, playback pipeline, and everything that made 4K movies play without buffering is untouched.

## What's new in v0.10.0

- **First play after install fixed (the "never loads" bug).** The fresh log showed the exact chain: fresh install = adaptive quality → the ladder's top variant (1504p @ 16 Mbps) on a cold line → 20 s of buffering → the watchdog auto-capped to 1080p **and wrote it to storage** → the mid-prepare track re-selection re-requested a variant playlist on the still-cold connection → the "reconnect" turned out to be a no-op (`prepare()` on an already-preparing player never cancels a hung loader) → the auto source-switch landed on the **same CDN backend** (vidcore and vidfast both serve from `moon.goldenkite.top`) and the new prepare sat with a wedged loader that stopped emitting events entirely. Force-close + reopen only "fixed" it because the persisted 1080p cap made the second session start at the 6 Mbps variant — which is why reinstall reproduced it every time. Fixes: **auto-caps are session-only now** (your explicit quality choice is the only thing that persists), the watchdog's reconnect **truly restarts the stream** (re-sets the media item — cancels hung requests), a **wedged-loader guard** fully rebuilds the player after 10 s of silence (the automated force-close+reopen, same position/quality), and the auto-switch is **backend-aware** — it prefers a provider on a *different* CDN host instead of hopping vidcore→vidfast on the same one. The buffer engine itself is untouched.
- **Subtitle timing — the real root cause (v0.9's fix didn't hold; you were right).** Your log proved no site subtitle body was ever captured, so the armed "English" was the tier-2 listing download — and it picked the *first English in raw JSON order*, while the sites pick **UTF-8-first / non-SDH-first, then your language** — a *different file*, different release/fps, hence arbitrary timelines on every title. Worse, the capture failed because of my own bug: the `/wyzie` proxy also intercepted the subtitle *file* downloads and re-served them as `application/json`, so the sniffer rejected the page's own subtitle. Now: the proxy only handles the listing, the harvester actually plays the page (muted, nudged) so the site downloads its auto-selected track — captured verbatim; when no body lands, the app downloads the **exact track the site's player would pick** (the site's own sort algorithm, extracted from their bundles) as a *site-paired* subtitle with sync 0 — including **mapple's own server-action listing**, which is now captured too (mapple previously had no site listing at all and fell to the generic index — that's why its timing was also wrong). Look for **"SITE-PAIRED"** in the logs/Subs menu; the ±60 s sliders stay for manual fine-tuning on generic tracks.
- **Square app icon.** The launcher icon is now the full image, edge-to-edge square — no round mask cropping it.
- **Home screen category titles are visible.** They rendered black-on-black (invisible on the dark theme); poster titles under cards had the same bug — both fixed.

### What was new in v0.9.0

- **Subtitle timing FIXED at the root: site-synced subtitles.** The sites pair each stream copy with a specific subtitle file (vidfast/vidcore decrypt the track list inside the page and download the one for your language; mapple auto-selects from its own backend). The old pipeline used the sites' *fallback* `/wyzie` listing — matched to the IMDb id, not the video copy — so a different cut/release could drift by seconds or minutes, differently on every title. Now the hidden harvester WebView **captures the subtitle file the page itself downloads** (your preferred language is seeded into the page first), and playback uses those exact bytes — the timeline is right by construction, no re-download. The `/wyzie` listing and the manual **±60 s ms-precision sync sliders** remain as fallback/fine-tuning. Look for **“Site-synced”** in the player's Subs menu.
- **Horizontal stretch truly fills now.** The old stretch scaled a *letterboxed* surface, which got cropped at the frame's own bounds — so the video stretched but stayed *behind* the left/right black bars. Any covering mode (Auto-fill / Stretch / Zoom / manual stretch) now switches the player frame itself to **full-screen fill** (hardware `object-fit: fill`-equivalent, exactly what the sites do), and extra stretching crops at the *screen* edges — never behind bars. This also fixes “auto doesn't fill some movies”.
- **Immersive loading screens.** Clicking a title now opens a cinematic loading experience — poster backdrop with a slow Ken-Burns zoom, ambient amber glow, the LoLTube wordmark, a sweeping progress bar and a live step checklist (site's player → verifying candidates → preparing playback). The mid-play buffering indicator is now a rotating gradient arc with a soft halo that fades in gently (no flash on quick seeks).
- **WebView fallback player** got the same immersive loading treatment instead of a black screen.

### What was new in v0.8.0

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

- **Auto (default):** measures the video's real aspect ratio (from the decoder, including anamorphic pixel ratio) and your display's ratio. If they differ by more than ~0.5%, the video is **stretched to fill the screen — zero black bars**. 16:9 content on a 16:9 panel stays pixel-perfect. (v0.9: the fill is now enforced at the *frame* level — the video surface is the full screen and the compositor stretches onto it, so it works for every movie, not just some.)
- **Fit** — letterbox/pillarbox with black bars
- **Stretch** — always fill, ignore ratio
- **Zoom** — fill and crop edges (uniform zoom, proportions kept)
- **Manual stretch (v0.8, fixed v0.9):** vertical + horizontal sliders (100–160%). With any stretch above 100% the player fills the screen first, then stretches further — the excess **crops at the screen edges** (that is what “stretch” means; there is never a “behind the black bars” crop). Use vertical when a movie still shows bars in Auto (bars baked into the frame — e.g. a cinemascope film on a 16:9 transfer), horizontal for odd screens/phones.

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
5. **Subtitles:** the same hidden page seeds your preferred language into the site's own selector, so the page downloads the subtitle it would pair with *that exact stream copy* — the harvester captures those bytes and playback uses them directly (**site-synced**, correct timeline by construction). Fallback: the provider's token-free `wyzie` listing (SRT/VTT), and embedded manifest tracks. Mapple's `/client-stream` fragment proxy is handled transparently by an OkHttp interceptor.

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
- **Subtitles out of sync** — v0.9 arms the *site-synced* subtitle (the file the site pairs with the exact stream copy) with sync 0 — if a title's Subs menu shows no "Site-synced" entry and the fallback subtitle drifts, use Settings → Subtitle sync (slider, per source) or the player's Sync button. Values stick per source.
- Subtitles don't load — the `wyzie` endpoint sometimes rate-limits; reselect from the Subs menu.
- **Reporting a playback problem** — copy the diagnostics log (Settings → Diagnostics): it contains the full error chain and stack frames, which pinpoints the exact cause.

## App or website — can a browser do this too?

A browser page **can** play these streams (that's exactly what the sources' own players are — hls.js in a tab), but it **cannot guarantee the app's two core promises**:

- **No locked 4K.** A page has no access to the codec list, decoder selection or `setForceHighestSupportedBitrate` — hls.js ABR decides by its own bandwidth estimate and browser-imposed MSE buffer limits (~12–60 MB total), which is precisely the stutter the sources show in-browser. There is also no way to pin the top rendition from JavaScript reliably.
- **No Deep-4K RAM buffer.** Browsers cap media buffers hard; the app's 45–120 s time-based buffer (clamped to the device's memory class) does not exist in a tab — a page can't buy RAM with an API.
- **No transport control.** No 16-per-host connection tuning, no OkHttp interceptor for Mapple's fragment proxy, no keep-alive pool — a page uses whatever the browser gives it.
- Background tabs get throttled, WebViews get killed, and a phone browser drops to the background codec path the moment you switch apps.

So: **as an app** (Android TV/phone today, the same architecture would map to Windows via a native shell with an embedded MSE-capable player or a LibVLC/MPV core) is the right home for "true locked 4K without buffer". A website makes a fine *companion* catalog, but not the playback engine.

## Legal

For personal experimentation only. The maintainers are not responsible for what third-party providers serve. If you own content that appears via these providers, contact the providers.
