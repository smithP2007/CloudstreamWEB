package cloudstreamweb

import java.nio.file.Files
import java.nio.file.Path

/**
 * Runtime configuration for the whole backend (server + plugin-runtime).
 */
object RuntimeConfig {
    /**
     * Precedence: csweb.dataDir system property (tests/in-process boots) →
     * CS_WEB_DATA_DIR env → ~/.cloudstream-web.
     */
    val dataDir: Path = Path.of(
        System.getProperty("csweb.dataDir")
            ?: System.getenv("CS_WEB_DATA_DIR")
            ?: "${System.getProperty("user.home")}/.cloudstream-web",
    )

    /** Port the Ktor server binds to (defaults per plan: localhost:8080). */
    val port: Int = System.getenv("PORT")?.toIntOrNull() ?: 8080

    /** LAN mode binds 0.0.0.0 instead of 127.0.0.1 (token auth is a later phase). */
    val lanMode: Boolean = System.getenv("CS_WEB_LAN") == "1"

    val host: String = if (lanMode) "0.0.0.0" else "127.0.0.1"

    val dataBase: Path get() = dataDir.resolve("db.sqlite")

    fun ensureDataDir(): Path {
        Files.createDirectories(dataDir.resolve("Extensions"))
        Files.createDirectories(dataDir.resolve("cache"))
        return dataDir
    }
}
