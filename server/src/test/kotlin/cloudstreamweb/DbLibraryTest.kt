package cloudstreamweb

import cloudstreamweb.persistence.Db
import cloudstreamweb.plugins.FakeSharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Library persistence (Phase 4). Runs against an isolated SQLite created from
 * the csweb.dataDir system property that the server test task sets — see
 * server/build.gradle.kts. Tests use unique keys so re-runs stay deterministic.
 */
class DbLibraryTest {

    /** pleasanter than fighting generic assertNotNull inference */
    private fun <T> required(x: T?, what: String): T = x ?: error("missing $what")

    private fun freshKey(prefix: String) = "$prefix-${java.util.UUID.randomUUID()}"

    // ---- bookmarks ---------------------------------------------------------

    @Test
    fun `bookmark roundtrip`() {
        val key = freshKey("bm")
        val provider = "testProv"

        Db.upsertBookmark(provider, key, "Test Movie", poster = "https://p/1.jpg", type = "MOVIE", sourcePlugin = "Plug")
        assertTrue(Db.isBookmarked(provider, key))
        val listed = Db.listBookmarks().first { it.url == key }
        assertEquals("Test Movie", listed.name)
        assertEquals("https://p/1.jpg", listed.poster)
        assertEquals("MOVIE", listed.type)
        assertEquals("Plug", listed.sourcePlugin)

        // upsert refreshes fields instead of duplicating
        Db.upsertBookmark(provider, key, "Renamed Movie", poster = null, type = null, sourcePlugin = null)
        val renamed = Db.listBookmarks().first { it.url == key }
        assertEquals("Renamed Movie", renamed.name)
        assertNull(renamed.poster)
        assertEquals(1, Db.listBookmarks().count { it.url == key })

        Db.deleteBookmark(provider, key)
        assertFalse(Db.isBookmarked(provider, key))
    }

    // ---- history ----------------------------------------------------------

    @Test
    fun `history upsert replace and resume lookup`() {
        val provider = "testProv"
        val url = freshKey("hist")
        val ep = "ep-$url"

        Db.upsertHistory(provider, url, ep, "Series X", episodeLabel = "S1E1", poster = null, position = 12.0, duration = 1000.0, finished = false)
        Db.upsertHistory(provider, url, ep, "Series X", episodeLabel = "S1E1", poster = null, position = 300.0, duration = 1000.0, finished = false)

        val entry = required(Db.getHistoryEntry(provider, url, ep), "history entry")
        assertEquals(300.0, entry.position, 0.001)
        assertFalse(entry.finished)

        // continue-watching: only entries with position > 5 and not finished
        assertTrue(Db.listHistory(activeOnly = true, limit = 200).any { it.url == url })

        // finished entries drop out of active listing
        Db.upsertHistory(provider, url, ep, "Series X", episodeLabel = "S1E1", poster = null, position = 990.0, duration = 1000.0, finished = true)
        assertFalse(Db.listHistory(activeOnly = true, limit = 200).any { it.url == url })
        assertTrue(Db.listHistory(activeOnly = false, limit = 200).any { it.url == url })

        // single-episode delete
        Db.deleteHistory(provider, url, ep)
        assertNull(Db.getHistoryEntry(provider, url, ep))
    }

    @Test
    fun `history item purge`() {
        val provider = "purgeProv"
        val url = freshKey("purge")
        Db.upsertHistory(provider, url, "e1", "N", null, null, 50.0, null, false)
        Db.upsertHistory(provider, url, "e2", "N", null, null, 60.0, null, false)

        Db.deleteHistory(provider, url, "e1")
        assertTrue(Db.getHistoryEntry(provider, url, "e2") != null, "e2 should survive e1 delete")

        Db.deleteHistory(provider, url, null)
        assertNull(Db.getHistoryEntry(provider, url, "e2"))
    }

    // ---- downloads ---------------------------------------------------------

    @Test
    fun `download lifecycle rows`() {
        val provider = "testProv"
        val name = freshKey("dl")

        val row = Db.createDownload(
            id = freshKey("id"),
            provider = provider,
            url = "https://src/item",
            name = name,
            linkUrl = "https://src/file.mkv",
            headersJson = "{}",
        )
        assertEquals("QUEUED", row.state)
        assertEquals(0L, row.downloadedBytes)

        // progress with path persistence (COALESCE keeps later writes)
        Db.setDownloadProgress(row.id, 1000L, "RUNNING", null, filePath = "/tmp/a/stuff.mkv")
        var current = required(Db.getDownload(row.id), "download row")
        assertEquals("RUNNING", current.state)
        assertEquals(1000L, current.downloadedBytes)
        assertEquals("/tmp/a/stuff.mkv", current.filePath)

        Db.setDownloadProgress(row.id, 2000L, null, null)
        current = required(Db.getDownload(row.id), "download row")
        assertEquals("/tmp/a/stuff.mkv", current.filePath) // path survived
        assertEquals(2000L, current.downloadedBytes)

        Db.setDownloadProgress(row.id, 3000L, null, "hiccups")
        current = required(Db.getDownload(row.id), "download row")
        assertEquals("RUNNING", current.state) // null state does not overwrite
        assertEquals("hiccups", current.detail)

        Db.setDownloadDone(row.id, "/tmp/a/stuff.mkv", 4000L)
        current = required(Db.getDownload(row.id), "download row")
        assertEquals("DONE", current.state)
        assertEquals(4000L, current.totalBytes)
        assertFalse(Db.listStaleRunning().any { it.id == row.id })

        // failure path preserves recorded bytes + detail
        val row2 = Db.createDownload(
            id = freshKey("id"),
            provider = provider,
            url = null,
            name = freshKey("dl-fail"),
            linkUrl = "https://src/x",
            headersJson = null,
        )
        Db.setDownloadFailed(row2.id, null, 123L, "upstream 404")
        val failed = required(Db.getDownload(row2.id), "failed row")
        assertEquals("FAILED", failed.state)
        assertEquals(123L, failed.downloadedBytes)
        assertEquals("upstream 404", failed.detail)
        assertTrue(Db.listStaleRunning().none { it.id == row2.id })

        // cleanup
        Db.deleteDownloadRow(row.id)
        Db.deleteDownloadRow(row2.id)
        assertNull(Db.getDownload(row.id))
        assertNull(Db.getDownload(row2.id))
    }

    // ---- the DB really points at the isolated dir --------------------------

    @Test
    fun `data dir is test-isolated`() {
        val prop = System.getProperty("csweb.dataDir") ?: error("csweb.dataDir not set by gradle")
        val dbFile = Path.of(prop).resolve("db.sqlite")
        assertTrue(Files.exists(dbFile), "library Db should point at the isolated test dir, was $dbFile")
        assertFalse(dbFile.toString().contains(".cloudstream-web"), "real user data dir must not be touched by tests")
    }

    // ---- plugin prefs (Phase 5: headless SharedPreferences persistence) ------

    @Test
    fun `plugin prefs write through to sqlite`() {
        val base = "SomePlugin/settings"
        val bKey = "$base/${freshKey("b")}"
        val iKey = "$base/${freshKey("i")}"
        val sKey = "$base/${freshKey("s")}"
        val setKey = "$base/${freshKey("set")}"

        FakeSharedPreferences.put(bKey, true)
        FakeSharedPreferences.put(iKey, 42)
        FakeSharedPreferences.put(sKey, "hello world")
        FakeSharedPreferences.put(setKey, setOf("a", "b"))

        val raw = Db.loadPrefs()
        assertEquals("b:true", raw[bKey])
        assertEquals("i:42", raw[iKey])
        assertEquals("s:hello world", raw[sKey])
        assertTrue(raw[setKey]!!.startsWith("set:"), raw[setKey])

        // readers resolve the stored values, defaults stay untouched
        assertTrue(FakeSharedPreferences.getBoolean(bKey, false))
        assertEquals(42, FakeSharedPreferences.getInt(iKey, 0))
        assertEquals("hello world", FakeSharedPreferences.getString(sKey, "def"))
        assertEquals(setOf("a", "b"), FakeSharedPreferences.getStringSet(setKey, null))
    }
}
