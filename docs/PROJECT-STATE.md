# LoLTube — Project State (read this first in every new session)

One file, kept current, so any future prompt can pick up where we left off.
Repo: https://github.com/skyro777/LoLTube · builds ONLY via GitHub Actions (`.github/workflows/build.yml`, triggers on push to main; APKs land in the rolling `latest` release).

## Current version

- **v0.9.0 (versionCode 9)** — pushed this session. Watch release: `LoLTube-release.apk`.
- Stack: Kotlin 2.0 / Compose / Media3-ExoPlayer 1.4.1, minSdk 28 (Android 9), targetSdk 34.
- Sources: vidfast.vc, vidcore.io (default first), mapple.fun. Catalog: Stremio Cinemeta (keyless).

## User-verified facts (do not regress)

1. **True 4K WORKS on all 3 sources** — root cause of old stalls was turbo prefetch; REMOVED in v0.8. Never re-add parallel prefetch workers.
2. **Playback engine (Deep 4K buffer profile + passive disk cache + 4K lock) is perfect — user said "don't change the buffer engine".** Keep untouched.
3. **Site subtitles are perfectly timed at the sources**; the sites pair each stream copy with a specific SRT. The app's old pipeline used the `/wyzie` *fallback* listing (IMDb-id-matched, not copy-matched) → random far offsets per title. FIXED in v0.9 (see below).
4. Horizontal stretch previously showed video *behind* left/right black bars (transform cropped at the letterbox frame). FIXED in v0.9 (see below).
5. Deep 4K = more RAM, zero buffer: default + README recommends.
6. App cache was reaching 500+MB → v0.8 storage janitor wipes on app close (never during playback).

## v0.9.0 changes (this session)

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
