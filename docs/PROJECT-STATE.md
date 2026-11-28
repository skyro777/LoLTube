# LoLTube — Project State (read this first in every new session)

One file, kept current, so any future prompt can pick up where we left off.
Repo: https://github.com/skyro777/LoLTube · builds ONLY via GitHub Actions (`.github/workflows/build.yml`, triggers on push to main; APKs land in the rolling `latest` release).

## Current version

- **v0.12.0 (versionCode 12)** — pushed this session ("subtitles that arrive + mapple direct control"). Watch release: `LoLTube-release.apk`.
- Stack: Kotlin 2.0 / Compose / Media3-ExoPlayer 1.4.1, minSdk 28 (Android 9), targetSdk 34.
- Sources: vidfast.vc, vidcore.io, mapple.fun. Catalog: Stremio Cinemeta (keyless).
- **Series routing order: Vidfast → Mapple → Vidcore** (vidcore demoted last for series; movies keep the saved providerOrder, default Vidcore-first).

## User-verified facts (do not regress)

1. **True 4K WORKS on all 3 sources for MOVIES** — root cause of old stalls was turbo prefetch; REMOVED in v0.8. Never re-add parallel prefetch workers.
2. **Playback engine (Deep 4K buffer profile + passive disk cache + 4K lock) is perfect — user said "don't change the buffer engine".** Keep untouched. v0.10–v0.12 fixes are AROUND the engine (watchdog/recovery/routing/subtitles/probing), not inside it.
3. **Site subtitles are perfectly timed at the sources**; v0.10 site-synced capture + site-algorithm pick fixed movies; v0.11 added ASS/SSA parsing; **v0.12 fixed the real series root cause — stale wyzie tokens** (see below).
4. Horizontal stretch previously showed video *behind* left/right black bars. FIXED in v0.9.
5. Deep 4K = more RAM, zero buffer: default + README recommends.
6. App cache was reaching 500+MB → v0.8 storage janitor wipes on app close (never during playback).
7. vidcore + vidfast share the `moon.goldenkite.top` backend for movies; **vidfast SERIES serve from `sun2.warmsquare.top`** (movies work there, series get born-dead 401 "expired" segment grants — the site's own player stalls too; app must rotate providers, not retry).
8. **vidcore has NO stream server-side for many series** (final stream POST 404s — verified live with playwright).
9. **Series subtitles on vidcore/vidfast**: `/wyzie?id={imdb}&season={s}&episode={e}` (live-verified; colon form is wrong). Listings contain DEAD tracks (404s) — fall through to the next track.
10. **Mapple ids are TMDB-only** (`/watch/tv/{tmdbId}-{s}-{e}`); vidfast/vidcore series accept BOTH imdb and tmdb ids in `/tv/{id}/{s}/{e}` — app uses TMDB for series when available.
11. **v0.12 LIVE-PROVEN (the series subtitle root cause):** wyzie token URLs (`{origin}/wyzie/{TOKEN}`) **rotate server-side** — the exact token URL from the user's failure log returned **404 on re-test**, while a FRESH listing's token for the SAME track returned **200** (valid ASS file, 43 KB). Tokens are deterministic within a listing (two listings seconds apart carry the SAME English token) and stay valid ≥ 4.5 min when fresh — but a token captured during harvest can be dead by attach time 75 s later. ALSO: the failing series' listing had **exactly ONE English track** (14 tracks total, all 200-OK when fresh) — the v0.11 "try 3 candidates" fallback had nothing to fall through to. **Fix = never use a stale token**: (1) harvest downloads the site's pick IMMEDIATELY when the listing lands (token seconds old); (2) the page itself fetches the track via evaluateJavascript (page origin/cookies/UA) — both for wyzie-proxy listings AND for sniffer-seen listings (mapple server action → in-page fetch of the English SRT); (3) preAttach re-fetches the listing fresh (new tokens) as the last fallback; download failures now log the HTTP status.
12. **Mapple internals (from the series SingleFile save + bundles)**: saved page was `mapple.fun/watch/tv/118924-1-1` (Marvel Studios Assembled S1E1) — playing source was **s27 (Hermes) — no capability flags, and the 4K button was absent** (no ≥1600p level): catalog 4K flags are HINTS, actual ladder is only knowable per-stream. Rotation = `readSourceOrder(mapple_source_orderv3)` round-robin skipping `window.__allFailedSources` + session fails; startup = hedged 2-source race, first online wins; `mapple_retry_count` ≥ 2 skips first source on load; per-media preferred source `mapple_preferred_sourcev2:{type}:{id}:{slug}` (24 h TTL). Per-source encrypt timeouts: mapple 8s / s10 12s / s2 22s / s28 25s / s25 24s / others 5s. Subtitles via server action `fetchSubtitlesAction({mediaId, mediaType:'tv', season:'1', episode:'1'})` → [{lang,label,url,isHearingImpaired}], auto-selects English by default (`autoSubtitles: true`), files are SRT fetched with plain cross-origin `fetch(url)` (upstream = cineby network, cue domains rewritten to mapple.rip). Session chain: `POST /api/ui/session` → requestToken (page seeds `window.__REQUEST_TOKEN_BOOTSTRAP__` = the session fetch promise, `window.__REQUEST_TOKEN__` after await); `POST /api/media-session {mediaId, mediaType, tv_slug, requestToken}` → streamToken (may require PoW; solver = `/pow-worker.js` worker, ~10s); `POST /api/encrypt {data:{...source}, endpoint:'stream-encrypted', requestToken}` → encrypted URL; `GET url&requestToken=&token=` (credentials include) → {success, data:{stream_url, format}}. `tv_slug` = "{season}-{episode}" as STRINGS. Player integrity checks: must not define `window.pt_raw_injected` / `flutter_inappwebview` (Android WebView is fine).

## v0.12.0 changes (this session — driven by subtitlenotloadlog.txt live experiments + the zip.zip series saves)

1. **Subtitle staleness fix (3 layers, `WebViewHarvester` + `Wyzie` + `PlayerViewModel`):** see fact 11. New: `Wyzie.siteOrdered/pickSiteTrack` (shared site-pick), `Wyzie.freshBestSub` (fresh listing + immediate download, returns (sub, body, mime)); harvester `onListingCaptured` launches the immediate download on IO; `fetchTrackInPage` makes the PAGE fetch the track (wyzie-proxy listings); the JS sniffer's listing branch now ALSO in-page-fetches the preferred track (mapple server-action listings → works before playback even starts); `preAttachSubtitle` ends with the fresh-listing fallback (9 s budget; call-site budgets 4→10 s). Failure logs now include the HTTP status.
2. **Mapple direct source control (the 13 sources, fast + best):** new injected JS probe — piggybacks the page's session tokens (fetch-wrapper capture of `/api/ui/session` + `/api/media-session` request body AND response; boot watcher awaits `__REQUEST_TOKEN_BOOTSTRAP__`; own media-session fallback after ~20 s with PoW via `/pow-worker.js` worker), then probes all 13 sources (4K-flagged first: s25, mapple, s2, s28, s19, s16, s10, then s27, s4, s12, s6, s7, s8) with concurrency 3: encrypt → encrypted URL → `stream_url` → master m3u8 → max RESOLUTION height → `__loltube.onMappleSource(source, height, url)` (offline → -1) + `onMappleProbeDone(count)`. Harvest early-exit adds `probe4k || probeDoneLive` for mapple (a verified-4K source or a complete live table resolves without waiting for play — typical 10–30 s instead of 60–150 s). **`StreamResolver`**: for mapple, probe sources are verified natively best-first (4K-first, then height desc) BEFORE the page-captured candidates; the winner carries `mappleSource`/`mappleSourceHeight` on `ResolvedStream`; log lines `[MappleProbe] probe table: …`, `DIRECT source control: s25 (2160p) verified — using it`. Page rotation captures stay the fallback (old behavior if the probe never fires).
3. **Poster speed:** `LoLTubeApp` implements Coil `ImageLoaderFactory` — `respectCacheHeaders(false)` (metahub ships no cache headers; the default Coil disk cache respected them and re-downloaded ~120 posters every cold start), `crossfade(140)`, memory cache 30%, tuned OkHttp (8s connect / 15s read).
4. **Cinemeta cancellation noise:** `runCatching().onFailure` rethrows `CancellationException` (cancelled searches were ERROR-logged while the user typed — the noise that buried real failures).
5. Buffer engine: untouched, as ordered.

## v0.11.0 changes (this session — driven by log.txt + subtitlenotloadlog.txt + live probing + zip.zip saved pages)

1. **Root cause of the vidcore↔vidfast infinite loop (log.txt, tt3952746 S3E11):**
   - Episode had saved progress > 5 min ⇒ every 401 hit the `autoReResolved` "link expired → reconnect" path → full re-resolve from the TOP of providerOrder (Vidcore first) → vidcore no-stream → vidfast "verifies" (master OK) but 401s at playback (born-dead segment grants on sun2.warmsquare.top) → each successful resolve reset the one-shot flag → infinite loop. Mapple never reached (vidfast always returned a stream to the resolver).
   - **Fixes in PlayerViewModel:** `routingOrder()` (series = saved order minus VIDCORE + VIDCORE last; movies = saved order); `readySinceLastResolve` window — playback errors before first READY count as provider rot in `playbackFailCounts` (2 ⇒ provider marked failed, autoSwitchProvider to next untried); `autoReconnects` capped at 3 (reset only on first READY / episode switch / Retry); `failedProviders` NO LONGER cleared on successful resolve (the loop's second hole) — now only "No video requests captured" hard-failures are added per resolve, cleared on retry()/switchEpisode.
2. **Mapple full-source-scan + control (WebViewHarvester):**
   - Mapple 13 sources from saved page: s25/mapple/s2/s28/s19/s16/s10 4K-capable first, then s27/s4/s12/s6/s7/s8; rotation engine: per-source timeouts (mapple 8s, s10 12s, s2 22s, s28 25s, s25 24s, others 5s), 2-source hedge race, worst-case ≈ 2 min.
   - `harvest()` gains `deadPageBreakMs`/`manifestGraceMs`/`requirePlaying`; resolver passes mapple profile: series 150 s / movies 75 s timeout, 60 s dead-page break, exit only when the page's video is PLAYING (or manifest+25 s quiet).
   - hook seeds `localStorage["mapple_source_orderv3"]` = 4K-first JSON array + removes `mapple_retry_count` (≥2 makes the page skip its first source). Nudges extended (90× for mapple).
   - StreamVerifier.pickBest now prefers the NEWEST capture within a rank (mapple rotation: last manifest = the working source; vidfast: freshest token).
3. **Series quality hunt (mapple probe):** after probeLadder, if series ladder top is 1..1999 (below 4K) and provider ≠ Mapple and not probed this episode → dedicated `resolver.resolve([MAPPLE])` with the long wait; if mapple's ladderTop is strictly higher, the stream (with its subs/siteSubs) REPLACES the current one before the player is built. Status shown in the loader. probeLadder now records `_ladderTop` (StateFlow) — UI reads it.
4. **Honest quality labels (PlayerScreen):** qualityShort shows "Max 720p" when the known ladder top < 2000; qualityMenuLabel annotates every entry with the real ceiling ("this source tops at 720p"). DetailsScreen auto-source label is type-aware ("Auto (Vidfast first)" for series).
5. **ASS/SSA subtitle support:** `Subtitles.parse` detects `[Script Info]`/`Dialogue:` and parses ASS events (H:MM:SS.cc centisecond timestamps, split(",", limit=10) — text field may contain commas, `\N`→newline, `{…}` stripped); `Wyzie.looksLikeSubtitle` + `looksLikeAss` accept ASS; mime `application/x-ssa` / `.ass` ext; harvester `onSiteSubtitle` + fetch-sniffer accept ASS bodies; full URLs in download-failure logs (the old `take(80)` looked like a "-e0" suffix); `preAttachSubtitle` tries up to 3 candidate tracks of the preferred language in order (dead-track fallback).
6. **Providers.embedUrl:** series use `req.tmdbId` when available for vidfast/vidcore (imdb fallback; movies unchanged).

## Known-open / next-prompt ideas

- **Watch the NEW v0.12 log lines when testing series**: `[MappleProbe] probe table: s25=2160p mapple=1080p …`, `DIRECT source control: s25 (2160p) verified — using it`, `source s27 offline/failed`, `[Subs] listing captured (…) — immediately downloading "English" (fresh token)`, `IMMEDIATE subtitle downloaded: …`, `FRESH-LISTING subtitle "English" armed: N cues`, `subtitle download failed (HTTP 404): …` (the status now tells us expired vs challenged vs rate-limited).
- **The mapple probe could be even faster**: currently concurrency 3, sequential encrypt per source. If mapple tolerates it, raising concurrency or racing pairs could cut a full 13-source sweep to <10 s. Only do this if 429s don't appear in the logs.
- The mapple probe could also drive `mapple_preferred_sourcev2:{type}:{id}:{slug}` (24 h TTL) seeding when the SAME episode is re-watched — cache the winner per episode app-side.
- Possible future: show the mapple per-source quality table in the loader status / Source menu UI.
- If vidfast series still 401s on a title where its site also stalls, that CDN genuinely has dead grants — the app rotates to Mapple (now fast) instead of looping; nothing more to fix app-side.
- The vidfast/vidcore pages' own subtitle auto-arm for series didn't fire in the user's log (no captured body) — with v0.12 the app no longer depends on it (3 app-side layers), but if we ever see the page's own download failing at the same instant, check whether the wyzie backend also rate-limits per-token.
- Token validity window: fresh tokens were proven valid ≥4.5 min; the user's stale one died within 75 s (probably an epoch boundary). If logs ever show `subtitle download failed (HTTP 404)` on an IMMEDIATE download, the window shrank — move the download even closer to the listing capture.

## v0.10.0 changes (previous session)

1. **First-open-after-reinstall "never loads" — full root-cause chain:**
   - Fresh install ⇒ DataStore empty ⇒ quality AUTO ⇒ ladder top (1504p @16.2 Mbps) on a cold line, no bandwidth estimate, empty cache ⇒ initial buffering > 20 s.
   - Watchdog stage 0 auto-capped CAP_1080 **and persisted it** (`setQuality` always wrote DataStore) ⇒ mid-prepare track re-selection (`setMaxVideoSize(1920,1088)` cancels in-flight first chunk, re-requests variant playlist on cold connection).
   - Stage 1 "reconnect" was a bare `prepare()` — a NO-OP on an already-preparing player (never cancels hung loader requests; OkHttp `callTimeout(0)`/90 s read ⇒ 60+ s of silence).
   - Stage 2 switched to Vidfast = same goldenkite backend ⇒ nothing changed; post-switch prepare went **fully event-silent** (no BUFFERING/error — watchdog never fired again; user closed the app).
   - Reopen "worked" only because the persisted CAP_1080 made session 2 start at 6.2 Mbps. Reinstall ⇒ AUTO again ⇒ deterministic reproduction.
   - **Fixes:** (a) `setQuality(mode, persist)` — watchdog/rebuffer auto-caps pass `persist=false` (session-only; only user menu picks persist); (b) `reprepareStream` = real re-prepare (`setMediaItem(currentItem, pos) + prepare()`) — kills hung loaders; stage-0 cap also triggers it; (c) **wedged-loader guard**: STATE_IDLE > 10 s while Phase.Ready ⇒ `rebuildAtPosition(null,false)` (automated force-close+reopen); `idleSinceMs` tracked in the listener; (d) auto-switch resolves the REST of the provider order in one pass with `excludeHost` (StreamResolver prefers a different CDN host; falls back to same-host if all share it) + logs same-backend landings; (e) failed switch resets `switchingSource`, re-arms stallStage=2 and adds failed providers to `failedProviders` (autoSwitchProvider skips them) — no more latched-forever switches.
2. **Subtitles — the REAL root cause (v0.9 capture never worked):**
   - log.txt proved: no "captured subtitle body" line ⇒ `siteSubs` empty ⇒ tier-2 armed the listing download ⇒ log line `[Subs] overlay subtitle "English" armed … sync=0ms`.
   - **Capture-killing bug (mine):** `shouldInterceptRequest` intercepted ALL `{host}/wyzie*` — including the `/wyzie/{TOKEN}` subtitle FILE downloads — re-served them as `application/json` ⇒ the JS fetch-sniffer's `looksSub` content-type gate rejected them ⇒ zero captures ever. Fix: intercept ONLY the listing (exact path `/wyzie` + `id` query param); token files pass through the WebView untouched (real cookies + real content-type ⇒ sniffer captures).
   - **Pick bug:** tier-2 took the first language match in RAW order; the sites (verified in their bundles: `586.beauty.js:7761-7782`, `281.beauty.js`) sort the listing **UTF-8 first, non-HI before HI**, then arm the first track of `localStorage["language"]`. With 34 tracks (La La Land) that's a DIFFERENT file ⇒ arbitrary timelines. Fix: `preAttachSubtitle` sorts with the site's comparator + exact-language-first pick; mapple = stable sort over encoding-less listing preserves raw order = mapple's own first-English pick.
   - **mapple listing capture (new):** mapple's listing comes from a Next.js server action (RSC stream, ct `text/x-stream`), never /wyzie. JS hook now forwards listing-shaped bodies (`"url"` + `"lang"|"language"|"label"|"display"`, no `-->`) to `onSiteSubListing` → `parseListingTracks` (tolerant RSC/JSON extractor, maps `{lang,label,url,isHearingImpaired}` → `Wyzie.Sub` + `isHearingImpaired` field added to the model; referer ladder extended with mappletv.uk/mapple.fun).
   - Site-paired arming: `attachSubtitleCues(sub, siteNative)` — site tracks force `_subDelayMs=0` (never inherit per-provider offsets calibrated against the WRONG files); log line **"SITE-PAIRED subtitle … sync=0ms"**. Menu picks: siteNative = url ∈ providerSubs.
   - Also: localStorage seeding retries on every injection (guard no longer stops it); periodic muted `video.play()` nudges (~25×/1.5 s) so the page actually plays and downloads its auto-selected subtitle inside the harvest; sub-grace window 8 s when a listing was captured.
3. **Icon:** legacy full-bleed square `ic_launcher.png` (48/72/96/144/192) generated from the recovered 1227×1224 source (git history); `mipmap-anydpi-v26/ic_launcher.xml` (adaptive round mask) DELETED; manifest icon/roundIcon → the PNGs; `ic_launcher_bg.png` mipmaps kept for the HomeScreen in-app logo.
4. **Home category titles:** `SectionHeader`/`MediaRow`/`PosterCard` title Texts had no color ⇒ Compose default `LocalContentColor = BLACK` on the dark background (home root is a `Column`, not a `Surface`). Fixed with explicit `color = onBackground`.

## Known-open / next-prompt ideas

- Website-vs-desktop-app feasibility question was answered in chat (browser can't hold a locked 4K without MediaSource abstractions + cache quota ≈ 1% of the native disk cache; Chromium desktop wrapper would). No implementation asked yet.
- If first-open still stalls on the user's line, the next lever is the playback OkHttp `callTimeout` (currently 0 = infinite) — NOT touched this round (buffer engine area).

## v0.9.0 changes (previous session)

1. **Site-synced subtitles (the timing fix):**
   - `WebViewHarvester` HOOK_JS now wraps `fetch` responses: any body that parses as SRT/VTT (`-->` ×3, 150B–2MB) from a subtitle-ish content-type/extension is reported via `__loltube.onSiteSubtitle(url, body)` → `SiteSubtitle(url, body, coupled)`; `coupled = !url.contains("/wyzie")`.
   - HOOK_JS seeds `localStorage["language"] = <preferredSubtitleLang>` at document start (vidfast/vidcore auto-select + auto-download that track = the stream-coupled file; mapple auto-selects English via its server action + fetches it).
   - HarvestResult.siteSubs → ResolvedStream.siteSubs → PlayerViewModel `_siteSubs`.
   - Priority: site-synced capture (parse captured body directly, zero network) > `/wyzie` listing > direct Wyzie API. Sync delay forced **0** for site-synced (never inherits old per-provider offsets).
   - Grace window after early-exit extended to 4s waiting for the subtitle capture.
   - Subs menu shows "— Site-synced (matches this stream) —" section; label "Site-synced" / "Site fallback".
2. **Stretch rewrite (`applyAspectTransform` in PlayerScreen.kt):**
   - Letterbox (FIT / AUTO within 0.5% / no manual stretch): `RESIZE_MODE_FIT`, identity transform.
   - ANY covering case (FILL/AUTO/zoom/manual stretch>100): `pv.resizeMode = RESIZE_MODE_FILL` — the AspectRatioFrameLayout = full screen at layout level, compositor stretches the video buffer (the sites' `object-fit: fill` equivalent). Extra scaling (ZOOM deficient-axis `rel`, manual multipliers) crops at SCREEN edges, never behind frame bars.
   - ZOOM = scale deficient axis only (uniform in video space).
3. **Immersive loading** (`Common.kt` → `ImmersiveLoading`): poster Ken-Burns backdrop, ambient amber orbs, LoLTube wordmark, sweep progress bar, step checklist (Loading player → Verifying → Preparing). Used in: PlayerScreen LoadingMeta + Resolving, DetailsScreen loading, WebViewPlayerScreen (until ~900ms post onPageFinished).
4. **`ModernBuffering`**: rotating gradient arc + halo, 400ms delayed fade-in — replaces the plain spinner mid-play.

## Architecture map (quick)

- `MainActivity` → `AppNav` → routes home / details/{imdb}/{type} / player/{...}?s&e&provider&startAt / settings / logs / webview?u.
- Playback: DetailsScreen Play → PlayerViewModel(init: Cinemeta meta → resolve start → startResolve) → `StreamResolver.resolve(req, order, prefSubLang)` → `WebViewHarvester.harvest(embedUrl, prefSubLang)` (offscreen WebView, HOOK_JS at document start; captures manifests via XHR/fetch/src hooks, /wyzie listing via shouldInterceptRequest proxy, site subtitle bodies via fetch-response sniffing) → `StreamVerifier.pickBest` → native ExoPlayer (`PlayerEngine.build` with Deep 4K LoadControl, SafeRenderers/SafeDataSource, PlaybackCache disk LRU, MappleStreamInterceptor + ManifestCapInterceptor on OkHttp).
- Subtitles: app-rendered Compose overlay (`SubtitleOverlay`), cue tick 100ms, `Subtitles.activeCueAt(cues, pos, delay)`, per-provider ±60s delay persisted (`subDelayByProvider`).
- Storage: `StorageJanitor.sweepOnExit` on MainActivity.onDestroy(isFinishing) when `autoCleanOnExit`.
- Settings persisted via DataStore (`Stores.kt`): providerOrder, quality, aspect, bufferProfile=DEEP default, sub lang/size/color/bg, subDelayByProvider, stretchY/X, autoClean, etc.

## Key research facts (from zip.zip SingleFile saves + live captures)

- vidfast/vidcore: same player codebase; decrypted config (JSVMP) contains `url` + `tracks[{file,label}]` per server; auto-select via `localStorage["language"]`; subtitle download is plain `fetch(t.url)`; `/wyzie?id=...` is their fallback (content-generic). HLS manifests have NO subtitle renditions; `hls.subtitleTrack = -1` forced.
- mapple: hls.js 1.6.16; subtitles via Next.js server action `fetchSubtitlesAction` → `[{lang,label,url,...}]`; auto-selects English by default (`autoSubtitles: true`); parser normalizes VTT→SRT, drops ad cues; user offset `timing.offset` default 0.
- All three: SRT/VTT sidecar files, timing = raw cue times + user slider (default 0). Sync comes from the backend pairing the right file with the right copy.
- Sites' video fit: vidfast/vidcore `object-fit: contain`; mapple auto (cover if >2.5:1 else contain).
- Research artifacts in the sandbox at `/home/z/my-project/work/` (zip sites at /tmp/zipsites may be gone between sessions; zip.zip source of truth is `/home/z/my-project/upload/zip.zip`).

## Open ideas / not done

- Windows/desktop port (user asked "app or website for PC?" — answered in chat + README §"App or website": app is the right home for locked 4K; website can't guarantee MSE buffers/codec pinning).
- Optional next: per-title (not per-provider) sync offset for fallback subs; subtitle language picker could offer ALL captured site tracks (currently captures only the auto-selected one).
- The in-player Aspect menu labels ("Stretch" = FILL) — semantics unchanged in v0.9.

## Build & verify loop (every session)

1. Edit code in `/home/z/my-project/work/loltube` (the clone with the PAT remote).
2. Commit + push to `main` → Actions builds APK → rolling `latest` release.
3. Check build status: `curl -s -H "Authorization: Bearer $PAT" https://api.github.com/repos/skyro777/LoLTube/actions/runs?per_page=1` and fetch the run's jobs/logs if it fails.
4. User side-loads `LoLTube-release.apk` (same signing key, installs over previous).
5. Ask user for fresh `log.txt` in repo root when debugging playback (delete after use; don't commit it).
