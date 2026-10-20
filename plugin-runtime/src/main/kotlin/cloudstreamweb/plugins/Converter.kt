package cloudstreamweb.plugins

import cloudstreamweb.RuntimeConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * .cs3 unpacking and dex→jar conversion.
 *
 * A .cs3 file is a zip with manifest.json + classes.dex (upstream PluginManager
 * reads the same entries). Desktop JVM cannot load dex, so classes.dex is
 * converted to a jar via google/enjarify (downloaded to plugin-runtime/tools by
 * scripts/bootstrap.sh; pure python, Apache-2.0).
 */
object Converter {

    data class Extracted(val manifestJson: String, val dexFiles: List<Path>, val dir: Path)

    /** Extracts a .cs3 into targetDir, guarding against zip-slip. */
    fun extractCs3(cs3: Path, targetDir: Path): Extracted {
        Files.createDirectories(targetDir)
        targetDir.toFile().cleanDir()
        val dexes = mutableListOf<Path>()
        var manifestJson: String? = null

        ZipFile(cs3.toFile()).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                if (entry.name.contains("..")) continue
                val out = targetDir.resolve(entry.name).normalize()
                if (!out.startsWith(targetDir.normalize())) continue // zip-slip guard
                Files.createDirectories(out.parent)
                zip.getInputStream(entry).use { input -> Files.copy(input, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                when {
                    entry.name == "manifest.json" -> manifestJson = Files.readString(out)
                    entry.name.endsWith(".dex") -> dexes.add(out)
                }
            }
        }

        val manifest = requireNotNull(manifestJson) { "manifest.json missing from ${cs3.fileName}" }
        require(dexes.isNotEmpty()) { "no classes.dex in ${cs3.fileName}" }
        return Extracted(manifest, dexes, targetDir)
    }

    /** Converts classes.dex to a java jar using the enjarify subprocess. */
    fun dexToJar(dex: Path, jar: Path) {
        val enjarifyHome = findEnjarifyHome()
            ?: throw IllegalStateException(
                "enjarify not found (tried CS_WEB_TOOLS_DIR and ../plugin-runtime/tools). " +
                    "Run scripts/bootstrap.sh"
            )

        Files.deleteIfExists(jar)
        val logFile = logFileFor(dex)
        val proc = ProcessBuilder(
            "python3", "-O", "-m", "enjarify.main",
            dex.toAbsolutePath().toString(),
            "-o", jar.toAbsolutePath().toString(),
        )
            .directory(enjarifyHome.toFile())
            .also { pb -> pb.environment()["PYTHONPATH"] = enjarifyHome.toAbsolutePath().toString() }
            .redirectErrorStream(true)
            .apply { redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile())) }
            .start()

        val exit = proc.waitFor()
        if (exit != 0) {
            throw IllegalStateException(
                "enjarify failed (exit $exit): ${
                    if (Files.exists(logFile)) Files.readString(logFile).takeLast(600) else "no log"
                }"
            )
        }
        require(Files.exists(jar) && Files.size(jar) > 0) { "enjarify produced no output" }
    }

    private fun logFileFor(dex: Path): Path =
        RuntimeConfig.dataDir.resolve("cache").resolve("convert-logs").also { Files.createDirectories(it) }
            .resolve("${sanitized(dex.fileName.toString())}.log")

    private fun sanitized(name: String) = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    /** Locates plugin-runtime/tools/enjarify (env override or walking up from cwd). */
    fun findEnjarifyHome(): Path? {
        System.getenv("CS_WEB_TOOLS_DIR")?.let { env ->
            val p = Path.of(env)
            val home = if (p.fileName?.toString() == "enjarify") p else p.resolve("enjarify")
            if (isEnjarifyHome(home)) return home
        }
        var dir: Path? = Path.of("").toAbsolutePath()
        repeat(7) {
            val d = dir ?: return null
            for (candidate in listOf(
                d.resolve("plugin-runtime/tools/enjarify"),
                d.resolve("tools/enjarify"),
            )) {
                if (isEnjarifyHome(candidate)) return candidate
            }
            dir = d.parent
        }
        return null
    }

    private fun isEnjarifyHome(p: Path): Boolean =
        Files.isRegularFile(p.resolve("enjarify/main.py"))

    private fun File.cleanDir() {
        if (!isDirectory) return
        walkTopDown()
            .filter { it != this }
            .sortedByDescending { it.length() }
            .forEach { it.delete() }
    }
}
