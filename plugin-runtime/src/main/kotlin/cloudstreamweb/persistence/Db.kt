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
}
