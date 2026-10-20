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
GET /api/streams?provider=&data=     → [StreamSource]
```

## Library (Phase 4)
```
GET/POST /api/library/*  (bookmarks, history, continue)
POST /api/downloads/*    (queue management)
```

Error shape: `{error: "HASH_MISMATCH" | "ENTRY_NOT_FOUND" | "REPO_PARSE_FAILED" | ... , detail?}`
