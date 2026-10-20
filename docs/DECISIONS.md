# DECISIONS log (append-only)

Format: date | decision | reason | evidence

- 2026-10 | Consume upstream plugin API from JitPack artifact `com.github.recloudstream.cloudstream:library:4.8.0` | Avoids Android SDK/AGP entirely; upstream `:library` is KMP with an official `jvm()` target; JitPack API reports 4.8.0 build "ok"; extension template pins same coordinates | jitpack.io/api/builds listing + upstream/jitpack.yml + upstream/library/build.gradle.kts (kotlin { jvm() }), extensions repo template `implementation("com.github.recloudstream.cloudstream:library:-SNAPSHOT")`
- 2026-10 | Kotlin 2.4.0 | Must match/be newer than the Kotlin used to build the artifact (upstream libs.versions.toml kotlinGradlePlugin=2.4.0) to read Kotlin metadata
- 2026-10 | Ktor 3.5.0 for the server | Matches upstream ktor version; single HTTP stack
- 2026-10 | jackson-module-kotlin 2.13.1 | Upstream requirement ("Do not bump"); BasePlugin.Manifest is parsed with jackson in the official loader
- 2026-10 | Extension loading path decided (Phase 2): unzip .cs3 → classes.dex → subprocess dex→jar (enjarify primary, dex2jar fallback) → URLClassLoader(child-first for plugin packages only) → read manifest.json → instantiate pluginClassName → call load() (no-arg cross-platform). Official extensions extend BasePlugin + @CloudstreamPlugin with no-arg load(), confirmed from extensions repo source (YoutubePlugin.kt).
- 2026-10 | Static serving via ktor staticFiles(default = "index.html") | Minimal, handles SPA deep links; /api/* routes registered first
- 2026-10 (Phase 2) | cs3 pipeline = download -> sha256(fileHash) verify -> unzip -> enjarify subprocess -> child-first URLClassLoader -> manifest pluginClassName -> BasePlugin.load() | Runtime-verified against 5 real official plugins with zero missing symbols; child-first local classload protects against plugin-owned class collisions while library/stdlib resolve via parent
- 2026-10 (Phase 2) | enjarify (google/enjarify, Apache-2.0) as dex-to-jar converter via python3 subprocess | pure-python; no external jar tooling; output jar loads cleanly on JDK 21
- 2026-10 (Phase 2) | cs3 at dataDir/Extensions/{repoHost}/{internalName}-{version}.cs3; converted jars cached under dataDir/cache/plugins/ | restoreAll() reuses cache so server restarts re-load without re-converting (verified live)
