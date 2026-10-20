# stub-inventory (Phase 2 deliverable)

Working table of `android.*`/app-module symbols that real extension bytecode
references but that the upstream JVM artifact does not provide, each with the
stub added and the evidence (which plugin demanded it).

| Symbol | Stub added | Evidence plugin | Notes |
|---|---|---|---|
| (none — see below) | | | |

## REA/verification pass (Phase 2)

Runtime verification (Feb build channel): 5 real plugins from the official
`recloudstream/extensions` repo installed through the full pipeline
(download -> sha256 verify -> extract -> enjarify -> child-first
URLClassLoader -> manifest.json entry class -> `load()`):

| Plugin | Result | Missing symbols |
|---|---|---|
| YoutubeProvider (v1) | LOADED, provider "YouTube" registered | (none) |
| TwitchProvider (v2) | LOADED, MainAPI + ExtractorApi registered | (none) |
| DailymotionProvider (v4) | LOADED | (none) |
| InternetArchiveProvider (v1) | LOADED | (none) |
| InvidiousProvider (v9) | LOADED | (none) |

Conclusion: for the official (modern) extension channel, the published
`library-jvm` artifact + Kotlin stdlib + okhttp/jsoup/jackson/NewPipeExtractor
transitives satisfy every runtime reference — **no android stubs needed so
far**. This table will grow if a community plugin that extends the Android
`Plugin` class or touches `android.*` fails to load.

## Deferred-risk stubs (known gaps, not yet requested by any loaded plugin)

| Symbol class | When it would matter | Planned approach |
|---|---|---|
| `com.lagradost.cloudstream3.plugins.Plugin` (android-Context variant) | community plugins extending `Plugin()` | port upstream app-module Plugin.kt with a headless context |
| `android.content.Context` / Resources | plugins using `requiresResources` / resources | stub + asset dir mapping |
| `com.lagradost.cloudstream3.utils.DataStore` | plugins persisting settings | wrapper over SQLite tables |
