package cloudstreamweb.persistence

import cloudstreamweb.RuntimeConfig
import cloudstreamweb.catalog.RepoEntry
import java.sql.Connection
import java.sql.DriverManager

/** Plain SQLite persistence (WAL) for repos/plugins/library state. */
object Db {
    private val conn: Connection by lazy {
        RuntimeConfig.ensureDataDir()
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:" + RuntimeConfig.dataBase)
        c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        c
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
}
