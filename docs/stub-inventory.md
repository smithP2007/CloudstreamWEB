# stub-inventory (Phase 2 deliverable)

Working table of `android.*`/app-module symbols that real extension bytecode
references but that the upstream JVM artifact does not provide, each with the
stub added and the evidence (which plugin demanded it).

| Symbol | Stub added | Evidence plugin | Notes |
|---|---|---|---|
| `com.lagradost.cloudstream3.plugins.Plugin` | headless abstract Plugin : BasePlugin (library) with load(context)->load() delegation, resources/openSettings fields; registerVideoClickAction NOT ported (no plugin calls it on load path) | CineStream v487 (CSX) | this was `NoClassDefFoundError com/lagradost/cloudstream3/plugins/Plugin` on install |
| `android.content.Context` (+`res.Resources`) | empty open class; class used as VALUE by DexStore key chain | CineStream (javap: descriptor-only refs on load path) | settings/webview paths call context.* and are dormant headless |
| `android.content.SharedPreferences` | interface + `FakeSharedPreferences` (SQLite-backed via prefs table since Phase 5, read-then-default) | CineStream | dex inlines cs3 DataStore.getKey -> getString; unset keys -> plugin defaults apply (providers stay enabled) |
| `com.lagradost.cloudstream3.utils.DataStore` | object getSharedPrefs(context) -> FakeSharedPreferences | CineStream | one process-wide store; write-through persisted to the prefs table (Phase 5) |
| `com.lagradost.cloudstream3.CloudStreamApp` | class + companion `context` getter + `setKey(path,value)` companion method | CineStream | javap: getstatic Companion; invokevirtual getContext then Companion.setKey |
| `com.lagradost.cloudstream3.utils.UiText` | sealed class (DynamicString/StringResource) + asString(Context) | CineStream | library has no UiText; resource-backed strings fall back to [res=…] |
| `com.lagradost.cloudstream3.utils.Event` | observer-set port | CineStream | reloadHomeEvent holds a headless instance |
| `com.lagradost.cloudstream3.MainActivity` / `CommonActivity` | companion-stub class + object; getReloadHomeEvent/ getActivity return headless no-ops | CineStream, CineSimklProvider | MainActivity stub deliberately does NOT extend android.app.Activity so no UI stack is pulled; `android.app.Activity` exists as a bare class for CommonActivity.getActivity descriptor |
| `syncproviders.{AuthAPI,SyncAPI,AuthRepo,SyncRepo}` (+AuthToken/AuthData/AuthUser) | ctor-reachable surface only (AuthRepo(api), api readers, authUser null) | CineSimklProvider ctor: `AccountManager.Companion.getSimklApi(): SimklApi` + `SyncRepo(SyncAPI)` | upstream suspend `library()` is emitted with JvmName `library-IoAF18A` (hyphen impossible in Kotlin/Java identifiers) — intentionally unstubbed; a provider calling it gets a typed NoSuchMethodError at REQUEST time (documented limitation) |
| `syncproviders.{AccountManager, providers.SimklApi}` | companion accessor + headless "not-logged-in" SimklApi | CineSimklProvider | simkl search/home work without account; library features no-op |
| `network.CloudflareKiller` | okhttp Interceptor headless port (cookie capture, NO challenge solving — no WebView/JS on JVM) | CineStreamExtractors (HubCloud/VCloud/GDFlix family) | changed CineStream/BollyFlix from 502 to full 200+ links; blocked mirrors still fail typed |
| `android.net.Uri` | parse+getHost backed by java.net.URI | CSX Settings getCookieForDomain | used at stream-extract time |

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
| ~~`com.lagradost.cloudstream3.plugins.Plugin` (android-Context variant)~~ **DONE (CineStream et al.)** | — | see table above |
| ~~`android.content.Context` / Resources~~ **DONE** | — | see table above |
| ~~`com.lagradost.cloudstream3.utils.DataStore`~~ **DONE (SQLite-backed from Phase 5)** | plugins reading *written* settings across restart | fake SharedPreferences + `prefs` SQLite table (write-through, type-tagged values, lazy restore) |
| `SyncRepo.library-IoAF18A` (suspend library flow) | a provider whose stream search derives from a sync-account's user library | JVM proxy of the user's watchlist (upstream signature carries a hyphen and cannot be declared from Kotlin/Java source) |
| `android.webkit.*` / WebView UI surfaces used by plugin settings dialogs | plugins' "openSettings" flows (never execute headless) | none required for stream playback |
