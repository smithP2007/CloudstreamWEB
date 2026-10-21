# cloud-stream-web

A self-hosted PC version of [CloudStream](https://github.com/recloudstream/cloudstream):
JVM backend + browser UI that can install real CloudStream repositories
(`.cs3` extensions) and run their providers exactly like the Android app.

**Full implementation plan:** `/home/smith/.opencode/plan/cloudstream-web-implementation.md`
Docs in `docs/` (DECISIONS, api, stub-inventory, LEGAL).

## Quick start

```bash
scripts/bootstrap.sh      # one-time: toolchain + npm deps
scripts/run.sh            # builds web + starts backend on http://localhost:8080
```

Status (see docs/PROGRESS.md for gates):

- [x] Phase 0 — scaffold, health endpoint, SPA shell, upstream artifact on JVM (Gate 0 = this check)
- [x] Phase 1 — repo catalog & manifest machinery
- [x] Phase 2 — extension install & load (.cs3 on desktop JVM)
- [x] Phase 3 — providers live: home/search/detail/streams
- [x] Phase 4 — library parity (bookmarks/history/downloads)
- [ ] Phase 5 — hardening & packaging
