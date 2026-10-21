# PROGRESS (per plan §12: gates recorded here, append-only)
## Gate 0 — PASSED (build-time: phase 0)
- [x] JDK 21 installed; Gradle wrapper 8.14.4 generated
- [x] Project scaffolded at /home/smith/CLT/cloud-stream-web (submodule upstream@master)
- [x] `./gradlew :server:installDist` builds; `:plugin-runtime:test` passes both
      UpstreamLibraryResolutionTest tests (artifact `com.github.recloudstream.cloudstream:library-jvm:4.8.0`
      resolves without Android SDK; BasePlugin/Manifest/CloudstreamPlugin/MainAPI/APIHolder classload on JVM;
      jackson parses plugin manifest format as the loader will)
- [x] `/api/health` → {"ok":true,"libraryAvailable":"loaded(Manifest,BasePlugin,CloudstreamPlugin,MainAPI,APIHolder)"}
- [x] web/dist built (Vite+React); SPA served at localhost:8080 with sidebar;
      browser shows "● connected — upstream plugin API: loaded(...)"
- Notes for implementers:
  - Kotlin nested-comment gotcha: `/api/*` inside a KDoc opens an unterminated comment. Avoid literal `/*` inside docs.
  - The library artifact's POM needs Google's maven repo (androidx.annotation). Already wired in settings.
  - Ktor 3: inside an existing `Routing`, call builders directly (`routing.staticFiles(...)`), not `routing { }`.

## Gate 1 — PASSED (phase 1: repo catalog & manifest machinery)
- [x] Compile: `:server:compileKotlin :plugin-runtime:compileKotlin` green; 18 unit tests green
      (RepoUrlTest 11, RepoFetcherTest 5 w/ stubbed Http, UpstreamLibraryResolutionTest 2)
- [x] `GET /api/catalog` <= upstream repos-db.json; verified entries sorted first
- [x] `POST /api/repos` accepts full URL and `cloudstreamrepo://` wrapped input; parses real
      upstream manifest (`recloudstream/extensions` → "Cloudstream providers repository")
- [x] `GET /api/repos` lists DB rows; `DELETE /api/repos?url=…` removes (204)
- [x] `GET /api/repos/{b64}/plugins` returns real SitePlugin list (5 official plugins:
      Dailymotion/InternetArchive/Invidious/Twitch/Youtube) with status/version/tvTypes parsed
- [x] Error contract per docs/api.md: REPO_INVALID(400), REPO_PARSE_FAILED(502), bad b64(400)
- [x] Repos page live in the SPA: add/remove repos, upstream catalog with Add buttons,
      per-repo plugin browse table (verified visually in browser)
- Ktor 3 gotchas logged: reified `call.respond(T)` needs explicit
  `import io.ktor.server.response.respond`; cs.repo URLs pass through unchanged
  (redirector domain); RepoUrl deviation: surviving `cloudstreamrepo://` prefix is
  stripped in resolve() so results are always fetchable

## Gate 2 — PASSED (phase 2: extension install & load)
- [x] Full pipeline live: POST /api/plugins/install -> DOWNLOAD -> VERIFY (sha256 vs
      plugins.json fileHash) -> EXTRACT -> CONVERT (enjarify) -> LOAD -> DONE job model
- [x] Five real official .cs3 plugins loaded on JVM: YoutubeProvider, TwitchProvider,
      DailymotionProvider, InternetArchiveProvider, InvidiousProvider — 0 missing symbols
      (stub-inventory.md updated with runtime table)
- [x] /api/providers lists registered providers w/ sourcePlugin; hasMainPage MainAPI +
      ExtractorApi registrations both observed
- [x] Uninstall: beforeUnload + provider/extractor removal + loader close (verified live)
- [x] Restart restore: restoreAll() reloads installed plugins from converted-jar cache
      without re-download (verified live; Twitch re-registered after restart)
- [x] UI: Install/Remove buttons with live job progress (stage+% via polling), installed
      badge comes from /api/repos/{b64}/plugins join with DB installed state
- Notes: server compile needs libs.cs.library declared (implementation transitivity);
  dexes.add not += inside when branch (List.plus fallback trap)

## Gate 3 — PASSED (phase 3: content + streams + playback)
- [x] NewPipeExtractor global downloader bridge (plugin-runtime/NewPipeBridge.kt; okhttp
      Downloader implements `execute`) — fixes YoutubeProvider "downloader is null";
      YouTube search 13-16 items, detail 143-ep channel lists resolved
- [x] dex2jar replaces enjarify as primary converter (preserves annotations:
      @JsonProperty data classes in community dexes parse -> Dailymotion/IA search back)
- [x] StatusPages error contract + [provider-error] stderr logging (typed 400/404/502)
- [x] Per-provider `mainUrl` override on home/search/detail/streams (upstream instance
      parity; Invidious escape hatch — search proved 20 items on invidious.f5.si but
      /api/v1/videos disabled on every healthy public instance today -> instance-dependent)
- [x] StreamProxy GET /api/stream: header replay (base64url JSON `h`), Range passthrough,
      identity encoding, m3u8 full recursive rewrite (keys/segments/variants)
- [x] scripts/provider_matrix.py — home/search/detail/streams/playability per provider;
      multi-candidate probe (first media content-type wins)
- [x] Community plugins load: Plugin/CloudStreamApp/DataStore/AuthAPI/SyncAPI/SyncRepo/
      AccountManager/SimklApi/CloudflareKiller/MainActivity/Event/Uri headless stubs
      (evidence-driven member sets from javap of dex2jar-converged CSX classes) —
      CineStream v487 load() full chain: 3 providers + 27 extractors registered;
      Bollyflix, MoviesDrive, Moviesmod, VegaMovies, MegaRepo MegaProvider all DONE
- [x] Result matrix 2026-10-22 (14 providers/10 plugins loaded):
      playable end-to-end through proxy: Dailymotion, Internet Archive, Twitch, YouTube,
      BollyFlix, CineStream, CineSimkl*, CineTmdb, MoviesDrive, Rogmovies
      (*run-to-run variance on CineSimkl/IA — upstream sites rotate; documented)
      not streamable today, source-side: Invidious (instance API disabled upstream),
      Moviesmod/TopMovies/VegaMovies home (their mirrors dead/blocked today)
- [x] Player UI: Browse (provider home rows), Search, Detail + episode strip, inline
      <video> player — hls.js for M3U8 via playlist-rewriting proxy, native for VIDEO/FILE,
      automatic candidate advance on media errors; verified in browser: BollyFlix movie
      (R2 mp4 via proxy, currentTime advancing 30s+, 9655s duration) and Twitch live
      (hls.js blob stream through rewritten m3u8 playlist, currentTime advancing 21s+)

## Gate 4 — PASSED (phase 4: library parity — bookmarks / history / downloads)
- [x] Library schema in SQLite: `bookmarks`, `history(provider,url,episode_data,position,
      duration,finished)`, `downloads(state DOWNLOADED queue table)` — WAL, single-connection
      Db (plugin-runtime/.../persistence/Db.kt, ~all library helpers)
- [x] LibraryRoutes: bookmarks CRUD + isBookmarked flag; history upsert/list/entry/delete
      (+ delete-all); `/api/library/continue` = active history (finished=0 AND position>5);
      POST /api/library/history auto-finishes when position >= duration-45 (player messaging
      compatible with upstream semantics)
- [x] DownloadManager: disk queue under <dataDir>/Downloads — per-~2% progress persisted,
      HTTP Range resume, per-row cancel → PAUSED (partial file kept), delete unlinks row+file;
      concurrency semaphore(2); recorded `file_path` at first RUNNING → resume determinism
      (append only when target file exists AND a range was honoured, else clean restart);
      filename decided once: link-URL ext → name ext → SPA `extHint` → content-type → bin
- [x] Boot restore hook in Application.kt: RUNNING/QUEUED rows after restart → PAUSED
      ("interrupted by server restart") — verified live after an actual restart mid-download
- [x] Subtitles surfaced: /api/streams collects SubtitleFile via subtitleCallback →
      `subtitles[{lang,url,headers}]`; /api/subtitle proxy fetches w/ provider headers and
      converts SRT→WebVTT (WEBVTT header + comma→dot ms, BOM strip; VTT passthrough)
- [x] SPA: api.ts library/download/proxy helpers; Player meta + throttled (~10s) progress
      reporting + pause/end/unmount saves + saved-position seek (controller handles
      cache-hit/late-resume races; only when fresh start & pos<duration-30) + subtitle
      <track> mounting + "⬇ download" button on the playing link; DetailContent bookmark
      toggle, `?data=` deep link (Continue-Watching invocations), PlayerMeta resolution;
      LibraryPage: Continue Watching grid (poster, % bar, resume link, remove), Bookmarks
      grid, History list w/ clear-all, Downloads panel (1.8s poll, pause/resume/discard)
- [x] Verified live in browser (BollyFlix "The Silent Planet", 1080p r2 .mkv through proxy):
      watch 26s → history row recorded at exact position; navigate-away flush; continue
      card → deep link → seek to saved pos (20.65s) confirmed via currentTime=24.16s after
      3.5s of autoplay; bookmark ☆→★ roundtrip reflected in /api/library/bookmarks; player
      ⬇ → RUNNING → UI pause → PAUSED@100MB → resume → DONE@2.33GB in Downloads panel
- [x] Byte-accuracy: 30MB test file MD5 identical after cancel@16MB + Range resume
      (29dcadf0839ad70e6a687d700ad112f5 both files); SRT→VTT conversion verified over HTTP
- [x] Tests: server DbLibraryTest (bookmark/history/download lifecycle + isolated
      test-data dir via csweb.dataDir), LibraryRoutesHttpTest (typed 400/404/502 contract
      via installApiContract() shared with main(), auto-finish margin, download queue
      endpoints), VttConversionTest; `./gradlew test` BUILD SUCCESSFUL
- Notes: installApiContract() extracted so tests exercise the same StatusPages contract;
  RuntimeConfig now prefers `csweb.dataDir` system property (env fallback untouched)
