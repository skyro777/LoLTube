# LoLTube Research — Catalog APIs + Media3 4K Playback (Task 2-c)

Date: 2026-11-22 · Environment: Linux sandbox, curl with Chrome UA. All JSON below is **real output captured live** unless marked "documented shape".

---

## PART A — Catalog API (IMDb/TMDB content, movies + TV)

### Requirements recap
Trending/popular browse (movies + TV), search, details (genres, rating, runtime, poster, year), TV season/episode lists, and both **IMDb id** and **TMDB id** per title (player providers use both).

### Candidates tested (2026-11-22)

| API | Key? | Status (live test) | Verdict |
|---|---|---|---|
| **TMDB v3** (api.themoviedb.org) | Free key (signup) | Reachable; `401` without key — expected. All endpoint docs verified 200 | ✅ **PRIMARY** |
| **TVmaze** (api.tvmaze.com) | Keyless | ✅ Fully working, real JSON below | ✅ **FALLBACK (TV)** |
| imdbapi.dev | Keyless | ❌ **DEAD** — DNS NXDOMAIN (`api.imdbapi.dev` does not resolve; root domain also unreachable). Not usable | ❌ |
| search.imdbot.workers.dev | Keyless | ❌ Broken upstream — every query returns `{"ok":false,"error_code":103}` (their TMDB/scrape backend failing) | ❌ |
| Trakt (api.trakt.tv) | client_id (app registration) | `403` without client id; requires creating an app at trakt.tv/oauth/applications. No browsing without key; ids via `ids.imdb`/`ids.tmdb` are good once registered | Possible 3rd option, extra friction |
| Simkl (api.simkl.com) | client_id (app registration) | Reachable (302 on root); registration required | Possible 3rd option |
| OMDB | Free key (signup form) | Working but `No API key provided` without key; no trending endpoint, no multi-type browse | ❌ secondary only |
| EpisoDate, iTunes search | keyless | Returned empty/0 results from this network (blocked) | ❌ |

### 🥇 PRIMARY: TMDB v3 (`https://api.themoviedb.org/3`)

**Why:** the only single API that covers *everything* LoLTube needs: trending movies+TV in one call, multi-search, full details, season/episode lists, posters, and id cross-walk both ways (IMDb ⇄ TMDB).

**Key acquisition (free, ~3 min):**
1. Sign up: https://www.themoviedb.org/signup (email or Google account — page verified reachable).
2. Settings → API → "Request an API Key" → **Developer** type → accept terms → short form (app name, URL can be a placeholder, personal use is accepted).
3. You immediately get: a **v3 api_key** (32 hex chars, query param `?api_key=`) and a **v4 Read Access Token** (JWT, `Authorization: Bearer` header).
4. Docs: https://developers.themoviedb.org/ (v3 reference) and https://developer.themoviedb.org/docs/getting-started.
5. Rate limits: ~50 req/s (docs: https://developer.themoviedb.org/docs/rate-limiting) — generous for a catalog app. Images served from `image.tmdb.org` are unmeteried CDN.

**Key endpoints for LoLTube** (doc URLs all verified live HTTP 200):

| App feature | Endpoint | Doc |
|---|---|---|
| Trending (movies+TV mixed) | `GET /3/trending/all/day?api_key=K&language=en-US` | developers.themoviedb.org/3/trending/get-trending |
| Popular movies / TV | `GET /3/movie/popular`, `GET /3/tv/popular` (+ `/top_rated`, `/upcoming`) | /3/movies/get-popular-movies |
| Search (multi) | `GET /3/search/multi?query=matrix` | /3/search/multi-search |
| Movie details | `GET /3/movie/{id}` → genres, runtime, vote_average, poster_path, release_date | /3/movies/get-movie-details |
| TV details | `GET /3/tv/{id}` → episode_run_time, number_of_seasons | /3/tv/get-tv-details |
| Season/episodes | `GET /3/tv/{id}/season/{n}` → episodes[] with name, overview, still_path, runtime, air_date, vote_average | /3/tv-seasons/get-tv-season-details |
| IMDb id for a TMDB title | `GET /3/movie/{id}/external_ids`, `GET /3/tv/{id}/external_ids` → `imdb_id` | /3/movies/get-movie-external-ids |
| TMDB id from IMDb id | `GET /3/find/tt0111161?external_source=imdb_id` → movie_result/tv_result | /3/find/find-by-id |

**Documented response shapes** (from TMDB docs — field names are stable; verify with your own key after signup):
```jsonc
// GET /3/trending/all/day
{ "page":1, "results":[
  { "id": 603692, "media_type":"movie",            // "tv" or "movie" per item
    "title":"John Wick: Chapter 4",                // tv uses "name"
    "poster_path":"/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg",
    "vote_average":7.7, "release_date":"2023-03-22",
    "genre_ids":[28,53], "overview":"..." }],
  "total_pages":1000 }

// GET /3/tv/1399/season/1  (Game of Thrones)
{ "id":3624, "season_number":1, "episodes":[
  { "episode_number":1, "name":"Winter Is Coming", "air_date":"2011-04-17",
    "runtime":62, "still_path":"/9pG9zAWXqBepR3sSgf3prh3sEFL.jpg",
    "overview":"...", "vote_average":8.1 } ] }

// GET /3/tv/1399/external_ids
{ "imdb_id":"tt0944947", "tvdb_id":121361, "id":1399 }
```
Poster URL builder: `https://image.tmdb.org/t/p/w500{poster_path}` (sizes: w92,w154,w185,w342,w500,w780,original).

**Note:** I could not complete signup from this sandbox (requires email verification), so no live TMDB JSON could be captured; leaked/public keys found via code search all return `401 Invalid API key` (TMDB revokes keys committed to public repos — do not hardcode yours either; ship it via BuildConfig/local.properties).

### 🥈 FALLBACK: TVmaze (`https://api.tvmaze.com`) — keyless, TV-only

Fully working **without any key** (only a UA header is good practice). Rate limit: 20 calls / 10 seconds per IP (documented at https://www.tvmaze.com/api). Covers TV shows with IMDb externals, seasons, episodes, ratings, artwork. No movies, no TMDB ids, and no single trending feed (use `/schedule` (today's airing) or `/shows?page=N`).

**Live captured JSON:**

```json
// GET /search/shows?q=matrix  (top hit, abbreviated)
{"score":0.88,"show":{"id":3616,"name":"Matrix","type":"Scripted","language":"English",
 "genres":["Drama"],"status":"Ended","runtime":60,"premiered":"1993-03-01",
 "rating":{"average":null},
 "externals":{"tvrage":4393,"thetvdb":76258,"imdb":"tt0106062"},
 "image":{"medium":"https://static.tvmaze.com/uploads/images/medium_portrait/302/755425.jpg",
          "original":"https://static.tvmaze.com/uploads/images/original_untouched/302/755425.jpg"},
 "summary":"<p>Steven Matrix is a freelance hitman...</p>"}}

// GET /shows/82  (Game of Thrones) — fields kept
{ "id": 82, "name": "Game of Thrones", "type": "Scripted",
  "genres": ["Drama","Adventure","Fantasy"], "status": "Ended",
  "averageRuntime": 61, "premiered": "2011-04-17",
  "rating": {"average": 8.9},
  "externals": {"tvrage": 24493, "thetvdb": 121361, "imdb": "tt0944947"},
  "image": "https://static.tvmaze.com/uploads/images/original_untouched/498/1245274.jpg" }

// GET /shows/82/seasons  (first 2 of 8)
[ { "id": 307, "number": 1, "episodeOrder": 10,
    "image": {"medium":".../medium_portrait/24/60659.jpg",
              "original":".../original_untouched/24/60659.jpg"} },
  { "id": 308, "number": 2, "episodeOrder": 10, "image": {...} } ]

// GET /shows/82/episodes  (S1E1 of 73 total)
{ "id": 4952, "season": 1, "number": 1, "name": "Winter is Coming",
  "airdate": "2011-04-17", "runtime": 60,
  "rating": {"average": 8.2},
  "image": {"medium":".../medium_landscape/478/1195111.jpg",
            "original":".../original_untouched/478/1195111.jpg"} }
```
Useful extras: `/singlesearch/shows?q=...&embed=episodes` (show+all episodes in 1 call), `/lookup/shows?imdb=tt0944947` (IMDb→TVmaze), `/schedule/full` (today's TV = a decent "trending TV" proxy).

### Recommendation
- **Primary: TMDB v3** — one free key covers movies+TV trending, search, details, seasons/episodes, posters/ratings, and IMDb⇄TMDB id mapping (`/find`, `/external_ids`). Store the key in `local.properties` → `BuildConfig`.
- **Fallback: TVmaze** — zero-key TV catalog with IMDb ids (pair with OMDB free key if movie metadata is needed offline from TMDB). Use TVmaze when TMDB key/rate-limit fails or for episode-airdate enrichment.
- Dead ends confirmed today: `api.imdbapi.dev` (NXDOMAIN), `search.imdbot.workers.dev` (error 103 upstream failure).

---

## PART B — Media3/ExoPlayer: 4K-smooth playback, aspect ratio, TV remote

**Version baseline (verified from Google Maven, 2026-11-22):** latest stable **Media3 `1.11.1`** (`androidx.media3:media3-*`), `1.12.0-beta01` in preview. All code below targets Media3 1.11.x.

### B1. Buffer configuration for high-bitrate 4K HLS

Default values in `DefaultLoadControl` (verified from `androidx/media` `release` branch source, `DefaultLoadControl.java`):

| Constant | Value | Meaning |
|---|---|---|
| `DEFAULT_MIN_BUFFER_MS` | **50 000** | buffer floor once filling (player starts buffering below this) |
| `DEFAULT_MAX_BUFFER_MS` | **50 000** | buffer ceiling — player stops prefetching at this |
| `DEFAULT_BUFFER_FOR_PLAYBACK_MS` | **1 000** | buffer needed to *start* playback after initial load |
| `DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS` | **2 000** | buffer needed to resume after explicit rebuffer event |
| `DEFAULT_BACK_BUFFER_DURATION_MS` | **0** | media kept behind playhead for backward seeks |
| `DEFAULT_TARGET_BUFFER_BYTES` | `C.LENGTH_UNSET` | auto: per-stream-type sizes (video buffer = `2000 * 64KB` ≈ **128 MB** remote / much smaller for local) |
| `DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS` / `MAX` | 1 000 / 50 000 | used when `isLocalFile()` (newer Media3 auto-tunes local playback) |

Docs: https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl · buffering guide: https://developer.android.com/media/media3/exoplayer/buffers

**Why 4K stutters with defaults:** (a) 1 s `bufferForPlaybackMs` means playback starts almost immediately on a network that may sustain only ~2–4 s of lookahead while ABR picks the wrong (too-high) rendition and immediately rebuffers; (b) a 50 s cap is generous, but the *initial* window is tiny for 4K: at 25–45 Mbps a 2 s HLS segment is 6–11 MB, and one slow segment fetch (TLS reconnect, radio wake-up) drains the 1 s runway instantly; (c) ABR oscillation: every down-switch then up-switch forces a decoder reconfigure on HEVC 4K (and on many SoCs a codec restart), which shows as a 100–500 ms stall. Related real issues: androidx/media#385 "Stutter Playback on Livestream DRM" (https://github.com/androidx/media/issues/385), androidx/media#2972 "Video freezes, audio continues on Google TV Streamer" (https://github.com/androidx/media/issues/2972).

**Recommended 4K-tuned LoadControl** (use when content is remote HLS/DASH ≥ 1080p):

```kotlin
@OptIn(UnstableApi::class)
fun build4KLoadControl(): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs        = */ 60_000,   // refill floor: ≥ ~30 4K segments
            /* maxBufferMs        = */ 120_000,  // 2 min runway for 4K bursts
            /* bufferForPlaybackMs = */ 5_000,   // don't start until 5 s of 4K is safe
            /* bufferForPlaybackAfterRebufferMs = */ 10_000) // be extra careful after a stall
        .setBackBuffer(30_000, /* retainBackBufferFromKeyframe = */ true) // instant rewind
        .setPrioritizeTimeOverSizeThresholds(true)  // fill 120 s even if >128MB byte cap
        .build()
// Constraint enforced by builder (checkArgument): minBufferMs >= max(bufferForPlaybackMs,
// bufferForPlaybackAfterRebufferMs), and maxBufferMs >= minBufferMs. Values above satisfy it.
```

When to use which: keep `DefaultLoadControl.DEFAULT_*` for SD/1080p mobile (they start fast, 1 s is fine at 5 Mbps, and 128 MB byte cap is rarely hit). Use custom values for 4K HDR: raise `bufferForPlayback*` (start-safety) more than `maxBufferMs` (memory) — 120 s of 45 Mbps video ≈ 675 MB buffered if not size-capped, so `setPrioritizeTimeOverSizeThresholds(false)` (default) keeps memory bounded by the 128 MB video allocator; pick `true` only on TV/fixed-memory-friendly devices (2 GB+ free) where runway matters more than RAM.

### B2. ABR strategy for 4K (avoid quality oscillation)

Docs: https://developer.android.com/media/media3/exoplayer/track-selection · https://developer.android.com/reference/androidx/media3/common/TrackSelectionParameters · `AdaptiveTrackSelection` reference: https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection

Verified defaults in `AdaptiveTrackSelection` source:
- `DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10_000` — must have 10 s of buffer before switching **up** (the anti-oscillation guard)
- `DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25_000` — allow down-switch if buffer below this
- `DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25_000`
- `DEFAULT_BANDWIDTH_FRACTION = 0.7f` — only 70% of estimated bandwidth is assumed usable

**Option A — force max quality (when user picks 4K, e.g. on Wi-Fi/TV):**

```kotlin
player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
    .setMaxVideoSize(3840, 2160)          // cap by screen size (or Int.MAX_VALUE to allow all)
    .setMaxVideoBitrate(if (userWants4K) 60_000_000 else 8_000_000)
    .setMinVideoWidth(0).setMinVideoHeight(0)
    .setForceHighestSupportedBitrate(true) // ignore ABR: always pick top rendition ≤ caps
    // .setVideoViewportFraction(1f)       // viewport-based capping (default accounts for it)
    .build()
```
`setForceHighestSupportedBitrate(true)` disables bandwidth-based adaptation entirely (still respects `maxVideoBitrate`/`maxVideoSize` and decoder support). For "4K only on Wi-Fi": combine with `player.trackSelectionParameters` updates from a `ConnectivityManager` NetworkCallback.

**Option B — tune `AdaptiveTrackSelection` to stop oscillation (keep adaptive):**

```kotlin
@OptIn(UnstableApi::class)
val trackSelector = DefaultTrackSelector(context).apply {
    parameters = buildUponParameters()
        .setMaxVideoSize(3840, 2160)   // never pick bigger than the panel
        .build()
}
// Hysteresis: require 25 s of buffered 4K before stepping UP (default 10 s is too eager at 4K),
// but drop fast (10 s) to avoid a hard rebuffer.
val adaptiveFactory = AdaptiveTrackSelection.Factory(
    /* minDurationForQualityIncreaseMs = */ 25_000,   // up-switch guard (anti-oscillation)
    /* maxDurationForQualityDecreaseMs = */ 10_000,   // down-switch earlier to protect buffer
    /* minDurationToRetainAfterDiscardMs = */ 25_000,
    /* bandwidthFraction = */ 0.6f                    // more conservative estimate at 4K
)
val player = ExoPlayer.Builder(context)
    .setTrackSelector(DefaultTrackSelector(context, adaptiveFactory))
    .setLoadControl(build4KLoadControl())
    .build()
```
Rule of thumb: at 4K, up-switch cost (decoder reconfigure + 6–11 MB segment) is high, so *lengthen* `minDurationForQualityIncreaseMs` (25–30 s) and *lower* `bandwidthFraction` (0.6) — you trade a slower ramp to 4K for near-zero mid-playback stalls. A cap by screen (`setMaxVideoSize`) also prevents the classic "play 2160p on a 1080p phone" battery heater.

### B3. Decoder selection: hardware HEVC/AVC, capability check for 3840×2160

Docs: https://developer.android.com/reference/androidx/media3/exoplayer/mediacodec/MediaCodecSelector · https://developer.android.com/reference/androidx/media3/exoplayer/mediacodec/MediaCodecInfo

Verified from source: `MediaCodecSelector.DEFAULT = MediaCodecUtil::getDecoderInfos` — returns decoders **in priority order, hardware first** (list already prefers hw); `PREFER_SOFTWARE` sorts software first. `MediaCodecInfo` exposes `hardwareAccelerated`, `software`, `capabilities`, `isSeamlessAdaptationSupported(Format)`; capability probing is `MediaCodecInfo.isSizeAndRateSupported(...)` backed by `MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported(w,h,fps)`.

Media3 automatically falls back down the list if the preferred decoder fails to initialize, and **automatically filters out decoders that don't support the format's size/rate via `isSizeAndRateSupported`** (that check happens inside `MediaCodecUtil.getDecoderInfos` before selection). If you want to forbid software fallback for 4K (software HEVC 4K = slideshow):

```kotlin
@OptIn(UnstableApi::class)
val hwOnlyCodecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        .filter { it.hardwareAccelerated }   // drop c2.android.avc.decoder / c2.android.hevc.decoder
}

// Pre-flight capability check (e.g. decide whether to offer the "4K" quality toggle at all):
@OptIn(UnstableApi::class)
fun supports4KHevc(context: Context, width: Int = 3840, height: Int = 2160, fps: Double = 30.0): Boolean = runCatching {
    MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, /* secure= */ false, /* tunneling= */ false)
        .any { info ->
            info.hardwareAccelerated &&
                (info.capabilities?.videoCapabilities?.areSizeAndRateSupported(width, height, fps) ?: false)
        }
}.getOrDefault(false)

val player = ExoPlayer.Builder(context)
    .setMediaCodecSelector(hwOnlyCodecSelector)
    .setLoadControl(build4KLoadControl())
    .setPriority(C.PRIORITY_PLAYBACK)   // top Media3 priority tier; use for foreground player
    .setVideoChangeFrameRateStrategy(
        VideoChangeFrameRateStrategy.CHANGE_FRAME_RATE_ONLY_IF_CTS_SUPPORTED) // match display to 24/50/60fps
    .build()
```
Notes: `setPriority(C.PRIORITY_PLAYBACK)` is the highest tier in `C` (see `C.java`: `PRIORITY_PLAYBACK` > `PRIORITY_PROCESSING_FOREGROUND` > `PRIORITY_PLAYBACK_PRELOAD` > ...); it matters when other Media3 components (preloading, downloading) compete. On failure of hw init with a filtered selector, playback errors with `PlaybackException ERROR_CODE_DECODER_INIT_FAILED` — surface a friendly "this device can't hardware-decode this stream" message instead of freezing.

### B4. Surface choice: SurfaceView vs TextureView for 4K/HDR

Docs: https://developer.android.com/media/media3/ui/surfaces

- **`SurfaceView` (default)**: composited directly by the hardware composer / SurfaceFlinger; zero extra GPU copy; full **HDR** (Dolby Vision/HDR10) tone-mapping path; supports secure (DRM) rendering; lower power. **Use this for 4K.** Known pitfall class: some cheap TV SoCs render 0–3 fps when the codec is configured directly onto a SurfaceView in certain orders (real example: androidx/media#3525, MStar MT5889 TVs, fixed by placeholder-first config inside Media3 — keep Media3 updated).
- **`TextureView`**: normal View, can be transformed/scaled/animated, but is drawn via a GPU composition pass — measurably higher cost at 4K (extra full-res copy per frame), historically breaks HDR (tone-mapped/SDR-clamped on many devices) and blocks some optimizations (e.g. tunneled video). Use only when you must (e.g. video inside scrolling UI, PiP pre-scaling, overlay blending needs).

In XML (PlayerView, verified attribute `surface_type` in `PlayerView.java`, default `surface_view`):

```xml
<androidx.media3.ui.PlayerView
    android:id="@+id/playerView"
    app:surface_type="surface_view"   <!-- default; alternatives: texture_view,
                                           spherical_gl_surface_view, video_decoder_gl_surface_view -->
    app:use_controller="true"
    app:resize_mode="fit" />
```
Or in code: `playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)` for a spinner during the bigger 4K buffering windows.

### B5. Aspect ratio: `AspectRatioFrameLayout` resize modes + auto-detect + toggle

`AspectRatioFrameLayout` (the `contentFrame` inside `PlayerView`) — verified constants from source (`AspectRatioFrameLayout.java`):

| Mode | Value | Effect |
|---|---|---|
| `RESIZE_MODE_FIT` | 0 | letterbox/pillarbox — whole video visible (default) |
| `RESIZE_MODE_FIXED_WIDTH` | 1 | fix width, derive height from aspect |
| `RESIZE_MODE_FIXED_HEIGHT` | 2 | fix height, derive width |
| `RESIZE_MODE_FILL` | 3 | **stretch to fill the screen (ignore aspect — "no black bars")** |
| `RESIZE_MODE_ZOOM` | 4 | crop-to-fill: scale up preserving aspect, overflow hidden |

APIs: `PlayerView.setResizeMode(int)`, `AspectRatioFrameLayout.setAspectRatio(widthHeightRatio)`, `setAspectRatioListener { widthHeightRatio, pixelWidthHeightRatio -> }`. Video size arrives via `Player.Listener.onVideoSizeChanged(VideoSize)` (verified in `Player.java`) — `VideoSize(width, height, pixelWidthHeightRatio)`; the *sample* aspect (e.g. 2.0 for anamorphic) must be folded in: `videoAspect = width * pixelWidthHeightRatio / height.toDouble()`. Docs: https://developer.android.com/reference/androidx/media3/ui/AspectRatioFrameLayout · https://developer.android.com/media/media3/ui/customize-playback

**Drop-in player screen: auto aspect detect (>2% mismatch ⇒ stretch) + manual Fit/Stretch/Zoom/Crop16:9 toggle:**

```kotlin
class PlayerScreen : AppCompatActivity() {
    private lateinit var playerView: PlayerView
    private var videoAspect = 16f / 9f          // updated by onVideoSizeChanged
    private var screenAspect = 16f / 9f
    private var mode: AspectMode = AspectMode.AUTO   // AUTO / FIT / STRETCH / ZOOM / CROP16_9

    enum class AspectMode { AUTO, FIT, STRETCH, ZOOM, CROP16_9 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        screenAspect = computeScreenAspect()          // (a) below
        playerView.player = buildPlayer()
        playerView.player!!.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {   // (b) below
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio /
                        videoSize.height.toFloat()
                    applyResizeMode()
                }
            }
        })
        // user toggle (toolbar or controller custom button):
        //   mode = AspectMode.STRETCH; applyResizeMode()
    }

    /** (a) Device aspect ratio. WindowMetrics is API 30+; fallback for API 29. */
    private fun computeScreenAspect(): Float {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else @Suppress("DEPRECATION") Rect().also {
            windowManager.defaultDisplay.getRectSize(it)   // API 29 path
        }
        return bounds.width().toFloat() / bounds.height()
    }

    /** (c) Policy: AUTO => stretch when video vs screen aspect differ by more than 2%. */
    private fun applyResizeMode() {
        val frame = playerView.contentView as? AspectRatioFrameLayout ?: return
        frame.setAspectRatio(videoAspect)             // let the layout know real video aspect
        val resize = when (mode) {
            AspectMode.FIT     -> AspectRatioFrameLayout.RESIZE_MODE_FIT      // letterbox
            AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL     // distort to fill
            AspectMode.ZOOM    -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM     // scale+crop fill
            AspectMode.CROP16_9-> AspectRatioFrameLayout.RESIZE_MODE_ZOOM.also {
                // emulate a 16:9 crop: temporarily lie about aspect so ZOOM crops to 16:9
                frame.setAspectRatio(16f / 9f)
            }
            AspectMode.AUTO    ->
                if (abs(videoAspect - screenAspect) / screenAspect > 0.02f)
                    AspectRatioFrameLayout.RESIZE_MODE_FILL    // 16:9 content on 21:9/4:3 etc.
                else
                    AspectRatioFrameLayout.RESIZE_MODE_FIT     // near-match screens
        }
        playerView.setResizeMode(resize)
    }
}
```
Implementation notes:
- "Stretch to fill without black bars" = `RESIZE_MODE_FILL` — it *distorts* geometry (people look thin/fat). `RESIZE_MODE_ZOOM` fills without black bars *and* without distortion, but crops edges. Offer both; the 2% threshold (0.02) avoids mode-flapping on 1600×900/1680×945 style panels.
- `setVideoSize`-equivalent in Media3 is purely a listener pattern: `onVideoSizeChanged(VideoSize)` → update `AspectRatioFrameLayout.setAspectRatio(w * pixelWidthHeightRatio / h)`. `PlayerView` already wires the content frame's aspect automatically; call `frame.setAspectRatio(...)` yourself only when you disable that or emulate crop modes.
- On phones in landscape, also consider `windowInsetsController.hide(WindowInsets.Type.systemBars())` + `screenOrientation = sensorLandscape` for true full-bleed 4K.

### B6. TV remote (D-pad) support

**Player:** `PlayerView`/`PlayerControlView` are already focus-navigable with a D-pad: DPAD directions move focus between seek bar / play-pause / rewind / forward / custom buttons; **DPAD_CENTER / ENTER on the focused button activates it**; when the controller is *hidden*, `PlayerView`'s default `ControllerAutoShowHide` behavior shows the controller on any DPAD key (`setControllerAutoShow(true)` default true — a DPAD press while hidden reveals controls instead of toggling play). Media3 has no built-in "center = play/pause when hidden", so implement it with an `onKeyListener`:

```kotlin
playerView.setOnKeyListener { _, keyCode, event ->
    if (event.action == KeyEvent.ACTION_DOWN &&
        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
        !playerView.isControllerFullyVisible
    ) {
        val p = playerView.player
        if (p != null) {
            if (p.isPlaying) p.pause() else p.play()
            playerView.showController()          // make the state change visible
        }
        true
    } else false
}
// Seek granularity for ◀◀/▶▶ buttons (PlayerControlView defaults: 10 s):
playerView.setSeekBackIncrementMs(10_000)       // PlayerView delegates to controller (Media3 ≥1.3)
playerView.setSeekForwardIncrementMs(10_000)
// Volume keys while video plays (PlayerView doesn't consume them by default):
onKeyDown(KEYCODE_VOLUME_UP/DOWN) -> audioManager.adjustStreamVolume(STREAM_MUSIC, +/-, 0)
```
Docs: https://developer.android.com/media/media3/ui/customize-playback · `PlayerView` reference: https://developer.android.com/reference/androidx/media3/ui/PlayerView

**Detecting TV:** 
```kotlin
val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
val isTv = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
// Also treat HDMI-connected Fire/Google TV as TV; check Leanback feature:
val isLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
```
On TV: increase card sizes, focus outlines, and remember D-pad focus is lost across layout changes — keep `RecyclerView`'s focused item stable (`focusable=true focusableInTouchMode=true` on cards, or use Compose `Modifier.focusable()` + `focusRestorer()`).

**Browse UI focus navigation:** for Views, focus search is automatic (nearest-neighbor algorithm) but give cards explicit `android:nextFocusUp/Down/Left/Right` only if the default order is wrong; grid spacing should be ≥ focus-outline padding so focus doesn't skip rows. Leanback interop (optional, no extra dependency unless you ship leanback): run the same `MainActivity` and branch on `isTv`, or declare a `LEANBACK_LAUNCHER` intent-filter to appear in the TV launcher; the Media3 `PlayerView` needs no leanback-specific changes.

### B7. minSdk 29 (Android 10) compatibility

- **Media3 support matrix:** every Media3 1.x release (1.0 → 1.11.1) has `minSdk = 21` (Android 5.0) for all `media3-*` artifacts — getting started: https://developer.android.com/media/media3/getting-started. minSdk 29 is therefore fully supported with **no Media3-version workaround**; use the newest (1.11.1) to get local-playback buffer tuning, DoVi, and the SurfaceView fixes above.
- APIs in this doc needing runtime checks on API 29: `WindowManager.currentWindowMetrics` (API 30+, fallback to `defaultDisplay.getRectSize` — handled in snippet B5); `Context.getSystemService(Class)` is fine (23+); `UiModeManager` (API 8+); HDR `Display.getHdrCapabilities()` (API 24+, use 26+ constants guardedly).
- Platform 4K/HDR facts on API 29+: HEVC hw decode for 2160p is mandatory for most 2016+ device tiers, but *not guaranteed* — always gate 4K tracks behind the B3 capability check; Dolby Vision support is device-specific (profile 5 vs 8), HDR10 is broadly available from API 24 (`FeatureInfo.FEATURE_HdrEditing`… no—check `Display.getHdrCapabilities().getSupportedHdrTypes()` containing `HDR_TYPE_DOLBY_VISION` / `HDR_TYPE_HDR10`).

### B8. Extra 4K-smoothness tricks

1. **OkHttpDataSource vs DefaultHttpDataSource** (artifact `androidx.media3:media3-datasource-okhttp`, class `androidx.media3.datasource.okhttp.OkHttpDataSource.Factory` — verified): OkHttp gives connection pooling + HTTP/2 multiplexing (fetches HLS segments over one warm connection; fewer TLS handshakes = fewer 4K stall windows), plus interceptors, better DNS caching. DefaultHttpDataSource uses `HttpURLConnection` (per-host keep-alive pool too, but no H2). Set generous timeouts and enable cross-protocol redirects (only DefaultHttpDataSource needs it — OkHttp follows them natively; verified `setAllowCrossProtocolRedirects` exists on `DefaultHttpDataSource.Factory`):
   ```kotlin
   val dataSourceFactory = OkHttpDataSource.Factory(
       OkHttpClient.Builder()
           .connectTimeout(10, TimeUnit.SECONDS)
           .readTimeout(20, TimeUnit.SECONDS)
           .retryOnConnectionFailure(true)   // handles IPv6→IPv4 fallback (happy eyeballs)
           .build()
   ).setUserAgent("LoLTube/1.0")
   // If staying on DefaultHttpDataSource:
   // DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)  // e.g. http→https CDN hops
   //                             .setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000)
   val player = ExoPlayer.Builder(context)
       .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
       .setLoadControl(build4KLoadControl())
       .build()
   ```
   DNS/IPv6: on broken-IPv6 networks, per-segment AAAA stalls look like "buffering every ~10 s"; OkHttp's `retryOnConnectionFailure` + Dns.SYSTEM fallback fixes most; worst case `OkHttpClient.Builder().dns(Dns::SYSTEM or a IPv4-first wrapper)`.
2. **Seek increments:** `PlayerControlView` defaults to 10 s; for movies make it 15–30 s (`playerView.setSeekBackIncrementMs(15_000)`), and `ExoPlayer.Builder.setSeekParameters(SeekParameters.CLOSEST_SYNC)` for snappier seeks on TS-less fMP4 HLS (default is `DEFAULT`/`EXACT` with a sync penalty on some streams).
3. **Live playback speed control off for VOD:** auto speed-to-live only applies to live streams; no-op for VOD, but if you play pseudo-live, disable via `player.setPlaybackParameters(PlaybackParameters(1f))` and never enable `experimentalSetOffloadedScheduling` for VOD 4K (codec re-init cost).
4. **Frame-drop monitoring** (verified callback on `AnalyticsListener`):
   ```kotlin
   player.addAnalyticsListener(object : AnalyticsListener {
       override fun onDroppedVideoFrames(e: EventTime, dropped: Int, elapsedMs: Long) {
           if (dropped > 3) warn("dropped $dropped frames in $elapsedMs ms — lower quality or check hw decode")
       }
       override fun onVideoDisabled(e: EventTime, counters: DecoderCounters) {
           log("session: dropped=${counters.droppedFrameCounter}, maxConsecutive=${counters.maxConsecutiveDroppedFrames}, skipped=${counters.skippedInputBufferCounter}")
       }
   })
   ```
   Persistent drops at 4K ⇒ decoder too slow (software!) or display path saturated — trigger ABR cap `setMaxVideoBitrate(15_000_000)` + `setMaxVideoSize(1920, 1080)` fallback.
5. **HDR:** don't transcode/tone-map manually; SurfaceView + platform tone-mapping handles HDR10/HLG, Dolby Vision needs device support (`Display.getHdrCapabilities()`), and on API 29+ you can detect via `Display.Mode`/`getHdrCapabilities` to label "HDR" in the UI. Keep `SurfaceView` (B4) — TextureView commonly clamps HDR to SDR.
6. **`setVideoChangeFrameRateStrategy`** (verified on `ExoPlayer.Builder`, Media3 ≥1.1): with `CHANGE_FRAME_RATE_ONLY_IF_CTS_SUPPORTED`, matches display refresh to 24/50/60 fps content — visibly reduces judder on 24 fps movies on 60 Hz panels.
7. **Back buffer:** `setBackBuffer(30_000, true)` (B1) makes ◀◀ rewinds instant — key for the "replay that scene" UX at 4K where an exact seek would re-buffer.
8. **Keep Media3 current:** the SurfaceView-on-TV decoder bug (androidx/media#3525) and Google TV Streamer freeze (androidx/media#2972) are the kind of device-specific 4K fixes you get by staying on the latest 1.x.

---

## Sources & citations index

**Part A (live-tested 2026-11-22):**
- TVmaze API: https://www.tvmaze.com/api (endpoints /search/shows, /shows/{id}, /shows/{id}/seasons, /shows/{id}/episodes, /lookup/shows?imdb=, /schedule)
- TMDB: signup https://www.themoviedb.org/signup · API docs https://developers.themoviedb.org/ (trending, popular, multi-search, tv-season-details, find-by-id, external-ids — all verified 200) · rate limits https://developer.themoviedb.org/docs/rate-limiting
- Trakt API docs https://trakt.docs.apiary.io/ (needs client_id) · OMDB https://www.omdbapi.com (free key)
- Dead/broken today: `api.imdbapi.dev` (NXDOMAIN), `search.imdbot.workers.dev` (error_code 103)

**Part B:**
- Media3 versions: https://dl.google.com/androidx/maven2/androidx/media3/media3-exoplayer/maven-metadata.xml (latest stable 1.11.1)
- Source-verified constants (androidx/media `release` branch): DefaultLoadControl.java, AdaptiveTrackSelection.java, AspectRatioFrameLayout.java, MediaCodecSelector.java, MediaCodecInfo.java, PlayerView.java, ExoPlayer.java, C.java, AnalyticsListener.java, DefaultHttpDataSource.java, OkHttpDataSource.java (raw.githubusercontent.com/androidx/media/release/libraries/...)
- Developer docs: /media/media3/exoplayer/buffers · /media/media3/exoplayer/track-selection · /media/media3/ui/surfaces · /media/media3/ui/customize-playback · /media/media3/getting-started · reference pages for DefaultLoadControl, AdaptiveTrackSelection, AspectRatioFrameLayout, PlayerView, MediaCodecSelector, TrackSelectionParameters (all fetched live, URLs valid)
- GitHub issues (titles verified live via browser): androidx/media#385 (Stutter Playback on Livestream DRM), androidx/media#2972 (Video freezes, audio continues on Google TV Streamer), androidx/media#3525 (MStar TV SurfaceView 0–3 fps decoder config bug)

## Next actions for the build team
1. Sign up for a TMDB developer key (owner account required; ~3 min) and wire `local.properties` → `BuildConfig.TMDB_KEY`; implement CatalogRepository against TMDB v3 with TVmaze fallback for TV episode data (both tested here).
2. Add `androidx.media3:media3-exoplayer`, `media3-ui`, `media3-datasource-okhttp`, `media3-hls` at **1.11.1**; set `minSdk 29` (all Media3 1.x fine ≥21).
3. Implement the B1+B2+B3 player builder (4K LoadControl + adaptive hysteresis + hw-only codec selector + priority + frame-rate strategy) behind a `SmoothPlayerFactory`.
4. Implement the B5 aspect-ratio controller (AUTO mode with 2% threshold + Fit/Stretch/Zoom/Crop16:9 toggle) and the B6 TV-mode branching (`UiModeManager`) with D-pad center-to-play.
5. Add dropped-frame analytics listener (B8.4) with automatic quality fallback; QA on a real 4K TV (Google TV/Fire TV) for the issues cited.
