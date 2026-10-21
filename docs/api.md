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
