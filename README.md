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

## LAN mode, token, and systemd (Phase 5)

The server binds `127.0.0.1` unless `CS_WEB_LAN=1`; with LAN exposure you
should also set a shared token:

```bash
CS_WEB_LAN=1 CS_WEB_LAN_TOKEN="h0m3lan" scripts/run.sh
```

- every `/api` route then answers 401 without the token (only `/api/health`
  and the static SPA shell stay open)
- the SPA prompts once for the token, stores it in `localStorage`, and sends
  `Authorization: Bearer`; media sources (`/api/stream`, `/api/subtitle`)
  authenticate via a `token` query parameter
- probes (`/api/health`) and the shell itself never need the token

Packaging: `scripts/package.sh` produces `build/csweb.tar.gz` (prebuilt
server + SPA + launcher + unit file). To run it as a user service:

```bash
./scripts/package.sh && mkdir -p ~/opt && tar -xzf build/csweb.tar.gz -C ~/opt
cp ~/opt/csweb/cloudstream-web.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now cloudstream-web
journalctl --user -u cloudstream-web -f
```

`CS_WEB_DATA_DIR` relocates the data root (default `~/.cloudstream-web`:
SQLite + extensions + downloads); downloads interrupted by a restart resume
from the stored byte offset as `PAUSED`.

Status (see docs/PROGRESS.md for gates):

- [x] Phase 0 — scaffold, health endpoint, SPA shell, upstream artifact on JVM (Gate 0 = this check)
- [x] Phase 1 — repo catalog & manifest machinery
- [x] Phase 2 — extension install & load (.cs3 on desktop JVM)
- [x] Phase 3 — providers live: home/search/detail/streams
- [x] Phase 4 — library parity (bookmarks/history/downloads)
- [x] Phase 5 — hardening & packaging
