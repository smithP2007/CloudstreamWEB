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

## Gate 5 — PASSED (phase 5: hardening & packaging)
- [x] LAN token (CS_WEB_LAN_TOKEN): installApiContract(lanToken) guard — /api answers
      401 {"error":"UNAUTHORIZED"} without the token; /api/health + static SPA shell stay
      open; Authorization: Bearer and ?token= both authenticate (constant-time compare);
      verified live (curl 401/200/401) + LanTokenAuthTest (header/query/missing paths)
- [x] SPA token UX: fetch layer attaches Bearer from localStorage + `?token=` on proxy
      URLs; any 401 fires csweb-auth → App token prompt overlay ("Save & retry" reloads
      with the token); browser-verified modulo: overlay appears → token → library queries OK
- [x] Typed error surfacing: web/src/lib/toast.ts toasts (error/info) wired into library
      remove/clear handlers, DownloadsPanel act() and Player createDownload — browser
      verified through the REAL handler path (toast error "Could not remove from
      Continue Watching: simulated 500 INTERNAL")
- [x] Graceful shutdown: SIGTERM hook stops Netty (1s grace/3s timeout) then closes the
      Db WALS connection; log shows "[shutdown] complete" (verified twice by restart)
      + Db reopens transparently on demand (conn getter, no lazy freeze)
- [x] Headless plugin prefs persist: FakeSharedPreferences write-through to new
      `prefs(key,value type-tagged)` SQLite table + lazy restore at first accessor;
      DbLibraryTest covers encode/decode roundtrip (String/Bool/Int/Set)
- [x] Packaging: scripts/package.sh builds build/csweb.tar.gz (39MB: prebuilt server +
      SPA + run-packaged.sh launcher + systemd user unit + API.md); package smoke-tested
      standalone on PORT=8099 + fresh CS_WEB_DATA_DIR (health 200, 401 guard, token 200,
      SPA shell 200, data dir auto-created, SIGTERM clean exit)
- [x] scripts/cloudstream-web.service (systemd user unit: Restart=on-failure, SIGTERM,
      EnvironmentFile for tokens) + README "LAN mode, token, and systemd" section
- [x] Provider matrix re-run (scripts/provider_matrix.py "news"): 8 providers fully
      playable through the /api/stream proxy (BollyFlix, CineStream, Dailymotion,
      Internet Archive, MoviesDrive, Rogmovies, Twitch, YouTube); upstream-side deadness
      unchanged (Invidious search 0 items, Moviesmod links=0, TopMovies/Vega home dead;
      CineSimkl/CineTmdb stream extract 502 upstream)
- [x] `./gradlew test` BUILD SUCCESSFUL (incl. new LanTokenAuthTest, prefs roundtrip)

## Post-plan polish (after Gate 5)
- [x] Player subtitle picker: shared SubtitlePicker in Controls ("subs off" + one
      option per track); modes applied immediately, and a 2-min restoration poll re-applies
      after hls.js re-attaches media (textTrack modes reset on source change — observed
      live). Browser-verified on CineStream "Love of Your Life" (35 tracks): selecting
      English shows 1681 converted WebVTT cues ("So, um, you made this?"), subs off clears;
      also verified with the synthetic-change lesson: dispatchEvent('change') needs
      bubbles: true to reach React's root listener (test only; real picks unaffected)
- [x] Player keyboard shortcuts (useKeyboardControls): space play/pause, arrows ±5s,
      PageUp/Down ±30s, m mute, f fullscreen; ignored while typing in inputs.
      Verified live (+5s, −30s, pause toggle)
- [x] Download size probe: HEAD request on first run fills total_bytes for chunked CDNs,
      guarded "AND total_bytes IS NULL" so real Content-Range totals win; Downloads UI now
      shows true percentages (r2 chunked 2500986645 B verified RUNNING)
- [x] Settings page replaces the placeholder: backend card (version/upstream API),
      LAN-token card (stored status, set/clear), library card with live counts + danger
      actions (clear all history / discard all downloads with confirm), providers table
      (name, plugin, tvTypes, hasMainPage) — rendered with 14 live providers
- [x] `./gradlew test` BUILD SUCCESSFUL (setDownloadTotal once-only covered)
- [x] **Source changer** (user-reported gap: no way to switch sources while one
      plays): Controls now renders a source select (one option per candidate:
      `i/N · extractor · quality`) plus ◀/▶ step buttons, wired to
      Player.setIndex; HLS candidates now display their true `index/total`
      (previously hardcoded 1/1). Position continuity on switch: the active
      playhead is mirrored into a shared ref (~2x/s, no re-renders) and handed
      to the next candidate as resume position — browser-verified on CineStream
      "Love of Your Life" (209 candidates): 3/209 VaPlayer 872p @694s →
      15/209 Voe 584p continuing at ~same timestamp, still playing, history
      row advancing (749/6678); ◀/▶ button path also verified (15→16).
- [x] `./gradlew test` BUILD SUCCESSFUL

## Audio-track selection (user-reported gap: default audio only)
- [x] HLS audio picker: `HlsAudioPicker` reads hls.js `audioTracks` (EXT-X-MEDIA
      renditions) once the manifest parses, hides itself when <2 tracks, and
      switching sets `hls.audioTrack`; AUDIO_TRACKS_UPDATED / AUDIO_TRACK_SWITCHED
      keep the select honest. Scan of the CineStream episode found real dual-audio
      playlists (VidHidePro #87, Voe #100/#104: हिन्दी hi + English en).
      Browser-verified: source switch to VidHidePro 400p → picker showed
      ["हिन्दी","English"]; default was हिन्दी (the user's complaint), switched to
      English (hls confirmed the rendition switch via AUDIO_TRACK_SWITCHED; buffer
      kept growing), round-tripped back to हिन्दी, playback uninterrupted (31.8s →
      99.9s → 123.4s across switches). Picker hidden on single-audio VaPlayer ✓
- [x] Native (direct VIDEO/FILE) audio picker: `NativeAudioPicker` drives the media
      element's `audioTracks` (label/language, enabled) where the browser exposes
      it — progressive enhancement only: Chromium does not implement
      HTMLMediaElement.audioTracks, so Safari/WebKit users get audible language
      choice on direct mkv/mp4, Chromium users need the HLS variants above.
- [x] hls instance lifted into state (`hlsInstance`) so the picker can enumerate
      and switch; Controls gained an `audio` slot next to the subtitle picker.
- [x] `./gradlew test` BUILD SUCCESSFUL; `npm run build` clean

## v2 "Playback Platform" — M0 gate (2026-10-22)

Scope: safety net before any feature work.

- [x] GitHub Actions CI (`.github/workflows/test.yml`): `./gradlew test` on
      every push/PR, JDK 21, `upstream` submodule checkout, gradle caching.
- [x] Error taxonomy: `cloudstreamweb.errors.ErrorCodes` — single source of
      truth for code→status (shipped codes keep their SPA-visible statuses;
      v2 playback codes pre-declared: UPSTREAM_403/URL_EXPIRED/PROBE_TIMEOUT/
      CODEC_UNSUPPORTED/FFMPEG_MISSING/GPU_ACCEL_UNAVAILABLE; unknown fail
      closed to 502). `ErrorTaxonomyTest` pins the table.
- [x] Request ids: every `/api` response gets `X-Req-Id`; every `[api-error]`
      log line prints `req=... path=... code=... msg=...` (typed failures log
      one compact line, unclassified ones keep the full stack).
- [x] Offline test rig: synthetic `master.m3u8` fixture + JDK-internal loopback
      HTTP server in `StreamProxyRoutingTest` (rewritten manifests, Range
      passthrough + metadata header copying, typed 502 bodies, VTT conversion,
      400 recoil). Real-media fragments stay out of the public repo
      (copyright); dev-only local fixture hook documented in docs/api.md.
- [x] Manifest-rewriter fuzz: `PlaylistRewriteFuzzTest` — 400 seeded hostile
      playlists with 6 invariants (line count, blank passthrough, proxied-ref
      decode == resolved URL, URI-attr in-place swap, comment echo,
      encode(decode(param)) round-trip) + real-world-shaped fixture test.
- [x] FIRST production bug fixed by the rig: the proxy's `respondBytesWriter`
      path wrote 0 bytes under the test engine and, per the live logs, died
      with `ClassCastException: ChannelWriterContent → kotlin.Unit` on client
      aborts. Runtime investigation (Ktor source) showed Ktor 3 drains
      streaming content AFTER the route handler returns, so the handler's outer
      `finally { resp.close() }` was closing the upstream body before drain —
      and `respondBytesWriter`'s continuation mishandles aborts. Passthrough
      now streams via `respondOutputStream` (JVM-native) with the upstream
      body closed by the pump block + a rethrow-safety catch; Ktor bumped
      3.5.0 → 3.6.0 (includes KTOR-8705 fix family).
- Gate: `./gradlew test` BUILD SUCCESSFUL (26 tests green incl. the 12 new).

## v2 — M1 gate: unified PlaybackEngine (2026-10-22)

Scope: one server pipeline for everything that talks to provider links
(DTO/REST contract untouched — SPA unaffected).

- [x] `cloudstreamweb.engine.PlaybackEngine`: the single OkHttp client
      (shared by proxy + downloads), header replay + Range forward +
      Accept-Encoding identity, the streaming passthrough pump,
      manifest-rewrite logic (moved), proxy-ref URL builders, and `linkId`
      = sha256(url+sorted headers)/16 — the cache-key namespace probe/remux/
      ffprobe caches will use in M2+ (all in the existing `cache/` dir;
      no SQLite schema churn).
- [x] `Passthrough` became a top-level engine extension with the
      after-handler-drain ownership baked in (the M0 bug class stays dead:
      an outer finally can no longer close the upstream body early).
- [x] `ProviderException`/`ErrorBody` moved to `cloudstreamweb.errors` next
      to the `ErrorCodes` registry; routes + engine reference the one home.
- [x] `/api/stream` + `/api/subtitle` are thin adapters over the engine;
      DownloadManager now reuses the engine's shared client and
      `buildUpstream(..., head = true)` for its total-length HEAD probe
      (removed its own duplicate builder/client).
- [x] `LinkIdTest` pins id stability, header-order independence and
      url/header sensitivity.
- Gate: `./gradlew test --rerun-tasks` BUILD SUCCESSFUL — 50 tests green
  (offline; includes the M0 rig exercising the engine paths end-to-end).

## v2 — M2 gate: capabilities + settings backbone (2026-10-22)

Scope: host capability truth + the five user toggles every playback feature
hangs off (user requirement: everything new is an on/off switch in Settings).

- [x] `engine/Capabilities`: ffmpeg/ffprobe resolution precedence
      (CS_WEB_FFMPEG env -> repo tools/ffmpeg/bin -> data-dir tools -> PATH;
      static builds installed by scripts/bootstrap.sh, gitignored, GPL notice
      in THIRD_PARTY_NOTICE.md). Short `-version` parsing, VAAPI via
      /dev/dri render nodes, NVDEC/NVENC via nvidia-smi. Missing capabilities
      degrade to `false` in the DTO — never throw.
- [x] `GET /api/capabilities`: CapabilitiesDto incl. SPA conveniences
      (audioRemuxAvailable = ffmpeg && ffprobe, liveTranscodeAvailable,
      decodeProbeAvailable).
- [x] `engine/PlaybackSettings`: five toggles (audioRemux/out-of-box ON,
      probeSources ON, liveTranscode OFF, gpuAccel auto|on|off,
      convertAfterDownload OFF), persisted in the existing `prefs` table
      under a `settings.*` namespace; garbage values fall back per-field;
      POST validation 400 REPO_INVALID + no-persist on bad gpuAccel.
- [x] `GET/POST /api/settings` (SystemRoutes) wired into Application routing.
- [x] SPA: api.ts `getCapabilities/getSettings/postSettings`; Settings page
      "Playback backends" card with live status lines (ffmpeg version, ffprobe,
      detected GPU) + five toggle rows, disabled+explained when the
      capability is absent (user rule honored).
- [x] ffmpeg 7.0.2-static + ffprobe installed locally via the new bootstrap
      step; live smoke: capabilities shows vaapi, settings POST round-trip +
      400 recoil verified through the running server; browser check of the
      settings card.
- [x] Tests: SystemRoutesTest (defaults, patch round-trip persistence,
      invalid gpuAccel 400 recoil, capabilities shape) — settings namespace
      reset around each test for determinism.
- Gate: `./gradlew test` green; npm run build green.
