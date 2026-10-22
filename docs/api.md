# REST API (V1)

Canonical contract; server implementation should mirror this file.
(Phase 0 implements only `/api/health` and static SPA serving.)

## Health
`GET /api/health` → `{ok: boolean, version: string, libraryAvailable: string}`

## Repos & plugins (Phase 1-2)
```
GET    /api/catalog                       → [{url, verified, name, description?}]  (upstream repos-db.json, 5-min cache)
POST   /api/repos    {input}              → {url, name, description, manifestVersion}  (accepts URL / cloudstreamrepo:// / shortcode)
GET    /api/repos                         → [RepositoryData + counts + enabled]
DELETE /api/repos?url=…                   → removes repo + plugins + data
GET    /api/repos/:b64/plugins            → [SitePlugin + installed/status]
POST   /api/plugins/install {repositoryUrl, internalName} → {jobId}
GET    /api/plugins/install/:jobId        → {stage, progress, error?}
POST   /api/plugins/uninstall {internalName}
POST   /api/plugins/update {internalName}
GET    /api/providers                     → active providers
```

## Content (Phase 3)
```
GET /api/search?q=&types=&provider=
GET /api/detail?provider=&url=
GET /api/streams?provider=&data=
     → {provider, data,
        links: [{name, url, source, quality, headers, linkType}],
        subtitles: [{lang, url, headers}]}   (subtitles optional; proxy via /api/subtitle)
GET /api/stream?url=&h=                  → proxied bytes (h = base64url JSON headers; Range supported; m3u8 rewritten)
GET /api/subtitle?url=&h=                → WebVTT (SRT converted on the fly)
```

Every content route accepts `mainUrl=` to override the provider instance.

## Auth (Phase 5, optional)

With `CS_WEB_LAN_TOKEN` set, every `/api` route except `/api/health`
requires `Authorization: Bearer <token>` **or** `?token=<token>` (the query
form is for `<video>`/`<track>` sources that cannot send headers). The SPA
prompts once and re-tries with the stored token. Error shape:
`401 {"error":"UNAUTHORIZED"}`.

## Library (Phase 4)

SQLite-backed; lives in the user's data dir. Poster/type names mirror the
server DTOs (camelCase JSON).

```
GET    /api/library/bookmarks                 → [BookmarkDto]   (newest first)
POST   /api/library/bookmarks                 {provider,url,name,poster?,type?,sourcePlugin?} → upsert
GET    /api/library/bookmark?provider=&url=   → {bookmarked: boolean}
DELETE /api/library/bookmarks?provider=&url=  → 204

GET    /api/library/history?active=0|1&limit=  → [HistoryDto]
GET    /api/library/history/entry?provider=&url=&data= → HistoryDto | 404 ENTRY_NOT_FOUND
POST   /api/library/history                   {provider,url,episodeData,name,episodeLabel?,poster?,position,duration?,finished?}
                                              — auto-finishes when position >= duration-45
DELETE /api/library/history?provider=&url=&data=  (data optional = whole item)
DELETE /api/library/history                   (clear all) → 204
GET    /api/library/continue?limit=           → [HistoryDto] active only (position > 5, not finished)
```

## Downloads (Phase 4)

```
GET    /api/downloads                     → [DownloadDto]  (id, state QUEUED|RUNNING|PAUSED|DONE|FAILED,
                                                             downloadedBytes, totalBytes?, detail?, filePath?)
POST   /api/downloads                     {provider?, url?, name, link:{url, headers?}, extHint?}
                                           → 201 DownloadDto — streams to <dataDir>/Downloads with
                                             Range resume + cancellation; headers replayed like the proxy
POST   /api/downloads/:id/resume          → DownloadDto (fails 400 if DONE)
POST   /api/downloads/:id/cancel          → DownloadDto (partial file kept for resume)
DELETE /api/downloads/:id                 → 204 (row + file)
```

Rows found RUNNING/QUEUED after a server restart are marked `PAUSED`
("interrupted by server restart") at boot; resume continues from the stored
byte offset when the CDN honours ranges.

Error shape: `{error: "HASH_MISMATCH" | "ENTRY_NOT_FOUND" | "REPO_PARSE_FAILED" | ... , detail?}`

## Error codes + request ids (v2 M0)

Every failure response is `{error: <code>, detail?}`; every `/api` response
carries an `X-Req-Id` header (8 hex chars) and the server logs the same id on
each `[api-error]` line (`[api-error] req=<id> path=<path> code=<code>
msg=...`), so a browser failure maps 1:1 onto the server log. The code ->
HTTP-status mapping is central in `server/src/main/kotlin/cloudstreamweb/errors/ErrorCodes.kt`;
unknown codes fail closed to 502.

| code | status | meaning |
|---|---|---|
| `UNAUTHORIZED` | 401 | LAN token missing/invalid |
| `REPO_INVALID` | 400 | malformed request (missing url, bad header blob, ...) |
| `PROVIDER_NOT_FOUND` / `ENTRY_NOT_FOUND` | 404 | no such provider / library entry |
| `PROVIDER_ERROR` | 502 | extension or upstream extraction failure (incl. hoster 4xx) |
| `UPSTREAM_403` | 502 | hoster refuses this request right now (v2 M3+) |
| `URL_EXPIRED` | 410 | signed link past its TTL; SPA re-extracts and retries |
| `PROBE_TIMEOUT` | 504 | candidate probe gave up (v2 M4+) |
| `CODEC_UNSUPPORTED` | 422 | stream the browser cannot decode (v2 M4+) |
| `FFMPEG_MISSING` / `GPU_ACCEL_UNAVAILABLE` | 501 | remux/transcode requested but capability absent (v2 M3+/M5) |
| internal failures | 500 | unclassified throwable, full stack in the server log |

Fixture policy for the offline test rig: synthetic text fixtures (playlists,
subtitles) are committed under `server/src/test/resources/fixtures/`;
recorded real-media fragments are NOT committed (public repo, upstream
copyright). A local dev-only mkv fixture can be plugged in via system/
environment property when decode-truth tests arrive (v2 M4).

## Capabilities + settings (v2 M2)

```
GET  /api/capabilities → CapabilitiesDto
GET  /api/settings     → SettingsDto
POST /api/settings     → partial patch → FULL updated SettingsDto (400 REPO_INVALID on bad values)
```

`CapabilitiesDto` = host truth behind the SPA's grayed-out toggles:

```json
{
  "ffmpegAvailable": true, "ffmpegVersion": "7.0.2-static", "ffmpegPath": "tools/ffmpeg/bin/ffmpeg",
  "ffprobeAvailable": true, "ffprobeVersion": "7.0.2-static", "ffprobePath": "tools/ffmpeg/bin/ffprobe",
  "gpuAccelerators": ["vaapi", "nvdec", "nvenc"],
  "audioRemuxAvailable": true, "liveTranscodeAvailable": true, "decodeProbeAvailable": true
}
```

ffmpeg/ffprobe resolution precedence: `CS_WEB_FFMPEG` env (binary or
directory) → `tools/ffmpeg/bin/` (repo root; produced by `scripts/bootstrap.sh`
and gitignored) → the same path under the data dir → `PATH`. Missing binaries
never throw — the DTO just reports `false` and the SPA disables the dependent
toggles.

`SettingsDto` = the five playback toggles (every playback feature is
switchable here; features read them per-request, no restart):

| field | default | meaning |
|---|---|---|
| `audioRemux` | `true` | M3: language selector for muxed multi-audio files (remuxes MKV → MP4 with `-c copy`) |
| `probeSources` | `true` | M4: ffprobe + browser MediaCapabilities truth pass behind the decode badges |
| `liveTranscode` | `false` | M5: transcode undecodable streams while playing |
| `gpuAccel` | `"auto"` | M5: `auto`/`on`/`off` hardware-acceleration mode |
| `convertAfterDownload` | `false` | M5: transcode-once cache after each download |

POST bodies may include any subset (`{"gpuAccel":"on"}`); `gpuAccel` outside
`auto|on|off` → `400 {error:"REPO_INVALID"}` without persisting anything.

## Audio language switching (v2 M3)

Muxed multi-audio files (Hindi/English/Korean dubs in one MKV) get a language
selector in Chromium, which cannot even play the Matroska container without
help. Two mechanisms, both through the same engine path so **probe truth ==
playback truth** (ffprobe/ffmpeg read the bytes through `GET /api/stream`
itself, via a loopback self-connection with `raw=1`).

```
GET /api/stream/audio-tracks?url=<upstream>&h=<b64-headers>
   → [{ordinal, streamIndex, lang, title, codec, channels, isDefault}, ...]
```

- Probed with ffprobe from the engine self-proxy input; cached per linkId
  (`dataDir/cache/probe/<linkId>.json`, TTL 6 h). `501 FFMPEG_MISSING` when
  ffprobe is absent, `504 PROBE_TIMEOUT` when the probe fails.
- ordinals are the audio-stream order (0 = first) — also the `?audio=` value.

```
GET /api/stream?url=<upstream>&h=<b64>&[audio=<ordinal>][&start=<sec>][&raw=1]
```

Branches for a direct file (in decision order):

| condition | behavior |
|---|---|
| `raw=1` | byte passthrough of the ORIGINAL container (self-connections, tests) |
| `audio=<ordinal>` present | remux `-c copy` → fragmented MP4 (`-map 0:v:0 -map 0:a:<ordinal>`); `501 FFMPEG_MISSING` if ffmpeg absent |
| mkv container + `audioRemux` toggle on | DEFAULT remux with the mux's default audio (`isDefault` track, else first) — this is what makes Chromium play muxed MKV at all |
| otherwise | byte passthrough (headers + Range replayed 1:1, as in M1) |
| m3u8 (content type or extension) | nested-URL playlist rewrite, no remux |

Remux output: `video/mp4` with `empty_moov+frag_keyframe+default_base_moof`
(streamable fragmented MP4; Chromium plays it natively). `&start=<sec>`
adds `-ss <sec>` before the input (fast mv/cue-based seek) so a language
switch can resume at the captured playhead — the fresh stream's timeline
starts at that point (the SPA pins its resume-seek to 0 on switch).

Upstream failure classification (both stream and subtitle routes):
`403 → UPSTREAM_403 (502)`, `410 → URL_EXPIRED (410)`, else `PROVIDER_ERROR
(502)` — the SPA reacts to the first two by silently re-running the extractor
once for the same source and swapping in the fresh URL (a manual `↺ refresh`
button bypasses the once-guard).

Notes:
- `-c copy` only fixes the CONTAINER. Files whose codec the browser cannot
  decode at all (H.264 High10 in the Hindmoviez 10bit mirrors) need the M5
  live-transcode path.
- The remux keeps the container's chapter track (mp4 `text` data stream) —
  tiny and ignored by browsers. Subtitle streams are intentionally dropped.

## Decode truth: probe + badges + ranking (v2 M4)

```
GET /api/stream/probe?url=<upstream>&h=<b64-headers>
   → LinkProbeDto {container, formatName, durationSec, bitRate,
                   video:{codec,profile,width,height,bitDepth,hdr,fps},
                   audio:[AudioTrackDto], subtitlesCount, streamed}
```

- Full `ffprobe -show_streams -show_format` through the same `raw=1`
  self-proxy (probe truth == playback truth), cached per linkId as
  `<linkId>.probe.json` (TTL 6h — separate file from the audio cache).
- Video facts come from the first video stream: `pix_fmt yuv420p10le` →
  10-bit; `color_transfer smpte2084` / `arib-std-b67` / bt2020 primaries →
  HDR; `avg_frame_rate 24000/1001` → 23.976 fps. `container` is the
  ffprobe format family (matroska → mkv, mov → mp4).
- **Probe budget is 45 s at process level** (`waitFor` + `destroyForcibly`):
  badges must never wait minutes for a stuck hoster — and the stdout/stderr
  drains run on daemon threads so a never-EOF-ing process cannot pin a
  request-handling thread (this deadlock was found live: it wedged ALL
  request processing until restart). The audio probe keeps the 300 s cap
  because it gates the default remux, not cosmetic truth.
- HLS links short-circuit to `{streamed:true}` (no spawn — manifest
  candidates are not the file-remux path).
- Typed failures: `501 FFMPEG_MISSING` (no ffprobe), `504 PROBE_TIMEOUT`
  (spawn failed/timeout — usually a dead mirror; that state is honest
  UNKNOWN, the SPA treats it as "no data", never as "bad codec").

SPA (`probeSources` toggle, default on — OFF disables every badge and the
reranking):
- Player fetches `/api/stream/probe` for every distinct FILE candidate
  (≤4 concurrent; the currently playing link first), each result ALSO fed
  through `MediaCapabilities.decodingInfo` (ffmpeg strings → codec
  strings: h264 → avc1.640034, hevc → hvc1…, vp9 → vp09…, av1 → av01…).
  Chromium software-decodes High10 H.264, so 10-bit h264 is still asked.
- Picker badges per candidate: `✓ res codec [bits] [HDR]` (supported +
  smooth), `△ …` (supported, software decode), `✗ …` (unsupported),
  `HLS` (manifest), `? no probe` (probe failed — unknown, not bad).
- **Playable-first reranking**: applied exactly ONCE per extraction, at
  whatever truth exists 25 s in (stragglers must not hold the ranking
  hostage); stable sort scores: smooth 0 < software-decode 1 < neutral
  unknown 4 < unsupported 5. The currently playing link keeps playing —
  its index is remapped by url identity, only the PICKER order changes.
- `bufferWatchdog` toggle (default on): after playback has started, a
  stall with ZERO buffered growth for ~10 s gets ONE cache-busted reload
  (`&cb=<n>`); still no growth ~40 s after that → the self-heal/advance
  path. Armed only after real playback started (hoster first-byte
  latency must not trip it).
- "What am I watching?" drawer under the controls: container/duration/
  bitrate face, codec profile + resolution + fps + bit-depth + HDR, the
  audio tracks with language names, subtitle count, and the browser's
  decode verdict in plain words — or an honest "probe failed"/"HLS"
  sentence when truth is unavailable.


