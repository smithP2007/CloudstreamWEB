# CloudStream Web

**Run real [CloudStream](https://github.com/recloudstream/cloudstream) extensions on your own machine, in your browser.**

A self-hosted PC/web port of the Android app: a JVM backend that installs actual
CloudStream `.cs3` repositories and executes their providers, plus a React SPA
that gives you search, detail pages, video playback, subtitles, watch history
and a download queue — all through the same extension code Android would run.

> ❗ **You bring your own repositories.** No extension repositories ship with this
> product; you install the `.cs3` repos yourself from the Repositories page, and
> you are responsible for what you run with them.

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

---

## ✨ What works

| Area | Details |
| --- | --- |
| 🧩 **Real `.cs3` extensions** | Install any CloudStream repository by URL or shortcode (`cloudstreamrepo://`, full URL, cutt.ly/py.md shortcodes); plugins are converted from dex to JVM bytecode and loaded headless |
| 🔍 **Providers live** | Home page, search, detail, and per-episode stream extraction exactly like the app's provider API |
| 🎬 **Playback with header relay** | Every link streams through a server-side proxy that replays the extractor's exact headers (Referer/UA/cookies) and rewrites HLS manifests (`m3u8`) and nested signed segments; hls.js and native `<video>` share the pipeline |
| 🔄 **Source changer** | Every candidate source is switchable mid-play from the controls (`i/N · extractor · quality` plus ◀/▶ steps), with the playhead carried over and auto-advance skipped on dead links |
| 🎧 **Audio-track selection** | HLS manifests list every `EXT-X-MEDIA` audio rendition (e.g. dual-audio Hindi/English) and the picker switches them live; direct files use the element's `audioTracks` where the browser exposes it |
| 💬 **Subtitles** | Auto-loads every provider subtitle as a proxied SRT→WebVTT track (hundreds of cues), with an in-player picker and per-input text sync |
| ⌨️ **Player shortcuts** | space play/pause, ←/→ ±5 s, PgUp/PgDn ±30 s, `m` mute, `f` fullscreen |
| 📚 **Library** | Bookmarks, continue-watching history with resume positions, and a download queue with pause/resume from byte offset, cancel/delete and cheap HEAD size probes so progress shows real percentages |
| ⚙️ **Settings page** | Backend health, LAN-token management, library counts + clear-histories, live provider overview |
| 🔔 **Toasts** | Failures surface through an in-app error pipeline instead of dying silently |
| 🔐 **LAN mode** | `CS_WEB_LAN=1` exposes the server off-localhost; `CS_WEB_LAN_TOKEN` then 401-guards every `/api` route (health and the SPA shell stay open) |

## 🚀 Quick start

Prerequisites: JDK 21+ (JVM), Node ≥ 18.

```bash
# one-time: fetch toolchain, npm deps and the upstream submodule
scripts/bootstrap.sh

# build the SPA and start the backend
scripts/run.sh        # → http://localhost:8080
```

`upstream/` is a submodule pin of the real
[recloudstream/cloudstream](https://github.com/recloudstream/cloudstream)
library; the backend embeds its embedded-runtime artifact on the JVM while
`plugin-runtime/` supplies the desktop stand-ins for Android-specific classes
(full inventory in [`docs/stub-inventory.md`](docs/stub-inventory.md)).

## ⚙️ Configuration

| Variable | Meaning |
| --- | --- |
| `CS_WEB_LAN=1` | Bind to `0.0.0.0` instead of `127.0.0.1` |
| `CS_WEB_LAN_TOKEN=<token>` | Require `Bearer` token on `/api/*` (media routes via `?token=`); `/api/health` stays open |
| `CS_WEB_DATA_DIR=<path>` | Data root; default `~/.cloudstream-web` (SQLite DB, installed `.cs3` extensions, `Downloads/`) |
| `csweb.dataDir` | Same as above, as a system property (higher precedence) |

Packaging: `scripts/package.sh` emits `build/csweb.tar.gz` — prebuilt server,
SPA, launcher and a `cloudstream-web.service` user unit:

```bash
scripts/package.sh && mkdir -p ~/opt && tar -xzf build/csweb.tar.gz -C ~/opt
cp ~/opt/csweb/cloudstream-web.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now cloudstream-web
journalctl --user -u cloudstream-web -f
```

Downloads interrupted by a restart come back as `PAUSED` and resume from their
stored byte offset on the next run.

## 🧩 How playback works

```
SPA (React)          Ktor server (JVM)                extension (.cs3)
────────────────     ────────────────────────         ────────────────
search/home/detail → /api/{search,home,detail} → CloudStreamProvider
streams           → /api/streams              → provider.loadLinks()
                      network.DataStore prefs ↔ SQLite  (settings survive restarts)
video submission  → /api/stream?…             → headers replayed, Range forwarded,
                      manifest rewritten (all nested
                      segment/rendition URLs proxied)
                      ├─ M3U8 → hls.js   (+ audio-track picker)
                      └─ direct → <video> (+ positional Range)
progress          → /api/library/history      → resume on next open
queue             → /api/downloads            → okhttp file sink with HEAD probe
```

Stub engineering notes live in [`docs/stub-inventory.md`](docs/stub-inventory.md),
decision log in [`docs/DECISIONS.md`](docs/DECISIONS.md), and the complete REST
contract the SPA talks in [`docs/api.md`](docs/api.md).

## ⚠️ Known limitations

- In-browser playback covers what extensions can extract headlessly; extractors
  that need a signed WebView session still fail (typed, never silent) — switch
  sources with the ◀/▶/dropdown when one mirror is dead.
- Sync providers (Simkl/Trakt) are stubbed to headless no-ops; `SyncRepo.library`
  is intentionally unavailable on this JVM surface.
- Android WebView-only widgets (Captcha crackers) have no desktop equivalent;
  their plugins keep working for search/details but may not play.

## 🛠 Development

```bash
./gradlew test                     # JVM + SQLite + routing tests (JUnit 5)
./gradlew :server:installDist      # Kotlin server distributable
cd web && npm run build            # React SPA into server's static dir
scripts/provider_matrix.py <q>     # probe every installed provider's home/search/streams
```

Docs for the plan gates and verification records are in
[`docs/PROGRESS.md`](docs/PROGRESS.md).

## License

GPL-3.0 — see [`LICENSE`](LICENSE).
Names and branding of CloudStream belong to the upstream project; this port is
unaffiliated and exists for interoperability with your own extension choices.
Content availability depends entirely on the repositories you install.
