package cloudstreamweb.persistence

import cloudstreamweb.RuntimeConfig
import cloudstreamweb.catalog.RepoEntry
import java.sql.Connection
import java.sql.DriverManager

/** Plain SQLite persistence (WAL) for repos/plugins/library state. */
object Db {
    @Volatile
    private var connOrNull: Connection? = null

    /** Reopen-on-demand single connection (WAL). */
    private val conn: Connection
        get() = connection()

    private fun connection(): Connection {
        connOrNull?.takeIf { !it.isClosed }?.let { return it }
        synchronized(this) {
            connOrNull?.takeIf { !it.isClosed }?.let { return it }
            RuntimeConfig.ensureDataDir()
            Class.forName("org.sqlite.JDBC")
            val c = DriverManager.getConnection("jdbc:sqlite:" + RuntimeConfig.dataBase)
            c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            connOrNull = c
            return c
        }
    }

    init {
        // ensure schema exists after fields are bound
        conn.createStatement().use { st ->
            st.execute("""
                CREATE TABLE IF NOT EXISTS repositories(
                    url TEXT PRIMARY KEY,
                    name TEXT,
                    description TEXT,
                    icon_url TEXT,
                    manifest_version INTEGER DEFAULT 1,
                    enabled INTEGER DEFAULT 1,
                    added_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """)
            st.execute("""
                CREATE TABLE IF NOT EXISTS installed_plugins(
                    internal_name TEXT PRIMARY KEY,
                    repository_url TEXT,
                    plugin_url TEXT,
                    version INTEGER,
                    file_hash TEXT,
                    file_path TEXT,
                    entry_class TEXT,
                    plugin_name TEXT,
                    load_state TEXT DEFAULT 'NONE',
                    load_detail TEXT,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """)
        }
    }

    fun upsertRepository(entry: RepoEntry) {
        conn.prepareStatement(
            """
            INSERT INTO repositories(url, name, description, icon_url, manifest_version, enabled, added_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(url) DO UPDATE SET
                name=excluded.name, description=excluded.description,
                icon_url=excluded.icon_url, manifest_version=excluded.manifest_version
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, entry.url)
            ps.setString(2, entry.name)
            ps.setString(3, entry.description)
            ps.setString(4, entry.iconUrl)
            ps.setInt(5, entry.manifestVersion)
            ps.setInt(6, if (entry.enabled) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun getRepositories(): List<RepoEntry> {
        conn.createStatement().use { st ->
            val rs = st.executeQuery(
                "SELECT url, name, description, icon_url, manifest_version, enabled FROM repositories ORDER BY added_at"
            )
            val out = mutableListOf<RepoEntry>()
            while (rs.next()) {
                out += RepoEntry(
                    url = rs.getString(1),
                    name = rs.getString(2),
                    description = rs.getString(3),
                    iconUrl = rs.getString(4),
                    manifestVersion = rs.getInt(5),
                    enabled = rs.getInt(6) == 1,
                )
            }
            return out
        }
    }

    fun hasRepository(url: String): Boolean {
        conn.prepareStatement("SELECT 1 FROM repositories WHERE url = ?").use { ps ->
            ps.setString(1, url)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    fun deleteRepository(url: String) {
        conn.prepareStatement("DELETE FROM repositories WHERE url = ?").use { ps ->
            ps.setString(1, url)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM installed_plugins WHERE repository_url = ?").use { ps ->
            ps.setString(1, url)
            ps.executeUpdate()
        }
    }

    // ---- installed plugins ---------------------------------------------------

    data class InstalledRow(
        val internalName: String,
        val repositoryUrl: String,
        val pluginUrl: String,
        val version: Int,
        val fileHash: String?,
        val filePath: String,
        val entryClass: String,
        val pluginName: String,
        val loadState: String,
        val loadDetail: String?,
    )

    fun upsertInstalled(
        internalName: String,
        repositoryUrl: String,
        pluginUrl: String,
        version: Int,
        fileHash: String?,
        filePath: String,
        entryClass: String,
        pluginName: String,
        loadState: String,
        loadDetail: String?,
    ) {
        conn.prepareStatement(
            """
            INSERT INTO installed_plugins(internal_name, repository_url, plugin_url, version, file_hash, file_path, entry_class, plugin_name, load_state, load_detail, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(internal_name) DO UPDATE SET
                repository_url=excluded.repository_url, plugin_url=excluded.plugin_url,
                version=excluded.version, file_hash=excluded.file_hash, file_path=excluded.file_path,
                entry_class=excluded.entry_class, plugin_name=excluded.plugin_name,
                load_state=excluded.load_state, load_detail=excluded.load_detail,
                updated_at=CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, internalName)
            ps.setString(2, repositoryUrl)
            ps.setString(3, pluginUrl)
            ps.setInt(4, version)
            ps.setString(5, fileHash)
            ps.setString(6, filePath)
            ps.setString(7, entryClass)
            ps.setString(8, pluginName)
            ps.setString(9, loadState)
            ps.setString(10, loadDetail)
            ps.executeUpdate()
        }
    }

    fun listInstalled(): List<InstalledRow> {
        conn.createStatement().use { st ->
            val rs = st.executeQuery(
                "SELECT internal_name, repository_url, plugin_url, version, file_hash, file_path, entry_class, plugin_name, load_state, load_detail FROM installed_plugins ORDER BY internal_name"
            )
            val out = mutableListOf<InstalledRow>()
            while (rs.next()) {
                out += InstalledRow(
                    internalName = rs.getString(1),
                    repositoryUrl = rs.getString(2),
                    pluginUrl = rs.getString(3),
                    version = rs.getInt(4),
                    fileHash = rs.getString(5),
                    filePath = rs.getString(6),
                    entryClass = rs.getString(7),
                    pluginName = rs.getString(8),
                    loadState = rs.getString(9) ?: "NONE",
                    loadDetail = rs.getString(10),
                )
            }
            return out
        }
    }

    fun setLoadState(internalName: String, state: String, detail: String?) {
        conn.prepareStatement(
            "UPDATE installed_plugins SET load_state = ?, load_detail = ?, updated_at = CURRENT_TIMESTAMP WHERE internal_name = ?"
        ).use { ps ->
            ps.setString(1, state)
            ps.setString(2, detail)
            ps.setString(3, internalName)
            ps.executeUpdate()
        }
    }

    fun deleteInstalled(internalName: String) {
        conn.prepareStatement("DELETE FROM installed_plugins WHERE internal_name = ?").use { ps ->
            ps.setString(1, internalName)
            ps.executeUpdate()
        }
    }

    // ---- library (phase 4) --------------------------------------------------

    /** Ensure phase-4 tables exist (idempotent; safe before any library call). */
    private fun ensureLibrarySchema() {
        conn.createStatement().use { st ->
            st.execute("""
                CREATE TABLE IF NOT EXISTS bookmarks(
                    provider TEXT NOT NULL,
                    url TEXT NOT NULL,
                    name TEXT NOT NULL,
                    poster TEXT,
                    type TEXT,
                    source_plugin TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(provider, url)
                )
            """)
            st.execute("""
                CREATE TABLE IF NOT EXISTS history(
                    provider TEXT NOT NULL,
                    url TEXT NOT NULL,
                    episode_data TEXT NOT NULL,
                    name TEXT NOT NULL,
                    episode_label TEXT,
                    poster TEXT,
                    position REAL NOT NULL DEFAULT 0,
                    duration REAL,
                    finished INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(provider, url, episode_data)
                )
            """)
            st.execute("""
                CREATE TABLE IF NOT EXISTS downloads(
                    id TEXT PRIMARY KEY,
                    provider TEXT,
                    url TEXT,
                    name TEXT NOT NULL,
                    link_url TEXT NOT NULL,
                    headers TEXT,
                    file_path TEXT,
                    total_bytes INTEGER,
                    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL DEFAULT 'QUEUED',
                    detail TEXT,
                    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """)
            // Plugin "SharedPreferences" persistence (Phase 5): headless plugins
            // keep settings across restarts. Values are type-tagged by
            // FakeSharedPreferences ("s:", "b:", "i:", "set:").
            st.execute("""
                CREATE TABLE IF NOT EXISTS prefs(
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """)
        }
    }

    /** Graceful-shutdown signal; later use reopens transparently. */
    fun close() {
        val c = connOrNull ?: return   // never opened -> nothing to close
        runCatching { c.close() }
        connOrNull = null
    }

    // ---- plugin prefs (headless SharedPreferences persistence) ----------------

    /** All persisted plugin settings as raw type-tagged values. */
    fun loadPrefs(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        connection().createStatement().use { st ->
            st.executeQuery("SELECT key, value FROM prefs").use { rs ->
                while (rs.next()) out[rs.getString(1)] = rs.getString(2)
            }
        }
        return out
    }

    fun savePref(key: String, encoded: String) {
        connection().prepareStatement(
            "INSERT INTO prefs(key, value, updated_at) VALUES(?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = CURRENT_TIMESTAMP",
        ).use { ps ->
            ps.setString(1, key)
            ps.setString(2, encoded)
            ps.executeUpdate()
        }
    }

    // ---- bookmarks -----------------------------------------------------------

    data class BookmarkRow(
        val provider: String,
        val url: String,
        val name: String,
        val poster: String?,
        val type: String?,
        val sourcePlugin: String?,
        val createdAt: String,
    )

    fun upsertBookmark(
        provider: String,
        url: String,
        name: String,
        poster: String?,
        type: String?,
        sourcePlugin: String?,
    ) {
        ensureLibrarySchema()
        conn.prepareStatement(
            """
            INSERT INTO bookmarks(provider, url, name, poster, type, source_plugin)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(provider, url) DO UPDATE SET
                name=excluded.name, poster=excluded.poster,
                type=excluded.type, source_plugin=excluded.source_plugin
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, provider)
            ps.setString(2, url)
            ps.setString(3, name)
            ps.setString(4, poster)
            ps.setString(5, type)
            ps.setString(6, sourcePlugin)
            ps.executeUpdate()
        }
    }

    fun listBookmarks(): List<BookmarkRow> {
        ensureLibrarySchema()
        conn.createStatement().use { st ->
            val rs = st.executeQuery(
                "SELECT provider, url, name, poster, type, source_plugin, created_at FROM bookmarks ORDER BY created_at DESC"
            )
            val out = mutableListOf<BookmarkRow>()
            while (rs.next()) {
                out += BookmarkRow(
                    provider = rs.getString(1),
                    url = rs.getString(2),
                    name = rs.getString(3),
                    poster = rs.getString(4),
                    type = rs.getString(5),
                    sourcePlugin = rs.getString(6),
                    createdAt = rs.getString(7),
                )
            }
            return out
        }
    }

    fun isBookmarked(provider: String, url: String): Boolean {
        ensureLibrarySchema()
        conn.prepareStatement("SELECT 1 FROM bookmarks WHERE provider = ? AND url = ?").use { ps ->
            ps.setString(1, provider)
            ps.setString(2, url)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    fun deleteBookmark(provider: String, url: String) {
        ensureLibrarySchema()
        conn.prepareStatement("DELETE FROM bookmarks WHERE provider = ? AND url = ?").use { ps ->
            ps.setString(1, provider)
            ps.setString(2, url)
            ps.executeUpdate()
        }
    }

    // ---- history / continue watching ----------------------------------------

    data class HistoryRow(
        val provider: String,
        val url: String,
        val episodeData: String,
        val name: String,
        val episodeLabel: String?,
        val poster: String?,
        val position: Double,
        val duration: Double?,
        val finished: Boolean,
        val updatedAt: String,
    )

    fun upsertHistory(
        provider: String,
        url: String,
        episodeData: String,
        name: String,
        episodeLabel: String?,
        poster: String?,
        position: Double,
        duration: Double?,
        finished: Boolean,
    ) {
        ensureLibrarySchema()
        conn.prepareStatement(
            """
            INSERT INTO history(provider, url, episode_data, name, episode_label, poster, position, duration, finished)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(provider, url, episode_data) DO UPDATE SET
                name=excluded.name, episode_label=excluded.episode_label,
                poster=excluded.poster, position=excluded.position,
                duration=excluded.duration, finished=excluded.finished,
                updated_at=CURRENT_TIMESTAMP
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, provider)
            ps.setString(2, url)
            ps.setString(3, episodeData)
            ps.setString(4, name)
            ps.setString(5, episodeLabel)
            ps.setString(6, poster)
            ps.setDouble(7, position)
            if (duration != null) ps.setDouble(8, duration) else ps.setNull(8, java.sql.Types.DOUBLE)
            ps.setInt(9, if (finished) 1 else 0)
            ps.executeUpdate()
        }
    }

    /** Newest-first history; optionally without finished entries. */
    fun listHistory(activeOnly: Boolean, limit: Int): List<HistoryRow> {
        ensureLibrarySchema()
        val sql = buildString {
            append("SELECT provider, url, episode_data, name, episode_label, poster, position, duration, finished, updated_at FROM history")
            if (activeOnly) append(" WHERE finished = 0 AND position > 5")
            append(" ORDER BY updated_at DESC LIMIT ?")
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<HistoryRow>()
                while (rs.next()) {
                    out += HistoryRow(
                        provider = rs.getString(1),
                        url = rs.getString(2),
                        episodeData = rs.getString(3),
                        name = rs.getString(4),
                        episodeLabel = rs.getString(5),
                        poster = rs.getString(6),
                        position = rs.getDouble(7),
                        duration = rs.getDouble(8),
                        finished = rs.getInt(9) == 1,
                        updatedAt = rs.getString(10),
                    )
                }
                return out
            }
        }
    }

    fun getHistoryEntry(provider: String, url: String, episodeData: String): HistoryRow? {
        ensureLibrarySchema()
        conn.prepareStatement(
            "SELECT provider, url, episode_data, name, episode_label, poster, position, duration, finished, updated_at FROM history WHERE provider = ? AND url = ? AND episode_data = ?"
        ).use { ps ->
            ps.setString(1, provider)
            ps.setString(2, url)
            ps.setString(3, episodeData)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return HistoryRow(
                    provider = rs.getString(1),
                    url = rs.getString(2),
                    episodeData = rs.getString(3),
                    name = rs.getString(4),
                    episodeLabel = rs.getString(5),
                    poster = rs.getString(6),
                    position = rs.getDouble(7),
                    duration = rs.getDouble(8),
                    finished = rs.getInt(9) == 1,
                    updatedAt = rs.getString(10),
                )
            }
        }
    }

    fun deleteHistory(provider: String, url: String, episodeData: String? = null) {
        ensureLibrarySchema()
        val sql = if (episodeData == null) {
            "DELETE FROM history WHERE provider = ? AND url = ?"
        } else {
            "DELETE FROM history WHERE provider = ? AND url = ? AND episode_data = ?"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, provider)
            ps.setString(2, url)
            if (episodeData != null) ps.setString(3, episodeData)
            ps.executeUpdate()
        }
    }

    fun deleteAllHistory() {
        ensureLibrarySchema()
        conn.createStatement().use { it.execute("DELETE FROM history") }
    }

    // ---- downloads -----------------------------------------------------------

    data class DownloadRow(
        val id: String,
        val provider: String?,
        val url: String?,
        val name: String,
        val linkUrl: String,
        val filePath: String?,
        val totalBytes: Long?,
        val downloadedBytes: Long,
        val state: String,
        val detail: String?,
        val createdAt: String,
    )

    fun createDownload(
        id: String,
        provider: String?,
        url: String?,
        name: String,
        linkUrl: String,
        headersJson: String?,
    ): DownloadRow {
        ensureLibrarySchema()
        conn.prepareStatement(
            "INSERT INTO downloads(id, provider, url, name, link_url, headers, state) VALUES (?, ?, ?, ?, ?, ?, 'QUEUED')"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, provider)
            ps.setString(3, url)
            ps.setString(4, name)
            ps.setString(5, linkUrl)
            ps.setString(6, headersJson)
            ps.executeUpdate()
        }
        return getDownload(id)!!
    }

    fun setDownloadProgress(
        id: String,
        downloadedBytes: Long,
        state: String?,
        detail: String?,
        filePath: String? = null,
    ) {
        conn.prepareStatement(
            "UPDATE downloads SET downloaded_bytes = ?, state = COALESCE(?, state), detail = COALESCE(?, detail), file_path = COALESCE(?, file_path), updated_at = CURRENT_TIMESTAMP WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, downloadedBytes)
            if (state != null) ps.setString(2, state) else ps.setNull(2, java.sql.Types.VARCHAR)
            if (detail != null) ps.setString(3, detail) else ps.setNull(3, java.sql.Types.VARCHAR)
            if (filePath != null) ps.setString(4, filePath) else ps.setNull(4, java.sql.Types.VARCHAR)
            ps.setString(5, id)
            ps.executeUpdate()
        }
    }

    fun setDownloadDone(id: String, filePath: String, totalBytes: Long) {
        conn.prepareStatement(
            "UPDATE downloads SET state='DONE', file_path=?, total_bytes=?, downloaded_bytes=?, updated_at=CURRENT_TIMESTAMP WHERE id=?"
        ).use { ps ->
            ps.setString(1, filePath)
            ps.setLong(2, totalBytes)
            ps.setLong(3, totalBytes)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    fun setDownloadFailed(id: String, filePath: String?, downloadedBytes: Long, detail: String?) {
        conn.prepareStatement(
            "UPDATE downloads SET state='FAILED', file_path=COALESCE(?, file_path), downloaded_bytes=?, detail=COALESCE(?, detail), updated_at=CURRENT_TIMESTAMP WHERE id=?"
        ).use { ps ->
            if (filePath != null) ps.setString(1, filePath) else ps.setNull(1, java.sql.Types.VARCHAR)
            ps.setLong(2, downloadedBytes)
            if (detail != null) ps.setString(3, detail) else ps.setNull(3, java.sql.Types.VARCHAR)
            ps.setString(4, id)
            ps.executeUpdate()
        }
    }

    /** Raw headers JSON of a download row (for resume; parsed by the manager). */
    fun getDownloadHeaders(id: String): String? {
        ensureLibrarySchema()
        conn.prepareStatement("SELECT headers FROM downloads WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    fun getDownload(id: String): DownloadRow? {
        ensureLibrarySchema()
        conn.prepareStatement(
            "SELECT id, provider, url, name, link_url, file_path, total_bytes, downloaded_bytes, state, detail, created_at FROM downloads WHERE id = ?"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return DownloadRow(
                    id = rs.getString(1),
                    provider = rs.getString(2),
                    url = rs.getString(3),
                    name = rs.getString(4),
                    linkUrl = rs.getString(5),
                    filePath = rs.getString(6),
                    totalBytes = rs.getLong(7).let { if (rs.wasNull()) null else it },
                    downloadedBytes = rs.getLong(8),
                    state = rs.getString(9),
                    detail = rs.getString(10),
                    createdAt = rs.getString(11),
                )
            }
        }
    }

    fun listDownloads(limit: Int = 100): List<DownloadRow> {
        ensureLibrarySchema()
        conn.prepareStatement(
            "SELECT id, provider, url, name, link_url, file_path, total_bytes, downloaded_bytes, state, detail, created_at FROM downloads ORDER BY created_at DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<DownloadRow>()
                while (rs.next()) {
                    out += DownloadRow(
                        id = rs.getString(1),
                        provider = rs.getString(2),
                        url = rs.getString(3),
                        name = rs.getString(4),
                        linkUrl = rs.getString(5),
                        filePath = rs.getString(6),
                        totalBytes = rs.getLong(7).let { if (rs.wasNull()) null else it },
                        downloadedBytes = rs.getLong(8),
                        state = rs.getString(9),
                        detail = rs.getString(10),
                        createdAt = rs.getString(11),
                    )
                }
                return out
            }
        }
    }

    fun deleteDownloadRow(id: String) {
        ensureLibrarySchema()
        conn.prepareStatement("DELETE FROM downloads WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate()
        }
    }

    /** Downloads still RUNNING after a process restart: server died mid-flight. */
    fun listStaleRunning(): List<DownloadRow> {
        ensureLibrarySchema()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, provider, url, name, link_url, file_path, total_bytes, downloaded_bytes, state, detail, created_at FROM downloads WHERE state IN ('RUNNING','QUEUED')"
            ).use { rs ->
                val out = mutableListOf<DownloadRow>()
                while (rs.next()) {
                    out += DownloadRow(
                        id = rs.getString(1),
                        provider = rs.getString(2),
                        url = rs.getString(3),
                        name = rs.getString(4),
                        linkUrl = rs.getString(5),
                        filePath = rs.getString(6),
                        totalBytes = rs.getLong(7).let { if (rs.wasNull()) null else it },
                        downloadedBytes = rs.getLong(8),
                        state = rs.getString(9),
                        detail = rs.getString(10),
                        createdAt = rs.getString(11),
                    )
                }
                return out
            }
        }
    }
}
