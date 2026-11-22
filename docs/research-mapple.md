# Mapple TV / Mapple Player — Full API Research Report

**Task ID:** 2-b • **Date:** 2026-11-22 • **Analyst:** research sub-agent
**Artifacts:** `/home/z/my-project/work/mapple-live/` (docs HTML + extracted text, RSC payload, this analysis)

---

## 0. TL;DR — What "Mapple" actually is

| Domain | Role |
|---|---|
| `mappletv.uk` | Marketing + documentation site for **"Mapple Player"** (an embeddable video player). **No content API lives here.** Any `/watch/*` path 302-redirects to `mapple.fun`. Legacy source name "s1" pointed at `mappletv.uk/watch/`. |
| `mapple.fun` | The **actual application** (Next.js 15, Turbopack, deployed on Vercel, behind Cloudflare). Hosts the player, the TMDB-proxy catalog, and all playback APIs. |
| `mapple.uk`, `mapple.tv` | Aliases → 301 → `mapple.fun`. |
| `sgp.cloud.appwrite.io/v1` | Appwrite backend (auth, favorites, comments, watch history, messages). Project `69357f4300029e916885`, DB `693586330029ae2f0d3f`. |

- **The only *public, documented* API** is the **iframe embed API** (`mapple.fun/watch/movie/{tmdb_id}` and `/watch/tv/{tmdb_id}-{season}-{episode}`). No API key, no SDK. 9 query parameters.
- **The internal JSON API** (TMDB proxy, stream resolution) is **not documented** and is protected by: Cloudflare WAF (hard IP block for datacenter IPs — verified live), a request-token session, a stream-token endpoint with **Proof-of-Work challenge**, **signed/encrypted URLs**, **429/503 rate limits**, and an anti-embedding integrity check.
- **Content IDs are TMDB numeric IDs** (e.g. `604` = The Matrix Reloaded, `83867` = a TV show). Docs *say* "IMDB or TMDB" for `{id}`; the FAQ clarifies "Use the numeric TMDB ID". (IMDb-style `tt0133093` was NOT verifiable live — see §6.)
- Playback is **clear HLS (m3u8)** via hls.js/MSE, or direct **MP4** in some cases. **No Widevine/PlayReady DRM found** (hls.js config has `licenseXhrSetup: void 0`; `enableSoftwareAES: true` only for AES-128 HLS if a stream uses it). 4K (2160p) is confirmed available on the primary sources.
- **DRM-free but strongly signed**: variant playlists are same-origin `/playlist/{a}/{b}.m3u8?e=<expiry>&s=<signature>` URLs, fragments may be proxied via `/client-stream/*` (fragment URL returns `{url, headers}` JSON, then the real CDN fetch).

---

## 1. Official embed API (from mappletv.uk/docs — fetched & verified live, HTTP 200)

Docs pages (all fetched to `mapple-live/docs-*.html`): `/docs/getting-started/introduction`, `/endpoints`, `/implementation`, `/customization`, `/events`, `/watch-party`. There is no `/sitemap.xml` (404); nav lists exactly these 6 pages.

### 1.1 Endpoints

```
GET https://mapple.fun/watch/movie/{id}                     # {id} = TMDB (or IMDb) movie id
GET https://mapple.fun/watch/tv/{id}-{season}-{episode}     # TV episode
```

**Movie embed** — required: `{id}`. Examples from docs: `mapple.fun/watch/movie/1084199`, `.../watch/movie/1084199?theme=16A085`.

**TV embed** — required: `{id}`, `{season}`, `{episode}` (dash-separated: `/watch/tv/83867-1-1`). Example: `https://mapple.fun/watch/tv/83867-1-1?nextButton=true&autoPlay=true`.

### 1.2 Query parameters (complete matrix, from docs landing page)

| Param | Type | Movie | TV | Description |
|---|---|---|---|---|
| `autoPlay` | boolean | ✓ | ✓ | Start playback on load |
| `startAt` | number (sec) | ✓ | ✓ | Start at offset (e.g. `startAt=120`) |
| `theme` | hex (no `#`) | ✓ | ✓ | Accent color, e.g. `theme=16A085` |
| `title` | boolean | ✓ | ✓ | Show title overlay |
| `poster` | boolean | ✓ | ✓ | Show poster before playback |
| `nextButton` | boolean | ✗ | ✓ | "Next episode" button at 90% watched |
| `autoNext` | boolean | ✗ | ✓ | Auto-play next episode (needs `autoPlay`) |
| `watchParty` | boolean | ✓ | ✓ | Show watch-party button |
| `partyId` | string | ✓ | ✓ | Join existing watch-party room (e.g. `DIHQQ3`) |

Additional params found in code (undocumented): `isIframe=true` (force iframe mode).

### 1.3 Embed snippet (docs, HTML)

```html
<iframe
  src="https://mapple.fun/watch/movie/1084199"
  width="100%" height="100%" frameborder="0"
  allowfullscreen allow="encrypted-media">
</iframe>
```
Docs recommend a 16:9 wrapper (`padding-bottom: 56.25%`). **Do NOT add a `sandbox` attribute** — the player refuses to run in sandboxed iframes ("Sandbox Detected" FAQ).

### 1.4 postMessage events (from `https://mapple.fun` origin only)

```js
window.addEventListener("message", ({ origin, data }) => {
  if (origin !== "https://mapple.fun" || !data) return;
  if (data.type === 'PLAYER_EVENT') { /* playback events */ }
  if (data.type === 'MEDIA_DATA')   { /* full media + progress */ }
  if (data.type === 'WATCH_PARTY')  { /* { roomId, userId } */ }
});
```

`PLAYER_EVENT.data`: `{event: "play"|"pause"|"seeked"|"ended"|"timeupdate", currentTime, duration, tmdbId, mediaType: "movie"|"tv", season?, episode?}`

`MEDIA_DATA.data` (per-title map keyed by TMDB id — used for "continue watching"):
```json
{ "533535": { "id": 533535, "type": "movie", "title": "Deadpool & Wolverine",
  "poster_path": "/bc8dVyu20EUxruj7yt4E6DMuBfo.jpg", "backdrop_path": "/by8a9F8eP7djQz1WzSDnptyT.jpg",
  "progress": { "watched": 353.582349, "duration": 7667.227 }, "last_updated": 174257064433 } }
```
Resume flow (docs): store `progress.watched`, then re-embed with `?startAt=<seconds>`.

`WATCH_PARTY.data`: `{roomId: "DIHQQ3" (6 chars), userId: "oqpdl2btw3s"}`; join by adding `partyId=DIHQQ3&watchParty=true&autoPlay=true`.

---

## 2. Internal web-app API (reverse-engineered from the saved player snapshot + ~100 JS chunks)

The saved `Movie _ Mapple.html` is **`https://mapple.fun/watch/movie/604?autoPlay=true`** (The Matrix Reloaded, TMDB 604), a Next.js App-Router page. Its video element is `<video data-playback-source="mapple" ... src="blob:https://mapple.fun/5f08eec2-...">` → MSE via hls.js (chunk `2oiguqhm96vqj.js` = hls.js 1.x build).

### 2.1 Complete internal endpoint inventory

All on `https://mapple.fun` (same-origin, `credentials: include` / `same-origin` unless noted):

| # | Method+Path | Purpose | Auth | Notes |
|---|---|---|---|---|
| 1 | `POST /api/ui/session` | **Request token bootstrap**. Resp: `{token: string, expiresAt: int, expiresIn?: int, deploymentVersion?: string}` | none (cookies) | Cached until 5 min before expiry; `deploymentVersion` mismatch triggers page reload with `?_mapple_build=` |
| 2 | `POST /api/ui/sync` | OpenPanel analytics beacon (`window.op` init, clientId, `trackScreenViews`) | none | SDK "nextjs" v1.5.1 |
| 3 | `GET /api/ui/assets/{hash}` | Serves **obfuscated ad scripts** (e.g. `6e4b9c2d` = popunder/anti-adblock blob, `window['ZpQw9XkLmN8c3vR3']=...`) | none | `data-cfasync=false` |
| 4 | `POST /api/tmdb-key` | **Rotating catalog key**. Resp: `{key: string, expiresAt: int}` (refreshed 30 s before expiry) | none | |
| 5 | `GET /api/tmdb/{tmdbPath}?{query}` | **TMDB proxy** (catalog). Header `x-api-key: {key from #4}`. 401 → refetch key, retry once. 15 s timeout | x-api-key | Standard TMDB JSON responses |
| 6 | `POST /api/media-session` | **Stream token + PoW**. Body: `{mediaId, mediaType, tv_slug, requestToken, pow?: {challengeId, nonce}}`. Resp: `{success, token, requiresPow?, pow?: {challengeId, challenge, difficulty}}` | requestToken | 429/503 handling, 5 s timeout per attempt, 20 s overall, max 2 attempts |
| 7 | `POST /api/encrypt` | **Encrypted-URL factory**. Body: `{data: {mediaId, mediaType, tv_slug, source}, endpoint: "stream-encrypted"\|"next-episode-encrypted", requestToken}`. Resp: `{url: string}` | requestToken (+ cookies) | Client appends `&requestToken=…&token=…` to `url` |
| 8 | `GET {encrypted stream url}` | **Stream resolution**. Resp: `{success, data: {stream_url, format: "hls"\|"mp4"}}` or `{success:false, error}` | requestToken + streamToken query params | fetched `credentials: "include"`; per-source timeout 5–25 s |
| 9 | `POST /api/intro-segments` | Intro/recap/outro skip segments. Body: `{tmdbId, mediaType, durationMs, season?, episode?, requestToken}`. Resp: `{segments: [{kind:"intro"\|"recap"\|"outro", startSeconds, endSeconds}]}` (≤20) | requestToken | Primary source is 3rd-party `GET https://api.theintrodb.org/v3/media?tmdb_id=&duration_ms=[&season=&episode=]` |
| 10 | `GET /thumb/key` | Thumbnail sprite key. Headers: `X-Thumb-Playlist: <playlistPath>`, `X-Media-Grant-Expiration: <e>`, `X-Media-Grant-Signature: <s>`. Resp: `{key, slotSeconds}` | grant headers | |
| 11 | `GET /thumb/{key}/{slot}.jpg` | Seek-preview thumbnail; retry with grant headers if 403 | grant headers | |
| 12 | `GET /playlist/{a}/{b}.m3u8?e=<exp>&s=<sig>` | **Signed variant playlists** referenced from master m3u8 (same-origin) | signed query | `e`=expiration, `s`=signature |
| 13 | `GET /client-stream/{...}` | **Fragment proxy**: a fragment URL under `/client-stream/` returns JSON `{url, headers}`; client then fetches `url` with `headers` and feeds bytes to MSE | (as returned) | 500 → fall back to direct load |
| 14 | `GET /pow-worker.js` | Web Worker solving PoW: `postMessage({challenge, difficulty})` → `{found, nonce}`; 10 s timeout | none | Algorithm not in snapshot (worker file not saved) |
| 15 | `GET /api/turnstile-config` | `{enabled: bool, siteKey: string\|null, invisibleSiteKey: string\|null}` | none | Cloudflare Turnstile |
| 16 | `POST /api/verify-turnstile` | `{token}` → `{success}` | none | Used before registration actions |
| 17 | `POST /api/action-session` | Server-action session. Header `Authorization: Bearer <Appwrite JWT>`. Resp: `{userId, expiresAt}`. `DELETE` to sign out | Appwrite JWT | |
| 18 | `POST /api/register-rate-limit` | `{action: "check"\|"success"}` → `{ok, message?}` | none | Registration throttle |
| 19 | `GET /api/home-slider?language={tmdbCode}` | Localized hero slider. Resp: `{items: [...]}` | none | |
| 20 | `GET /api/socket` (socket.io, `transports: ["polling","websocket"]`) | Watch-party sync. Events: `ROOM_CREATED`, `ROOM_JOINED`, … plus `syncPlayerAction`/`onPlayerAction` server actions | cookie | 20 s timeout, 5 reconnects |
| 21 | `GET /api/anime/{id}` | Anime details (proxied anime DB): `{description/overview, genres[], cover/image, score, releaseDate/aired, duration}` | none | Also `/api/manga/…`, `/api/novels/…` |
| 22 | Server Actions (Next.js RSC `createServerReference`) | `fetchSubtitlesAction({mediaId, mediaType, season?, episode?})` → `[{lang, label, url, isHearingImpaired}]`; `addToFavorites` / `removeFromFavorites` / `checkFavoriteStatus`; `sendWelcomeNotification`; `getUserProfile`; `fetchDramaDetails` | action session | Action IDs captured, e.g. fetchSubtitles `406c628cd91c0525e678ba0659bccd666dac166252` |
| 23 | Appwrite (external) | `https://sgp.cloud.appwrite.io/v1`, project `69357f4300029e916885`, database `693586330029ae2f0d3f`, collections: `messages`, `message_reactions`, `typing_indicators`, `watch_history`, `tv_show_season_array`, `comments`, `avatar`; storage bucket `69377bef002e5c575fa8` | Appwrite session | Email+password auth via `account.createEmailPasswordSession` |

### 2.2 Catalog (TMDB proxy) — the endpoints an Android "list" screen needs

`/api/tmdb-key` gives a proxy key, then everything is plain TMDB v3 paths under `/api/tmdb/`:

- Trending: `trending/movie/{day|week}?page=` , `trending/tv/{day|week}?page=`
- Search: `search/multi|movie|tv?query=&page=&include_adult=false`
- Discover: `discover/movie?...`, `discover/tv?...` (any TMDB params, e.g. `with_watch_providers=283|1968`, `with_companies=`, `with_keywords=`)
- Details: `movie/{id}?language=en-US`, `tv/{id}?language=...`, `tv/{id}/season/{n}?append_to_response=...`, `person/{id}`
- Genres: `genre/movie/list`, `genre/tv/list`; Watch providers: `watch/providers/movie?watch_region=`
- Language mapping in app: en→en-US, ar→ar-SA, de→de-DE, es-ES, es-419→es-MX, fil→tl-PH, fr→fr-FR, hi→hi-IN, it→it-IT, ja→ja-JP, ko→ko-KR, nl→nl-NL, pt-BR, ru→ru-RU, zh-CN, zh-TW

Site route conventions (pages, not API): `/movie/{id}`, `/movie/{id}-{slug}`, `/tv/{id}`, `/movie/popular|upcoming|now-playing|top-rated|netflix|disney|hulu|max|prime|apple|crunchyroll|history`, `/tv/popular|on-the-air|airing-today|top-rated|anime-series|...`, `/4k-contents`, `/search?q=`.

**Practical note:** the catalog is literally TMDB — an Android app can talk to TMDB directly for listings and only use Mapple for playback.

### 2.3 Playback sources (the "god" sources)

Current catalog (chunk `2jm6_0979cnb1.js`, module 359609) — 14 sources, IDs and capabilities:

| id | Name | Category | maxRes hint | 4K | Multi-audio |
|---|---|---|---|---|---|
| `mapple` (legacy default, `LEGACY_MAPPLE_SOURCE_ID`) | Zeus ⚡ | core | 2160 | confirmed | – |
| `s25` | Poseidon 🔱 | language | 2160 | confirmed | – |
| `s2` | Athena 🦉 | language | 2160 | confirmed | ✓ |
| `s28` | Astraeus 🌟 | language | 2160 | confirmed | – |
| `s19` | Apollo ☀️ | language | 2160 | confirmed | ✓ |
| `s16` | Ares ⛓️ | language | 2160 | confirmed | ✓ |
| `s10` | Prometheus 🔥 | language | – | confirmed | – |
| `s27` | Hermes 🪽 | language | – | – | – |
| `s4` | Hera 👑 | language | – | – | – |
| `s12` | Persephone 🌺 | language | – | – | – |
| `s6` | Aphrodite 🐚 | language | – | – | – |
| `s7` | Demeter 🌾 | language | – | – | – |
| `s8` | Dionysus 🍇 | language | – | – | – |

Legacy `VideoAPIs` table (same chunk) reveals the upstream embed providers Mapple aggregates:

| legacy id | api base | label |
|---|---|---|
| `s1` | `https://mappletv.uk/watch/` | "- Mapple 4k/1080p" |
| `s2` | `https://www.vidfast.pro/` | "- Most reliable" |
| `s3` | `https://vidlink.pro/` | "- Fast" |
| `s4` | `https://player.videasy.net/` | "- May have 4k" |
| `s5` | `https://vidsrc.cc/v3/embed/` | "- Backup" |
| `s6` | `https://vixsrc.to/` | "- Multi-audio" |

Per-source resolve timeouts (`l(source)`): mapple 8 s, s10 12 s, s2 22 s, s28 25 s, s25 24 s, others 5 s. Per-source HLS timeout profiles: mapple `tm` (manifest 15 s / playlist 20 s / frag-first-byte 15 s; startup 15/25/35 s), s28 `tp` (20/35/30; startup 25/45/60), default `td` (10/10/15; startup 15/20/25).

Preferred source persisted in `localStorage["mapple_preferred_sourcev2:{mediaType}:{mediaId}:{scope}"]` = `{source, savedAt}` for 24 h. Failed sources tracked in `window.__allFailedSources`.

### 2.4 Stream resolution flow (content id → playable m3u8) — exact sequence

```
1. POST /api/ui/session                     → requestToken (TTL-bounded; refreshed 5 min before expiry)
2. POST /api/media-session                  body {mediaId: 604, mediaType: "movie", tv_slug: "", requestToken}
      ├── if requiresPow: solve PoW in /pow-worker.js (challenge, difficulty) → nonce
      │     re-POST with pow: {challengeId, nonce}
      └── → {success, token}                streamToken (client caches 100 s; 3 s in-flight dedupe)
3. POST /api/encrypt                        body {data: {mediaId, mediaType, tv_slug, source: "mapple"},
                                                     endpoint: "stream-encrypted", requestToken}
      └── → {url}                           server-built encrypted URL (same-origin)
4. GET  {url}&requestToken=…&token=…        credentials: include
      └── → {success, data: {stream_url, format: "hls"|"mp4"}}
          (up to 2 sources raced: current + next, 2nd starts after 400 ms hedge;
           first {success, data.stream_url} wins)
5a. HLS: hls.js loadSource(stream_url) → master m3u8
      - variant playlists: /playlist/{a}/{b}.m3u8?e=<exp>&s=<sig>  (signed grants)
      - fragments: direct CDN or /client-stream/{...} proxy
          (proxy fetch → {url, headers} JSON → fetch(url, {headers}) → bytes → MSE)
      - Safari fallback: native <video src=m3u8>
5b. MP4: <video src=stream_url> directly
```

`format` fallback detection: if `format` missing, `.mp4` in path or in `filename` query param → mp4, else hls.

Trailer guard: for movies, if `duration < 600 s` the source is rejected as "likely a trailer/sample" and the next source is tried.

`tv_slug` = `"{season}-{episode}"` (regex `^(\d{1,4})-([1-9]\d{0,4})$`), e.g. `83867-1-1` → `tv_slug="1-1"`.

**Next episode** (TV): `POST /api/encrypt {endpoint:"next-episode-encrypted", data:{mediaId, mediaType:"tv", season, episode}}` → `{url}` → GET → `{success, data: nextEpisode}`; player preloads the next episode's stream near the end.

### 2.5 Quality levels

- Quality list = hls.js `levels` (raw `height`, `bitrate`). Auto-selection prefers **highest height**, tie-broken by bitrate. Height > 1400 px is treated as "HD/4K" tier (badge logic).
- Marketing page: "True 4K Ultra HD — genuine 3840×2160 streams with HDR support" and "2160p / 1080p / 720p / 360p" ladder. Source catalog marks 7 sources with `maxResolutionHint: 2160, fourKAvailability: "confirmed"`.
- ABR config: initial buffer 8 s (raised to 40 s after 4 s of stable playback, `maxBufferSize` 24 MB, backBuffer 10 s), `abrEwmaDefaultEstimate` 3 Mbps, `capLevelToPlayerSize: false`, frag retries 6 (`fragLoadingRetryDelay` 500 ms → 64 s max), on repeated 5xx fragment errors the player **probes one level down** ("probing {height}p after 5xx").
- **No concrete 4K bitrate numbers were captured** (master m3u8 contents are not in the snapshot; live fetch blocked — see §6). Expect typical 2160p HLS tiers ~15–25 Mbps; verify at runtime by parsing the master playlist.

### 2.6 Subtitles & audio

- Subtitle list via **server action** `fetchSubtitlesAction({mediaId, mediaType, season?, episode?})` → `[{lang, label, url, isHearingImpaired}]` — subtitle **files are SRT**, fetched client-side (`fetch(url).text()`) and parsed with regex `(\d+)\s*\n(\d{2}:\d{2}:\d{2},\d{3})\s*-->\s*...`; cues rendered through a **native `addTextTrack("subtitles")`** (the `mapple-native-caption-video` element), not WebVTT sidecar files.
- Auto-select first English non-HI track if `autoSubtitles` preference on. "HI" variants labeled.
- Local **.srt upload** supported (≤ 1 MB), parsed client-side; caption styling (font/size/weight/color/shadow/bg) and ±timing offset adjustable in UI.
- Subtitle text is scrubbed: blocked phrases ("opensubtitles", "vip member", "remove all ads", "advertise your product", "contact www.", "support us", "subtitles for any video", "ai."), and upstream provider domains are rewritten to `mapple.rip` — i.e., **the backend aggregates subtitles from OpenSubtitles-style providers**.
- Optional **profanity filter** with bleep: mutes profanity windows using `/censor.mp3?v=2` decoded via WebAudio.
- Multi-audio: hls.js `audioTracks` exposed in settings (sources s2/s19/s16 flagged `multiAudio`).

### 2.7 Rate limits & abuse protection (documented from client code)

| Mechanism | Detail |
|---|---|
| HTTP 429 | On `/api/media-session` and `/api/encrypt`. Client reads `Retry-After` (seconds or HTTP-date). Enters **global cooldown** ("Playback is cooling down. Please try again shortly."). 1 retry if delay ≤ 1 s (encrypt) / ≤ 11 s (media-session). |
| HTTP 503 | "Playback verification unavailable" — 1 retry if `Retry-After` ≤ 1 s. |
| Proof-of-Work | `media-session` may respond `{requiresPow: true, pow: {challengeId, challenge, difficulty}}`; client must solve (`/pow-worker.js`, ≤ 10 s) and resubmit `{pow: {challengeId, nonce}}`. |
| Stream token TTL | **100 seconds** client-side cache; refresh throttled to 1 per 3 s per media. |
| Request token | Refreshed when < 5 min left of TTL. |
| Encrypted-URL cache | Non-stream URLs cached 5 min client-side (max 100 entries). |
| Integrity check | `fetchStreamUrl` returns "Player integrity check failed." if `window.pt_raw_injected === true` **or `window.flutter_inappwebview !== undefined`** (explicitly blocks Flutter InAppWebView!). |
| Iframe sandbox | Player shows "Sandbox Detected" inside `<iframe sandbox>`; refuses playback. `isIframe=true` param or cross-origin top window enables iframe mode; `document.referrer.includes("mappletv.uk")` sets `isParentMappletv`. |
| Analytics on failures | `V({sourceId, error, resolveMs, title})` telemetry posted (2 % sampling) with startup_ms, frag p50/p95, kbps p50, height_switches, etc. |
| Hard limits not published | No public rate-limit numbers; docs never mention quotas. |

### 2.8 Ads (important discrepancy vs marketing)

The saved player page (`mapple.fun/watch/movie/604?autoPlay=true`) contains these ad payloads:

- `llvpn.com/tag.min.js` with `dataset.zone = '11028449'` (loaded on every page via inline script)
- `window.aclib.runPop({ zoneId: "11201262" })` (popunder, via `/api/ui/assets/6e4b9c2d`)
- `ou.urbanfungose.com/sx8q6JQhUzM/146419` and `rapidadexchange.com`, plus `auvsqmezygxso.space` / `jcvfysvesqsyn.site/ad/visit.php?al=1` (popunder/redirect chain)

So the *site* serving the embed runs popunder/redirect ads even though the player UI itself is "ad-free". A native app embedding the page in a WebView must expect (and ideally suppress) popup navigation attempts.

### 2.9 Chromecast, watch party, misc

- Chromecast supported (`cast_sender.js` present, `x-webkit-airplay="allow"`).
- Watch party rooms are 6-char codes; sync via socket.io `/api/socket` + server actions; host/member roles; rejoin after 5 s disconnect.
- Keyboard shortcuts: Space, ←/→/↑/↓, F, M.
- Analytics: OpenPanel self-hosted (`/api/ui/sync`), Cloudflare beacon.
- localStorage keys: `mapple-theme-color`, `mapple_user_preferences` (reduceMotion, autoSubtitles, profanityFilter, profanityBleep, qualityPreference), `mapple_preferred_sourcev2:*`, `mapplePlayerProgress` (docs), `mappleWatchParty` (docs).

---

## 3. Live verification performed (curl + headless browser)

| Test | Result |
|---|---|
| `GET mappletv.uk/docs/getting-started/endpoints` (+UA) | **200** — full docs captured |
| `GET mappletv.uk/docs/getting-started/{introduction,implementation,customization,events,watch-party}` (+UA) | **200** — all captured |
| `GET mappletv.uk/` (+UA) | **200** — landing with full API reference + FAQ (payload extracted) |
| `GET mappletv.uk/sitemap.xml` | 404 (no sitemap; docs nav = 6 pages) |
| `GET mappletv.uk/api/tmdb-key` | 404 (no API on this domain) |
| `GET mappletv.uk/watch/movie/604` | **302 → `https://mapple.fun/watch/movie/604`** |
| `POST mappletv.uk/` | 405 (GET-only landing) |
| `GET mapple.fun/` (curl + Chrome UA) | **403 Cloudflare challenge page** |
| `POST mapple.fun/api/ui/session` (curl + UA) | **403 Cloudflare** |
| `GET mapple.fun/api/turnstile-config` (headless Chromium, agent-browser) | **Blocked — "Sorry, you have been blocked … mapple.fun"** (hard IP block, CF-Ray …HKG) |
| `mapple.uk/…`, `mapple.tv/…` | **301 → mapple.fun** (same block applies) |
| Wayback Machine / r.jina.ai / CORS proxies from sandbox | Unavailable (blocked/522/401) |

**Consequence:** the internal endpoints (§2) could not be exercised end-to-end from this sandbox — the sandbox egress IP is hard-blocked by Cloudflare WAF on `mapple.fun`. All §2 detail is from static analysis of the shipped client code (high confidence: it is the production bundle), with the exact request/response shapes as coded. On a **residential/mobile IP** (like a real Android device) the endpoints are expected to behave as documented above; datacenter IPs (CI, cloud functions, proxies) will likely be blocked.

Reconstructed curl transcript of the playback chain (to run from a clean IP):

```bash
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

# 0. (browser must first pass Cloudflare — cookies like cf_clearance may be required)

# 1. request token
curl -s -X POST "https://mapple.fun/api/ui/session" -H "User-Agent: $UA" \
     -H "Accept: application/json" -c cookies.txt
# → {"token":"...","expiresAt":1732278000000,"expiresIn":3600000,"deploymentVersion":"..."}

# 2. stream token (may require PoW round-trip)
curl -s -X POST "https://mapple.fun/api/media-session" -H "User-Agent: $UA" \
     -H "Content-Type: application/json" -b cookies.txt \
     -d '{"mediaId":604,"mediaType":"movie","tv_slug":"","requestToken":"<TOKEN>"}'
# → {"success":true,"token":"..."}  (or {"success":true,"requiresPow":true,"pow":{...}})

# 3. encrypted URL
curl -s -X POST "https://mapple.fun/api/encrypt" -H "User-Agent: $UA" \
     -H "Content-Type: application/json" -b cookies.txt \
     -d '{"data":{"mediaId":604,"mediaType":"movie","tv_slug":"","source":"mapple"},
          "endpoint":"stream-encrypted","requestToken":"<TOKEN>"}'
# → {"url":"https://mapple.fun/<encrypted-path>..."}

# 4. resolve stream (append tokens)
curl -s -b cookies.txt -H "User-Agent: $UA" \
  "<URL_FROM_STEP_3>&requestToken=<TOKEN>&token=<STREAM_TOKEN>"
# → {"success":true,"data":{"stream_url":"https://.../master.m3u8","format":"hls"}}

# 5. fetch manifest (first bytes)
curl -s -H "User-Agent: $UA" "<stream_url>" | head -c 400
```

---

## 4. DRM, geo, expiry, headers — direct answers

- **DRM: none.** No Widevine/PlayReady/ClearKey anywhere in the bundle (`licenseXhrSetup: void 0`, no EME controller configured, no license URLs). The `allow="encrypted-media"` iframe attribute is boilerplate. Streams are clear HLS (fMP4) or MP4; hls.js has `enableSoftwareAES: true` in case a stream uses AES-128 keys.
- **Signed URLs & expiry:** variant playlists carry `?e=<expiry>&s=<signature>` ("X-Media-Grant-Expiration"/"X-Media-Grant-Signature" mirror these as headers for the thumbnail endpoint) — expiry length unknown (not observable offline). Stream token is short-lived (100 s client cache). Encrypted URLs are single-purpose, server-built, and must be fetched with cookies (`credentials: include`).
- **Headers needed:** all mapple.fun API calls are same-origin with `Content-Type: application/json` (+`x-api-key` for `/api/tmdb/*`, `Authorization: Bearer <Appwrite JWT>` for `/api/action-session`, grant headers for `/thumb/*`). No explicit `Referer`/`Origin` checks found in client code — but Cloudflare sits in front (browser-like UA mandatory; plain curl → 403 even before the IP block; JS challenge/cookies likely required).
- **Geo/IP:** No geo-blocking logic in the app itself; the only hard gate observed is **Cloudflare IP reputation** (datacenter IPs blocked — verified). CF edge hit: HKG.
- **Anti-embedding:** sandboxed iframes blocked; `flutter_inappwebview` explicitly blocked; `pt_raw_injected` tamper check; `isIframe`/referrer detection changes behavior (e.g., hides site chrome, `isParentMappletv` special-case for mappletv.uk parent).

---

## 5. Recommended approach for the native Android app

1. **Catalog:** don't reverse Mapple for listings — it's a TMDB mirror. Use TMDB API directly (same data, official key) for trending/detail/search, storing TMDB ids.
2. **Playback (lowest risk):** embed `https://mapple.fun/watch/{movie|tv}/{id}[-{s}-{e}]?...` in an Android **WebView** (NOT `flutter_inappwebview` — explicitly blocked; avoid `sandbox`-equivalent settings), with:
   - a JS bridge listening for `postMessage` (`PLAYER_EVENT`, `MEDIA_DATA`, `WATCH_PARTY`) — validate `origin === "https://mapple.fun"`;
   - `MEDIA_DATA.progress.watched` → persist → relaunch with `?startAt=`;
   - `onCreateWindow`/popup suppression to neutralize the popunder ads (`llvpn`, `urbanfungose`, `rapidadexchange`);
   - `allow="encrypted-media"`, fullscreen support, `playsinline`.
3. **Playback (native ExoPlayer, unsupported path):** replicate §2.4 (session token → PoW → encrypt → stream_url) in Kotlin. Feasible but you must (a) pass Cloudflare with browser-grade TLS fingerprint + cookies, (b) implement the PoW solver (SHA-256 style nonce search — algorithm in `/pow-worker.js`, fetchable from mapple.fun), (c) handle `/client-stream/*` fragment proxying, and (d) accept breakage whenever Mapple rotates the scheme. Cache stream tokens ≤ 100 s and respect 429 `Retry-After`.
4. **Auth/accounts (optional):** Appwrite SDK (`sgp.cloud.appwrite.io/v1`, project `69357f4300029e916885`) supports email/password; Mapple wraps it with `/api/action-session` for server actions.

---

## 6. Open questions / gotchas

- **IMDb IDs:** docs endpoints page says "{id}: Movie identifier from IMDB or TMDB", but the FAQ says "Use the numeric TMDB ID". Unverifiable live (blocked IP). The app itself only ever uses TMDB ids (`tmdbId` in events, `/api/media-session` `mediaId`). Assume TMDB numeric; convert IMDb→TMDB via TMDB's `/find/{external_id}?external_source=imdb_id`.
- Exact shape of the encrypted URL from `/api/encrypt` (path/signature format) — server-generated, not in client bundle.
- PoW hash algorithm — lives in `/pow-worker.js` (fetch from a clean IP to inspect).
- 4K variant bitrates — need a live master playlist parse.
- `tt0133093` (The Matrix) test requested: TMDB equivalent is `604`… actually The Matrix = TMDB `603`; Matrix Reloaded (the saved page) = `604`. Use `603`/`604` when testing.
- The "6e4b9c2d" 656 KB asset is an **obfuscated ad/anti-adblock script**, not player code — don't waste time on it.
- Cloudflare managed content signals in `robots.txt` (search: yes for most, ai-input/ai-train: no) — scraping/mirroring caveats apply.

---

## Appendix A — key chunk map (Mapple_files/)

| File | Content |
|---|---|
| `Movie _ Mapple.html` | Saved `/watch/movie/604?autoPlay=true` page (RSC payload decoded → `rsc-payload.txt`) |
| `21bneb2761j_v.js.download` | **Playback auth core**: `/api/media-session`, PoW, `/api/encrypt`, `createEncryptedApiUrl`, `fetchStreamUrl`, `fetchFirstAvailableStream`, source race/hedge, integrity checks, rate-limit classes |
| `0cqkqmin8jetg.js.download` | **Player UI**: hls.js wiring + custom fragment loader (`/client-stream/`), quality levels, thumbnails (`/thumb/*`), intro segments, source selector, error/retry ladder |
| `3rk4jcrdlf9j8.js.download` | `PlayerProvider`: query params, subtitle fetch/upload/SRT parse, profanity filter, next-episode, watch-party hooks |
| `2jm6_0979cnb1.js.download` | **Source catalog (gods)**, legacy `VideoAPIs` upstream table, turnstile-config hook, Appwrite collections |
| `2oiguqhm96vqj.js.download` | hls.js 1.x (unmodified except loader override from player chunk) |
| `0ix-55m1wg4q9.js.download` | TMDB client: `/api/tmdb-key`, `/api/tmdb/*` + `x-api-key` |
| `0xgm3m5-lpx2_.js.download` | Appwrite web SDK |
| `3f6yz3s5fjhra.js.download` | Auth UI: login/register, `/api/verify-turnstile`, `/api/register-rate-limit`, `/api/action-session`, iframe/sandbox detection |
| `2k4q84-kbgzbv.js.download` | Watch-party socket.io client (`/api/socket`) |
| `1l3yf4dks5nck.js.download` | Home slider (`/api/home-slider`) + provider rows (netflix/disney/… via TMDB discover) |
| `13z-vmnqkg9g4.js.download` / `3ln063635jx6m.js.download` | Detail pages, TMDB hooks (trending/search/discover/season), `/api/anime| manga| novels` |
| `6e4b9c2d` | Obfuscated ad script (popunder) |

## Appendix B — artifacts saved

- `mapple-live/docs-*.html` — 6 docs pages + landing (raw HTML, 200 OK)
- `mapple-live/{endpoints,implementation,customization,events,watch-party,introduction,home}-extracted.txt` — extracted text
- `mapple-live/rsc-payload.txt` — decoded Next.js RSC payload of the saved player page
- `mapple-live/headers-session.txt`, `session.json`, `jina-test.txt`, `site-home.html`, `robots.txt` — live probe artifacts
- This file: `/home/z/my-project/work/findings-mapple.md`
