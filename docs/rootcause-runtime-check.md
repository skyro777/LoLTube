# Root cause of ERROR_CODE_FAILED_RUNTIME_CHECK (fixed in v0.6.0)

## What the user saw

Playback never started on any title, any quality, any of the three sources.
The on-screen cascade was: "Playback hiccup — reconnecting…" → "This source is
stalling — switching to {source}…" (all sources) → terminal
`ERROR_CODE_FAILED_RUNTIME_CHECK`.

## The real error chain (from the v0.5.0 in-app log, log.txt)

```
ERROR_CODE_FAILED_RUNTIME_CHECK ::
ExoPlaybackException(Source error) <- HttpDataSourceException(Malformed URL)
  @ OkHttpDataSource.makeRequest:369 OkHttpDataSource.open:262
    TeeDataSource.open:54 CacheDataSource.openNextSource:802
    CacheDataSource.open:615 SafeDataSource.open:53
```

and, in fail-safe mode:

```
  @ OkHttpDataSource.makeRequest:369 OkHttpDataSource.open:262
    StatsDataSource.open:86 ProgressiveMediaPeriod$ExtractingLoadable.load:1045
    Loader$LoadTask.run:421
```

`ProgressiveMediaPeriod$ExtractingLoadable` is media3's SIDECAR/subtitle
sideload path (SingleSampleMediaSource), not HLS segment loading. That was the
thread to pull.

## Root cause

media3 1.4.1 `OkHttpDataSource.makeRequest` does:

```java
HttpUrl url = HttpUrl.parse(dataSpec.uri.toString());
if (url == null) {
  throw new HttpDataSourceException(
      "Malformed URL",
      dataSpec,
      PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,   // <- the "runtime check"
      TYPE_OPEN);
}
```

OkHttp's `HttpUrl.parse` only understands http/https. It returns **null** for
every other scheme — verified empirically:

| URI | `HttpUrl.parse` result |
|---|---|
| `https://moon.goldenkite.top/vd/.../master.m3u8?s=...` | OK |
| `https://greenkite.top/vd/.../seg-1-s1080p-v1-a1.m4s` | OK (all 1727 segments clean ASCII) |
| `file:///data/user/0/com.loltube.app/cache/subs.vtt` | **null → "Malformed URL"** |
| `content://...`, `/path-only`, `data:...` | **null** |

The app pre-attached the preferred subtitle (downloaded by `Wyzie.download`)
as `MediaItem.SubtitleConfiguration(android.net.Uri.fromFile(file))` — a
`file://` URI — before every first prepare. Sideloaded subtitles are fetched
by the very same `DataSource.Factory` that was handed to
`DefaultMediaSourceFactory`. That factory was **OkHttpDataSource.Factory
(wrapped in the cache stack)** with no scheme routing, so the player tried to
fetch the local subtitle file over "OkHttp" → `HttpUrl.parse(file://…)` →
null → `HttpDataSourceException("Malformed URL", ERROR_CODE_FAILED_RUNTIME_CHECK)`
→ the sideload failure is fatal during `MediaPeriod.prepare` → onPlayerError.

The recovery ladder then rebuilt the player with **the same subtitle still
attached** (full rebuild → step-down rebuild → fail-safe rebuild — all re-load
the same `file://` sidecar over OkHttp and die the same way), then switched
providers — the new provider's harvest supplied subtitles too, the same
failure happened on all three sources, and the cascade ended in the terminal
error the user saw.

This also explains, in one stroke:
- why playback failed **at every quality** (quality never mattered);
- why it failed on **all three sources** (subtitle sidecar attached on every resolve);
- why **subtitles never appeared** (the sideload always died);
- why the older "v0.2 always played" pipeline worked (it never attached subtitles);
- why the codec theory (crash-safe renderers, decoder caps, ladder) could never
  fix it (nothing was wrong with the codecs).

## The fix (v0.6.0)

1. **Scheme routing (the actual fix)** — `PlayerEngine.build` now wraps the
   pipeline in `DefaultDataSource.Factory(context, …)` before handing it to
   `DefaultMediaSourceFactory` (both normal and fail-safe mode):
   - `http/https` → OkHttp (through the disk-cache + SafeDataSource stack, as before);
   - `file://` → `FileDataSource` (subtitle sidecars now load — subtitles finally appear);
   - `content://` → `ContentDataSource`; other schemes → stock fallback.
2. **Site-native subtitles** — the subtitle listing now comes first from the
   provider page itself (harvested via the `/wyzie` request the page makes in
   `WebViewHarvester` — the exact tracks the site's own player offers) plus
   the stream's own manifest-embedded tracks. The separate subtitle API
   (`Wyzie.fetch`) is no longer called up-front; it stays only as a
   post-resolve fallback when the site's own listing is empty (e.g. mapple,
   whose subtitle endpoint is Cloudflare-protected — per research, mapple's
   own native path is in-player UI only).
3. **Insurance guard** — if a playback error's cause chain ever contains
   "Malformed URL" while a sidecar subtitle is attached, the sidecar is
   dropped and the player rebuilt without it — a sideload problem can never
   cascade into provider switching again.

## Why 4K-no-buffer is unaffected (and now actually reachable)

The TurboPrefetcher flywheel (4-worker parallel read-ahead into the 800 MB
disk cache), the adaptive ABR with fast down-switching, the crash-proof
renderer layer and the codec-aware quality ceiling are all unchanged — they
just never got a chance to run because playback died during preparation.
With preparation fixed, the pipeline engages: ladder probe → 4K pre-select
when decoders support it → segment flywheel → subtitle sidecars load locally
instead of killing the stream.
