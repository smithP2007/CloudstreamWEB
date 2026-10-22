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


