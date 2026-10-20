package cloudstreamweb.plugins

import cloudstreamweb.RuntimeConfig
import cloudstreamweb.catalog.OkHttp
import cloudstreamweb.catalog.RepoFetcher
import cloudstreamweb.catalog.RepoUrl
import cloudstreamweb.persistence.Db
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.BasePlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Phase 2 runtime: installs real CloudStream .cs3 plugins and loads them on the
 * desktop JVM, mirroring upstream PluginManager semantics:
 *
 *   download → sha256-verify (fileHash from plugins.json) → extract →
 *   dex→jar (enjarify) → child-first URLClassLoader → instantiate the class
 *   named in manifest.json → set filename → load() → provider registration
 *   happens inside the plugin (registerMainAPI into APIHolder).
 */
object ExtensionRuntime {

    sealed interface Stage {
        data object Queued : Stage
        data object Downloading : Stage
        data object Verifying : Stage
        data object Extracting : Stage
        data object Converting : Stage
        data object Loading : Stage
        data object Done : Stage
        data class Failed(val message: String) : Stage
    }

    @Serializable
    data class JobView(
        val id: String,
        val stage: String,
        val progress: Int,
        val error: String? = null,
        val repositoryUrl: String? = null,
        val internalName: String? = null,
    )

    class Job(
        val id: String,
        val repositoryUrl: String?,
        val internalName: String?,
    ) {
        @Volatile var stage: Stage = Stage.Queued
        fun view(): JobView = JobView(
            id = id,
            stage = stageName(),
            progress = progress(),
            error = (stage as? Stage.Failed)?.message,
            repositoryUrl = repositoryUrl,
            internalName = internalName,
        )

        private fun stageName(): String = when (val s = stage) {
            is Stage.Queued -> "QUEUED"
            is Stage.Downloading -> "DOWNLOAD"
            is Stage.Verifying -> "VERIFY"
            is Stage.Extracting -> "EXTRACT"
            is Stage.Converting -> "CONVERT"
            is Stage.Loading -> "LOAD"
            is Stage.Done -> "DONE"
            is Stage.Failed -> "FAILED"
        }

        private fun progress(): Int = when (stage) {
            is Stage.Queued -> 5
            is Stage.Downloading -> 25
            is Stage.Verifying -> 40
            is Stage.Extracting -> 55
            is Stage.Converting -> 70
            is Stage.Loading -> 85
            is Stage.Done -> 100
            is Stage.Failed -> 100
        }
    }

    class LivePlugin(
        val internalName: String,
        val filename: String,
        val version: Int,
        val plugin: BasePlugin,
        val loader: ClassLoader,
        val manifest: BasePlugin.Manifest,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val live = ConcurrentHashMap<String, LivePlugin>()
    private val perPluginLocks = ConcurrentHashMap<String, Mutex>()
    private val http = OkHttp()
    private val fetcher = RepoFetcher(http = http)
    private val json = jacksonObjectMapper().apply { configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false) }

    // ---- install jobs -----------------------------------------------------

    /** Starts an async install and returns its job id. */
    fun beginInstall(repositoryUrl: String, internalName: String): JobView {
        val id = UUID.randomUUID().toString()
        val job = Job(id, repositoryUrl, internalName)
        jobs[id] = job
        perPluginLocks.computeIfAbsent(internalName) { Mutex() }
        scope.launch {
            try {
                installSync(repositoryUrl, internalName, job)
                job.stage = Stage.Done
            } catch (t: Throwable) {
                System.err.println("install $internalName failed: ${t}")
                job.stage = Stage.Failed(t.message ?: t.javaClass.simpleName)
                Db.setLoadState(internalName, "FAILED", t.message?.take(400))
            }
        }
        return job.view()
    }

    fun jobView(id: String): JobView? = jobs[id]?.view()

    /** Synchronous install (jobs call this; tests use it directly). */
    suspend fun installSync(repositoryUrlRaw: String, internalName: String, job: Job? = null): LivePlugin {
        return withContext(Dispatchers.IO) {
            val mutex = perPluginLocks.computeIfAbsent(internalName) { Mutex() }
            mutex.withLock {
                val repositoryUrl = RepoUrl.resolve(repositoryUrlRaw, http)
                    ?: throw IllegalArgumentException("repo url cannot be resolved")

                val sitePlugin = fetcher.repoPlugins(repositoryUrl)
                    .firstOrNull { it.internalName == internalName }
                    ?: throw IllegalArgumentException("ENTRY_NOT_FOUND $internalName in $repositoryUrl")

                // 1. download ------------------------------------------------
                job?.let { it.stage = Stage.Downloading }
                val repoDir = RuntimeConfig.dataDir.resolve("Extensions").resolve(repoFolder(repositoryUrl))
                Files.createDirectories(repoDir)
                val cs3 = repoDir.resolve("${sanitized(internalName)}-${sitePlugin.version}.cs3")
                downloadTo(sitePlugin.url, cs3)

                // 2. verify hash ----------------------------------------------
                job?.let { it.stage = Stage.Verifying }
                val actual = sha256Hex(cs3)
                val expected = sitePlugin.fileHash?.removePrefix("sha256-")?.lowercase()
                if (expected != null && expected != actual) {
                    Files.deleteIfExists(cs3)
                    throw SecurityException("HASH_MISMATCH for $internalName: expected $expected got $actual")
                }

                // 3. extract --------------------------------------------------
                job?.let { it.stage = Stage.Extracting }
                val workDir = RuntimeConfig.dataDir.resolve("cache")
                    .resolve("plugins").resolve("${sanitized(internalName)}-${sitePlugin.version}")
                val extracted = Converter.extractCs3(cs3, workDir)
                val manifest = json.readValue(extracted.manifestJson, BasePlugin.Manifest::class.java)
                    ?: throw IllegalStateException("unparseable manifest.json")
                if (manifest.name == null) manifest.name = sitePlugin.name

                // 4. convert --------------------------------------------------
                job?.let { it.stage = Stage.Converting }
                val jar = workDir.resolve("classes.jar")
                Converter.dexToJar(extracted.dexFiles.first(), jar)

                // 5. load -----------------------------------------------------
                job?.let { it.stage = Stage.Loading }
                return@withLock loadFromCache(internalName, sitePlugin.version, manifest, jar, sitePlugin.url, repositoryUrl, cs3)
            }
        }
    }

    /** Boots the plugin from its converted jar (install step 5 + startup restore). */
    private fun loadFromCache(
        internalName: String,
        version: Int,
        manifest: BasePlugin.Manifest,
        jar: Path,
        pluginUrl: String,
        repositoryUrl: String,
        cs3Path: Path,
    ): LivePlugin {
        val entryClass = requireNotNull(manifest.pluginClassName) { "manifest.pluginClassName missing" }
        val loader = ChildFirstLoader(listOf(jar.toUri().toURL()), ExtensionRuntime::class.java.classLoader)
        val plugin: BasePlugin = try {
            val clazz = Class.forName(entryClass, true, loader)
            val instance = clazz.getDeclaredConstructor().newInstance()
            check(instance is BasePlugin) { "${clazz.name} does not extend BasePlugin (Android-flavored plugin?)" }
            instance
        } catch (t: Throwable) {
            try { (loader as? URLClassLoader)?.close() } catch (_: Exception) { }
            throw t
        }

        val filename = "$internalName-$version"
        plugin.filename = filename
        try {
            plugin.load()
        } catch (t: Throwable) {
            try { (loader as? ChildFirstLoader)?.close() } catch (_: Exception) { }
            // mirror upstream: a partially-loaded plugin must not stay live
            throw IllegalStateException("load() failed for $filename: ${t.message}", t)
        }

        val livePlugin = LivePlugin(internalName, filename, version, plugin, loader, manifest)
        live[internalName] = livePlugin
        Db.upsertInstalled(
            internalName = internalName,
            repositoryUrl = repositoryUrl,
            pluginUrl = pluginUrl,
            version = version,
            fileHash = sha256Hex(cs3Path),
            filePath = cs3Path.toString(),
            entryClass = entryClass,
            pluginName = manifest.name ?: internalName,
            loadState = "LOADED",
            loadDetail = null,
        )
        return livePlugin
    }

    // ---- uninstall --------------------------------------------------------

    fun uninstall(internalName: String) {
        val p = live.remove(internalName)
        if (p != null) {
            try { p.plugin.beforeUnload() } catch (t: Throwable) { System.err.println("beforeUnload failed for $internalName: $t") }
            removeRegistrations(p.filename)
            try { (p.loader as? URLClassLoader)?.close() } catch (_: Exception) { }
        }
        Db.deleteInstalled(internalName)
        // remove files best-effort
        Files.walk(RuntimeConfig.dataDir.resolve("Extensions")).use { walk ->
            walk.filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("${sanitized(internalName)}-") }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    private fun removeRegistrations(filename: String) {
        try {
            APIHolder.allProviders.removeAll { it.sourcePlugin == filename }
        } catch (t: Throwable) { System.err.println("provider cleanup failed: $t") }
        try {
            com.lagradost.cloudstream3.utils.extractorApis.removeAll { it.sourcePlugin == filename }
        } catch (t: Throwable) { System.err.println("extractor cleanup failed: $t") }
    }

    // ---- startup restore ---------------------------------------------------

    /** Called at server boot: reload installed plugins from disk cache. */
    fun restoreAll() = scope.launch {
        for (row in Db.listInstalled()) {
            try {
                val cs3 = Path.of(row.filePath)
                if (!Files.exists(cs3)) {
                    Db.setLoadState(row.internalName, "FAILED", "cs3 file missing: $cs3")
                    continue
                }
                val workDir = RuntimeConfig.dataDir.resolve("cache").resolve("plugins").resolve("${sanitized(row.internalName)}-${row.version}")
                val jar = workDir.resolve("classes.jar")
                if (!Files.exists(jar)) {
                    val extracted = Converter.extractCs3(cs3, workDir)
                    val manifest = json.readValue(extracted.manifestJson, BasePlugin.Manifest::class.java)
                    if (manifest.name == null) manifest.name = row.pluginName
                    Converter.dexToJar(extracted.dexFiles.first(), jar)
                }
                val manifest = json.readValue(
                    ZipFile(cs3.toFile()).use { zip ->
                        val entry = zip.getEntry("manifest.json") ?: throw IllegalStateException("no manifest")
                        zip.getInputStream(entry).bufferedReader().readText()
                    },
                    BasePlugin.Manifest::class.java,
                ).apply { if (name == null) name = row.pluginName }

                loadFromCache(row.internalName, row.version, manifest, jar, row.pluginUrl, row.repositoryUrl, cs3)
                System.err.println("restored plugin ${row.internalName} (v${row.version})")
            } catch (t: Throwable) {
                Db.setLoadState(row.internalName, "FAILED", "restore: ${t.message?.take(300)}")
                System.err.println("restore failed for ${row.internalName}: $t")
            }
        }
    }

    // ---- queries ------------------------------------------------------------

    fun loadStates(): Map<String, Pair<String, String?>> =
        Db.listInstalled().associate { it.internalName to Pair(it.loadState, it.loadDetail) }

    fun livePlugin(internalName: String): LivePlugin? = live[internalName]

    // ---- helpers -------------------------------------------------------------

    private fun repoFolder(url: String): String = sanitized(url.removePrefix("https://").removePrefix("http://").substringBefore('/'))

    private fun sanitized(name: String) = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun sha256Hex(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun downloadTo(url: String, target: Path) {
        val resp = http.getBytes(url)
        try {
            Files.write(target, resp)
        } catch (t: Throwable) {
            Files.deleteIfExists(target)
            throw t
        }
    }
}
